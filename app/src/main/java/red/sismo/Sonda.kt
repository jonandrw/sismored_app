package red.sismo

import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlin.concurrent.thread
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Sonda acústica: qué hay alrededor de quien lleva el móvil.
 *
 * Son tres herramientas distintas, todas del lado de quien está atrapado o de
 * quien busca, y todas manuales — ninguna corre sola gastando batería.
 *
 *  - **Eco**: un chirp corto y un filtro adaptado. Dice a qué distancia están
 *    las superficies y cuánto tarda en morir el sonido, que es lo que distingue
 *    un hueco pequeño entre escombros de una nave industrial.
 *  - **Doppler**: un tono fijo y sus bandas laterales. Dice si algo se mueve
 *    cerca. **No** dice cuántos, ni dónde.
 *  - **Respiración**: el mismo tono, veinte segundos, mirando si esa modulación
 *    tiene el periodo de un pecho que sube y baja. Es lo que separa un cuerpo de
 *    una lona suelta, y lo único que se acerca a decir «hay alguien vivo ahí».
 *  - **Barrido de penetración**: 80 Hz a 4 kHz. Los escombros filtran distinto
 *    según la frecuencia y nadie sabe cómo filtra ESTE montón, así que se barre
 *    entero para que algo pase sí o sí.
 *
 * Port de la segunda mitad de `SismoRed/listen.js`.
 */
class Sonda(
    private val mic: Microfono,
    private val onRegistro: (String) -> Unit = {}
) {

    companion object {
        private const val TAG = "SismoRed"
        private const val C_AIRE = 343.0        // velocidad del sonido, m/s
        private const val DUR_CHIRP = 0.10      // 100 ms
        private const val F0 = 2000.0
        private const val F1 = 8000.0

        /* ---------- sondeo por ráfaga ----------
           Un chasquido suelto da una medida y ninguna forma de saber si es
           buena. Ocho chasquidos permiten APILAR las correlaciones: el eco real
           cae siempre en el mismo sitio y se suma, y el ruido cae en sitios
           distintos y se promedia a la baja — la relación señal/ruido mejora con
           la raíz del número de disparos, unos 9 dB con ocho.

           Y, sobre todo, deja medir el error de verdad: si los ocho dicen 118 cm
           el número vale; si dicen entre 90 y 150 no hay pared ahí, hay ruido. */
        private const val CHASQUIDOS = 8
        /** Intentos automaticos antes de dar un resultado, en las tres herramientas
         *  de medida. Una medida suelta puede tener mala suerte —un golpe, una
         *  puerta, un coche— y repetirla cinco veces sin que nadie tenga que pulsar
         *  nada es lo que convierte un numero en algo que se puede creer. Al quinto
         *  se imprime y la herramienta se apaga sola: asi el resultado se queda
         *  quieto en la pantalla en vez de borrarse con el intento siguiente. */
        const val INTENTOS = 5
        private const val ALCANCE_M = 6.0

        /* ---------- por qué el chasquido suena SIEMPRE, y se oye ----------
           Tres cosas lo tapaban, y hacían falta las tres:

           1. El camino de audio de Android tarda entre 50 y 150 ms en arrancar, y
              el chirp era más corto que eso: unas veces no llegaba a salir y otras
              salía tarde. Lo arregla el silencio de `PRE_MS` por delante, y que
              `Altavoz` no suelte el track hasta que la cabeza de reproducción haya
              pasado por el último marco.
           2. Sonaba al volumen de ALARMA que tuviera puesto el usuario. `USAGE_ALARM`
              salta el modo silencio pero no sube el volumen — eso lo hacía solo la
              sirena. Ahora la ráfaga entera va dentro de `Altavoz.aTodoVolumen`.
           3. Y seguía sin oírse con 40 ms. El oído integra la energía en unos
              200 ms: un chasquido más corto que eso se percibe como un tic mínimo
              aunque salga a fondo de escala — por eso el barrido, que son 2,5 s
              seguidos, sí se oye alto. `DUR_CHIRP` son ahora **100 ms**, cinco veces
              la energía del original. La resolución no sufre: en un filtro adaptado
              la manda el ANCHO DE BANDA (6 kHz ≈ 3 cm), no la duración.

              Alargarlo obliga a re-sintonizar la captura, y no es opcional: con
              100 ms de chirp más 120 ms de incertidumbre de latencia no cabe todo en
              una ventana de 250 ms. La ventana pasa a 400 ms y la espera a 280, que
              deja el chasquido y su eco dentro con margen por los dos lados. Y 400 ms
              siguen siendo menos que los ~660 que separan un disparo del siguiente,
              así que en la ventana no puede colarse el chasquido anterior.

           Además, como ahora se sabe cuándo suena, la espera puede bajar: antes
           había que dar 350 ms de margen porque no se sabía. */

        private const val PRE_MS = 150

        /** Margen para la latencia de captura, que en Android no es despreciable.
         *  Se cuenta desde que el chasquido ha SONADO, no desde que se pide. La
         *  ventana que se lee luego son 400 ms, así que el chasquido y su eco tienen
         *  que caer dentro: con 280 ms de espera hay unos 20 ms de holgura por
         *  delante y 145 por detrás, que cubre lo que puede variar la latencia de
         *  entrada de un móvil a otro. */
        private const val ESPERA_MS = 280L
        /** Silencio entre chasquidos: da tiempo a que muera la cola del anterior. */
        private const val PAUSA_MS = 120L
        /** Ventana de captura, en muestras. 400 ms: ver la nota de arriba. */
        private fun VENTANA_N(sr: Int) = (sr * 0.40).roundToInt()
        /** Sobre la pila el ruido ya está promediado, así que se puede bajar el
         *  listón respecto al 0,18 que hacía falta con un solo disparo. */
        private const val UMBRAL_PILA = 0.12
        private const val UMBRAL_INDIV = 0.10
        /** El doppler va justo por encima de la banda de la malla; ver `doppler()`. */
        private const val F_DOPPLER = 18500.0

        /* ---------- respiración; ver `respiracion()` ---------- */
        /** Veinticinco segundos. Son tres periodos del ritmo más lento que se
         *  busca (9 respiraciones por minuto = 6,7 s cada una) más el segundo que
         *  se descarta al arrancar el tono. Con veinte no llegaba: la serie se
         *  quedaba en 200 muestras contra las 201 que exige el análisis, y el
         *  veredicto salía «nada se mueve» hiciera lo que hiciera. */
        const val RESP_SEG = 25
        /** Diez muestras por segundo. La respiración es cosa de segundos, no de
         *  milisegundos: muestrear más rápido solo añade ruido a la serie. */
        private const val MUESTRAS_S = 10
        /** 9 a 36 respiraciones por minuto, en hercios. Por debajo de 9 ya no se
         *  distingue de un escombro asentándose; por encima de 36 no es respirar,
         *  es moverse. */
        private const val RESP_HZ_MIN = 0.15
        private const val RESP_HZ_MAX = 0.6
        /** Medido contra series sintéticas (`fx sounds/resp.py`): respirando da
         *  entre 0,45 y 0,97 —incluso con el ruido al doble de la señal, o con el
         *  rescatista acercándose— y el ruido puro, un golpe suelto, un motor y
         *  una deriva dan 0,00. Lo único que se acerca por abajo es una vibración
         *  mecánica rítmica, que da 0,44: por eso el veredicto dice PROBABLE y lo
         *  advierte. Con una persona de verdad debajo de un escombro esto no está
         *  medido, y por eso el resultado imprime siempre el número crudo. */
        private const val UMBRAL_RESP = 0.45
        /** Solo para distinguir «no se mueve NADA» de «se mueve sin ritmo». Va muy
         *  bajo a propósito: las unidades son el logaritmo de un cociente de
         *  espectros y no se sabe cuánto vale eso con un cuerpo real, así que un
         *  umbral alto aquí escondería justo lo que se busca. */
        private const val ENERGIA_MIN = 0.01
    }

    private val h = Handler(Looper.getMainLooper())

    /** Junta líneas para las salidas de pantalla. Existe para no escribir saltos
     *  de línea a mano en el código: los scripts que editan estos archivos los han
     *  convertido en saltos reales más de una vez y parten la cadena. */
    private fun linea(vararg partes: String) = partes.joinToString(System.lineSeparator())
    /** Frecuencia del tono doppler, ajustable desde la pantalla. No es un
     *  capricho: el altavoz de cada móvil corta a una altura distinta, y en uno
     *  que no llegue a 18,5 kHz el doppler mide silencio y dice «sin movimiento»
     *  con toda la seguridad del mundo. Bajarla lo arregla, a costa de dejar la
     *  malla más sorda mientras suena. */
    @Volatile var fDoppler = F_DOPPLER

    /** Amplitud del tono, de 0,1 a 1. La pone la pantalla desde `Opciones.volSenal`
     *  (1 a 10). Antes estaba clavada en 0,6 y no había forma de subirla: por un
     *  escombro grueso hace falta todo lo que dé el altavoz. */
    @Volatile var volTono = 0.6

    /** Cuántas veces por encima del fondo están las bandas laterales AHORA MISMO.
     *  1 = todo quieto. Lo lee la animación del movimiento a la velocidad de la
     *  pantalla, no del refresco de los textos. */
    @Volatile var nivelDoppler = 1.0; private set
    @Volatile var ocupada = false; private set

    /**
     * La bandera de los dos que sacan tono continuo: el doppler y la respiración.
     *
     * Es compartida y de la clase a propósito. Antes cada medida tenía la suya en
     * una variable local, y bastaba con que el análisis muriera por una excepción
     * para que nadie la bajara: el hilo del tono se quedaba sonando **para
     * siempre**, y DETENER no lo tocaba porque DETENER no sabía nada de la sonda.
     * La única forma de callarlo era cerrar la app.
     */
    @Volatile private var tonoVivo = false

    /** Cuál de las dos herramientas de tono lo tiene ahora: "movimiento",
     *  "respiracion" o vacío. El tono es uno y el altavoz es uno, así que solo
     *  puede haber una encendida, y la pantalla necesita saber cuál para pintar
     *  su interruptor y no el del otro. */
    @Volatile var quienTono = ""; private set

    /** ¿Hay algo sonando ahora mismo por el altavoz? Lo enseña la pantalla, que es
     *  lo que faltaba para poder apagarlo: no se puede detener lo que no se ve. */
    val sonando: Boolean get() = tonoVivo || barriendo || ecoContinuo

    /** Para cortar una ráfaga de chasquidos por la mitad al apagar el eco. */
    private val cancelarEco: Boolean get() = !ecoContinuo && ocupada && paradaPedida
    @Volatile private var paradaPedida = false

    /** Apaga solo el tono continuo, sin tocar el barrido ni el eco. Lo usa el
     *  servicio antes de encender la otra herramienta de tono: el altavoz es uno
     *  y el tono es uno, así que movimiento y respiración no pueden convivir. */
    fun pararTono() {
        if (tonoVivo) { tonoVivo = false; quienTono = "" }
    }

    /** Corta todo lo que esté sonando: tono continuo y barrido. Lo llama DETENER,
     *  y también el botón de cada herramienta cuando ya está en marcha. */
    fun pararTodo() {
        val habia = sonando
        paradaPedida = true
        quienTono = ""
        tonoVivo = false
        barriendo = false
        ecoContinuo = false
        if (habia) reg("todo lo que sonaba está apagado")
    }
    /** Relación bandas laterales / portadora del último marco. La escribe el
     *  hilo del micrófono y la lee el del doppler, de ahí el @Volatile. */
    @Volatile private var dopplerRel = 0.0

    /* ================= chirp y filtro adaptado ================= */

    /** Chirp lineal con ventana de Hann: sin ventana, los cortes secos ensucian
     *  la correlación y aparecen ecos donde no los hay. */
    fun chirp(sr: Int, dur: Double = DUR_CHIRP, f0: Double = F0, f1: Double = F1): FloatArray {
        val n = (sr * dur).roundToInt()
        val k = (f1 - f0) / dur
        return FloatArray(n) { i ->
            val t = i.toDouble() / sr
            val w = 0.5 - 0.5 * cos(2.0 * PI * i / (n - 1))
            (sin(2.0 * PI * (f0 * t + 0.5 * k * t * t)) * w).toFloat()
        }
    }

    class Pico(val distancia: Double, val amplitud: Double)
    class Eco(val picos: List<Pico>, val maximo: Double, val directo: Int)

    /**
     * Un reflector visto por la ráfaga entera.
     *
     * [dispersion] es lo que un solo chasquido no puede dar: cuánto se mueve la
     * medida entre disparos. [presencia] es en cuántos de los ocho apareció. Un
     * reflector a 118 cm con 2 cm de dispersión y presencia 8/8 es una pared;
     * el mismo a 118 cm con 40 cm de dispersión y presencia 3/8 es ruido con
     * suerte, y hay que decirlo en vez de dar el número a secas.
     */
    class Reflector(
        val distancia: Double,
        val amplitud: Double,
        val dispersion: Double,
        val presencia: Int,
        val total: Int
    )

    /** Correlación con el patrón, en valor absoluto. Sale aparte de
     *  [analizarEco] porque el sondeo por ráfaga necesita apilar varias. */
    private fun correlar(rec: FloatArray, tpl: FloatArray): DoubleArray {
        val n = rec.size - tpl.size
        if (n <= 1) return DoubleArray(0)
        val corr = DoubleArray(n)
        for (i in 0 until n) {
            var s = 0.0
            var j = 0
            while (j < tpl.size) { s += rec[i + j] * tpl[j]; j += 2 }   // paso 2: mitad de coste, misma forma
            corr[i] = abs(s)
        }
        return corr
    }

    /**
     * Alinea una correlación con su propio pico directo y la normaliza.
     *
     * Alinear con el directo de CADA disparo es lo que hace que no haya que
     * calibrar la latencia de audio del móvil — que es distinta en cada modelo
     * y que además fluctúa entre disparos.
     */
    private fun tramo(corr: DoubleArray, maxLag: Int): DoubleArray? {
        if (corr.isEmpty()) return null
        var d0 = 0; var mx = 0.0
        for (i in corr.indices) if (corr[i] > mx) { mx = corr[i]; d0 = i }
        if (mx <= 1e-12) return null
        return DoubleArray(maxLag + 1) { k ->
            val i = d0 + k
            if (i < corr.size) corr[i] / mx else 0.0
        }
    }

    /**
     * El análisis de la ráfaga, sin altavoz y sin micrófono, para que se pueda
     * autocomprobar entero sin salir a ninguna parte.
     */
    fun apilar(tramos: List<DoubleArray>, sr: Int): List<Reflector> {
        if (tramos.isEmpty()) return emptyList()
        val maxLag = tramos[0].size - 1
        val ciego = max(1, (sr * 0.0006).roundToInt())          // ~10 cm: zona ciega del directo
        val n = tramos.size

        val pila = DoubleArray(maxLag + 1)
        for (t in tramos) for (k in 0..maxLag) pila[k] += t[k]
        for (k in pila.indices) pila[k] /= n

        val refs = ArrayList<Reflector>()
        var i = ciego
        while (i < maxLag) {
            if (pila[i] > pila[i - 1] && pila[i] >= pila[i + 1] && pila[i] > UMBRAL_PILA) {
                // ¿en cuántos disparos aparece, y dónde exactamente en cada uno?
                val ds = ArrayList<Double>()
                for (t in tramos) {
                    var mejor = -1; var mv = 0.0
                    for (j in max(1, i - ciego)..min(maxLag - 1, i + ciego)) {
                        if (t[j] > mv) { mv = t[j]; mejor = j }
                    }
                    if (mejor > 0 && mv > UMBRAL_INDIV) ds.add(mejor.toDouble() / sr * C_AIRE / 2)
                }
                // si no está en la mayoría de los disparos, no está
                if (ds.size * 2 >= n) {
                    val media = ds.average()
                    val sd = sqrt(ds.sumOf { (it - media) * (it - media) } / ds.size)
                    refs.add(Reflector(media, pila[i], sd, ds.size, n))
                }
                i += ciego
            }
            i++
        }
        return refs.sortedByDescending { it.amplitud }.take(4).sortedBy { it.distancia }
    }

    /**
     * Correlaciona lo grabado con el chirp que se emitió.
     *
     * El primer pico es el sonido directo altavoz→micrófono; todo lo que viene
     * después son ecos. Se mide **relativo a ese pico**, y por eso no hace falta
     * calibrar la latencia de audio del móvil, que es distinta en cada modelo y
     * que de otro modo habría que medir para cada fabricante.
     */
    fun analizarEco(rec: FloatArray, tpl: FloatArray, sr: Int): Eco {
        val corr = correlar(rec, tpl)
        val n = corr.size
        if (n <= 1) return Eco(emptyList(), 0.0, 0)
        var d0 = 0; var mx = 0.0
        for (i in 0 until n) if (corr[i] > mx) { mx = corr[i]; d0 = i }

        val ciego = (sr * 0.0006).roundToInt()                 // ~10 cm: zona ciega del directo
        val maxLag = (sr * 2 * 6 / C_AIRE).roundToInt()        // hasta 6 m
        val picos = ArrayList<Pico>()
        var i = d0 + ciego
        val tope = min(n - 1, d0 + maxLag)
        while (i < tope) {
            if (corr[i] > corr[i - 1] && corr[i] >= corr[i + 1] && corr[i] > mx * 0.18) {
                picos.add(Pico((i - d0).toDouble() / sr * C_AIRE / 2, corr[i] / mx))
                i += ciego
            }
            i++
        }
        val mejores = picos.sortedByDescending { it.amplitud }.take(4).sortedBy { it.distancia }
        return Eco(mejores, mx, d0)
    }

    /**
     * Cola reverberante: cuánto tarda en morir el sonido. Un hueco pequeño entre
     * escombros se apaga en milisegundos; una nave industrial tarda segundos.
     * Se ajusta una recta al tramo de caída y se extrapola a 60 dB.
     */
    fun rt60(rec: FloatArray, sr: Int, desde: Int): Double {
        val win = (sr * 0.005).roundToInt()
        if (win < 1) return 0.0
        val t = ArrayList<Double>(); val db = ArrayList<Double>()
        var i = desde
        while (i + win < rec.size) {
            var s = 0.0
            for (j in 0 until win) s += rec[i + j].toDouble() * rec[i + j]
            t.add((i - desde).toDouble() / sr)
            db.add(10.0 * log10(s / win + 1e-12))
            i += win
        }
        if (db.size < 4) return 0.0
        val pk = db.max()
        val idx = db.indices.filter { db[it] < pk - 5 && db[it] > pk - 30 }
        if (idx.size < 3) return 0.0
        var sx = 0.0; var sy = 0.0; var sxy = 0.0; var sxx = 0.0
        for (k in idx) { sx += t[k]; sy += db[k]; sxy += t[k] * db[k]; sxx += t[k] * t[k] }
        val n = idx.size
        val den = n * sxx - sx * sx
        if (abs(den) < 1e-9) return 0.0
        val m = (n * sxy - sx * sy) / den
        return if (m < -1) -60.0 / m else 0.0
    }

    /* ================= sondear ================= */

    /**
     * Una ráfaga de chasquidos y lo que vuelve de cada uno, apilado.
     *
     * `onProgreso` va contando los disparos, porque son unos cuatro segundos y
     * un botón que no responde en cuatro segundos parece roto. `onResultado`
     * llega al final, los dos en el hilo principal.
     */
    @Volatile var ecoContinuo = false; private set

    /**
     * El eco, en marcha o parado.
     *
     * Con el interruptor encendido repite la ráfaga una y otra vez: eso es lo que
     * convierte una medida suelta en ecolocalización de verdad, porque quien está
     * atrapado mueve el móvil y necesita ver cómo cambia lo que tiene delante. Se
     * para en cuanto se apaga, sin esperar a terminar la ráfaga en curso.
     */
    fun eco(encender: Boolean, onProgreso: (String) -> Unit, onResultado: (String) -> Unit) {
        if (encender == ecoContinuo) return
        ecoContinuo = encender
        if (!encender) { reg("Eco apagado"); return }
        // sin esto, una parada anterior dejaba la bandera arriba para siempre
        paradaPedida = false
        reg("Eco continuo encendido")
        thread(name = "eco-continuo", isDaemon = true) {
            var ultimo = ""
            var hechos = 0
            for (intento in 1..INTENTOS) {
                if (!ecoContinuo) break
                sondear(
                    { p -> h.post { onProgreso(linea("Intento $intento de $INTENTOS", p)) } },
                    { r -> ultimo = r }
                )
                while (ocupada && ecoContinuo) Thread.sleep(100)
                hechos = intento
                var esperado = 0
                while (ecoContinuo && esperado < 500) { Thread.sleep(100); esperado += 100 }
            }
            // el resultado se queda quieto, y la herramienta se apaga sola
            val cortado = !ecoContinuo
            ecoContinuo = false
            val txt = if (ultimo.isBlank())
                "Sin resultado: la medida no llego a completarse."
            else linea(
                if (cortado) "Detenido tras $hechos de $INTENTOS intentos." else "Resultado tras $INTENTOS intentos.",
                ultimo
            )
            h.post { onResultado(txt) }
            reg("Eco continuo terminado")
        }
    }

    fun sondear(onProgreso: (String) -> Unit = {}, onResultado: (String) -> Unit) {
        /* Pulsar y que no pase nada visible es el peor resultado posible: quien
           lo prueba concluye que la app está rota. Así que todos los caminos,
           incluso los que fallan, contestan por la misma salida. */
        if (ocupada) {
            reg("La sonda ya estaba ocupada: no lanzo otra rafaga")
            h.post { onResultado("Espera: ya hay una medida en marcha.") }
            return
        }
        if (!mic.abrir(Microfono.USA_SONDA)) {
            reg("La sonda necesita el permiso del microfono")
            h.post { onResultado("Necesito el micrófono para escuchar el eco. Concédelo y vuelve a intentarlo.") }
            return
        }
        ocupada = true
        thread(name = "sonda", isDaemon = true) {
            try {
                val sr = mic.sr
                val tpl = chirp(sr)
                val pcm = ShortArray(tpl.size) { (tpl[it] * Short.MAX_VALUE * 0.95).toInt().toShort() }
                val maxLag = (sr * 2 * ALCANCE_M / C_AIRE).roundToInt()

                val tramos = ArrayList<DoubleArray>()
                var ultima: FloatArray? = null
                var ultimoDirecto = 0

                // la ráfaga entera con el canal de alarma al máximo: es lo que hace
                // que los ocho chasquidos se OIGAN, no solo que se midan
                Altavoz.aTodoVolumen {
                    for (r in 1..CHASQUIDOS) {
                        if (cancelarEco) break
                        h.post { onProgreso("Sondeando… chasquido $r de $CHASQUIDOS") }
                        // vuelve cuando el chasquido ya ha sonado, no cuando se ha pedido
                        Altavoz.reproducir(pcm, sr, colaMs = 0, preMs = PRE_MS)
                        Thread.sleep(ESPERA_MS)
                        val rec = mic.cola(VENTANA_N(sr))
                        val corr = correlar(rec, tpl)
                        tramo(corr, maxLag)?.let { tramos.add(it) }
                        if (corr.isNotEmpty()) {
                            var d0 = 0; var mx = 0.0
                            for (i in corr.indices) if (corr[i] > mx) { mx = corr[i]; d0 = i }
                            ultima = rec; ultimoDirecto = d0
                        }
                        Thread.sleep(PAUSA_MS)
                    }
                }

                val refs = apilar(tramos, sr)
                val t = ultima?.let { rt60(it, sr, ultimoDirecto + tpl.size) } ?: 0.0

                val donde = if (refs.isEmpty())
                    "sin ecos que se repitan: espacio abierto, o el micrófono está tapado"
                else refs.joinToString("\n") {
                    "%d cm ± %d (%d/%d disparos, %d%%)".format(
                        (it.distancia * 100).roundToInt(),
                        (it.dispersion * 100).roundToInt(),
                        it.presencia, it.total,
                        (it.amplitud * 100).roundToInt()
                    )
                }
                val cola = when {
                    t <= 0.0 -> "Cola del sonido: no medible."
                    t < 0.15 -> "Cola del sonido: %.2f s. Hueco pequeño, o muy absorbente.".format(t)
                    t < 0.6 -> "Cola del sonido: %.2f s. Como una habitación.".format(t)
                    else -> "Cola del sonido: %.2f s. Espacio grande.".format(t)
                }
                /* Solo se avisa de confinamiento si la medida es firme. Decirle a
                   alguien que tiene una pared a 60 cm cuando es ruido es peor que
                   no decirle nada. */
                val firme = refs.firstOrNull()
                    ?.takeIf { it.distancia < 0.8 && it.dispersion < 0.12 && it.presencia * 2 > it.total }
                val txt = donde + "\n" + cola +
                    if (firme != null) "\nsuperficie firme a menos de 80 cm: probable confinamiento" else ""
                reg("Sonda: ${refs.size} superficie(s) alrededor")
                h.post { onResultado(txt) }
            } catch (ex: Exception) {
                Log.e(TAG, "sonda falló", ex)
                h.post { onResultado("la sonda falló: ${ex.message}") }
            } finally {
                mic.cerrar(Microfono.USA_SONDA)
                ocupada = false
            }
        }
    }

    /* ================= doppler ================= */

    /**
     * Un tono fijo de 18,5 kHz. Si algo se mueve cerca, el eco vuelve con la
     * frecuencia corrida y aparecen bandas laterales alrededor de la portadora.
     * Detecta que **se mueve un cuerpo**: ni cuántos, ni dónde, ni la respiración.
     *
     * Ojo con una interacción que no es evidente: 18,5 kHz cae dentro de la banda
     * donde la malla mide su ruido de fondo, así que **mientras el doppler suena,
     * la malla queda casi sorda**. Dura unos segundos y lo lanza una persona a
     * mano, pero por eso no puede quedarse corriendo solo.
     */
    fun doppler(encender: Boolean, onProgreso: (String) -> Unit, onResultado: (String) -> Unit) {
        if (!encender) {
            tonoVivo = false; quienTono = ""
            h.post { onResultado("Movimiento apagado.") }
            return
        }
        if (ocupada) { h.post { onResultado("Espera: ya hay una medida en marcha.") }; return }
        if (!mic.abrir(Microfono.USA_SONDA)) {
            reg("esto necesita el permiso del micrófono")
            h.post { onResultado("Necesito el micrófono para medir el movimiento. Concédelo y vuelve a intentarlo.") }
            return
        }
        ocupada = true
        quienTono = "movimiento"
        val f = fDoppler
        reg("Mientras suene el tono, la malla no oye a otros móviles")

        val sr = mic.sr
        val ventana = DoubleArray(Microfono.N) { 0.5 - 0.5 * cos(2.0 * PI * it / (Microfono.N - 1)) }
        val espectro = DoubleArray(Microfono.N / 2)
        val res = sr.toDouble() / Microfono.N
        val c = (f / res).roundToInt()

        dopplerRel = 0.0
        val oyente = Microfono.Oyente { marco ->
            Fft.magnitudes(marco, ventana, espectro)
            val port = espectro.getOrElse(c) { 0.0 }.let { it * it } + 1e-12
            var lado = 0.0
            for (k in 3..25) {
                lado += espectro.getOrElse(c - k) { 0.0 }.let { it * it }
                lado += espectro.getOrElse(c + k) { 0.0 }.let { it * it }
            }
            dopplerRel = lado / port
        }

        thread(name = "doppler", isDaemon = true) {
            var tono: Thread? = null
            try {
                mic.registrar(oyente)
                // tono continuo mientras dure la medida, en trozos de 1 s
                tonoVivo = true
                val n = sr
                val pcm = ShortArray(n) {
                    (sin(2.0 * PI * f * it / sr) * Short.MAX_VALUE * volTono).toInt().toShort()
                }
                tono = thread(name = "doppler-tono", isDaemon = true) {
                    Altavoz.aTodoVolumen { while (tonoVivo) Altavoz.reproducir(pcm, sr, colaMs = 0) }
                }

                // fondo: cómo se ve la portadora con todo quieto
                var base = 0.0
                for (i in 0 until 10) { Thread.sleep(100); base += dopplerRel }
                base /= 10.0

                /* Cinco intentos de cinco segundos. No es un tono infinito: una
                   medida que no acaba nunca no da un resultado que se pueda leer, y
                   era lo que pasaba. */
                var pico = 0.0
                for (intento in 1..INTENTOS) {
                    if (!tonoVivo) break
                    var picoIntento = 0.0
                    val t1 = System.currentTimeMillis()
                    while (tonoVivo && System.currentTimeMillis() - t1 < 5000) {
                        Thread.sleep(100)
                        val v = dopplerRel
                        if (v > picoIntento) picoIntento = v
                        if (v > pico) pico = v
                        val rel = v / (base + 1e-12)
                        nivelDoppler = rel
                        val maxi = pico / (base + 1e-12)
                        h.post {
                            onProgreso(
                                linea(
                                    "Intento $intento de $INTENTOS",
                                    "Ahora: %.1f veces el fondo (quieto = 1)".format(rel),
                                    "Maximo visto: %.1f veces".format(maxi),
                                    "No muevas el movil"
                                )
                            )
                        }
                    }
                }
                val hechos = tonoVivo
                tonoVivo = false
                nivelDoppler = 1.0
                val rel = pico / (base + 1e-12)
                val veredicto = when {
                    rel > 4 -> "MOVIMIENTO CERCA"
                    rel > 2 -> "Movimiento leve, o una corriente de aire"
                    else -> "Sin movimiento"
                }
                reg("Movimiento: $veredicto (%.1f veces el fondo)".format(rel))
                h.post {
                    onResultado(
                        linea(
                            veredicto + ".",
                            if (hechos) "Resultado tras $INTENTOS intentos." else "Detenido antes de acabar.",
                            "Maximo medido: %.1f veces el fondo.".format(rel),
                            "Alcance util de 1 a 2 metros."
                        )
                    )
                }
            } catch (ex: Exception) {
                Log.e(TAG, "doppler falló", ex)
                h.post { onResultado("El movimiento falló: ${ex.message}") }
            } finally {
                // SIEMPRE, pase lo que pase: es lo que evita el tono eterno
                tonoVivo = false
                quienTono = ""
                nivelDoppler = 1.0
                mic.quitar(oyente)
                mic.cerrar(Microfono.USA_SONDA)
                try { tono?.join(1500) } catch (_: Exception) {}
                ocupada = false
            }
        }
    }

    /* ================= respiración: ¿hay un cuerpo vivo? ================= */

    /**
     * El mismo tono de 18,5 kHz que el doppler, mirando otra cosa. El doppler
     * dice «algo se mueve»; esto dice «algo se mueve **como respira un cuerpo**».
     *
     * Un pecho que sube y baja modula el eco despacio y CON PERIODO: entre 0,15 y
     * 0,6 Hz, que son 9 a 36 respiraciones por minuto. Un escombro asentándose,
     * una corriente de aire o una lona suelta también modulan, pero sin periodo:
     * empujan una vez y paran. La periodicidad es lo único que separa un cuerpo
     * de un montón de cosas que se mueven, y es la misma pista que quedó apuntada
     * para separar un motor de un derrumbe.
     *
     * Por eso son veinte segundos y no cinco: para dar algo por periódico hay que
     * verlo repetirse tres veces, y a nueve respiraciones por minuto una sola
     * dura casi siete segundos.
     *
     * OJO CON EL UMBRAL: [UMBRAL_RESP] es una estimación, no un número medido con
     * una persona debajo de un escombro. Por eso el resultado imprime SIEMPRE la
     * periodicidad cruda y el periodo hallado — son los dos números que hacen
     * falta para calibrarlo en campo, y sin calibrar esto no puede decir «no hay
     * nadie» con ninguna autoridad.
     */
    fun respiracion(
        encender: Boolean,
        onProgreso: (String) -> Unit,
        onResultado: (String) -> Unit
    ) {
        if (!encender) {
            tonoVivo = false; quienTono = ""
            h.post { onResultado("Búsqueda de respiración apagada.") }
            return
        }
        val segundos = RESP_SEG
        if (ocupada) { h.post { onResultado("Espera: ya hay una medida en marcha.") }; return }
        if (!mic.abrir(Microfono.USA_SONDA)) {
            reg("esto necesita el permiso del micrófono")
            h.post { onResultado("Necesito el micrófono para oír el eco. Concédelo y vuelve a intentarlo.") }
            return
        }
        ocupada = true
        quienTono = "respiracion"
        val f = fDoppler
        reg("Buscando respiración: ciclos de $segundos s. La malla queda sorda mientras suene")

        val sr = mic.sr
        val ventana = DoubleArray(Microfono.N) { 0.5 - 0.5 * cos(2.0 * PI * it / (Microfono.N - 1)) }
        val espectro = DoubleArray(Microfono.N / 2)
        val res = sr.toDouble() / Microfono.N
        val c = (f / res).roundToInt()

        dopplerRel = 0.0
        val oyente = Microfono.Oyente { marco ->
            Fft.magnitudes(marco, ventana, espectro)
            val port = espectro.getOrElse(c) { 0.0 }.let { it * it } + 1e-12
            var lado = 0.0
            for (k in 3..25) {
                lado += espectro.getOrElse(c - k) { 0.0 }.let { it * it }
                lado += espectro.getOrElse(c + k) { 0.0 }.let { it * it }
            }
            dopplerRel = lado / port
        }

        thread(name = "respiracion", isDaemon = true) {
            var tono: Thread? = null
            try {
                mic.registrar(oyente)
                tonoVivo = true
                val pcm = ShortArray(sr) {
                    (sin(2.0 * PI * f * it / sr) * Short.MAX_VALUE * volTono).toInt().toShort()
                }
                tono = thread(name = "respiracion-tono", isDaemon = true) {
                    Altavoz.aTodoVolumen { while (tonoVivo) Altavoz.reproducir(pcm, sr, colaMs = 0) }
                }

                /* Un segundo de descarte: mientras el tono arranca, la relación
                   bandas/portadora da valores enormes que no son movimiento. */
                Thread.sleep(1000)

                var ciclo = 0
                var mejor: Ritmo? = null
                var mejorBpm = 0.0
                while (tonoVivo && ciclo < INTENTOS) {
                ciclo++
                val serie = ArrayList<Double>(segundos * MUESTRAS_S)
                val t0 = System.currentTimeMillis()
                while (tonoVivo && System.currentTimeMillis() - t0 < segundos * 1000L) {
                    Thread.sleep(1000L / MUESTRAS_S)
                    /* En logaritmo, no en crudo: la relación es un cociente y un
                       solo golpe cerca la multiplica por cien. En log, ese golpe
                       es un escalón y no aplasta la respiración, que es un rizo
                       pequeño encima. */
                    serie.add(ln(dopplerRel + 1e-9))
                    nivelDoppler = dopplerRel
                    val queda = segundos - (System.currentTimeMillis() - t0) / 1000
                    val n = ciclo
                    h.post {
                        onProgreso(
                            linea(
                                "Escuchando si alguien respira…",
                                "Quedan $queda s de esta medida (ciclo $n)",
                                "No toques el móvil y no hables"
                            )
                        )
                    }
                }
                if (!tonoVivo && serie.size < segundos * MUESTRAS_S / 2) {
                    h.post { onResultado("Medida cancelada: hacen falta los $segundos s enteros.") }
                    break
                }

                val r = periodicidad(serie)
                val bpm = if (r.lag > 0) 60.0 * MUESTRAS_S / r.lag else 0.0
                // se queda el intento con mas ritmo: una respiracion debil aparece
                // en uno de cinco y no en todos
                if (mejor == null || r.fuerza > mejor!!.fuerza) { mejor = r; mejorBpm = bpm }
                /* La banda ya está impuesta en la búsqueda del retardo, así que
                   aquí NO se vuelve a filtrar por respiraciones por minuto. Se
                   hacía, y descartaba justo el ritmo más lento que se busca: 0,15
                   Hz es un retardo de 67 muestras, que son 8,96/min, y un
                   `bpm >= 9` lo tiraba. Lo encontró el autotest. */
                val veredicto = when {
                    r.energia < ENERGIA_MIN -> "NADA SE MUEVE AHÍ · ni un cuerpo ni un escombro"
                    r.fuerza >= UMBRAL_RESP && r.lag > 0 ->
                        "PROBABLE CUERPO HUMANO · ${bpm.roundToInt()} respiraciones/min"
                    else -> "algo se mueve, pero sin ritmo de respiración"
                }
                val nc = ciclo
                h.post {
                    onProgreso(
                        linea(
                            "Intento $nc de $INTENTOS",
                            veredicto,
                            "Periodicidad %.2f · hace falta %.2f".format(r.fuerza, UMBRAL_RESP)
                        )
                    )
                }
                }   // fin del ciclo

                /* Un resultado, al final, y la herramienta se apaga sola: asi el
                   veredicto se queda quieto en la pantalla. Se queda el intento con
                   mas ritmo, no el ultimo: una respiracion debil aparece en uno de
                   cinco y no en todos. */
                val completo = ciclo >= INTENTOS
                tonoVivo = false
                nivelDoppler = 1.0
                val m = mejor
                val vf = when {
                    m == null -> "Sin medida completa."
                    m.energia < ENERGIA_MIN -> "NADA SE MUEVE AHI. Ni un cuerpo ni un escombro."
                    m.fuerza >= UMBRAL_RESP && m.lag > 0 ->
                        "PROBABLE CUERPO HUMANO. ${mejorBpm.roundToInt()} respiraciones por minuto."
                    else -> "Algo se mueve, pero sin ritmo de respiracion."
                }
                reg("Respiracion: $vf")
                h.post {
                    onResultado(
                        linea(
                            vf,
                            if (completo) "Resultado tras $INTENTOS intentos." else "Detenido en el intento $ciclo.",
                            if (m != null) "Periodicidad %.2f, hace falta %.2f.".format(m.fuerza, UMBRAL_RESP) else "",
                            if (m != null) "Movimiento medido: %.3f.".format(m.energia) else "",
                            "Un cuerpo inconsciente respira muy poco y puede no salir.",
                            "Algo mecanico con ritmo puede imitarlo: comprueba con la voz."
                        )
                    )
                }
            } catch (ex: Exception) {
                Log.e(TAG, "respiración falló", ex)
                h.post { onResultado("La medida falló: ${ex.message}") }
            } finally {
                tonoVivo = false
                quienTono = ""
                nivelDoppler = 1.0
                mic.quitar(oyente)
                mic.cerrar(Microfono.USA_SONDA)
                try { tono?.join(1500) } catch (_: Exception) {}
                ocupada = false
            }
        }
    }

    /** Lo que se puede decir de una serie: cuánto se mueve y con cuánto ritmo. */
    class Ritmo(val fuerza: Double, val lag: Int, val energia: Double)

    /**
     * Autocorrelación normalizada de la serie, buscando el mejor periodo dentro de
     * la banda de la respiración humana.
     *
     * Antes hay que quitarle la tendencia: si el rescatista se acerca despacio, la
     * serie entera sube, y una rampa se autocorrelaciona consigo misma casi al 100 %
     * en cualquier retardo. Restando la media móvil queda solo el rizo.
     */
    internal fun periodicidad(serie: List<Double>): Ritmo {
        val n = serie.size
        val lagMin = (MUESTRAS_S / RESP_HZ_MAX).roundToInt()
        val lagMax = (MUESTRAS_S / RESP_HZ_MIN).roundToInt()
        // hacen falta tres periodos del más lento para poder afirmar algo
        if (n < lagMax * 3) return Ritmo(0.0, 0, 0.0)

        /* 1. Quitar la recta de mínimos cuadrados. Si el rescatista se acerca
              despacio, la serie entera sube, y una rampa se autocorrelaciona
              consigo misma casi al 100 % en cualquier retardo: sin esto, andar
              hacia el escombro daba «cuerpo humano» él solo. */
        var sx = 0.0; var sy = 0.0; var sxy = 0.0; var sxx = 0.0
        for (i in 0 until n) {
            sx += i; sy += serie[i]; sxy += i * serie[i]; sxx += i.toDouble() * i
        }
        val den0 = n * sxx - sx * sx
        val m = if (abs(den0) > 1e-9) (n * sxy - sx * sy) / den0 else 0.0
        val b = (sy - m * sx) / n
        var x = DoubleArray(n) { serie[it] - (m * it + b) }

        /* 2. Paso bajo: nada más rápido que RESP_HZ_MAX es respirar. Y en
              CASCADA, dos pasadas: una media móvil sola deja pasar un 10 % de
              una vibración de 2 Hz, y un 10 % de algo perfectamente periódico
              sigue correlacionando perfecto, porque la autocorrelación
              normalizada no mira la amplitud. Con dos pasadas queda en un 1 %,
              por debajo del ruido. */
        val w = max(1, (MUESTRAS_S / (2 * RESP_HZ_MAX)).roundToInt())
        repeat(2) {
            val y = DoubleArray(n)
            for (i in 0 until n) {
                var s = 0.0; var k = 0
                for (j in max(0, i - w)..min(n - 1, i + w)) { s += x[j]; k++ }
                y[i] = s / k
            }
            x = y
        }

        var e0 = 0.0
        for (v in x) e0 += v * v
        if (e0 < 1e-12) return Ritmo(0.0, 0, 0.0)
        val energia = sqrt(e0 / n)

        // 3. autocorrelación normalizada en toda la banda, un retardo de margen
        //    a cada lado para poder mirar si el pico es un máximo de verdad
        val tope = min(lagMax, n / 3)
        val r = HashMap<Int, Double>()
        for (lag in (lagMin - 1)..(tope + 1)) {
            if (lag < 1 || lag >= n) continue
            var num = 0.0; var d1 = 0.0; var d2 = 0.0
            for (i in 0 until n - lag) {
                num += x[i] * x[i + lag]; d1 += x[i] * x[i]; d2 += x[i + lag] * x[i + lag]
            }
            val dd = sqrt(d1 * d2)
            r[lag] = if (dd > 1e-12) num / dd else 0.0
        }

        /* 4. El pico tiene que ser un máximo INTERIOR. Una deriva suave da su
              máximo en el retardo más corto y baja sin parar: eso no es un
              ritmo, es una pendiente, y así se descarta sin tener que inventar
              un umbral de energía. */
        var mejor = 0.0; var mejorLag = 0
        for (lag in lagMin..tope) {
            val a = r[lag - 1] ?: continue
            val c = r[lag + 1] ?: continue
            val v = r[lag] ?: continue
            if (v > a && v >= c && v > mejor) { mejor = v; mejorLag = lag }
        }
        return Ritmo(mejor, mejorLag, energia)
    }

    /* ================= barrido de penetración ================= */

    @Volatile private var barriendo = false

    /**
     * De 80 Hz a 4 kHz cada 4 s. Los escombros filtran distinto según la
     * frecuencia y nadie sabe cómo filtra ESTE montón: lo grave atraviesa masa,
     * lo agudo lo localiza mejor el oído. Barriendo entero, algo pasa sí o sí.
     */
    fun barrido(encender: Boolean) {
        if (encender == barriendo) return
        barriendo = encender
        if (!encender) { reg("Barrido apagado"); return }
        reg("barrido 80 Hz → 4 kHz cada 4 s")
        thread(name = "barrido", isDaemon = true) {
            val sr = 48000
            val dur = 2.5
            val n = (sr * dur).toInt()
            val pcm = ShortArray(n)
            var fase = 0.0
            val rampa = (0.05 * sr).toInt()
            for (i in 0 until n) {
                val t = i.toDouble() / n
                val f = 80.0 * Math.pow(4000.0 / 80.0, t)      // barrido exponencial
                fase += 2.0 * PI * f / sr
                // diente de sierra: mucho más rico en armónicos que un seno, y por
                // tanto con más probabilidad de que algo atraviese los escombros
                val s = 2.0 * ((fase / (2 * PI)) % 1.0) - 1.0
                val env = when {
                    i < rampa -> i.toDouble() / rampa
                    i > n - rampa -> (n - i).toDouble() / rampa
                    else -> 1.0
                }
                pcm[i] = (s * env * Short.MAX_VALUE * volTono).toInt().toShort()
            }
            Altavoz.aTodoVolumen {
                while (barriendo) {
                    Altavoz.reproducir(pcm, sr, colaMs = 0)
                    var esperado = 0
                    while (barriendo && esperado < 1500) { Thread.sleep(100); esperado += 100 }
                }
            }
        }
    }

    /* ================= autotest =================
       Ecos simulados, sin altavoz y sin micrófono. Si esto falla, no hace falta
       salir a probar nada. */

    /** Una grabación sintética: directo + eco a [d] metros, sobre ruido. */
    private fun simular(sr: Int, tpl: FloatArray, d: Double, eco: Float, ruido: Double): FloatArray {
        val rec = FloatArray((sr * 0.25).roundToInt())
        for (i in rec.indices) rec[i] = ((Math.random() - 0.5) * ruido).toFloat()
        val off = (sr * 0.02).roundToInt()
        val lag = (2 * d / C_AIRE * sr).roundToInt()
        for (i in tpl.indices) {
            if (off + i < rec.size) rec[off + i] += tpl[i]                          // directo
            if (eco > 0 && off + lag + i < rec.size) rec[off + lag + i] += tpl[i] * eco
        }
        return rec
    }

    fun autotest(): Boolean {
        val sr = mic.sr
        val tpl = chirp(sr)
        val d = 1.20
        val maxLag = (sr * 2 * ALCANCE_M / C_AIRE).roundToInt()
        val partes = ArrayList<String>()
        var todo = true

        // 1) un solo disparo, como antes: el filtro adaptado tiene que ver el eco
        val e = analizarEco(simular(sr, tpl, d, 0.35f, 0.02), tpl, sr)
        val uno = e.picos.firstOrNull { abs(it.distancia - d) < 0.08 }
        if (uno == null) todo = false
        partes.add("1 chasquido → " + (uno?.let { "%.2f m OK".format(it.distancia) } ?: "FALLÓ"))

        /* 2) la ráfaga con MUCHO ruido — el escenario real bajo escombros. El
              apilado no baja el umbral: lo que hace es que el ruido, que cae en
              un sitio distinto en cada disparo, se promedie a la baja mientras
              el eco, que cae siempre en el mismo, se mantenga. */
        val RUIDO = 0.60
        val flojos = ArrayList<DoubleArray>()
        for (r in 0 until CHASQUIDOS) {
            tramo(correlar(simular(sr, tpl, d, 0.22f, RUIDO), tpl), maxLag)?.let { flojos.add(it) }
        }
        val refs = apilar(flojos, sr)
        val hit = refs.firstOrNull { abs(it.distancia - d) < 0.10 }
        if (hit == null) todo = false
        partes.add("$CHASQUIDOS chasquidos con ruido → " +
            (hit?.let { "%.2f m ±%.0f cm, %d/%d OK".format(it.distancia, it.dispersion * 100, it.presencia, it.total) }
                ?: "FALLÓ"))

        /* 3) el mismo ruido pero SIN ninguna pared. Aquí es donde se ve para qué
              sirve: un disparo suelto se inventa reflectores y la ráfaga no. */
        val vacios = ArrayList<DoubleArray>()
        for (r in 0 until CHASQUIDOS) {
            tramo(correlar(simular(sr, tpl, d, 0f, RUIDO), tpl), maxLag)?.let { vacios.add(it) }
        }
        val inventados = analizarEco(simular(sr, tpl, d, 0f, RUIDO), tpl, sr).picos.size
        val falsos = apilar(vacios, sr)
        if (falsos.isNotEmpty()) todo = false
        partes.add("solo ruido → 1 disparo se inventa $inventados, la ráfaga ${falsos.size} " +
            if (falsos.isEmpty()) "OK" else "FALLÓ")

        Log.i(TAG, "autotest sonda · " + partes.joinToString(" | "))
        return todo && autotestRespiracion()
    }

    /**
     * El análisis de respiración, contra series hechas a mano.
     *
     * No prueba el altavoz ni el micrófono: prueba la única parte que puede
     * romperse en silencio al tocar un umbral o una constante. Los tres casos que
     * están aquí son los tres que **fallaron** mientras se escribía, y cada uno
     * vigila una cosa distinta:
     *
     *  - Respirar a 15/min con el ruido al doble de la señal tiene que salir. Con
     *    veinte segundos de medida en vez de veinticinco, la serie se quedaba una
     *    muestra por debajo de lo que exige el análisis y NO salía nunca.
     *  - Acercarse andando no puede dar «cuerpo humano» por la rampa: sin quitar
     *    la recta de mínimos cuadrados daba 0,89 él solo.
     *  - Una vibración de 2 Hz tampoco: con una sola media móvil en vez de dos en
     *    cascada daba 0,95, porque la autocorrelación normalizada no mira la
     *    amplitud y un 10 % de algo periódico sigue correlacionando perfecto.
     *
     * El mismo banco, para tocar umbrales sin compilar, está en `fx sounds/resp.py`.
     */
    private fun autotestRespiracion(): Boolean {
        var todo = true
        val partes = ArrayList<String>()
        val n = RESP_SEG * MUESTRAS_S
        val rnd = java.util.Random(1)

        fun serie(hz: Double, amp: Double, ruido: Double, deriva: Double = 0.0) =
            List(n) { i ->
                val t = i.toDouble() / MUESTRAS_S
                amp * sin(2.0 * PI * hz * t) + rnd.nextGaussian() * ruido + deriva * t
            }

        /** [debeSalir] null = solo informativo: es un caso al límite y el
         *  resultado depende del sorteo del ruido, así que afirmarlo daría un
         *  autotest que falla unas veces y no otras. */
        fun caso(nombre: String, s: List<Double>, debeSalir: Boolean?, bpmEsperado: Double = 0.0) {
            val r = periodicidad(s)
            val bpm = if (r.lag > 0) 60.0 * MUESTRAS_S / r.lag else 0.0
            val salio = r.fuerza >= UMBRAL_RESP && r.lag > 0
            val bien = debeSalir == null || (salio == debeSalir &&
                (!debeSalir || abs(bpm - bpmEsperado) <= 3.0))
            if (!bien) todo = false
            partes.add("$nombre → %.2f".format(r.fuerza) +
                (if (bpm > 0) " · %.0f/min".format(bpm) else "") +
                when {
                    debeSalir == null -> " (informativo)"
                    bien -> " OK"
                    else -> " FALLÓ"
                })
        }

        caso("respira 15/min con ruido igual", serie(0.25, 0.3, 0.30), true, 15.0)
        caso("respira 9/min, el más lento de la banda", serie(0.15, 0.4, 0.15), true, 9.0)
        caso("acercándose andando", serie(0.25, 0.4, 0.15, deriva = 0.5), true, 15.0)
        caso("solo ruido", serie(0.0, 0.0, 0.30), false)
        caso("vibración de 2 Hz", serie(2.0, 0.6, 0.10), false)
        caso("deriva sola", serie(0.0, 0.0, 0.05, deriva = 1.0), false)
        /* Señal a la mitad del ruido: aquí el método ya no da garantías. Se mide
           y se enseña el número, pero no se afirma nada — con una realización del
           ruido sale 0,50 y con otra 0,21. Si un día hay que subir la
           sensibilidad, ESTE es el caso contra el que medir. */
        caso("respira 15/min con ruido doble", serie(0.25, 0.2, 0.40), null)

        Log.i(TAG, "autotest respiración · " + partes.joinToString(" | "))
        return todo
    }

    private fun reg(m: String) {
        Log.i(TAG, "sonda: $m")
        h.post { onRegistro(m) }
    }
}
