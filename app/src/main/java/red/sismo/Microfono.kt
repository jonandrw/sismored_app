package red.sismo

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioRecordingConfiguration
import android.media.MediaRecorder
import android.os.Build
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.util.Log
import kotlin.concurrent.thread

/**
 * Un único micrófono para toda la app.
 *
 * No es una comodidad, es una obligación: Android no deja a dos `AudioRecord`
 * capturar de la misma fuente a la vez con garantías — el segundo abre y
 * devuelve silencio, o le quita la captura al primero. La malla y la escucha
 * forense se necesitan **simultáneas** (mientras suena la alarma hay que seguir
 * oyendo balizas y hay que seguir oyendo a quien golpea), así que abren aquí y
 * se reparten las mismas muestras.
 *
 * Sirve dos cosas distintas porque las dos hacen falta:
 *  - una **ventana deslizante** de N muestras, que es lo que consume el
 *    decodificador de la malla marco a marco;
 *  - un **anillo con los últimos 2 s de PCM crudo**, que es lo que necesitan el
 *    tono fundamental y el nivel de la escucha forense, y que no se puede sacar
 *    de un espectro.
 */
class Microfono(private val ctx: Context) {

    companion object {
        const val N = 2048          // ventana de análisis (42 ms a 48 kHz)
        const val SALTO = 1024      // solape del 50 %
        private const val SEGUNDOS_ANILLO = 2
        private const val TAG = "SismoRed"

        /**
         * Cuántos [SALTO] se piden al micrófono de una vez cuando lo único que
         * escucha es la malla.
         *
         * **El tamaño de análisis y el de lectura eran el mismo número, y no
         * tienen por qué serlo.** Leyendo de [SALTO] en [SALTO] el procesador
         * despertaba 47 veces por segundo —21 ms— y no podía dormirse nunca:
         * ése es el gasto de tener la malla escuchando, no el micrófono, que
         * son microamperios.
         *
         * Con 24 se pide medio segundo de golpe. El DMA llena el búfer
         * mientras el procesador duerme, y al despertar se corren sobre ese
         * bloque las mismas 24 ventanas de [N] con el mismo solape: **la
         * secuencia de marcos que sale es idéntica**, no es una aproximación.
         * Lo único que se paga es medio segundo de retraso, y contra una
         * baliza que suena 4 s de cada 8 durante horas eso no existe.
         */
        /**
         * Cuánto se puede vaciar de una vez: 72 saltos, 1,5 s de audio.
         *
         * **No es «cuánto llega», es «cuánto cabe sacar».** Estaba en 24 —512
         * ms, apenas 62 más que la siesta— y con eso el Redmi no llegaba: si
         * procesar los marcos de una vuelta pasa de ese margen, la siguiente
         * encuentra más audio del que puede sacar, el retraso se acumula y el
         * búfer acaba saturado perdiendo muestras, o sea perdiendo balizas. Se
         * vio en el registro como «bloque lleno» sin parar. El Huawei sí
         * llegaba: es cosa del procesador, así que el margen tiene que ser
         * grande para no depender del aparato.
         */
        private const val SALTOS_POR_LECTURA = 72

        /** Lo que duerme el hilo entre vaciados: es esto lo que fija el gasto,
         *  porque es lo que tarda el proceso en volver a existir. */
        private const val MS_SIESTA = 450L

        // quién tiene el micrófono abierto, como el micUsers de la PWA
        const val USA_MALLA = 1
        const val USA_FORENSE = 2
        const val USA_SONDA = 4
        const val USA_INTERFONO = 8
        const val USA_PANICO = 16
    }

    /**
     * Se llama desde el hilo del micrófono, no desde el principal.
     *
     * [tMs] es **cuándo se capturó** este marco, no cuándo le ha tocado el
     * turno a la CPU. Los dos números eran el mismo mientras se leía de 21 ms
     * en 21 ms, y aun así no eran lo mismo: con el móvil cargado el marco se
     * fechaba tarde y la cadencia de la malla se medía torcida. Leyendo por
     * bloques la diferencia deja de ser un sesgo y pasa a ser medio segundo,
     * así que el instante tiene que viajar con el marco.
     */
    fun interface Oyente { fun onMarco(marco: ShortArray, tMs: Long) }

    @Volatile var abierto = false; private set
    @Volatile var sr = 48000; private set
    @Volatile var fuenteCruda = false; private set

    /**
     * El sistema puede enmudecer la captura sin cerrarla: otra app coge el
     * micrófono, o el fabricante lo decide, y `read` sigue devolviendo marcos
     * pero llenos de ceros. Sin esto la malla se queda sorda durante segundos
     * sin que nadie se entere, y luego no hay forma de saber si la baliza no
     * llegó o si es que no estábamos escuchando.
     */
    @Volatile var silenciado = false; private set

    private var record: AudioRecord? = null
    private var vigilante: AudioManager.AudioRecordingCallback? = null
    /** Volátil: lo escribe quien abre y cierra, y lo lee el hilo de captura
     *  en cada vuelta para decidir el tamaño de lectura. */
    @Volatile private var usuarios = 0

    /**
     * Cuántas muestras pedir de una vez.
     *
     * Grande cuando **solo** está la malla: es el caso de todo el día y de
     * toda la noche, y ahí lo que importa es que el procesador duerma.
     * Pequeño en cuanto se engancha cualquier otro —forense, interfono,
     * sonda, pánico—, porque ésos aparecen durante una alarma y entonces
     * manda la latencia y la batería da igual.
     */
    private fun tamanoLectura(): Int =
        if (usuarios == USA_MALLA) SALTO * SALTOS_POR_LECTURA else SALTO
    private val oyentes = java.util.concurrent.CopyOnWriteArrayList<Oyente>()

    /* Anillo de PCM crudo en float, para tono y nivel. */
    private var anillo = FloatArray(0)
    @Volatile private var w = 0
    private val cerrojo = Any()

    fun hayPermiso(): Boolean =
        ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    fun registrar(o: Oyente) { oyentes.addIfAbsent(o) }
    fun quitar(o: Oyente) { oyentes.remove(o) }

    /** Devuelve false si no hay permiso o el micrófono no arranca. Nunca lanza. */
    @Synchronized
    fun abrir(quien: Int): Boolean {
        if (abierto) { usuarios = usuarios or quien; return true }
        if (!hayPermiso()) { Log.i(TAG, "microfono: sin permiso"); return false }
        return try {
            arrancar()
            usuarios = usuarios or quien
            true
        } catch (e: Exception) {
            Log.e(TAG, "microfono no arranca", e); false
        }
    }

    /** Solo se cierra de verdad cuando lo suelta el último. */
    @Synchronized
    fun cerrar(quien: Int) {
        usuarios = usuarios and quien.inv()
        if (usuarios != 0) return
        abierto = false
        silenciado = false
        vigilante?.let { cb ->
            vigilante = null
            try { (ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager).unregisterAudioRecordingCallback(cb) }
            catch (_: Exception) {}
        }
        val r = record; record = null
        try { r?.stop() } catch (_: Exception) {}
        try { r?.release() } catch (_: Exception) {}
        Log.i(TAG, "microfono: cerrado")
    }

    private fun arrancar() {
        /* Fuente sin procesar. Es LA decisión de esta clase: con la fuente normal
           el móvil aplica cancelación de eco y supresión de ruido, y los tonos de
           17 kHz de la malla desaparecen — no se atenúan, desaparecen. El control
           automático de ganancia además falsea el nivel, que es justo lo que mide
           la escucha forense. */
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        fuenteCruda = am.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true"
        val fuente = if (fuenteCruda) MediaRecorder.AudioSource.UNPROCESSED
                     else MediaRecorder.AudioSource.VOICE_RECOGNITION

        var min = AudioRecord.getMinBufferSize(48000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        sr = 48000
        if (min <= 0) {                       // algún fabricante no da 48 kHz
            sr = 44100
            min = AudioRecord.getMinBufferSize(sr, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        }
        if (min <= 0) throw IllegalStateException("sin tasa de muestreo utilizable")

        /* Dos segundos de holgura, en BYTES —que es lo que pide el
           constructor y lo que devuelve `getMinBufferSize`—. Estaba en
           `N * 8` = 16 KB, o sea 8192 muestras: 170 ms, y con eso no se puede
           pedir medio segundo de golpe sin desbordar. Ampliar el búfer no
           gasta nada, son 187 KB y no consume corriente: lo que decide el
           gasto es cada cuánto se despierta a leerlo. */
        val r = AudioRecord(
            fuente, sr, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
            maxOf(min, sr * 2 * 2)
        )
        if (r.state != AudioRecord.STATE_INITIALIZED) { r.release(); throw IllegalStateException("AudioRecord no inicializa") }

        /* Cinturón y tirantes: aunque la fuente sea cruda, hay fabricantes que
           enganchan los efectos igual. Se crean solo para apagarlos. */
        apagarEfectos(r.audioSessionId)

        synchronized(cerrojo) { anillo = FloatArray(sr * SEGUNDOS_ANILLO); w = 0 }

        r.startRecording()
        record = r
        abierto = true
        Log.i(TAG, "microfono: $sr Hz" + if (fuenteCruda) " (fuente cruda)" else " (voice_recognition)")
        vigilarMudez(am, r.audioSessionId)

        thread(name = "microfono", isDaemon = true) {
            val bloque = ShortArray(SALTO * SALTOS_POR_LECTURA)
            val marco = ShortArray(N)
            var modoAnterior = 0
            var saturado = false
            var tAviso = 0L
            while (abierto) {
                /* Se relee en cada vuelta: una alarma puede engancharse al
                   micrófono en mitad de la noche y a partir de ahí hay prisa. */
                val pedir = tamanoLectura()
                if (pedir != modoAnterior) {
                    modoAnterior = pedir
                    Log.i(TAG, if (pedir > SALTO)
                        "microfono: solo la malla, leo $pedir muestras por siesta de $MS_SIESTA ms"
                        else "microfono: hay prisa (usuarios=$usuarios), leo de $SALTO en $SALTO")
                }
                var leidas = 0
                if (pedir > SALTO) {
                    /* NO SE ESPERA EN EL `read`, SE DUERME Y SE VACÍA.

                       Pedir el bloque entero con la lectura bloqueante no sirve
                       de nada: `AudioRecord` despierta al hilo por su futex en
                       cada periodo del HAL, así que aunque el bucle de Java dé
                       dos vueltas por segundo, el hilo se despertaba 48 —medido
                       el 18 de septiembre: 2 lecturas/s y 2.887 conmutaciones
                       voluntarias en 60 s—. El tamaño de lectura no manda sobre
                       los despertares; mandar sobre ellos es no estar esperando.

                       Durmiendo por reloj propio y vaciando con lectura NO
                       bloqueante, el proceso se queda fuera de la ecuación
                       medio segundo entero. El servidor de audio sigue
                       corriendo —eso no lo controla nadie desde aquí—, pero el
                       nuestro deja de acompañarlo. */
                    /* Si la vuelta anterior salió llena hay retraso acumulado:
                       se vacía otra vez sin dormir hasta ponerse al día. */
                    if (!saturado) {
                        try { Thread.sleep(MS_SIESTA) } catch (_: InterruptedException) { break }
                        if (!abierto) break
                    }
                    val n = try { r.read(bloque, 0, pedir, AudioRecord.READ_NON_BLOCKING) }
                            catch (e: Exception) { -1 }
                    if (n < 0) return@thread
                    /* Múltiplo de SALTO: el resto se queda en el búfer del
                       sistema y entra en la siguiente vuelta, que es justo lo
                       que conserva la continuidad de la ventana deslizante. */
                    leidas = (n / SALTO) * SALTO
                    saturado = n >= pedir
                    if (saturado && System.currentTimeMillis() - tAviso > 10_000L) {
                        tAviso = System.currentTimeMillis()
                        Log.i(TAG, "microfono: no doy abasto, puede faltar audio")
                    }
                    if (leidas == 0) continue
                } else {
                    while (leidas < pedir && abierto) {
                        val n = try { r.read(bloque, leidas, pedir - leidas) } catch (e: Exception) { -1 }
                        if (n <= 0) { if (n < 0) return@thread else continue }
                        leidas += n
                    }
                }
                if (!abierto) break
                /* El bloque acaba de llegar entero, así que este instante es el
                   de su ÚLTIMA muestra. El de cada marco se saca restando lo
                   que queda de bloque por detrás. */
                val tFin = System.currentTimeMillis()
                var off = 0
                while (off + SALTO <= leidas) {
                    // ventana deslizante: se tira la mitad vieja y entra el trozo nuevo
                    System.arraycopy(marco, SALTO, marco, 0, N - SALTO)
                    System.arraycopy(bloque, off, marco, N - SALTO, SALTO)
                    empujarAnillo(bloque, off, SALTO)
                    off += SALTO
                    val tMarco = tFin - ((leidas - off).toLong() * 1000L) / sr
                    for (o in oyentes) {
                        // un oyente que falle no puede dejar sordos a los demás
                        try { o.onMarco(marco, tMarco) } catch (e: Exception) { Log.e(TAG, "oyente de microfono", e) }
                    }
                }
            }
        }
    }

    /** Lo avisa la propia plataforma desde Android 10; no hay que olfatear ceros. */
    private fun vigilarMudez(am: AudioManager, sesion: Int) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val cb = object : AudioManager.AudioRecordingCallback() {
            override fun onRecordingConfigChanged(configs: MutableList<AudioRecordingConfiguration>) {
                val mia = configs.firstOrNull { it.clientAudioSessionId == sesion } ?: return
                if (mia.isClientSilenced == silenciado) return
                silenciado = mia.isClientSilenced
                Log.i(TAG, if (silenciado) "microfono: enmudecido por el sistema, sordos"
                           else "microfono: vuelve a oírse")
            }
        }
        try { am.registerAudioRecordingCallback(cb, null); vigilante = cb }
        catch (e: Exception) { Log.e(TAG, "no se pudo vigilar la mudez del microfono", e) }
    }

    private fun apagarEfectos(sesion: Int) {
        try { if (AcousticEchoCanceler.isAvailable()) AcousticEchoCanceler.create(sesion)?.enabled = false } catch (_: Exception) {}
        try { if (NoiseSuppressor.isAvailable()) NoiseSuppressor.create(sesion)?.enabled = false } catch (_: Exception) {}
        try { if (AutomaticGainControl.isAvailable()) AutomaticGainControl.create(sesion)?.enabled = false } catch (_: Exception) {}
    }

    /** Por tramos hasta el final del anillo, para no hacer un módulo por
     *  muestra: son 48.000 por segundo y esto está en el camino caliente. */
    private fun empujarAnillo(x: ShortArray, off: Int, len: Int) {
        synchronized(cerrojo) {
            if (anillo.isEmpty()) return
            var i = off
            var quedan = len
            while (quedan > 0) {
                val cabe = minOf(quedan, anillo.size - w)
                for (k in 0 until cabe) anillo[w + k] = x[i + k] / 32768f
                w = (w + cabe) % anillo.size
                i += cabe; quedan -= cabe
            }
        }
    }

    /** Las últimas n muestras en orden cronológico. */
    fun cola(n: Int): FloatArray {
        synchronized(cerrojo) {
            val out = FloatArray(n)
            if (anillo.isEmpty()) return out
            val len = anillo.size
            for (i in 0 until n) out[i] = anillo[((w - n + i) % len + len) % len]
            return out
        }
    }
}
