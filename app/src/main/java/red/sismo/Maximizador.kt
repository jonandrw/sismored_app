package red.sismo

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.pow

/**
 * Maximizador: limitador de pico con anticipación.
 *
 * Sirve para lo único que importa aquí — que la sirena suene lo más fuerte que
 * el altavoz permita **sin deformar la onda**.
 *
 * El problema con lo que había antes (una tangente hiperbólica) es que sube el
 * volumen percibido a base de redondear los picos, y eso es distorsión: genera
 * armónicos que no estaban, y en un altavoz pequeño a tope se oye como un
 * zumbido sucio que además tapa la propia sirena. Funciona, pero ensucia.
 *
 * Un limitador con anticipación hace lo contrario: **baja la ganancia justo
 * antes de que llegue el pico**, deja pasar la forma de onda intacta y la suelta
 * suavemente después. Como la reducción se aplica a una copia retrasada de la
 * señal, el limitador ya sabe lo que viene:
 *
 *  1. Se retrasa la señal unos milisegundos en un anillo.
 *  2. Con la muestra que ENTRA (la del futuro) se calcula cuánta ganancia hace
 *     falta para que no pase del techo.
 *  3. Si hay que bajar, se baja al instante — pero lo que se está oyendo en ese
 *     momento es la señal de hace unos milisegundos, así que la bajada llega
 *     antes que el pico y no se nota como un golpe.
 *  4. Cuando el pico pasa, la ganancia vuelve a 1 despacio, en curva.
 *
 * Resultado: se puede empujar la señal muy por encima de fondo de escala y
 * ninguna muestra recorta. El pico queda clavado en el techo y la energía media
 * sube mucho, que es lo que el oído entiende como «más alto».
 */
class Maximizador(
    sr: Int,
    /** Cuánto se empuja la señal antes de limitar, en dB. Es el mando de
     *  volumen real: a más empuje, más energía media y más trabaja el limitador. */
    empujeDb: Double = 9.0,
    /** Techo de salida. −0,3 dBFS deja margen para que la reconstrucción del
     *  conversor no se pase por arriba, que es un recorte que nadie ve venir. */
    techoDb: Double = -0.3,
    /** Anticipación. 3 ms bastan para que la bajada sea inaudible y no retrasan
     *  nada perceptible en una sirena. */
    anticipacionMs: Double = 3.0,
    /** Cuánto tarda en devolver la ganancia. Corto zumba, largo apaga la
     *  sirena tras cada pico; 80 ms es el término medio de siempre. */
    caidaMs: Double = 80.0
) {

    private val empuje = 10.0.pow(empujeDb / 20.0)
    private val techo = 10.0.pow(techoDb / 20.0)

    private val n = maxOf(1, (sr * anticipacionMs / 1000.0).toInt())
    private val anillo = DoubleArray(n)
    private var w = 0

    /* Coeficiente exponencial de vuelta: la ganancia recupera el 63 % en `caidaMs`. */
    private val subida = exp(-1.0 / (sr * caidaMs / 1000.0))

    private var ganancia = 1.0
    /** Cuánto está reduciendo ahora mismo, en dB. Útil para enseñarlo o medirlo. */
    val reduccionDb: Double get() = 20.0 * kotlin.math.log10(ganancia.coerceAtLeast(1e-6))

    /**
     * Mete una muestra (de −1 a 1) y devuelve la que toca sacar, ya limitada.
     *
     * Las primeras [n] llamadas devuelven silencio: es el retraso de la
     * anticipación, y en una sirena que suena durante minutos no lo nota nadie.
     */
    fun paso(x: Double): Double {
        val entrada = x * empuje

        // 1) cuánta ganancia admite la muestra que acaba de entrar
        val pico = abs(entrada)
        val quiere = if (pico > techo) techo / pico else 1.0

        /* 2) bajar es inmediato y subir es lento. La bajada se aplica ya, pero
              lo que sale por el altavoz es la muestra retrasada, así que la
              reducción va POR DELANTE del pico que la provocó. */
        ganancia = if (quiere < ganancia) quiere
                   else 1.0 - (1.0 - ganancia) * subida

        // 3) sacar la retrasada con la ganancia de ahora
        val salida = anillo[w] * ganancia
        anillo[w] = entrada
        w = (w + 1) % n

        // cinturón y tirantes: ni un solo desbordamiento del entero de 16 bits
        return salida.coerceIn(-1.0, 1.0)
    }

    fun reiniciar() {
        anillo.fill(0.0)
        w = 0
        ganancia = 1.0
    }
}
