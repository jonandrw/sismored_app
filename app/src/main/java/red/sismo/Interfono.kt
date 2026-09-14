package red.sismo

import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlin.concurrent.thread
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.roundToInt

/**
 * Interfono acústico entre el rescatista y quien está debajo.
 *
 * Lo primero que hay que decir es lo que NO es: no es una llamada. Ningún canal
 * de los que tiene la app puede llevar voz. El anuncio de radio son 31 bytes
 * enteros y la malla acústica manda un símbolo cada varios segundos; con eso no
 * se transmite una frase ni comprimida ni troceada. Lo que cruza de verdad una
 * capa de escombros es el sonido, y por ahí va esto.
 *
 * Así que el interfono es **medio dúplex y en un solo móvil**: el del rescatista
 * habla hacia abajo por el altavoz y luego calla y escucha. Es como se hace en un
 * rescate real —gritar y callarse— con la diferencia de que el móvil empuja la voz
 * con el maximizador que ya usa la sirena, y de que sabe esperar en silencio el
 * tiempo justo.
 *
 * El ciclo es de cuatro pasos y no se solapan nunca:
 *
 *  1. **Fondo** (0,5 s). Cuánto ruido hay en la banda de la voz sin nadie
 *     hablando. Sin esta medida, «se oye algo» no significa nada: al lado de una
 *     excavadora todo pasa el umbral y en un sótano en silencio no lo pasa ni un
 *     grito.
 *  2. **Grabar** al rescatista, hasta [HABLA_MS].
 *  3. **Hablar hacia abajo**: se reproduce lo grabado, maximizado. El micrófono
 *     está CERRADO mientras suena — con los dos abiertos a la vez y ganancia de
 *     +12 dB el móvil se pone a pitar y no se oye ni una palabra.
 *  4. **Escuchar** [ESCUCHA_MS]. Si aparece energía en la banda de la voz por
 *     encima del fondo, se graba y la ventana se queda abierta mientras dure, con
 *     un tope. Se cierra sola tras [SILENCIO_CIERRE_MS] callados, que es lo que
 *     dura una pausa entre frases sin que parezca que se ha cortado.
 *  5. Se le devuelve al rescatista lo que ha contestado, también maximizado,
 *     porque una voz a través de dos metros de escombro llega a un nivel que con
 *     el altavoz al aire no se entiende.
 *
 * Sobre reconocer «voz humana»: aquí se mide **energía en la banda de 300 a
 * 3400 Hz sostenida**, no se clasifica. Es a propósito. El detector de voz de
 * `Escucha` está sin resolver —contra 21 grabaciones humanas reales no se
 * enciende ni una vez— y colgar de él la decisión de si alguien ha contestado
 * sería construir sobre algo que se sabe que no funciona. Una puerta de banda con
 * el fondo medido no distingue una voz de un golpe metálico, pero no falla en la
 * dirección peligrosa: no dice «no ha contestado nadie» cuando sí.
 */
class Interfono(
    private val mic: Microfono,
    /**
     * Se llama justo antes de sacar algo por el altavoz, con los milisegundos que
     * va a durar. Quien lo reciba tiene que ensordecer la malla y los detectores.
     *
     * No es una precaución teórica: cerrar el micrófono desde aquí no lo cierra,
     * porque la malla y los detectores lo tienen abierto por su cuenta y
     * `Microfono` solo cuenta usuarios. Probado en un Samsung A10s, la primera
     * versión de esto reprodujo la voz maximizada y su propio detector la anotó
     * dos veces como GRITO DE AUXILIO. Con el detector de estruendo armado, eso
     * no es un registro sucio: es una alarma falsa disparada por uno mismo.
     */
    private val onAltavoz: (Long) -> Unit = {},
    private val onRegistro: (String) -> Unit = {}
) {

    companion object {
        private const val TAG = "SismoRed"

        /** Lo que se le graba al rescatista. Más de cuatro segundos y la gente
         *  divaga; quien está debajo necesita una pregunta corta. */
        const val HABLA_MS = 4000L
        /** «El canal abierto unos 3 o 4 segundos». Cuatro. */
        const val ESCUCHA_MS = 4000L
        /** Si contesta y sigue hablando, se le deja seguir hasta aquí. */
        const val ESCUCHA_MAX_MS = 15000L
        /** Silencio que cierra el canal. Una pausa entre frases es más corta. */
        const val SILENCIO_CIERRE_MS = 700L

        /** La banda donde está la voz. Por debajo de 300 Hz manda el retumbe del
         *  escombro y por encima de 3400 Hz ya no queda casi nada después de
         *  atravesarlo: los agudos son lo primero que se come la masa. */
        private const val VOZ_HZ_MIN = 300.0
        private const val VOZ_HZ_MAX = 3400.0
        /** Cuánto tiene que subir sobre el fondo medido para contar como
         *  respuesta. Ocho decibelios es algo más del doble de presión: por
         *  debajo de eso, el propio ruido del sitio lo dispara solo. */
        private const val SOBRE_FONDO_DB = 8.0
        /** Y tiene que aguantar. Un portazo pasa el umbral y no es una respuesta. */
        private const val SOSTENIDO_MS = 250L
    }

    private val h = Handler(Looper.getMainLooper())

    /** Junta lineas sin escribir saltos a mano en el codigo. */
    private fun linea(vararg partes: String) = partes.joinToString(System.lineSeparator())
    private fun reg(s: String) = h.post { onRegistro(s) }

    enum class Fase {
        CERRADO,
        CALIBRANDO,
        GRABANDO_VOZ,
        ENVIANDO,
        ESCUCHANDO,
        CONTESTANDO
    }

    @Volatile var fase: Fase = Fase.CERRADO; private set
    @Volatile var ocupado = false; private set

    /** Lo baja `parar()`. Mismo motivo que en la sonda: cualquier cosa que suene
     *  tiene que poder callarse desde DETENER, y el ciclo son diez segundos en los
     *  que el móvil está sacando voz a todo volumen. */
    @Volatile private var cancelado = false

    /** Corta el ciclo donde esté. Lo llama DETENER. */
    fun parar() { if (ocupado) cancelado = true; fase = Fase.CERRADO }
    /** Lo que la pantalla enseña del ciclo en marcha. */
    @Volatile var estado = "—"; private set

    /* ---------- medida de la banda de la voz ---------- */

    private val ventana = DoubleArray(Microfono.N) {
        0.5 - 0.5 * cos(2.0 * PI * it / (Microfono.N - 1))
    }
    private val espectro = DoubleArray(Microfono.N / 2)

    /** Nivel en la banda de la voz del último marco, en dB. Lo escribe el hilo
     *  del micrófono y lo lee el del ciclo, de ahí el @Volatile. */
    @Volatile var vozDb = -120.0; private set

    private fun medidor(sr: Int): Microfono.Oyente {
        val res = sr.toDouble() / Microfono.N
        val k0 = (VOZ_HZ_MIN / res).roundToInt().coerceAtLeast(1)
        val k1 = (VOZ_HZ_MAX / res).roundToInt().coerceAtMost(espectro.size - 1)
        return Microfono.Oyente { marco ->
            Fft.magnitudes(marco, ventana, espectro)
            var s = 0.0
            for (k in k0..k1) s += espectro[k] * espectro[k]
            vozDb = 10.0 * log10(s / (k1 - k0 + 1) + 1e-12)
        }
    }

    /* ---------- grabación ---------- */

    /** Acumula marcos crudos mientras esté encendido. Se usa para grabar tanto al
     *  rescatista como la respuesta: los dos hay que reproducirlos después. */
    private class Grabadora : Microfono.Oyente {
        val trozos = ArrayList<ShortArray>()
        @Volatile var grabando = false
        override fun onMarco(marco: ShortArray) {
            if (grabando) trozos.add(marco.copyOf())
        }
        fun junto(): ShortArray {
            val n = trozos.sumOf { it.size }
            val out = ShortArray(n)
            var i = 0
            for (t in trozos) { t.copyInto(out, i); i += t.size }
            return out
        }
        fun vaciar() { trozos.clear() }
    }

    /** Sube la voz todo lo que el altavoz aguanta sin recortar. Es el mismo
     *  maximizador de la sirena: una voz que ha cruzado dos metros de escombro
     *  llega tan baja que sin esto no se entiende. */
    /** Saca audio por el altavoz habiendo ensordecido antes lo que escucha. El
     *  margen de medio segundo cubre la cola de la reproducción. */
    private fun sacar(pcm: ShortArray, sr: Int) {
        if (pcm.isEmpty() || cancelado) return
        val dura = pcm.size * 1000L / sr
        onAltavoz(dura + 500)
        Altavoz.reproducir(maximizar(pcm, sr), sr, colaMs = 250)
    }

    private fun maximizar(pcm: ShortArray, sr: Int): ShortArray {
        val max = Maximizador(sr, empujeDb = 12.0)
        return ShortArray(pcm.size) {
            (max.paso(pcm[it] / 32768.0) * 32767.0).toInt()
                .coerceIn(-32768, 32767).toShort()
        }
    }

    /**
     * Un ciclo entero: graba, habla hacia abajo, escucha y devuelve lo oído.
     *
     * Bloquea en su propio hilo. `onProgreso` va contando en qué paso está, porque
     * son unos diez segundos en los que el móvil hace cosas distintas y quien lo
     * sujeta tiene que saber si le toca hablar o callarse.
     */
    fun ciclo(onProgreso: (String) -> Unit, onResultado: (String) -> Unit) {
        if (ocupado) { h.post { onResultado("espera: el interfono ya está en marcha") }; return }
        if (!mic.abrir(Microfono.USA_INTERFONO)) {
            reg("el interfono necesita el permiso del micrófono")
            h.post { onResultado("Necesito el micrófono para el interfono. Concédelo y vuelve a intentarlo.") }
            return
        }
        ocupado = true
        cancelado = false
        val sr = mic.sr
        val medidor = medidor(sr)
        val grab = Grabadora()

        fun paso(s: String) { estado = s; h.post { onProgreso(s) } }

        thread(name = "interfono", isDaemon = true) {
            try {
                mic.registrar(medidor)
                mic.registrar(grab)

                // 1. fondo
                fase = Fase.CALIBRANDO
                paso(linea("Midiendo el ruido de este sitio.", "No hables todavia."))
                var fondo = 0.0; var n = 0
                val t0 = System.currentTimeMillis()
                while (System.currentTimeMillis() - t0 < 500) {
                    Thread.sleep(50); fondo += vozDb; n++
                }
                fondo = if (n > 0) fondo / n else -120.0
                val umbral = fondo + SOBRE_FONDO_DB

                // 2. grabar al rescatista
                fase = Fase.GRABANDO_VOZ
                paso(linea("HABLA AHORA.", "Tienes ${HABLA_MS / 1000} segundos."))
                grab.vaciar(); grab.grabando = true
                Thread.sleep(HABLA_MS)
                grab.grabando = false
                val dicho = grab.junto()

                /* 3. hablar hacia abajo con el micrófono CERRADO. Con los dos
                      abiertos y +12 dB de empuje, el móvil se realimenta y lo
                      único que sale es un pitido. */
                mic.quitar(medidor); mic.quitar(grab)
                mic.cerrar(Microfono.USA_INTERFONO)
                fase = Fase.ENVIANDO
                paso(linea("Hablando hacia abajo, a todo volumen.", "Aparta la oreja del altavoz."))
                sacar(dicho, sr)

                // 4. escuchar la respuesta
                if (!mic.abrir(Microfono.USA_INTERFONO)) {
                    h.post { onResultado("he perdido el micrófono al terminar de hablar") }
                    return@thread
                }
                mic.registrar(medidor); mic.registrar(grab)
                grab.vaciar()
                fase = Fase.ESCUCHANDO
                paso(linea("ESCUCHANDO.", "Calla y no muevas el movil."))

                var contesto = false
                var pico = -120.0
                var desdeVoz = 0L          // cuándo empezó a oírse algo
                var ultimaVoz = 0L         // la última vez que se oyó
                val t1 = System.currentTimeMillis()
                while (!cancelado) {
                    Thread.sleep(50)
                    val ahora = System.currentTimeMillis()
                    val v = vozDb
                    if (v > pico) pico = v

                    if (v > umbral) {
                        if (desdeVoz == 0L) desdeVoz = ahora
                        ultimaVoz = ahora
                        // sostenido: un portazo pasa el umbral y no es una respuesta
                        if (!contesto && ahora - desdeVoz >= SOSTENIDO_MS) {
                            contesto = true
                            grab.grabando = true
                            fase = Fase.CONTESTANDO
                            paso("TE ESTAN CONTESTANDO.")
                            reg("Interfono: respuesta en la banda de la voz")
                        }
                    } else if (desdeVoz != 0L && ahora - ultimaVoz > SILENCIO_CIERRE_MS) {
                        desdeVoz = 0L
                        if (contesto) break          // ha terminado de hablar
                    }

                    if (!contesto && ahora - t1 > ESCUCHA_MS) break
                    if (contesto && ahora - t1 > ESCUCHA_MAX_MS) break
                }
                grab.grabando = false
                val oido = grab.junto()
                if (cancelado) {
                    h.post { onResultado("Interfono detenido.") }
                    return@thread
                }

                // 5. devolverle al rescatista lo que ha llegado
                mic.quitar(medidor); mic.quitar(grab)
                mic.cerrar(Microfono.USA_INTERFONO)
                if (contesto && oido.isNotEmpty()) {
                    paso("Reproduciendo lo que ha llegado.")
                    sacar(oido, sr)
                }

                val txt = if (contesto)
                    linea(
                        "TE HAN CONTESTADO.",
                        "Pico: %.0f dB sobre el fondo.".format(pico - fondo),
                        "Fondo de este sitio: %.0f dB.".format(fondo),
                        "Duracion de la respuesta: %.1f s.".format(oido.size.toDouble() / sr),
                        "Es energia en la banda de la voz, no una voz reconocida:",
                        "un golpe metalico ritmico tambien la pasa.",
                        "Vuelve a hablar y pide que contesten dos veces."
                    )
                else
                    linea(
                        "Nadie ha contestado.",
                        "Lo mas alto que se ha oido estaba %.0f dB sobre el fondo.".format(pico - fondo),
                        "Hacen falta %.0f dB para contar como respuesta.".format(SOBRE_FONDO_DB),
                        "Pega el movil al escombro y repite:",
                        "el contacto directo gana mas que subir el volumen."
                    )
                reg(if (contesto) "Interfono: contestaron" else "Interfono: sin respuesta")
                estado = if (contesto) "contestaron" else "sin respuesta"
                h.post { onResultado(txt) }
            } catch (ex: Exception) {
                Log.e(TAG, "interfono falló", ex)
                h.post { onResultado("el interfono falló: ${ex.message}") }
            } finally {
                mic.quitar(medidor)
                mic.quitar(grab)
                mic.cerrar(Microfono.USA_INTERFONO)
                ocupado = false
                fase = Fase.CERRADO
            }
        }
    }
}
