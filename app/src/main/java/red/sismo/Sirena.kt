package red.sismo

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.audiofx.LoudnessEnhancer
import android.util.Log
import kotlin.concurrent.thread
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/**
 * Sirena sintetizada y escrita directamente al canal de ALARMA.
 *
 * Esta clase es la razón de existir de la versión nativa. En el navegador la
 * sirena salía floja y no había forma de arreglarlo: la web no puede tocar el
 * volumen del sistema ni saltarse el modo silencio. Aquí sí:
 *
 *  - USAGE_ALARM suena aunque el móvil esté en silencio o en vibración.
 *  - setStreamVolume(STREAM_ALARM, max) sube el volumen desde el código.
 *
 * El barrido va por 2–4 kHz, donde el oído humano es más sensible: a igual
 * potencia del altavoz se percibe mucho más fuerte que en 800 Hz.
 */
class Sirena(private val audio: AudioManager) {

    private var track: AudioTrack? = null
    private var realce: LoudnessEnhancer? = null
    private var sonando = false
    private var volumenPrevio = -1

    companion object {
        private const val SR = 44100
        private const val BYTES_TRAMA = 2       // PCM 16 bits, mono
        private const val F_MIN = 2000.0
        private const val F_MAX = 4000.0
        private const val BARRIDO_HZ = 0.7      // sube y baja 0,7 veces por segundo

        /* ---------- lo que hace que suene fuerte de verdad ----------
           Subir el índice del canal de alarma al máximo no basta: alarma y
           multimedia salen por el mismo altavoz y topan en el mismo
           amplificador. Lo que se percibe como volumen es la ENERGÍA de la
           señal, y un seno a fondo de escala solo tiene 0,707 de RMS — se deja
           3 dB en el aire sin llegar a recortar nunca.

           De eso se encarga `Maximizador`: empuja la señal muy por encima del
           tope y le pone un limitador con anticipación, que baja la ganancia
           justo ANTES de cada pico. Sube la energía media sin redondear la onda,
           que es exactamente lo que distinguía «más alto» de «más sucio». */
        /** Cuánto empuja el maximizador antes de limitar. Es EL mando de
         *  volumen: 12 dB de empuje sobre una onda que ya llegaba al tope
         *  suben mucho la energía media, y el limitador se encarga de que ni
         *  una muestra recorte. Si algún altavoz suena forzado, baja de aquí. */
        private const val EMPUJE_DB = 12.0

        /** Realce del sistema, en centibelios. Es un compresor: no puede romper
         *  el altavoz, lo que hace es acercar todo el rato al techo en vez de
         *  llegar solo en los picos. No todos los móviles lo traen.
         *
         *  A partir de aquí lo que queda ya no es volumen sino distorsión: el
         *  altavoz entra en su propia protección y recorta él. Si hay que bajar
         *  porque en algún móvil suene sucio, este es el número. */
        private const val REALCE_MB = 2000      // +20 dB
    }

    /** Guarda el volumen del usuario y pone el canal de alarma al máximo. */
    private fun subirVolumenAlarma() {
        try {
            if (volumenPrevio < 0) volumenPrevio = audio.getStreamVolume(AudioManager.STREAM_ALARM)
            val max = audio.getStreamMaxVolume(AudioManager.STREAM_ALARM)
            audio.setStreamVolume(AudioManager.STREAM_ALARM, max, 0)
        } catch (e: SecurityException) {
            // Algunos fabricantes lo bloquean con No Molestar activo.
        }
    }

    /** Devuelve el volumen que tenía el usuario. Cortesía, no seguridad. */
    private fun restaurarVolumen() {
        if (volumenPrevio >= 0) {
            try { audio.setStreamVolume(AudioManager.STREAM_ALARM, volumenPrevio, 0) } catch (_: Exception) {}
            volumenPrevio = -1
        }
    }

    /** Nunca lanza: en una app de emergencia, un fallo de audio no puede
     *  llevarse por delante el servicio que mantiene todo lo demás vivo. */
    fun start() = try { arrancar() } catch (e: Exception) { sonando = false; Log.e("SismoRed", "sirena fallo", e); Unit }

    private fun arrancar() {
        if (sonando) return
        sonando = true
        subirVolumenAlarma()

        /* El buffer va en BYTES y tiene que ser múltiplo exacto del tamaño de
           trama: 2 bytes en PCM de 16 bits mono. SR/4 = 11025 es impar, y
           AudioTrack lo rechaza con "Invalid audio buffer size". Se redondea. */
        val minBuf = AudioTrack.getMinBufferSize(
            SR, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val pedido = maxOf(if (minBuf > 0) minBuf else 0, SR / 2)   // ~0,5 s
        val buf = (pedido / BYTES_TRAMA) * BYTES_TRAMA

        val t = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)                 // salta el silencio
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SR)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(buf)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        track = t
        t.setVolume(AudioTrack.getMaxVolume())

        /* El realce va sobre la sesión de audio de ESTE track, no sobre la
           mezcla del sistema: si el móvil no lo soporta, la sirena suena igual
           que antes en vez de no sonar. */
        try {
            realce = LoudnessEnhancer(t.audioSessionId).apply {
                setTargetGain(REALCE_MB)
                enabled = true
            }
            Log.i("SismoRed", "sirena: realce de ${REALCE_MB / 100} dB activo")
        } catch (e: Exception) {
            Log.i("SismoRed", "sirena: sin realce de volumen (${e.javaClass.simpleName})")
        }

        t.play()

        val max = Maximizador(SR, empujeDb = EMPUJE_DB)
        thread(name = "sirena", isDaemon = true) {
            val muestras = ShortArray(buf / 2)
            var fase = 0.0
            var n = 0L
            while (sonando) {
                for (i in muestras.indices) {
                    val t0 = n.toDouble() / SR
                    // barrido triangular entre F_MIN y F_MAX
                    val v = abs(((t0 * BARRIDO_HZ) % 1.0) * 2.0 - 1.0)
                    val f = F_MIN + (F_MAX - F_MIN) * v
                    fase += 2.0 * PI * f / SR
                    if (fase > 2 * PI) fase -= 2 * PI
                    // fundamental + tercer armónico: más áspera, se localiza mejor
                    val s = sin(fase) * 0.75 + sin(3 * fase) * 0.25
                    // el maximizador empuja y limita: sube la energía media sin
                    // redondear los picos, que es lo que ensuciaba antes
                    muestras[i] = (max.paso(s) * Short.MAX_VALUE).toInt().toShort()
                    n++
                }
                try {
                    t.write(muestras, 0, muestras.size)
                } catch (e: Exception) {
                    break
                }
            }
        }
    }

    fun stop() {
        sonando = false
        // el efecto se suelta antes que el track: al revés deja la sesión colgada
        try { realce?.enabled = false } catch (_: Exception) {}
        try { realce?.release() } catch (_: Exception) {}
        realce = null
        try {
            track?.pause()
            track?.flush()
            track?.release()
        } catch (_: Exception) {}
        track = null
        restaurarVolumen()
    }

    fun estaSonando() = sonando
}
