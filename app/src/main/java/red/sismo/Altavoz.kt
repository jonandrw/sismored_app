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
     * Bloquea hasta que termina de sonar. Llamar siempre desde un hilo aparte.
     *
     * [preMs] mete silencio DELANTE del sonido. No es un adorno: entre `play()` y
     * el primer sample que sale de verdad por el altavoz hay entre 50 y 150 ms
     * según el móvil, y con sonidos cortos —el chasquido de la sonda son 20 ms—
     * ese arranque se comía el sonido entero unas veces y otras no. Con el
     * silencio delante, el camino de audio ya está caliente cuando llega lo que
     * importa, y suena SIEMPRE en el mismo momento.
     *
     * El vaciado tampoco puede ser un `sleep` a ojo: se espera a que la cabeza de
     * reproducción pase por el último marco escrito. Antes, `colaMs = 0` soltaba
     * el track antes de que el altavoz hubiera sonado, y el chasquido se perdía.
     */
    fun reproducir(pcm: ShortArray, sr: Int, colaMs: Long = 200, preMs: Int = 0) {
        var t: AudioTrack? = null
        try {
            val pre = if (preMs > 0) sr * preMs / 1000 else 0
            val todo = if (pre == 0) pcm else ShortArray(pre + pcm.size).also {
                pcm.copyInto(it, pre)
            }
            val minBuf = AudioTrack.getMinBufferSize(sr, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
            /* En BYTES y múltiplo del tamaño de trama (2 bytes en PCM 16 mono):
               un tamaño impar lo rechaza con «Invalid audio buffer size». */
            val buf = ((max(minBuf, sr / 2)) / 2) * 2
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
                .setBufferSizeInBytes(buf)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            t.setVolume(AudioTrack.getMaxVolume())
            t.play()
            var esc = 0
            while (esc < todo.size) {
                val n = t.write(todo, esc, min(buf / 2, todo.size - esc))
                if (n <= 0) break
                esc += n
            }
            /* Que acabe de vaciarse antes de soltarlo, o se corta el final. Se
               mide, no se supone: el tope es la duración de lo escrito más medio
               segundo, para que un `playbackHeadPosition` que no avance (pasa si
               el sistema desvía el audio) no cuelgue el hilo. */
            val tope = System.currentTimeMillis() + esc * 1000L / sr + 500
            while (t.playbackHeadPosition < esc && System.currentTimeMillis() < tope) {
                try { Thread.sleep(5) } catch (_: InterruptedException) { break }
            }
            if (colaMs > 0) try { Thread.sleep(colaMs) } catch (_: InterruptedException) {}
        } catch (e: Exception) {
            Log.e(TAG, "altavoz falló", e)
        } finally {
            try { t?.stop() } catch (_: Exception) {}
            try { t?.release() } catch (_: Exception) {}
        }
    }
}
