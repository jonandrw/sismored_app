package red.sismo

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import kotlin.math.max
import kotlin.math.min

/**
 * Sacar PCM por el altavoz, siempre por el canal de ALARMA.
 *
 * Va por `USAGE_ALARM` sin excepción: es lo que suena aunque el móvil esté en
 * silencio o en vibración, y aquí no hay ningún sonido que sea decorativo — o
 * es la sirena, o es una baliza que alguien tiene que oír, o es un «te oigo».
 *
 * `MallaAcustica` mantiene su propia copia a propósito: allí la compuerta
 * anti-eco tiene que abrirse y cerrarse en el mismo sitio donde se escribe al
 * buffer, y sacarlo aquí obligaría a devolver relojes por parámetro.
 */
object Altavoz {

    private const val TAG = "SismoRed"

    /* ---------- el volumen del sistema ----------
       `USAGE_ALARM` hace que suene aunque el móvil esté en silencio, pero NO sube
       el volumen: suena al índice de alarma que tenga puesto el usuario. La sirena
       siempre lo ha subido a mano (`Sirena.subirVolumenAlarma`); la sonda no, y por
       eso sus chasquidos «no se oían» aunque el pre-rollo ya hubiera arreglado que
       se cortaran. Un chasquido de 20 ms al 30 % del volumen de alarma no se oye
       en la calle, y menos encima de un escombro.

       Va con cuenta de anidamiento: el barrido y la sonda pueden pedirlo a la vez
       y el primero que termine no puede devolver el volumen mientras el otro sigue
       sonando. */
    @Volatile var audio: android.media.AudioManager? = null

    private var previo = -1
    private var dentro = 0

    private fun subir() = synchronized(this) {
        dentro++
        if (dentro > 1) return@synchronized
        val am = audio ?: return@synchronized
        try {
            previo = am.getStreamVolume(android.media.AudioManager.STREAM_ALARM)
            am.setStreamVolume(
                android.media.AudioManager.STREAM_ALARM,
                am.getStreamMaxVolume(android.media.AudioManager.STREAM_ALARM), 0
            )
        } catch (_: SecurityException) {
            // algunos fabricantes lo bloquean con No Molestar activo
        } catch (_: Exception) {}
    }

    private fun bajar() = synchronized(this) {
        dentro--
        if (dentro > 0) return@synchronized
        dentro = 0
        val am = audio
        if (am != null && previo >= 0) {
            try {
                am.setStreamVolume(android.media.AudioManager.STREAM_ALARM, previo, 0)
            } catch (_: Exception) {}
        }
        previo = -1
    }

    /**
     * Ejecuta [bloque] con el canal de alarma al máximo, y devuelve después el
     * volumen que tenía el usuario.
     *
     * Se envuelve la ráfaga o la medida ENTERA, no cada sonido: subir y bajar el
     * volumen ocho veces seguidas saca la barra de volumen del sistema en pantalla
     * en cada disparo — en Samsung eso ya pasó una vez con la malla.
     */
    fun <T> aTodoVolumen(bloque: () -> T): T {
        subir()
        try { return bloque() } finally { bajar() }
    }

    /**
     * Inicia un bucle continuo de reproducción de [pcm] a [sr] usando hardware DMA (MODE_STATIC loop)
     * a todo volumen por el canal de alarma.
     *
     * No bloquea el hilo llamante y evita recrear AudioTracks en bucles rápidos (lo que causa cortes,
     * pops y sobrecarga en el audio HAL).
     * Devuelve un [AutoCloseable] que detiene y libera el AudioTrack de inmediato y restaura el volumen.
     */
    fun iniciarBucle(pcm: ShortArray, sr: Int): AutoCloseable {
        subir()
        var t: AudioTrack? = null
        try {
            val minBuf = AudioTrack.getMinBufferSize(sr, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val bufBytes = maxOf(minBuf, pcm.size * 2)
            val pcmFinal = if (pcm.size * 2 >= bufBytes) pcm else ShortArray(bufBytes / 2).also {
                pcm.copyInto(it)
            }
            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sr)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(bufBytes)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()
            track.setVolume(AudioTrack.getMaxVolume())
            track.write(pcmFinal, 0, pcmFinal.size)
            track.setLoopPoints(0, pcm.size, -1) // Bucle continuo por hardware
            track.play()
            t = track
        } catch (e: Exception) {
            Log.e(TAG, "altavoz bucle falló", e)
            bajar()
        }
        return AutoCloseable {
            try { t?.stop() } catch (_: Exception) {}
            try { t?.release() } catch (_: Exception) {}
            bajar()
        }
    }

    /**
     * Bloquea hasta que termina de sonar. Llamar siempre desde un hilo aparte.
     *
     * [preMs] mete silencio DELANTE del sonido. Con el silencio delante, el camino
     * de audio ya está caliente cuando llega lo que importa, y suena SIEMPRE en el mismo momento.
     *
     * Utiliza [AudioTrack.MODE_STATIC] para sonidos discretos: precarga todo el buffer en
     * memoria antes de reproducir, evitando el underrun y la inanición de frames que sufría
     * MODE_STREAM con chasquidos cortos (< 50 ms) donde el servidor de audio nunca llegaba
     * al umbral de disparo y descartaba el audio en `stop()`.
     */
    fun reproducir(pcm: ShortArray, sr: Int, colaMs: Long = 200, preMs: Int = 0) {
        var t: AudioTrack? = null
        try {
            val pre = if (preMs > 0) sr * preMs / 1000 else 0
            val todo = if (pre == 0) pcm else ShortArray(pre + pcm.size).also {
                pcm.copyInto(it, pre)
            }
            val minBuf = AudioTrack.getMinBufferSize(sr, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val bufBytes = maxOf(minBuf, todo.size * 2)

            // Si los datos son menores que minBuf, se rellena con silencio al final
            // para que el buffer estático esté 100% satisfecho según las reglas de Android Audio HAL.
            val pcmFinal = if (todo.size * 2 >= bufBytes) todo else ShortArray(bufBytes / 2).also {
                todo.copyInto(it)
            }

            if (bufBytes <= 1024 * 1024) {
                // Modo estático para sonidos que caben en memoria (chasquidos, balizas, barridos hasta ~5 s)
                t = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ALARM)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(sr)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build()
                    )
                    .setBufferSizeInBytes(bufBytes)
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .build()

                t.setVolume(AudioTrack.getMaxVolume())
                t.write(pcmFinal, 0, pcmFinal.size)
                t.play()

                val durMs = todo.size * 1000L / sr
                val tope = System.currentTimeMillis() + durMs + 600L
                while (t.playbackHeadPosition < todo.size && System.currentTimeMillis() < tope) {
                    try { Thread.sleep(2) } catch (_: InterruptedException) { break }
                }
                // Margen mínimo de vaciado físico del DAC / bobina del altavoz para no cortar el sonido
                val esperaCola = maxOf(colaMs, 60L)
                try { Thread.sleep(esperaCola) } catch (_: InterruptedException) {}
            } else {
                // Modo stream por si alguna vez se pasa un buffer enorme (> 1 MB)
                val streamBuf = ((max(minBuf, sr / 2)) / 2) * 2
                t = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ALARM)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(sr)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build()
                    )
                    .setBufferSizeInBytes(streamBuf)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()

                t.setVolume(AudioTrack.getMaxVolume())
                t.play()
                var esc = 0
                while (esc < todo.size) {
                    val n = t.write(todo, esc, min(streamBuf / 2, todo.size - esc))
                    if (n <= 0) break
                    esc += n
                }
                val tope = System.currentTimeMillis() + esc * 1000L / sr + 500
                while (t.playbackHeadPosition < esc && System.currentTimeMillis() < tope) {
                    try { Thread.sleep(5) } catch (_: InterruptedException) { break }
                }
                if (colaMs > 0) try { Thread.sleep(colaMs) } catch (_: InterruptedException) {}
            }
        } catch (e: Exception) {
            Log.e(TAG, "altavoz falló", e)
        } finally {
            try { t?.stop() } catch (_: Exception) {}
            try { t?.release() } catch (_: Exception) {}
        }
    }
}
