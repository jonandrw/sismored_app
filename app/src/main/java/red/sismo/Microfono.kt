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

        // quién tiene el micrófono abierto, como el micUsers de la PWA
        const val USA_MALLA = 1
        const val USA_FORENSE = 2
        const val USA_SONDA = 4
        const val USA_INTERFONO = 8
        const val USA_PANICO = 16
    }

    /** Se llama desde el hilo del micrófono, no desde el principal. */
    fun interface Oyente { fun onMarco(marco: ShortArray) }

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
    private var usuarios = 0
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

        val r = AudioRecord(
            fuente, sr, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
            maxOf(min, N * 8)                 // holgura: perder marcos es perder balizas
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
            val trozo = ShortArray(SALTO)
            val marco = ShortArray(N)
            while (abierto) {
                var leidas = 0
                while (leidas < SALTO && abierto) {
                    val n = try { r.read(trozo, leidas, SALTO - leidas) } catch (e: Exception) { -1 }
                    if (n <= 0) { if (n < 0) return@thread else continue }
                    leidas += n
                }
                if (!abierto) break
                // ventana deslizante: se tira la mitad vieja y entra el trozo nuevo
                System.arraycopy(marco, SALTO, marco, 0, N - SALTO)
                System.arraycopy(trozo, 0, marco, N - SALTO, SALTO)
                empujarAnillo(trozo)
                for (o in oyentes) {
                    // un oyente que falle no puede dejar sordos a los demás
                    try { o.onMarco(marco) } catch (e: Exception) { Log.e(TAG, "oyente de microfono", e) }
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

    private fun empujarAnillo(x: ShortArray) {
        synchronized(cerrojo) {
            if (anillo.isEmpty()) return
            for (v in x) { anillo[w] = v / 32768f; w = (w + 1) % anillo.size }
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
