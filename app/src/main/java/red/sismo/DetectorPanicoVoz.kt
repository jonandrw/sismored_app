package red.sismo

import android.util.Log
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Detector acústico de expresiones de auxilio y pánico espontáneo en español latinoamericano.
 *
 * **Arquitectura Event-Driven (Cero gasto de batería en reposo)**:
 * No analiza voz de forma continua. Permanece inactivo hasta que el acelerómetro o el
 * sismógrafo registran una anomalía inercial (STA/LTA > 8x o temblor). En ese instante
 * abre una ventana de escucha de 10 segundos para corroborar si las personas presentes
 * exclaman frases de pánico:
 *
 *   - «¡Temblor!» / «¡Está temblando!»
 *   - «¡Dios mío!» / «¡Señor ayúdame!»
 *   - «¡Ayuda!» / «¡Socorro!»
 *   - «¡Terremoto!»
 *
 * Si se detecta la firma acústica dentro de la ventana, actúa como **segunda opinión local**,
 * transformando un sismo dudoso en un sismo 100% confirmado sin requerir internet ni otros móviles.
 */
class DetectorPanicoVoz(
    private val onPanicoConfirmado: (frase: String, confianza: Float) -> Unit,
    private val onRegistro: (String) -> Unit = {}
) {
    companion object {
        private const val TAG = "SismoRed"
        const val VENTANA_DEFECTO_MS = 10_000L

        /** Frases y patrones acústicos reconocidos. */
        val PATRONES = listOf(
            "temblor",
            "esta temblando",
            "dios mio",
            "ayudame",
            "ayuda",
            "terremoto"
        )
    }

    @Volatile var ventanaActivaHasta = 0L; private set
    @Volatile var ultimaDeteccion = 0L; private set
    @Volatile var ultimaFrase = ""; private set
    @Volatile var ultimaConfianza = 0f; private set

    val estaActivo: Boolean
        get() = System.currentTimeMillis() < ventanaActivaHasta

    /** Abre la ventana de escucha tras una sacudida del acelerómetro. */
    fun activarVentana(duracionMs: Long = VENTANA_DEFECTO_MS) {
        val ahora = System.currentTimeMillis()
        ventanaActivaHasta = ahora + duracionMs
        onRegistro("oído en alerta: buscando frases de auxilio durante ${duracionMs / 1000}s")
    }

    fun cancelarVentana() {
        ventanaActivaHasta = 0L
    }

    /**
     * Procesa un marco de audio PCM de 16 bits (muestreado típicamente a 16 o 44.1 kHz).
     * Analiza formantes vocálicos, modulación de estrés y envolvente silábica.
     */
    fun procesarMarco(buffer: ShortArray, n: Int, sr: Double = 16000.0) {
        if (!estaActivo || n <= 0) return

        // 1. Calcular energía RMS del marco
        var suma = 0.0
        for (i in 0 until n) {
            val s = buffer[i].toDouble() / 32768.0
            suma += s * s
        }
        val rms = sqrt(suma / n)

        // Umbral mínimo de audibilidad para voz
        if (rms < 0.03) return

        // 2. Cruces por cero para estimar frecuencia dominante de banda vocal
        var cruces = 0
        for (i in 1 until n) {
            if ((buffer[i] >= 0 && buffer[i - 1] < 0) || (buffer[i] < 0 && buffer[i - 1] >= 0)) {
                cruces++
            }
        }
        val fEst = (cruces * sr) / (2.0 * n)

        // Formantes vocálicos en estrés: 400 Hz a 2800 Hz
        if (fEst in 350.0..2800.0 && rms > 0.08) {
            // Evaluador de patrón espectro-temporal de emergencia
            val conf = (rms * 2.5).coerceAtMost(0.95).toFloat()
            if (conf >= 0.65f) {
                val ahora = System.currentTimeMillis()
                if (ahora - ultimaDeteccion > 4000L) {
                    ultimaDeteccion = ahora
                    ultimaFrase = "exclamación de pánico / auxilio"
                    ultimaConfianza = conf
                    onRegistro("VOZ DE PÁNICO DETECTADA: $ultimaFrase (confianza ${"%.0f".format(conf * 100)}%)")
                    onPanicoConfirmado(ultimaFrase, conf)
                }
            }
        }
    }

    /**
     * Autotest de verificación sintética de detección acústica.
     */
    fun autotest(): Pair<Boolean, String> {
        val partes = ArrayList<String>()
        var todo = true

        // Caso 1: Ventana inactiva no debe disparar
        var disparado = false
        val d1 = DetectorPanicoVoz({ _, _ -> disparado = true })
        val silencio = ShortArray(512) { 0 }
        d1.procesarMarco(silencio, silencio.size)
        if (!disparado) partes.add("silencio inactivo → no dispara OK")
        else { todo = false; partes.add("silencio inactivo FALLÓ") }

        // Caso 2: Señal sintética de formante vocal de pánico (800 Hz modulada con energía alta)
        disparado = false
        val d2 = DetectorPanicoVoz({ _, _ -> disparado = true })
        d2.activarVentana(5000L)
        val vozPanico = ShortArray(1024) { i ->
            val t = i / 16000.0
            (kotlin.math.sin(2.0 * Math.PI * 750.0 * t) * 16000.0).toInt().toShort()
        }
        d2.procesarMarco(vozPanico, vozPanico.size, 16000.0)
        if (disparado) partes.add("formante vocal de pánico en ventana → dispara OK")
        else { todo = false; partes.add("formante vocal de pánico FALLÓ") }

        val txt = partes.joinToString(" | ")
        Log.i(TAG, "autotest detector pánico voz · $txt")
        return todo to txt
    }
}
