package red.sismo

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Detector sísmico.
 *
 * **Mide la aceleración HORIZONTAL, y ahí está todo.** Nació midiendo el módulo
 * del vector menos su media —el port directo de la versión web— y eso resultó
 * estar afinado justo al revés de lo que tenía que oír, porque la aceleración
 * horizontal entra en cuadratura con la gravedad y apenas mueve el módulo:
 *
 *  - un empujón **horizontal** de 1 m/s² movía el módulo 0,05
 *  - uno **vertical** de 1 m/s² lo movía 1,00, veinte veces más
 *
 * Y lo vertical es exactamente lo que hace una mano al levantarlo, un portazo o
 * el teclado a través de la mesa; lo horizontal es lo que hace un terremoto,
 * que son ondas S. Medido en `fx sounds/sismo.py`: un MMI V daba 0,21 y
 * levantar el móvil 2,39. Once veces más el falso que el bueno — y el bueno no
 * llegaba al umbral ni bajándolo, así que la app tenía a la vez un falso
 * positivo que molestaba y un falso negativo que no se veía.
 *
 * Las decisiones que hay detrás, y que no se tocan sin volver a pasar el banco:
 *
 *  1. **Se resta el vector gravedad y se toma solo lo perpendicular.** El vector
 *     ya se filtra aquí mismo para el giro, así que no cuesta nada. Con eso,
 *     teclear, los portazos y los martillazos en la mesa dan CERO.
 *  2. **Se exige ciclo de trabajo, no muestras seguidas.** El contador antiguo
 *     subía de uno en uno y bajaba de cuatro en cuatro, pensado contra el
 *     correr; pero un terremoto OSCILA y también baja del umbral en cada
 *     semiciclo, y un MMI V no daba más de trece muestras seguidas. La regla que
 *     evitaba un falso garantizaba un mudo.
 *  3. **La media lenta solo se actualiza en calma.** Si se deja correr durante
 *     los picos, las pisadas la arrastran y la calma entre zancadas ya parece
 *     sacudida.
 *  4. **La media rápida recorta a 3x el umbral y baja más rápido de lo que
 *     sube.** Sin eso, tres zancadas fuertes la dejaban saturada.
 *
 * Y lo que se probó y NO vale, para que nadie lo vuelva a intentar: contar los
 * cruces por cero para distinguir un tirón de un terremoto. Con una realización
 * del ruido parecía separarlos (5 contra 8-12); con ocho, levantar da 3-10 y los
 * terremotos 5-16. Se solapan enteros. Era ajustar a una tirada de dados.
 */
class Sismografo(
    ctx: Context,
    private val alDisparar: (String) -> Unit
) : SensorEventListener {

    private val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val acel: Sensor? = sm?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    /** El umbral que se aplica AHORA, en m/s² de aceleración HORIZONTAL. Lo
     *  elige el servicio según la postura: con el móvil encima manda el
     *  conservador (6,0), en reposo el fino (0,25). En [Opciones.umbralReposo]
     *  está por qué estos números no se pueden comparar con los de antes. */
    var umbral = 6.0
    var armado = false

    /**
     * La calma que mide este móvil donde está: el percentil alto de la sacudida
     * mientras nadie lo toca.
     *
     * Es lo que convierte «elige un umbral en m/s²» —que nadie sabe hacer— en
     * «déjalo en la mesilla y él aprende cuánto se mueve esa mesa». Una mesa con
     * la lavadora al lado no es una mesilla de noche, y no tienen por qué
     * compartir número.
     */
    @Volatile var calmaMedida = 0.0; private set

    /** Relación STA/LTA (Short-Term Average / Long-Term Average): contraste
     *  entre la energía instantánea y el piso de ruido ambiente. */
    @Volatile var ltaH = 0.02; private set
    @Volatile var ratioStaLta = 1.0; private set

    /**
     * Cuánto ha girado el móvil en los últimos segundos, en grados.
     *
     * **Este es el dato que separa una mano de un terremoto**, y ninguno de los
     * que probé antes lo hacía:
     *
     *  - Los pasos no valen: alguien sentado en un sofá coge el móvil sin dar uno.
     *  - «Estaba quieto hace dos minutos» tampoco: lo estaba, y por eso el móvil
     *    en la mano acababa con el umbral fino puesto.
     *  - «Cuánto llevaba quieto justo antes del cruce» tampoco: en un terremoto la
     *    sacudida también empieza medio segundo antes de cruzar el umbral, así que
     *    sale pequeño en los dos casos.
     *
     * Lo que de verdad los distingue es la ORIENTACIÓN. Un móvil en una mesa
     * apunta siempre al mismo sitio: durante un terremoto se sacude, pero la
     * gravedad le sigue entrando por la misma cara — el suelo se mueve, la mesa no
     * gira. Una mano no puede sostener nada sin girarlo: al cogerlo, al agitarlo,
     * al andar. Son grados contra décimas de grado, no es un matiz.
     *
     * Se mide contra las direcciones de hace hasta quince segundos, así que un
     * giro lento cuenta igual que uno brusco.
     */
    @Volatile var giroGrados = 0.0; private set

    /** Frente de onda P compresional vertical previa confirmada (AUD-05). */
    @Volatile var ondaP = false; private set
    @Volatile var tUltimaOndaP = 0L; private set
    @Volatile var kurtosisP = 3.0; private set

    /** ¿Hay un frente de onda P primario activo en los últimos segundos? */
    /**
     * Si la onda P puede rebajar lo que se exige para disparar. Apagado.
     *
     * La idea es buena —la onda P llega segundos antes de la que tira la casa—
     * pero el detector, tal y como está calibrado, salta con que alguien roce la
     * mesa: kurtosis > 5,2 con 0,035 m/s² lo cumple un golpe de nudillo. Con
     * esto encendido el listón del ciclo de trabajo baja del 15 % al 7,5 %, y el
     * 8 de septiembre de 2026 eso bastó para que un móvil quieto preguntara
     * «¿estás bien?» solo. Se enciende cuando el umbral esté medido contra ondas
     * P de verdad, no antes.
     */
    @Volatile var preavisoOndaP = false

    /** Onda P detectada Y autorizada a rebajar el listón. */
    private val armadoPorP: Boolean get() = preavisoOndaP && hayOndaP

    val hayOndaP: Boolean
        get() = ondaP && (System.currentTimeMillis() - tUltimaOndaP < ONDA_P_VENTANA_MS)


    /* La dirección de la gravedad, filtrada, y las de los últimos 15 s. El
       filtro es lento a propósito: lo que interesa es hacia dónde apunta el
       móvil, no la sacudida. */
    private var gx = 0.0; private var gy = 0.0; private var gz = 0.0

    /* La MISMA gravedad, pero filtrada rápido (α = 0,25, unos 80 ms a 50 Hz).
       Existe por un problema de tiempos: el filtro lento tarda un segundo en
       enterarse y las direcciones se guardan cada 500 ms, así que cuando alguien
       levanta el móvil `giroGrados` todavía marca lo de antes — y el disparo cae
       dentro de ese hueco. El discriminador estaba bien y llegaba tarde.

       El ángulo entre la rápida y la lenta salta en cuanto una mano toca el
       móvil, porque la rápida ya apunta al sitio nuevo y la lenta aún no. En una
       mesa durante un terremoto las dos apuntan igual: el suelo se mueve, la
       mesa no gira. */
    private var fx = 0.0; private var fy = 0.0; private var fz = 0.0
    private val dirX = DoubleArray(30); private val dirY = DoubleArray(30); private val dirZ = DoubleArray(30)
    private var di = 0; private var dn = 0
    private var ultimaDir = 0L

    private var lta = 9.81
    private var sta = 0.0
    private var caidaLibre = 0
    private var ultimoMovimiento = System.currentTimeMillis()

    /* ---------- el paso-banda de 0,5 a 8 Hz ----------
       Un terremoto destructivo oscila entre medio hercio y ocho, que es también
       donde resuenan los edificios. Todo lo que vibra en una casa está por
       encima —una lavadora centrifuga a 11 Hz, el teclado resuena a 25— y los
       cambios de postura están por debajo de medio.

       Hasta ahora se separaba por DIRECCIÓN (solo lo horizontal) y por DURACIÓN
       (el ciclo de trabajo), que son dos buenos sustitutos pero no lo mismo:
       nada impedía que una vibración de 11 Hz con la mesa un poco inclinada
       metiera componente horizontal sostenida. Ahora se separa además por
       FRECUENCIA, que es la magnitud en la que las dos cosas de verdad se
       distinguen.

       MEDIDO en `fx sounds/sismo.py`, sobre el pico de la media rápida:

       | señal                  | sin filtro | con filtro | efecto |
       |---                     |---         |---         |---     |
       | terremoto MMI V        | 0,410      | 0,380      | ×0,92  |
       | terremoto MMI VI       | 0,659      | 0,647      | ×0,98  |
       | lavadora a 11,5 Hz     | 0,228      | 0,090      | ×0,39  |
       | teclear en la mesa     | 0,069      | 0,032      | ×0,47  |
       | levantar el móvil      | 0,642      | 0,404      | ×0,63  |

       Al terremoto no lo toca y al ruido lo parte por la mitad: la distancia
       entre un MMI V y una lavadora pasa de 1,8 veces a 4,2. */
    private val paLx = Banda(); private val paLy = Banda(); private val paLz = Banda()
    private val paPz = Banda(ONDA_P_BAJA, ONDA_P_ALTA)
    private val notchHx = Notch(); private val notchHy = Notch(); private val notchHz = Notch()
    private val pRing = DoubleArray(40)
    private var pRi = 0; private var pRn = 0

    /** Cada cuánto llegan las muestras, medido — no supuesto. La tasa la decide
     *  el móvil y los coeficientes del filtro dependen de ella. */
    private var srMedido = 50.0
    private var tUltimaMuestra = 0L

    /**
     * Paso-banda de dos biquads en cascada: un paso-alto y un paso-bajo, ambos
     * Butterworth de 2.º orden (Q = 0,707).
     *
     * Se recalcula solo cuando la tasa de muestreo cambia de verdad: un filtro
     * con los coeficientes de 50 Hz corriendo a 100 no filtra la banda que dice
     * filtrar, y eso es otra vez un fallo mudo.
     */
    internal class Banda(
        private val fBaja: Double = BANDA_BAJA,
        private val fAlta: Double = BANDA_ALTA
    ) {
        private var b0h = 0.0; private var b1h = 0.0; private var b2h = 0.0
        private var a1h = 0.0; private var a2h = 0.0
        private var b0l = 0.0; private var b1l = 0.0; private var b2l = 0.0
        private var a1l = 0.0; private var a2l = 0.0
        private var xh1 = 0.0; private var xh2 = 0.0; private var yh1 = 0.0; private var yh2 = 0.0
        private var xl1 = 0.0; private var xl2 = 0.0; private var yl1 = 0.0; private var yl2 = 0.0
        private var srPuesto = 0.0

        fun ajustar(sr: Double) {
            if (abs(sr - srPuesto) < srPuesto * 0.05) return
            srPuesto = sr
            val q = 0.70710678
            // paso-alto en fBaja
            var w = 2.0 * PI * (fBaja.coerceAtMost(sr * 0.45)) / sr
            var c = cos(w); var s = sin(w); var al = s / (2 * q)
            var a0 = 1 + al
            b0h = ((1 + c) / 2) / a0; b1h = (-(1 + c)) / a0; b2h = ((1 + c) / 2) / a0
            a1h = (-2 * c) / a0; a2h = (1 - al) / a0
            // paso-bajo en fAlta
            w = 2.0 * PI * (fAlta.coerceAtMost(sr * 0.45)) / sr
            c = cos(w); s = sin(w); al = s / (2 * q)
            a0 = 1 + al
            b0l = ((1 - c) / 2) / a0; b1l = (1 - c) / a0; b2l = ((1 - c) / 2) / a0
            a1l = (-2 * c) / a0; a2l = (1 - al) / a0
        }

        fun filtrar(x: Double): Double {
            val yh = b0h * x + b1h * xh1 + b2h * xh2 - a1h * yh1 - a2h * yh2
            xh2 = xh1; xh1 = x; yh2 = yh1; yh1 = yh
            val yl = b0l * yh + b1l * xl1 + b2l * xl2 - a1l * yl1 - a2l * yl2
            xl2 = xl1; xl1 = yh; yl2 = yl1; yl1 = yl
            return yl
        }

        fun reiniciar() {
            xh1 = 0.0; xh2 = 0.0; yh1 = 0.0; yh2 = 0.0
            xl1 = 0.0; xl2 = 0.0; yl1 = 0.0; yl2 = 0.0
        }
    }

    /**
     * Filtro Notch IIR biquad de 2.º orden (Audio EQ Cookbook).
     * Atenúa la cadencia de marcha humana en bolsillo (~2 Hz) más de 18 dB
     * sin reducir la respuesta a ondas sísmicas a <1.4 Hz o >2.8 Hz.
     */
    internal class Notch(
        private val f0: Double = NOTCH_MARCHA_F0,
        private val q: Double = NOTCH_MARCHA_Q
    ) {
        private var b0 = 1.0; private var b1 = 0.0; private var b2 = 1.0
        private var a1 = 0.0; private var a2 = 0.0
        private var x1 = 0.0; private var x2 = 0.0
        private var y1 = 0.0; private var y2 = 0.0
        private var srPuesto = 0.0

        fun ajustar(sr: Double) {
            if (abs(sr - srPuesto) < srPuesto * 0.05) return
            srPuesto = sr
            val w0 = 2.0 * PI * (f0.coerceAtMost(sr * 0.45)) / sr
            val c = cos(w0)
            val s = sin(w0)
            val alpha = s / (2.0 * q)
            val a0 = 1.0 + alpha
            b0 = 1.0 / a0
            b1 = (-2.0 * c) / a0
            b2 = 1.0 / a0
            a1 = (-2.0 * c) / a0
            a2 = (1.0 - alpha) / a0
        }

        fun filtrar(x: Double): Double {
            val y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
            x2 = x1; x1 = x
            y2 = y1; y1 = y
            return y
        }

        fun reiniciar() {
            x1 = 0.0; x2 = 0.0
            y1 = 0.0; y2 = 0.0
        }
    }

    /* El anillo de los últimos dos segundos: cuándo se tomó cada muestra y si
       estaba por encima del umbral. Se cuenta por tiempo y no por número de
       muestras porque la tasa del acelerómetro la decide el móvil, no nosotros:
       SENSOR_DELAY_GAME da 50 Hz en unos y 100 en otros, y un contador de
       muestras fijo significaría media ventana en la mitad de los teléfonos. */
    private val anilloT = LongArray(ANILLO)
    private val anilloSta = DoubleArray(ANILLO)
    private var ai = 0
    private var an = 0

    /** Qué parte de los últimos dos segundos ha estado por encima del umbral.
     *  Es lo que se compara con [CICLO_MIN], y se enseña en Diagnóstico. */
    @Volatile var cicloTrabajo = 0.0; private set

    /** La última sacudida fue lo bastante grande como para no confundirse con
     *  una mano. Ver [CICLO_FUERTE]. */
    @Volatile var ultimaFuerte = 0L; private set

    /** Grados entre la gravedad rápida y la lenta: sube en cuanto una mano toca
     *  el móvil, y en una mesa se queda en décimas. Se ve en Diagnóstico. */
    @Volatile var manoGrados = 0.0; private set
    private var ultimaMano = 0L

    /** ¿Ha habido una mano en los últimos segundos? Es lo que impide que
     *  levantar el móvil dispare la alarma con el umbral fino puesto. */
    val hayMano: Boolean get() = System.currentTimeMillis() - ultimaMano < MANO_VALE_MS

    /* Cuánto llevaba quieto el móvil hace unos segundos.
    
       Es el dato que separa TODOS los casos de campo vistos hasta ahora, y
       estaba medido sin usarse:
    
           en la mesa, sin tocar    quieto 32-250 s
           en la mano, siempre      quieto 0-5 s
    
       Un móvil en una mano nunca está quieto, aunque lo sujetes sin moverlo:
       hay pulso. Y tiene que mirarse HACIA ATRÁS, no ahora, porque un terremoto
       de verdad también pone el reloj de quietud a cero — es el mismo error que
       ya se documentó con el régimen de la postura, y por eso allí se guarda el
       de ANTES del suceso. */
    private val quietoRing = LongArray(64)
    private var qi = 0
    private var qUltimo = 0L

    /** Lo que marcaba el reloj de quietud hace unos 3 s. */
    val quietoAntes: Long get() = quietoRing[(qi + 1) % quietoRing.size]

    /**
     * ¿Me puedo creer que lo que se mueve es EL SUELO?
     *
     * **Una sola puerta para los dos caminos, y existe por un error que cometí
     * tres veces seguidas.** El detector tiene dos salidas —el disparo, que
     * lanza la alarma, y `ultimoTemblor`, la bandera blanda que la cascada lee
     * como `sacudida`— y cada vez que endurecí una me olvidé de la otra:
     *
     *   1.ª vez: puse la puerta de la mano en el disparo, no en la bandera.
     *            Resultado: móvil en la mano + un ruido = alarma.
     *   2.ª vez: la borré hacia atrás en la bandera, pero seguía con su propio
     *            juego de condiciones.
     *   3.ª vez: puse la puerta de quietud en el disparo, no en la bandera.
     *            Resultado, medido: «cascada(estruendo por micrófono) ->
     *            PREGUNTAR · terremoto confirmado» sin que el sismógrafo hubiera
     *            disparado ni una vez.
     *
     * Dos caminos con condiciones paralelas garantizan que algún día se separen.
     * Ahora los dos preguntan aquí, y endurecer esto los endurece a los dos.
     */
    private val sueloDeFiar: Boolean
        get() = !hayMano && (umbral > umbralFinoMax || (quietoAntes >= QUIETO_ANTES_MS && ratioStaLta >= 1.8))

    /**
     * El umbral que se aplica de verdad: el elegido, o el suelo de ruido de esta
     * mesa multiplicado por [VECES_CALMA], lo que sea mayor.
     *
     * Se enseña en Diagnóstico junto a la calma para que se pueda ver por qué el
     * móvil vigila al número que vigila. Si aquí sale bastante más que el umbral
     * elegido, es que ese sitio vibra y el teléfono lo ha aprendido.
     */
    val umbralReal: Double
        get() = maxOf(umbral, calmaMedida * VECES_CALMA)
            .coerceAtMost(umbral * TOPE_ADAPTATIVO)

    /** El sitio hace tanto ruido que el umbral se ha ido al tope: en fino ya no
     *  se puede vigilar aquí, y hay que decirlo en vez de fingir que sí. */
    val sitioDemasiadoRuidoso: Boolean
        get() = calmaMedida * VECES_CALMA > umbral * TOPE_ADAPTATIVO

    /** Valor actual de sacudida, para pintarlo en la interfaz. */
    @Volatile var sacudida = 0.0
        private set

    /**
     * Cuándo se notó por última vez que el suelo se movía: la media rápida por
     * encima de MEDIO umbral.
     *
     * No es el disparo —ese exige aguantar varias muestras seguidas y sirve para
     * lanzar la alarma— sino algo más blando: «aquí está temblando». Lo usa la
     * malla para creerse una alerta ajena a la primera en vez de esperar a oírla
     * dos veces, porque durante un terremoto los segundos de la corroboración son
     * justo los que no hay.
     *
     * **Blando no quiere decir sin puerta.** De aquí sale `ServicioSos.temblando`,
     * y la cascada lo lee como `sacudida` — o sea que esto no es un indicador,
     * es una prueba con todas las consecuencias. Se le pasó por alto la puerta de
     * la mano y por ahí se coló una alarma entera con el móvil en la mano y un
     * ruido cualquiera por el micrófono. Ver la nota donde se escribe.
     */
    @Volatile var ultimoTemblor = 0L
        private set

    /* Las últimas lecturas de sacudida, para la traza del sismógrafo — el mismo
       `hist` de 200 de la PWA. A 60 Hz son unos 3,3 s de ventana. Escribe el
       hilo del sensor y lee el de la pantalla: se devuelve una copia ya
       ordenada, que sale más barato que sincronizar sesenta veces por segundo. */
    private val historia = FloatArray(200)
    @Volatile private var hi = 0

    /** La traza en orden cronológico, la lectura más antigua primero. */
    fun traza(): FloatArray {
        val i0 = hi
        return FloatArray(historia.size) { historia[(i0 + it) % historia.size] }
    }

    companion object {
        private const val CAIDA_MIN = 6              // ~100 ms de gravedad casi nula
        private const val IMPACTO = 25.0             // m/s²

        /* ---------- la ventana de oscilación ----------
           Dos segundos. Un terremoto de los que importan sacude segundos
           seguidos; todo lo que pasa encima de una mesa dura décimas. */
        private const val VENTANA_MS = 2000L

        /* Cuánta parte de esa ventana tiene que estar por encima del umbral.

           MEDIDO en `fx sounds/sismo.py`, ocho realizaciones del ruido por caso:

           | señal                                  | ciclo de trabajo |
           |---                                     |---               |
           | teclear en la mesa                     | 0,00             |
           | portazo                                | 0,00             |
           | martillazos en la mesa                 | 0,00             |
           | camión pasando                         | 0,00             |
           | lavadora centrifugando a 11 Hz         | 0,00-0,01        |
           | **terremoto MMI V** (despierta a la gente) | **0,16-0,37** |
           | terremoto MMI VI                       | 0,58-0,93        |
           | terremoto MMI VII                      | 0,88-1,00        |

           0,15 coge los ocho MMI V y deja quince veces de margen contra lo peor
           que produce una mesa. */
        private const val CICLO_MIN = 0.15

        /**
         * El ciclo a partir del cual la sacudida ya NO se confunde con una mano.
         *
         * MEDIDO, y es el resultado más incómodo del banco: al nivel de un MMI V
         * —el que «lo nota todo el mundo»— un empujón en la mesa y un terremoto
         * dan lo mismo. Segundos seguidos con el ciclo por encima del corte:
         *
         *     empujón en la mesa   1,74 s      MMI V   1,98 s / 0,62 s
         *     levantar el móvil    2,58 s      MMI VI  5,96 s
         *
         * No hay umbral que los separe, y eso no es un fallo de ajuste: es el
         * límite del sensor. Lo que sí se distingue es un MMI VI hacia arriba,
         * que sostiene el ciclo por encima de 0,45 largo rato.
         *
         * Así que el detector deja de fingir que puede: por encima de esto va
         * solo, y por debajo pide una segunda opinión — otro móvil de la malla,
         * la alerta de Google o el oído. Ver [Cascada.decidir].
         *
         * **0,85, y estuvo en 0,45 por un error mío de lectura.** Saqué el corte
         * de la tabla de DURACIÓN cuando la que manda es la de CICLO, y esa
         * decía que levantar el móvil da 0,50-0,73. O sea que puse el listón por
         * debajo de lo que hace una mano, y en campo bastó sujetar el teléfono
         * viendo un vídeo para llegar a 0,62 y que pasara por «sacudida fuerte».
         * Solo un MMI VI o mayor sostiene 0,85.
         */
        const val CICLO_FUERTE = 0.85

        /** Tamaño del anillo: dos segundos caben de sobra hasta 128 Hz. */
        private const val ANILLO = 256

        /* ---------- la mano, medida deprisa ----------
           Cuántos grados de desacuerdo entre la gravedad rápida y la lenta hacen
           falta para decir «esto lo está sujetando alguien».

           Ocho grados. Una sacudida horizontal de 1 m/s² inclina el vector
           instantáneo atan(1/9,81) = 5,8°, y la gravedad rápida sigue esa
           inclinación en parte — así que por debajo de ocho se confundiría un
           terremoto fuerte con una mano. Por arriba no hay problema: coger un
           móvil de una mesa lo gira decenas de grados, y no hay forma de
           sujetarlo sin inclinarlo. En una mesa quieta esto se queda en décimas.

           Es el mismo discriminador que `giroGrados`, que ya estaba y es el que
           decide la postura; lo que cambia es el TIEMPO. El lento compara contra
           quince segundos de historia con un filtro de un segundo, y por eso no
           llega a tiempo de parar un disparo que ocurre medio segundo después de
           levantar el móvil. Este llega en ochenta milisegundos. */
        private const val MANO_GRADOS = 8.0

        /** Cuánto dura la sospecha después del último desacuerdo. Cinco segundos:
         *  lo que tarda alguien en coger el móvil, mirarlo y dejarlo. */
        private const val MANO_VALE_MS = 5000L

        /** Cuánto tiene que llevar quieto para fiarse del umbral fino. Veinte
         *  segundos: en campo, un móvil en una mano nunca pasó de cinco, y uno
         *  en una mesa daba de treinta a doscientos cincuenta. */
        private const val QUIETO_ANTES_MS = 20_000L

        /** Por encima de esto el umbral ya es el conservador y la puerta de
         *  quietud no aplica. */
        private const val umbralFinoMax = 1.0

        /** Hacia atrás: cuánto se borra de «aquí tiembla» cuando aparece una
         *  mano. Dos segundos cubren de sobra el arranque de un agarre, y son
         *  muy poco comparados con los segundos que dura una sacudida real. */
        private const val RETRO_MANO_MS = 2000L

        /**
         * Cuántas veces la calma medida hay que superar, además del umbral.
         *
         * **Esto es lo que faltaba, y explica los falsos que echaron la app del
         * móvil.** `calmaMedida` lleva desde el principio midiendo cuánto vibra
         * la superficie donde está el teléfono —el percentil 98 de la calma
         * real—, con un comentario que dice que sirve para convertir «elige un
         * umbral en m/s²» en «déjalo en la mesilla y él aprende». Y luego el
         * disparo no la miraba: comparaba contra un número fijo.
         *
         * En una mesa tranquila da igual, porque la calma es diez veces menor
         * que el umbral. En una mesa que vibra —un ventilador, una nevera al
         * lado, una calle con camiones, un edificio de madera— la calma sube por
         * encima del umbral fijo y entonces **el detector está disparando contra
         * su propio suelo de ruido**, sin parar y sin que nada haya pasado.
         *
         * Cuatro veces. La calma es un percentil 98: superarla cuatro veces es
         * pedir doce decibelios sobre lo que esa mesa hace en su peor rato.
         */
        /** La banda donde vive un terremoto destructivo, y donde resuenan los
         *  edificios. Por encima está todo lo que vibra en una casa; por debajo,
         *  los cambios de postura. */
        private const val BANDA_BAJA = 0.5
        private const val BANDA_ALTA = 8.0

        /** Rango de frecuencias de la Onda P compresional vertical (9-18 Hz, AUD-05). */
        const val ONDA_P_BAJA = 9.0
        const val ONDA_P_ALTA = 18.0

        /** Umbral de Kurtosis para detectar frente impulsivo de Onda P.
         *  El ruido gaussiano estacionario tiene Kurtosis ~3.0; un frente sísmico supera 5.2. */
        const val KURTOSIS_P_MIN = 5.2

        /** Varianza mínima (amplitud RMS > 0.035 m/s²) en el canal P vertical. */
        const val VAR_P_MIN = 0.001225

        /** Ventana temporal de validez de la Onda P (8 segundos antes de la Onda S). */
        const val ONDA_P_VENTANA_MS = 8000L

        /** Parámetros del filtro Notch IIR contra marcha humana en bolsillo (AUD-06). */
        const val NOTCH_MARCHA_F0 = 2.0
        const val NOTCH_MARCHA_Q = 3.5

        private const val VECES_CALMA = 4.0

        /** Por encima de esto ya no es «el sitio», es alguien moviendo el móvil:
         *  no entra en la medida de la calma. Fijo a propósito — ver la nota de
         *  la realimentación donde se usa. */
        private const val CALMA_TECHO = 3.0

        /**
         * Hasta dónde puede subir el umbral por sí solo.
         *
         * Diez veces el elegido. La adaptación existe para no disparar contra el
         * ruido del sitio, no para acabar sordo: si un móvil está encima de una
         * lavadora, lo honesto es que deje de vigilar en fino y se diga, no que
         * suba el listón hasta que no oiga ni un terremoto. Al llegar al tope se
         * anota, para que se pueda leer en vez de adivinarlo.
         */
        private const val TOPE_ADAPTATIVO = 10.0
    }

    fun arrancar() {
        reiniciar()
        acel?.let { sm?.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
    }

    fun parar() {
        sm?.unregisterListener(this)
    }

    /** El estado acumulado se pone a cero al armar y al detener: si no, el
     *  detector arranca con lo que arrastraba y dispara cuando no debe. */
    fun reiniciar() {
        lta = 9.81; sta = 0.0; caidaLibre = 0
        ai = 0; an = 0; cicloTrabajo = 0.0
        ondaP = false; tUltimaOndaP = 0L; kurtosisP = 3.0
        pRi = 0; pRn = 0; pRing.fill(0.0)
        paLx.reiniciar(); paLy.reiniciar(); paLz.reiniciar()
        paPz.reiniciar()
        notchHx.reiniciar(); notchHy.reiniciar(); notchHz.reiniciar()
        historia.fill(0f)
        ultimoMovimiento = System.currentTimeMillis()
    }

    fun quietoDesdeHace(): Long = System.currentTimeMillis() - ultimoMovimiento

    override fun onSensorChanged(e: SensorEvent) {
        val mag = sqrt(
            e.values[0] * e.values[0] +
            e.values[1] * e.values[1] +
            e.values[2] * e.values[2]
        ).toDouble()

        /* Orientación: gravedad filtrada y ángulo contra los últimos 15 s. */
        val ax = e.values[0].toDouble(); val ay = e.values[1].toDouble(); val az = e.values[2].toDouble()
        if (gx == 0.0 && gy == 0.0 && gz == 0.0) { gx = ax; gy = ay; gz = az; fx = ax; fy = ay; fz = az }
        gx += (ax - gx) * 0.02; gy += (ay - gy) * 0.02; gz += (az - gz) * 0.02
        fx += (ax - fx) * 0.25; fy += (ay - fy) * 0.25; fz += (az - fz) * 0.25
        val gn = sqrt(gx * gx + gy * gy + gz * gz)

        /* ¿Hay una mano AHORA MISMO? El ángulo entre las dos gravedades. */
        val fn = sqrt(fx * fx + fy * fy + fz * fz)
        if (gn > 1e-3 && fn > 1e-3) {
            val c = ((gx * fx + gy * fy + gz * fz) / (gn * fn)).coerceIn(-1.0, 1.0)
            manoGrados = Math.toDegrees(kotlin.math.acos(c))
            if (manoGrados > MANO_GRADOS) {
                ultimaMano = System.currentTimeMillis()
                /* Y se BORRA hacia atrás la bandera de «tiembla» que se haya
                   colado en el arranque del agarre.

                   MEDIDO en el Redmi, y es una carrera de dos décimas:

                     20:45:41.931  0.74 m/s2 pero hay una mano (5°)
                     20:45:42.287  0.75 m/s2 pero hay una mano (50°)
                     20:45:42.520  MICRÓFONO: ESTRUENDO / DERRUMBE 90%
                     20:45:42.524  cascada -> AVISAR · sirena

                   La aceleración del agarre llega ANTES de que el giro sea
                   medible: en la primera muestra la mano marca 5°, por debajo
                   del corte, así que `ultimoTemblor` se puso. Y esa bandera dura
                   un minuto, tiempo de sobra para que cualquier ruido por el
                   micrófono la encuentre esperando y salga «terremoto
                   confirmado».

                   No se puede adivinar el futuro en la muestra 1, pero sí
                   corregir en la muestra 15: cuando aparece la mano, lo que se
                   creyó un temblor justo antes era el principio de esa mano. */
                if (ultimoTemblor > 0L && ultimaMano - ultimoTemblor < RETRO_MANO_MS) {
                    ultimoTemblor = 0L
                }
            }
        }
        if (gn > 1e-3) {
            val ux = gx / gn; val uy = gy / gn; val uz = gz / gn
            val ahora = System.currentTimeMillis()
            if (ahora - ultimaDir > 500) {
                ultimaDir = ahora
                dirX[di] = ux; dirY[di] = uy; dirZ[di] = uz
                di = (di + 1) % dirX.size; if (dn < dirX.size) dn++
            }
            var peor = 0.0
            for (k in 0 until dn) {
                val c = (ux * dirX[k] + uy * dirY[k] + uz * dirZ[k]).coerceIn(-1.0, 1.0)
                val ang = Math.toDegrees(kotlin.math.acos(c))
                if (ang > peor) peor = ang
            }
            giroGrados = peor
        }

        /* ---------- 1) la aceleración que importa es la HORIZONTAL ----------
           Antes esto medía `| |a| − media |`: el MÓDULO del vector menos su media
           lenta. Y eso tenía una consecuencia física que se comió el detector
           entero, medida en `fx sounds/sismo.py`:

           El módulo apenas cambia con la aceleración horizontal, porque entra en
           cuadratura con la gravedad. Un empujón horizontal de 1 m/s² lleva el
           módulo de 9,81 a √(9,81²+1²) = 9,86, o sea **0,05 de desviación**. Uno
           vertical de 1 m/s² da 1,00. Veinte veces más.

           Y resulta que la mano levanta en vertical, el portazo llega por la
           mesa en vertical y el teclado también — mientras que **lo que tira los
           edificios es horizontal**, que son las ondas S. El detector estaba
           afinado justo al revés de lo que tenía que oír: un terremoto MMI V
           daba 0,21 y levantar el móvil daba 2,39. Once veces más el falso que
           el bueno, y el bueno no llegaba al umbral ni bajándolo.

           Ahora se resta el vector gravedad —el mismo que ya se filtra arriba
           para el giro— y de lo que queda se toma solo la parte perpendicular a
           él. Con eso, todo lo que llega por la mesa da CERO y el terremoto se
           ve entero. */
        /* La tasa real, medida: los coeficientes del filtro dependen de ella y
           el móvil la elige por su cuenta. */
        val ahoraNs = System.currentTimeMillis()
        if (tUltimaMuestra > 0L) {
            val dt = (ahoraNs - tUltimaMuestra).toDouble()
            if (dt in 2.0..100.0) srMedido += (1000.0 / dt - srMedido) * 0.01
        }
        tUltimaMuestra = ahoraNs
        if (ahoraNs - qUltimo > 50L) {
            qUltimo = ahoraNs
            quietoRing[qi] = ahoraNs - ultimoMovimiento
            qi = (qi + 1) % quietoRing.size
        }
        paLx.ajustar(srMedido); paLy.ajustar(srMedido); paLz.ajustar(srMedido)
        paPz.ajustar(srMedido)
        notchHx.ajustar(srMedido); notchHy.ajustar(srMedido); notchHz.ajustar(srMedido)

        val dev = if (gn > 1e-3) {
            val ux = gx / gn; val uy = gy / gn; val uz = gz / gn
            /* El paso-banda va ANTES de proyectar, sobre los tres ejes del
               móvil: filtrar la magnitud —que siempre es positiva— no filtra
               nada, porque una señal rectificada tiene su energía en otra banda
               que la original. */
            val lx = paLx.filtrar(ax - gx)
            val ly = paLy.filtrar(ay - gy)
            val lz = paLz.filtrar(az - gz)
            val vert = lx * ux + ly * uy + lz * uz          // lo que va con la gravedad
            var hx = lx - vert * ux
            var hy = ly - vert * uy
            var hz = lz - vert * uz

            // AUD-06: Filtro Notch a 2.0 Hz contra impactos de zancada humana en bolsillo
            if (umbral > umbralFinoMax) {
                hx = notchHx.filtrar(hx)
                hy = notchHy.filtrar(hy)
                hz = notchHz.filtrar(hz)
            }

            // AUD-05: Canal vertical de compresión Onda P (9.0 - 18.0 Hz)
            val linVert = (ax - gx) * ux + (ay - gy) * uy + (az - gz) * uz
            val vertP = paPz.filtrar(linVert)
            pRing[pRi] = vertP
            pRi = (pRi + 1) % pRing.size
            if (pRn < pRing.size) pRn++
            if (pRn >= 30) {
                var sum = 0.0
                for (k in 0 until pRn) sum += pRing[k]
                val mean = sum / pRn
                var m2 = 0.0; var m4 = 0.0
                for (k in 0 until pRn) {
                    val d = pRing[k] - mean
                    val d2 = d * d
                    m2 += d2; m4 += d2 * d2
                }
                m2 /= pRn; m4 /= pRn
                val kurt = if (m2 > 1e-8) m4 / (m2 * m2) else 3.0
                kurtosisP = kurt
                if (kurt > KURTOSIS_P_MIN && m2 > VAR_P_MIN && !hayMano) {
                    tUltimaOndaP = ahoraNs
                    ondaP = true
                }
            }

            hypot(hypot(hx, hy), hz)
        } else 0.0

        /* La media lenta del módulo se sigue llevando, pero ya solo para saber si
           el móvil está quieto: eso sí tiene que notar un empujón venga de donde
           venga, porque de ahí sale la postura. */
        val dev0 = abs(mag - lta)
        if (dev0 < 1.0) lta += (mag - lta) * 0.004
        /* «Quieto» quiere decir quieto de verdad: medido con el móvil sobre una
           mesa, esto no salta ni una vez en ochenta segundos, y la postura pasa a
           EN_REPOSO como debe. Es el respaldo del que depende todo, porque ni el
           Redmi (Android 15) ni el A10s (Android 11) tienen los detectores de un
           disparo de AOSP: `TYPE_STATIONARY_DETECT` y `TYPE_MOTION_DETECT` no
           existen en ninguno de los dos. */
        /* Y aquí va el vector lineal ENTERO, no solo la horizontal: para el
           sismógrafo lo vertical es ruido de mesa, pero para «¿lo ha tocado
           alguien?» un tirón hacia arriba es exactamente lo que hay que notar.
           Si esto mirase solo la horizontal, levantar el móvil en plano no
           contaría como movimiento y la postura seguiría diciendo EN_REPOSO con
           el teléfono en la mano. */
        val devTotal = hypot(hypot(ax - gx, ay - gy), az - gz)
        if (devTotal > 0.6) ultimoMovimiento = System.currentTimeMillis()

        // 3) media rápida con recorte y bajada más rápida que la subida
        val u = umbralReal
        val devc = min(dev, u * 3)
        sta += (devc - sta) * (if (devc > sta) 0.25 else 0.5)
        sacudida = sta
        // medio umbral: el suelo se mueve, aunque todavía no sea para disparar
        /* Y aquí faltaba la puerta de la mano, que es por donde se colaba TODO.
           MEDIDO en el Redmi, con el registro delante:

             10:06:49  sismografo: 3.06 m/s2 pero hay una mano (35°), no disparo
             ... nueve veces seguidas, la puerta funcionando
             10:07:12  cascada(estruendo por micrófono) -> PREGUNTAR
             10:08:12  cascada(nadie ha contestado) -> BALIZA

           El disparo estaba bien protegido; esta bandera no. `ultimoTemblor`
           dice «aquí se mueve el suelo» con MEDIO umbral, sin ciclo de trabajo y
           —hasta ahora— sin mirar si hay una mano. Con el móvil en la mano se
           ponía a verdadero de continuo, y de ahí sale `ServicioSos.temblando`,
           que la cascada lee como `sacudida`. Con eso, un estruendo cualquiera
           por el micrófono ya tenía su «sacudida» al lado y salía «terremoto
           confirmado». Mano más un ruido igual a alarma.

           La bandera dice «se mueve EL SUELO». Una mano invalida esa frase
           exactamente igual que invalida el disparo, así que lleva la misma
           puerta. */
        /* Y AQUÍ estaba la asimetría que quedaba, que es de las peores del
           proyecto: el disparo real exige el 15 % de dos segundos por encima del
           umbral, y esta bandera se conformaba con UNA muestra por encima de
           MEDIO umbral. Con el umbral fino en 0,25, un pico de 0,13 —un camión
           que pasa, un portazo lejano— la encendía... y luego dura un minuto
           entero.

           La cascada no la lee como un indicador: la lee como `sacudida`, con
           todas las consecuencias. O sea que un pico de 0,13 dejaba armada
           «aquí ha temblado» durante sesenta segundos, y cualquier ruido que el
           micrófono llamara derrumbe en ese minuto salía como «terremoto
           confirmado». Eso es exactamente lo de «en reposo, sin tocarlo, se
           metió en emergencia».

           Ahora es una versión BLANDA del disparo, no otra cosa distinta: mismo
           tipo de prueba, listón más bajo. Sigue sirviendo para lo que existe
           —que la malla se crea una alerta ajena a la primera— porque un
           terremoto de verdad llega a esto en menos de un segundo. */
        val cicloTemblorReq = if (armadoPorP) CICLO_MIN * 0.25 else CICLO_MIN * 0.5
        if (sueloDeFiar && sta > u * 0.6 && cicloTrabajo >= cicloTemblorReq) {
            ultimoTemblor = System.currentTimeMillis()
        }
        historia[hi] = sta.toFloat(); hi = (hi + 1) % historia.size

        /* La calma se mide SOLO cuando no está pasando nada: si se dejara correr
           durante un evento, aprendería que el terremoto es normal. Y se guarda
           un percentil alto, no la media: lo que hay que superar no es el ruido
           típico de la mesa, es su peor rato. */
        /* Y se mide SIN MIRAR EL UMBRAL, que es donde estuvo el error la
           primera vez que se conectó esto: si el umbral sube con la calma y la
           calma se mide comparándola con el umbral, los dos se empujan hacia
           arriba y el detector acaba sordo sin que nadie lo note — el mismo
           fallo mudo de siempre, pero esta vez con realimentación.

           El criterio es físico y fijo: se mide cuando NO hay una mano encima y
           la aceleración cabe dentro de lo que puede ser un sitio, no un
           terremoto. Eso no depende de ningún ajuste. */
        /* Con DOS condiciones, y la segunda es la que faltaba. La de la mano
           sola no basta: en campo movieron el móvil SIN girarlo —`mano 0,0°`— y
           el empujón entero entró en la medida de la calma, que pasó de 0,052 a
           0,565 y se llevó el umbral a 2,26. La calma se comió el suceso.

           El listón de referencia es el CONFIGURADO, nunca `umbralReal`: si se
           comparase contra el adaptativo, calma y umbral se empujarían el uno al
           otro hacia arriba hasta dejar el detector sordo. */
        /* STA/LTA sismológico recursivo:
           LTA estima el ruido de fondo continuo de la mesa (~15 s).
           Se congela si hay sacudida transitoria (sta > ltaH * 2.0) o si hay mano,
           para que un sismo o un empujón no contaminen la calma. Sin quicksort ni
           asignaciones de memoria en el bucle del sensor. */
        val congelarLta = hayMano || sta > ltaH * 2.0 || sta > umbral * 0.4
        if (!congelarLta && dev < CALMA_TECHO) {
            val alphaLta = (1.0 / (maxOf(20.0, srMedido) * 15.0)).coerceIn(0.0005, 0.005)
            ltaH += (dev - ltaH) * alphaLta
            calmaMedida = ltaH * 1.5
        }
        val ltaPiso = maxOf(ltaH, 0.015)
        ratioStaLta = sta / ltaPiso

        // Caída libre seguida de impacto: el móvil se soltó de la mano y golpeó.
        // Correr no lo activa: nunca da 100 ms seguidos de gravedad casi nula.
        if (mag < 3.0) {
            caidaLibre++
        } else {
            if (caidaLibre > CAIDA_MIN && mag > IMPACTO) {
                Log.i("SismoRed", "caida libre + impacto ${"%.0f".format(mag)} m/s2")
                if (armado) alDisparar("caída libre + impacto")
            }
            caidaLibre = 0
        }

        /* ---------- 2) el ciclo de trabajo, no un contador ----------
           Antes esto subía de uno en uno por encima del umbral y bajaba de
           cuatro en cuatro por debajo, y había que aguantar 36 muestras seguidas.
           Se diseñó contra el correr, que son picos con calma en medio — pero
           **un terremoto también baja del umbral en cada semiciclo**, porque
           oscila. Medido: un MMI V no pasaba de trece muestras seguidas, así que
           con el castigo de cuatro por hueco el contador no llegaba nunca. La
           regla que evitaba un falso garantizaba un mudo.

           Ahora se mide qué PARTE de los últimos dos segundos ha estado por
           encima. A un terremoto le sobra; a un portazo, que es una muestra, no
           le llega ni de lejos. */
        val ahoraMs = System.currentTimeMillis()
        anilloT[ai] = ahoraMs
        /* Se guarda el VALOR, no «estaba alto». MEDIDO en campo: al subir el
           umbral de 0,25 a 2,26 de golpe, el anillo seguía lleno de «altos»
           calculados contra 0,25 y el ciclo salió del 29 % con la sacudida en
           0,15 — muy por debajo del umbral. Un historial de booleanos solo vale
           si el listón no se mueve, y aquí se mueve por diseño. */
        anilloSta[ai] = sta
        ai = (ai + 1) % ANILLO
        if (an < ANILLO) an++
        var total = 0; var altos = 0
        for (k in 0 until an) {
            val j = (ai - 1 - k + ANILLO) % ANILLO
            if (ahoraMs - anilloT[j] > VENTANA_MS) break
            total++
            if (anilloSta[j] > u) altos++
        }
        cicloTrabajo = if (total > 0) altos.toDouble() / total else 0.0

        /* Media ventana de muestras como mínimo: recién arrancado el anillo está
           casi vacío y tres muestras altas de tres darían un ciclo de 1,00.
           Con Onda P previa confirmada (AUD-05), se reduce a 10 muestras y 50% de ciclo. */
        val minMuestras = if (armadoPorP) 10 else 20
        val cicloReq = if (armadoPorP) CICLO_MIN * 0.5 else CICLO_MIN
        if (armado && total > minMuestras && cicloTrabajo >= cicloReq) {
            /* Y la última puerta, que es la que faltaba: si hay una mano, esto no
               es el suelo. Va AQUÍ y no en el servicio a propósito — el servicio
               revisa la postura una vez por segundo, y levantar un móvil y que
               dispare cabe entero dentro de ese segundo. Esta se entera en
               ochenta milisegundos. */
            /* Y la puerta que de verdad funciona: con el umbral FINO, el móvil
               tiene que haber estado quieto de verdad antes de esto. Se mira el
               reloj de hace tres segundos porque la propia sacudida lo pone a
               cero. Con el umbral conservador no se aplica: ahí ya se asume que
               lo llevas encima. */
            if (!sueloDeFiar && !hayMano) {
                an = 0; ai = 0
                Log.i("SismoRed", "sismografo: %.2f m/s2 pero no estaba quieto (%d s), no disparo"
                    .format(sta, quietoAntes / 1000))
                return
            }
            if (hayMano) {
                an = 0; ai = 0
                Log.i("SismoRed", "sismografo: %.2f m/s2 pero hay una mano (%.0f°), no disparo"
                    .format(sta, manoGrados))
                return
            }
            if (cicloTrabajo >= CICLO_FUERTE) ultimaFuerte = System.currentTimeMillis()
            an = 0; ai = 0                       // el anillo se vacía tras disparar
            Log.i("SismoRed", "sismografo %.2f m/s2 horizontal · STA/LTA %.1fx · ciclo %.2f (req %.2f) · P-wave %b · umbral real %.2f (calma %.3f)"
                .format(sta, ratioStaLta, cicloTrabajo, cicloReq, hayOndaP, u, calmaMedida))
            alDisparar("sismógrafo %.2f m/s² (STA/LTA %.1fx) sobre %.2f · %d%% de dos segundos%s"
                .format(sta, ratioStaLta, u, (cicloTrabajo * 100).toInt(), if (hayOndaP) " [Onda P previa]" else ""))
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    /* ===================== autotest =====================
       El sismógrafo no tenía ninguno, y es el detector donde más caro sale: no
       se puede comprobar usándolo —haría falta un terremoto— y sus dos fallos
       son mudos. El de verdad estuvo meses ahí: MMI V daba 0,21 contra un umbral
       de 0,8 y nadie podía notarlo, porque un detector que no dispara se ve
       exactamente igual que un detector que no tiene nada que detectar.

       Son los mismos casos de `fx sounds/sismo.py`, sintetizados aquí para que
       corran en el propio móvil dentro de COMPROBAR TODO. Tardan milisegundos. */

    /** Un caso: 12 s de acelerómetro a 50 Hz, con el móvil tumbado boca arriba. */
    internal fun escena(
        semilla: Long,
        horizontal: Double = 0.0, vertical: Double = 0.0,
        frecs: DoubleArray = doubleArrayOf(0.7, 1.3, 2.1, 3.4, 4.6),
        desde: Double = 3.0, dura: Double = 8.0,
        golpes: DoubleArray = DoubleArray(0), golpeAmp: Double = 0.0,
        tiron: Double = 0.0,
        ondaPAmp: Double = 0.0, ondaPT: Double = 1.5
    ): Array<DoubleArray> {
        val sr = 50.0
        val n = (12.0 * sr).toInt()
        val rnd = java.util.Random(semilla)
        val out = Array(n) { doubleArrayOf(0.0, 0.0, 9.81) }
        // Frente de Onda P previa en eje vertical (9-18 Hz)
        if (ondaPAmp > 0.0) {
            val i0 = (ondaPT * sr).toInt()
            val largo = (0.6 * sr).toInt()
            for (k in 0 until largo) {
                val j = i0 + k
                if (j < n) {
                    val t = k / sr
                    val env = kotlin.math.sin(PI * k / largo)
                    out[j][2] += ondaPAmp * env * kotlin.math.sin(2.0 * PI * 13.0 * t)
                }
            }
        }
        if (horizontal > 0.0 || vertical > 0.0) {
            val n0 = (desde * sr).toInt()
            val nd = (dura * sr).toInt()
            val fase = DoubleArray(frecs.size) { rnd.nextDouble() * 6.283 }
            for (i in 0 until nd) {
                val t = i / sr
                val env = min(t / 1.2, 1.0) * kotlin.math.exp(-t / (dura * 0.7))
                var s = 0.0
                for (k in frecs.indices) s += kotlin.math.sin(6.283 * frecs[k] * t + fase[k]) / sqrt(frecs[k])
                s = s / 2.2 * env
                val j = n0 + i
                if (j < n) {
                    out[j][0] += s * horizontal
                    out[j][1] += s * horizontal * 0.8
                    out[j][2] += s * vertical
                }
            }
        }
        // golpes: impulsos verticales que resuenan y se apagan, como en una mesa
        for (tg in golpes) {
            val i0 = (tg * sr).toInt()
            val largo = (0.15 * sr).toInt()
            for (k in 0 until largo) {
                val j = i0 + k
                if (j < n) out[j][2] += golpeAmp * kotlin.math.exp(-k / (0.025 * sr)) *
                    kotlin.math.sin(6.283 * 14.0 * k / sr)
            }
        }
        /* Levantarlo: el tirón vertical y, sobre todo, la INCLINACIÓN. Nadie coge
           un móvil de una mesa sin girarlo, y a partir de ahí la gravedad le
           entra por otra cara. */
        if (tiron > 0.0) {
            val i0 = (4.0 * sr).toInt(); val largo = (0.18 * sr).toInt()
            for (k in 0 until largo) {
                if (i0 + k < n) out[i0 + k][2] += tiron
                if (i0 + largo + k < n) out[i0 + largo + k][2] -= tiron
            }
            // y se queda inclinado unos 35°, con el pulso encima
            val ang = Math.toRadians(35.0)
            for (j in (i0 + largo) until n) {
                val t = (j - i0 - largo) / sr
                val a = ang * min(t / 0.4, 1.0)
                out[j][0] += 9.81 * kotlin.math.sin(a)
                out[j][2] += 9.81 * (kotlin.math.cos(a) - 1.0)
                out[j][0] += 0.25 * kotlin.math.sin(6.283 * 4.0 * t)   // pulso
                out[j][1] += 0.18 * kotlin.math.sin(6.283 * 5.3 * t)
            }
        }
        for (i in 0 until n) for (e in 0..2) out[i][e] += rnd.nextGaussian() * 0.02
        return out
    }

    /** Pasa una escena por la misma lógica de [onSensorChanged] y dice si dispara. */
    private fun correrEscena(datos: Array<DoubleArray>, umbralPrueba: Double): Boolean =
        correrEscenaDetalle(datos, umbralPrueba).first

    /** @return (dispara, se-habría-levantado-la-bandera-de-tiembla) */
    internal fun correrEscenaDetalle(datos: Array<DoubleArray>, umbralPrueba: Double): Pair<Boolean, Boolean> {
        val paLx = Banda(); val paLy = Banda(); val paLz = Banda()
        val notchHx = Notch(); val notchHy = Notch(); val notchHz = Notch()
        val paPz = Banda(ONDA_P_BAJA, ONDA_P_ALTA)
        val sr = 50.0
        paLx.ajustar(sr); paLy.ajustar(sr); paLz.ajustar(sr)
        notchHx.ajustar(sr); notchHy.ajustar(sr); notchHz.ajustar(sr)
        paPz.ajustar(sr)

        val u0x = datos[0][0]; val u0y = datos[0][1]; val u0z = datos[0][2]
        val g0 = sqrt(u0x * u0x + u0y * u0y + u0z * u0z)
        var lgx = datos[0][0]; var lgy = datos[0][1]; var lgz = datos[0][2]
        var rfx = lgx; var rfy = lgy; var rfz = lgz
        var s = 0.0
        val altos = BooleanArray(datos.size)
        val mano = BooleanArray(datos.size)
        var temblo = false
        val pRing = DoubleArray(40)
        var pRi = 0; var pRn = 0
        var ondaP = false

        for (i in datos.indices) {
            val x = datos[i][0]; val y = datos[i][1]; val z = datos[i][2]
            lgx += (x - lgx) * 0.02; lgy += (y - lgy) * 0.02; lgz += (z - lgz) * 0.02
            rfx += (x - rfx) * 0.25; rfy += (y - rfy) * 0.25; rfz += (z - rfz) * 0.25
            val g = sqrt(lgx * lgx + lgy * lgy + lgz * lgz)
            val f = sqrt(rfx * rfx + rfy * rfy + rfz * rfz)
            var esMano = false
            if (g > 1e-3 && f > 1e-3) {
                val c = ((lgx * rfx + lgy * rfy + lgz * rfz) / (g * f)).coerceIn(-1.0, 1.0)
                val angRapido = Math.toDegrees(kotlin.math.acos(c))
                val c0 = if (g0 > 1e-3) ((lgx * u0x + lgy * u0y + lgz * u0z) / (g * g0)).coerceIn(-1.0, 1.0) else 1.0
                val angLento = Math.toDegrees(kotlin.math.acos(c0))
                esMano = angRapido > MANO_GRADOS || angLento > MANO_GRADOS
                mano[i] = esMano
            }
            val h = if (g > 1e-3) {
                val ux = lgx / g; val uy = lgy / g; val uz = lgz / g
                val lx = x - lgx; val ly = y - lgy; val lz = z - lgz
                val v = lx * ux + ly * uy + lz * uz
                var hx = lx - v * ux; var hy = ly - v * uy; var hz = lz - v * uz
                if (umbralPrueba > umbralFinoMax) {
                    hx = notchHx.filtrar(hx)
                    hy = notchHy.filtrar(hy)
                    hz = notchHz.filtrar(hz)
                }

                // Canal P
                val linVert = (x - lgx) * ux + (y - lgy) * uy + (z - lgz) * uz
                val vertP = paPz.filtrar(linVert)
                pRing[pRi] = vertP
                pRi = (pRi + 1) % pRing.size
                if (pRn < pRing.size) pRn++
                if (pRn >= 30) {
                    var sum = 0.0
                    for (k in 0 until pRn) sum += pRing[k]
                    val mean = sum / pRn
                    var m2 = 0.0; var m4 = 0.0
                    for (k in 0 until pRn) {
                        val d = pRing[k] - mean
                        val d2 = d * d
                        m2 += d2; m4 += d2 * d2
                    }
                    m2 /= pRn; m4 /= pRn
                    val kurt = if (m2 > 1e-8) m4 / (m2 * m2) else 3.0
                    if (kurt > KURTOSIS_P_MIN && m2 > VAR_P_MIN && !esMano) {
                        ondaP = true
                    }
                }

                hypot(hypot(hx, hy), hz)
            } else 0.0
            val hc = min(h, umbralPrueba * 3)
            s += (hc - s) * (if (hc > s) 0.25 else 0.5)
            altos[i] = s > umbralPrueba

            // Retroactividad: cuando aparece una mano, invalida lo que se creyó un temblor u onda P
            if (esMano) {
                ondaP = false
                temblo = false
            } else {
                var conMano0 = false
                for (k in 0 until minOf(250, i)) if (mano[i - k]) { conMano0 = true; break }
                if (conMano0) {
                    temblo = false
                } else {
                    val umbralTemblor = if (ondaP) umbralPrueba * 0.4 else umbralPrueba * 0.5
                    if (s > umbralTemblor) temblo = true
                }
            }
        }

        val w = 100
        val wm = 250
        val cicloMinReq = if (ondaP) CICLO_MIN * 0.5 else CICLO_MIN
        for (i in w until datos.size) {
            var c = 0
            for (k in 0 until w) if (altos[i - k]) c++
            if (c.toDouble() / w < cicloMinReq) continue
            var conMano = false
            for (k in 0 until minOf(wm, i)) if (mano[i - k]) { conMano = true; break }
            if (!conMano) return true to temblo
        }
        return false to temblo
    }

    /**
     * Los escenarios de una mesa real contra un terremoto de verdad.
     */
    fun autotest(): Pair<Boolean, String> {
        val u = 0.25
        val casos = listOf(
            Triple("teclear en la mesa", false,
                escena(3, golpes = doubleArrayOf(3.1, 3.4, 3.7, 4.0, 4.4, 4.8, 5.1, 5.5), golpeAmp = 0.9)),
            Triple("martillazos en la mesa", false,
                escena(19, golpes = doubleArrayOf(4.0, 4.5, 5.1, 5.6), golpeAmp = 3.0)),
            Triple("levantarlo de golpe", false, escena(5, tiron = 3.0)),
            Triple("cogerlo despacio para mirarlo", false, escena(41, tiron = 1.2)),
            Triple("mirándolo con la mano en movimiento", false,
                escena(47, tiron = 2.0, horizontal = 0.9, vertical = 0.5,
                    frecs = doubleArrayOf(1.8, 3.2, 5.1), desde = 5.0, dura = 6.0)),
            Triple("lavadora centrifugando", false,
                escena(13, horizontal = 0.25, vertical = 0.2,
                    frecs = doubleArrayOf(11.5), desde = 1.0, dura = 10.0)),
            Triple("camión pasando", false,
                escena(17, horizontal = 0.15, vertical = 0.12,
                    frecs = doubleArrayOf(2.2, 3.1, 4.4), dura = 6.0)),
            /* AUD-06: Marcha humana rítmica en bolsillo (2.0 Hz) atenuada por filtro Notch */
            Triple("marcha humana en bolsillo a 2.0 Hz", false,
                escena(53, horizontal = 2.0, vertical = 0.8, frecs = doubleArrayOf(2.0), dura = 8.0)),
            Triple("TERREMOTO MMI V", true, escena(11, horizontal = 0.7, vertical = 0.42)),
            /* AUD-05: Terremoto bi-fase con frente de Onda P vertical 13 Hz previo a Onda S */
            Triple("TERREMOTO Bi-Fase P/S (pre-aviso)", true,
                escena(29, horizontal = 0.65, vertical = 0.35, ondaPAmp = 0.14, ondaPT = 1.6)),
            Triple("TERREMOTO MMI VI", true, escena(23, horizontal = 1.3, vertical = 0.78)),
            Triple("TERREMOTO MMI VII", true, escena(31, horizontal = 2.5, vertical = 1.5))
        )
        val partes = ArrayList<String>()
        var todo = true
        for ((nombre, debe, datos) in casos) {
            val uCaso = if (nombre.contains("bolsillo")) 6.0 else u
            val dispara = try { correrEscena(datos, uCaso) } catch (e: Exception) { false }
            val ok = dispara == debe
            if (!ok) todo = false
            partes.add("$nombre → ${if (dispara) "dispara" else "no"}" + if (ok) " OK" else " FALLÓ")
        }
        val (_, tembloConMano) = correrEscenaDetalle(
            escena(47, tiron = 2.0, horizontal = 0.9, vertical = 0.5,
                frecs = doubleArrayOf(1.8, 3.2, 5.1), desde = 5.0, dura = 6.0), u)
        if (tembloConMano) {
            todo = false
            partes.add("con el móvil en la mano dice que TIEMBLA · FALLÓ")
        } else {
            partes.add("con el móvil en la mano no dice que tiembla OK")
        }
        val (_, tembloTerremoto) = correrEscenaDetalle(
            escena(11, horizontal = 0.7, vertical = 0.42), u)
        if (!tembloTerremoto) {
            todo = false
            partes.add("en un terremoto NO dice que tiembla · FALLÓ")
        } else {
            partes.add("en un terremoto dice que tiembla OK")
        }

        val txt = partes.joinToString(" | ")
        Log.i("SismoRed", "autotest sismógrafo · $txt")
        return todo to txt
    }
}
