package red.sismo

import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlin.concurrent.thread
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
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
    private val onRegistro: (String) -> Unit = {},
    private val op: Opciones? = null
) {

    companion object {
        private const val TAG = "SismoRed"
        private const val C_AIRE = 343.0        // velocidad del sonido, m/s

        /* ---------- el chasquido bio-acústico (impulse biosonar) ----------
           Inspirado en los murciélagos, cetáceos y en el biosonar de "A Quiet Place":
           En lugar de un tono continuo inaudible e ineficiente a 18,5 kHz (donde los
           altavoces de móviles pierden 30 dB de potencia y los escombros absorben todo),
           se emite un tren de impulsos secos en la banda dulce del altavoz (2,5 kHz a 4,2 kHz).

           - 8 ms de duración con ventana Tukey: concentra la máxima energía acústica del altavoz
             sin pops de DC ni distorsión, y reduce la zona ciega a menos de 70 cm.
           - Ancho de banda de 1,7 kHz: pico de correlación ultra estrecho (~10 cm de resolución). */
        const val DUR_BIO_CHASQUIDO = 0.008      // 8 ms
        const val F_BIO_0 = 2500.0              // 2,5 kHz
        const val F_BIO_1 = 4200.0              // 4,2 kHz
        const val CADENCIA_RESP_HZ = 10         // 10 chasquidos por segundo (100 ms periodo)

        private const val DUR_CHIRP = 0.10      // 100 ms
        private const val F0 = 2000.0
        private const val F1 = 8000.0

        /* ---------- sondeo por ráfaga ----------
           Un chasquido suelto da una medida y ninguna forma de saber si es
           buena. Ocho chasquidos permiten APILAR las correlaciones: el eco real
           cae siempre en el mismo sitio y se suma, y el ruido cae en sitios
           distintos y se promedia a la baja — la relación señal/ruido mejora con
           la raíz del número de disparos, unos 9 dB con ocho. */
        private const val CHASQUIDOS = 8
        /** Intentos automaticos antes de dar un resultado, en las tres herramientas
         *  de medida. */
        const val INTENTOS = 5
        private const val ALCANCE_M = 6.0

        private const val PRE_MS = 40
        private const val ESPERA_MS = 100L
        private const val PAUSA_MS = 60L
        private fun VENTANA_N(sr: Int) = (sr * 0.20).roundToInt()
        private const val CAMINO_DIRECTO_M = 0.15

        /* Los umbrales, ahora sacados de la física y no de un eco inventado.
           Con 3 % se ve hormigón hasta ~2,2 m y tabique hasta ~1,2 m, que es el
           rango en el que esta herramienta tiene sentido —un hueco, una pared al
           otro lado de un escombro—. Bajar más no compra alcance útil y empieza a
           inventar: lo comprueba el propio autotest, que con solo ruido no puede
           dar ni un eco. */
        private const val UMBRAL_PILA = 0.030
        private const val UMBRAL_INDIV = 0.025
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
        /* 0,20 Hz = 12 respiraciones por minuto, y ANTES estaba en 0,15 (9/min).
           Lo bajó de golpe una medida de campo: con un ventilador oscilante en la
           sala y nadie cerca del móvil, el detector dijo **PROBABLE CUERPO
           HUMANO, 9 respiraciones por minuto** — exactamente el borde de abajo de
           la banda. Un ventilador que barre de lado a lado cada 6-7 segundos son
           0,15 Hz clavados, y ahí es donde viven también las cortinas, las puertas
           que oscilan y cualquier cosa colgada.

           Un adulto en reposo respira entre 12 y 20 por minuto, y alguien
           atrapado y asustado respira MÁS deprisa, no más despacio. O sea que la
           franja de 9 a 12 aportaba casi ninguna persona real y todos los
           ventiladores del mundo. Se pierde poco y se gana no mentir.

           Esto es la primera vez que este detector se mide contra audio real, y
           lo primero que hizo fue un falso positivo. */
        private const val RESP_HZ_MIN = 0.20
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
    @Volatile private var bucleActivo: AutoCloseable? = null

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
        if (tonoVivo) {
            tonoVivo = false
            quienTono = ""
            try { bucleActivo?.close() } catch (_: Exception) {}
            bucleActivo = null
        }
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
        try { bucleActivo?.close() } catch (_: Exception) {}
        bucleActivo = null
        if (habia) reg("todo lo que sonaba está apagado")
    }
    /** Relación bandas laterales / portadora del último marco. La escribe el
     *  hilo del micrófono y la lee el del doppler, de ahí el @Volatile. */
    @Volatile private var dopplerRel = 0.0

    /* ---------- fase del tono, que es lo que ve una respiración ----------
       La relación bandas/portadora NO puede ver respirar, y no es cuestión de
       umbrales: con marcos de 2048 a 48 kHz cada bin son 23,4 Hz, la banda que se
       analizaba empieza en el bin 3 —70 Hz— y un tórax respirando desplaza
       **0,5 Hz**. Es la cincuentava parte de UN bin. Estaba midiendo en un sitio
       donde la señal no está, y por eso no detectaba a nadie ni a diez
       centímetros.

       Lo que sí la ve es la FASE. A 18,5 kHz la longitud de onda son 1,85 cm, y
       una excursión de tórax de 5 mm cambia el camino de ida y vuelta en 1 cm:
       **3,4 radianes**. No es una señal pequeña, es enorme — solo había que
       mirarla donde estaba.

       Se demodula en I/Q con el índice ABSOLUTO de muestra: si se usara el índice
       local de cada marco, el salto de fase entre marcos sería un valor distinto
       cada vez y metería un ruido que no existe. */
    @Volatile private var fase = 0.0
    /** Amplitud del propio tono recibido. Si esto es ~0, el móvil no se está
     *  oyendo a sí mismo y cualquier veredicto sería inventado. */
    @Volatile private var faseAmp = 0.0
    private var marcosVistos = 0L

    /* ================= chirp y filtro adaptado ================= */

    /** Chirp bio-acústico lineal con ventana de Tukey (taper del 15% en los extremos):
     *  concentra la máxima energía en la banda dulce del altavoz (2,5 a 4,2 kHz) y
     *  permite una resolución espacial de ~10 cm sin clipping. */
    fun chirp(sr: Int, dur: Double = DUR_CHIRP, f0: Double = F0, f1: Double = F1): FloatArray {
        val n = (sr * dur).roundToInt()
        val k = (f1 - f0) / dur
        val taper = (n * 0.15).roundToInt().coerceAtLeast(1)
        return FloatArray(n) { i ->
            val t = i.toDouble() / sr
            val w = when {
                i < taper -> 0.5 - 0.5 * cos(PI * i / taper)
                i >= n - taper -> 0.5 - 0.5 * cos(PI * (n - 1 - i) / taper)
                else -> 1.0
            }
            (sin(2.0 * PI * (f0 * t + 0.5 * k * t * t)) * w).toFloat()
        }
    }

    /** Cuántos reflectores encontró la última pila, antes de recortar a cuatro. */
    @Volatile var encontrados = 0; private set

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
    /**
     * La firma del propio móvil: lo que devuelve la correlación **sin que haya
     * nada delante**. Caminos altavoz→micrófono por la carcasa, resonancias del
     * cuerpo y los lóbulos del propio chirp.
     *
     * Esto es lo que rompía la herramienta, y se vio en campo: daba **las mismas
     * cuatro superficies tapando el móvil con objetos encima**. Claro — esos
     * cuatro picos no eran de la sala. Son fijos, se apilan perfecto en los ocho
     * disparos porque están en todos, y con el umbral bajado al 3 % pasan de
     * sobra. Los ecos de verdad (3-7 %) quedaban por debajo y nunca entraban en
     * la lista de cuatro.
     *
     * Se aprende una vez y se resta siempre. Es lo mismo que hace cualquier radar
     * con su acoplo directo, y sin ello un móvil no puede sondear nada.
     */
    @Volatile var firma: DoubleArray? = null
        private set

    init {
        op?.sondaFirma?.let { f ->
            firma = f
            Log.i(TAG, "sonda: firma del móvil restaurada desde almacenamiento (${f.size} puntos)")
        }
    }

    fun firmaBorrar() {
        firma = null
        op?.sondaFirma = null
    }

    fun aprenderFirma(tramos: List<DoubleArray>) {
        if (tramos.isEmpty()) return
        val maxLag = tramos[0].size - 1
        val f = DoubleArray(maxLag + 1)
        for (t in tramos) for (k in 0..maxLag) f[k] += t[k]
        for (k in f.indices) f[k] /= tramos.size
        firma = f
        op?.sondaFirma = f
        Log.i(TAG, "sonda: firma del móvil aprendida (%d puntos, pico %.3f) y persistida".format(f.size, f.max()))
    }

    fun apilar(tramos: List<DoubleArray>, sr: Int): List<Reflector> {
        if (tramos.isEmpty()) return emptyList()
        val maxLag = tramos[0].size - 1
        val ciego = max(1, (sr * 0.0006).roundToInt())          // ~10 cm: zona ciega del directo
        val n = tramos.size

        val pila = DoubleArray(maxLag + 1)
        for (t in tramos) for (k in 0..maxLag) pila[k] += t[k]
        for (k in pila.indices) pila[k] /= n

        /* Fuera la firma del propio móvil. Lo que queda es lo que ha cambiado
           respecto a «el teléfono solo», que es la definición de reflector. */
        val f = firma
        if (f != null && f.size == pila.size) {
            for (k in pila.indices) pila[k] = max(0.0, pila[k] - f[k])
        } else {
            /* Auto-Zero NLMS adaptativo (AUD-04): cuando no hay firma calibrada
               al aire (firma == null), un filtro adaptativo NLMS estima la respuesta
               estacionaria del chasis en el campo cercano (< 50 cm) a partir de los
               primeros 3 disparos y la cancela con un taper suave de Hann para evitar
               artefactos de borde, eliminando paredes fantasma producidas por acoplo
               interno altavoz-micrófono. */
            val kPass = (sr * 2 * 0.48 / C_AIRE).roundToInt()
            val kStop = (sr * 2 * 0.62 / C_AIRE).roundToInt()
            val nCoef = min(pila.size, kStop)
            val w = DoubleArray(nCoef)
            val numDisparos = min(3, tramos.size)
            for (r in 0 until numDisparos) {
                val t = tramos[r]
                val x = if (t.isNotEmpty()) t[0] else 1.0
                val invNorm = 1.0 / (x * x + 1e-6)
                val mu = 1.0 / (r + 1.0)
                for (k in 1 until min(nCoef, t.size)) {
                    val yEst = w[k] * x
                    val err = t[k] - yEst
                    w[k] += (mu * err * x * invNorm).coerceIn(-1.0, 1.0)
                }
            }
            for (k in 1 until nCoef) {
                val taper = if (k <= kPass) 1.0 else {
                    0.5 * (1.0 + cos(PI * (k - kPass) / (kStop - kPass)))
                }
                pila[k] = max(0.0, pila[k] - w[k] * taper)
            }
        }

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
            } else {
                i++
            }
        }
        /* Se siguen dando como mucho cuatro para no llenar la pantalla, pero
           quien llama sabe cuántos había: decir «4 superficies» siempre que haya
           cuatro o más es un tope disfrazado de medida, y en campo salía 4 en las
           cinco rondas pasara lo que pasara. */
        encontrados = refs.size
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

    /** Si está en true, la próxima ráfaga se guarda como firma del móvil en vez
     *  de analizarse. Es la misma captura: no hay dos códigos que mantener. */
    @Volatile private var aprendiendo = false

    /**
     * Aprender la firma del propio móvil con una ráfaga de verdad.
     *
     * Hay que sujetarlo **lejos de todo** —brazo estirado, hacia arriba, nada a
     * menos de dos metros—. Lo que devuelva ahí es el teléfono oyéndose a sí
     * mismo, y es lo que se resta a partir de entonces.
     *
     * Sin esto el arreglo no sirve de nada: la firma se queda vacía y el eco
     * vuelve a dar las mismas cuatro superficies pase lo que pase.
     */
    fun aprenderMovil(onProgreso: (String) -> Unit = {}, onResultado: (String) -> Unit) {
        if (ocupada) { h.post { onResultado("Espera: ya hay una medida en marcha.") }; return }
        aprendiendo = true
        sondear(onProgreso, onResultado)
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

                if (aprendiendo) {
                    aprendiendo = false
                    aprenderFirma(tramos)
                    val n = firma?.size ?: 0
                    h.post {
                        onResultado(linea(
                            "Firma del móvil aprendida ($n puntos).",
                            "A partir de ahora se resta de cada medida, así que lo que",
                            "salga será de la sala y no del propio teléfono.",
                            "Si cambias de funda o de móvil, vuelve a aprenderla."
                        ))
                    }
                    ocupada = false
                    return@thread
                }

                val refs = apilar(tramos, sr)
                val t = ultima?.let { rt60(it, sr, ultimoDirecto + tpl.size) } ?: 0.0

                // se dice cuántos había de verdad, no siempre "4"
                val cuantos = encontrados
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
                /* El número de verdad, y si se han recortado, decirlo. «4
                   superficies» siempre que hubiera cuatro o más era un tope
                   disfrazado de medida, y en campo salía 4 en las cinco rondas
                   pasara lo que pasara. */
                reg("Sonda: $cuantos superficie(s) alrededor" +
                    (if (cuantos > refs.size) ", enseño las ${refs.size} más fuertes" else "") +
                    (if (firma == null) " · Auto-Zero NLMS (<50cm filtrado, sin calibración manual)" else " · con firma calibrada"))
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
     * Detector de movimiento por sonar de impulsos bio-acústicos (MTI Pulse Sonar).
     *
     * Emite un tren periódico de chasquidos secos a 10 Hz en la banda dulce (2,5-4,2 kHz).
     * Compara cada perfil de eco contra el anterior entre 0,4 y 3,5 metros: si todo está quieto,
     * el eco es idéntico; si un cuerpo o extremidad se mueve, la diferencia temporal se dispara.
     *
     * Ventajas frente al tono ultrasónico antiguo:
     *  1. 30 dB más de potencia acústica física por aprovechar la resonancia del altavoz.
     *  2. No deja sorda la malla acústica (16-18 kHz) mientras suena.
     *  3. Rango de penetración muy superior en escombros, mantas y polvo.
     */
    fun doppler(encender: Boolean, onProgreso: (String) -> Unit, onResultado: (String) -> Unit) {
        if (!encender) {
            tonoVivo = false; quienTono = ""
            try { bucleActivo?.close() } catch (_: Exception) {}
            bucleActivo = null
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
        reg("Buscando movimiento con tren bio-acústico de chasquidos (2,5-4,2 kHz a ${CADENCIA_RESP_HZ} Hz).")

        val sr = mic.sr
        val click = chirp(sr, dur = DUR_BIO_CHASQUIDO, f0 = F_BIO_0, f1 = F_BIO_1)
        val paso = sr / CADENCIA_RESP_HZ
        val pcmTren = ShortArray(sr)
        val volFactor = volTono.coerceIn(0.2, 1.0)
        for (k in 0 until CADENCIA_RESP_HZ) {
            val start = k * paso
            for (i in click.indices) {
                if (start + i < sr) {
                    pcmTren[start + i] = (click[i] * Short.MAX_VALUE * 0.95 * volFactor).toInt().toShort()
                }
            }
        }

        thread(name = "doppler-biosonar", isDaemon = true) {
            var bucle: AutoCloseable? = null
            try {
                tonoVivo = true
                bucle = Altavoz.iniciarBucle(pcmTren, sr)
                bucleActivo = bucle

                Thread.sleep(400)

                val ciego = (sr * 0.35 * 2 / C_AIRE).roundToInt()
                val maxLag = (sr * 3.5 * 2 / C_AIRE).roundToInt()

                var prevCorr: DoubleArray? = null
                var base = 0.0
                var cuentaBase = 0

                val tBase = System.currentTimeMillis()
                while (tonoVivo && System.currentTimeMillis() - tBase < 700L) {
                    Thread.sleep(1000L / CADENCIA_RESP_HZ)
                    val rec = mic.cola((sr * 0.15).roundToInt())
                    val corr = correlar(rec, click)
                    if (corr.isNotEmpty()) {
                        if (prevCorr != null && prevCorr!!.size == corr.size) {
                            var d0 = 0; var mx0 = 0.0
                            for (i in corr.indices) if (corr[i] > mx0) { mx0 = corr[i]; d0 = i }
                            val start = minOf(corr.size - 1, d0 + ciego)
                            val end = minOf(corr.size - 1, d0 + maxLag)
                            if (end > start) {
                                var sumDiff = 0.0
                                for (k in start..end) sumDiff += abs(corr[k] - prevCorr!![k])
                                base += sumDiff / (end - start + 1)
                                cuentaBase++
                            }
                        }
                        prevCorr = corr
                    }
                }
                base = if (cuentaBase > 0) base / cuentaBase else 1e-4
                base = maxOf(base, 1e-4)

                var pico = 0.0
                for (intento in 1..INTENTOS) {
                    if (!tonoVivo) break
                    var picoIntento = 0.0
                    val t1 = System.currentTimeMillis()
                    while (tonoVivo && System.currentTimeMillis() - t1 < 5000L) {
                        Thread.sleep(1000L / CADENCIA_RESP_HZ)
                        val rec = mic.cola((sr * 0.15).roundToInt())
                        val corr = correlar(rec, click)
                        if (corr.isNotEmpty()) {
                            if (prevCorr != null && prevCorr!!.size == corr.size) {
                                var d0 = 0; var mx0 = 0.0
                                for (i in corr.indices) if (corr[i] > mx0) { mx0 = corr[i]; d0 = i }
                                val start = minOf(corr.size - 1, d0 + ciego)
                                val end = minOf(corr.size - 1, d0 + maxLag)
                                if (end > start) {
                                    var sumDiff = 0.0
                                    for (k in start..end) sumDiff += abs(corr[k] - prevCorr!![k])
                                    val difMed = sumDiff / (end - start + 1)
                                    val rel = difMed / base
                                    nivelDoppler = rel
                                    if (rel > picoIntento) picoIntento = rel
                                    if (rel > pico) pico = rel
                                    h.post {
                                        onProgreso(
                                            linea(
                                                "Intento $intento de $INTENTOS (biosonar)",
                                                "Ahora: %.1f veces el fondo (quieto = 1)".format(rel),
                                                "Máximo visto: %.1f veces".format(pico),
                                                "No muevas el móvil"
                                            )
                                        )
                                    }
                                }
                            }
                            prevCorr = corr
                        }
                    }
                }
                val hechos = tonoVivo
                tonoVivo = false
                nivelDoppler = 1.0
                val rel = pico
                val veredicto = when {
                    rel > 3.0 -> "MOVIMIENTO CERCA"
                    rel > 1.8 -> "Movimiento leve, o una corriente de aire"
                    else -> "Sin movimiento"
                }
                reg("Movimiento biosonar: $veredicto (%.1f veces el fondo)".format(rel))
                h.post {
                    onResultado(
                        linea(
                            veredicto + ".",
                            if (hechos) "Resultado tras $INTENTOS intentos." else "Detenido antes de acabar.",
                            "Máximo medido: %.1f veces el fondo.".format(rel),
                            "Chasquidos de 2,5-4,2 kHz (alcance hasta 3,5 m)."
                        )
                    )
                }
            } catch (ex: Exception) {
                Log.e(TAG, "doppler biosonar falló", ex)
                h.post { onResultado("El movimiento falló: ${ex.message}") }
            } finally {
                tonoVivo = false
                quienTono = ""
                nivelDoppler = 1.0
                mic.cerrar(Microfono.USA_SONDA)
                try { bucle?.close() } catch (_: Exception) {}
                bucleActivo = null
                ocupada = false
            }
        }
    }

    /* ================= respiración: ¿hay un cuerpo vivo? ================= */

    /**
     * Detección de respiración por biosonar de impulsos con acotación de distancia (Range-Gated Sonar).
     *
     * En lugar de un tono continuo de 18,5 kHz inaudible y atenuado, emite un tren bio-acústico de
     * chasquidos a 10 Hz (estilo murciélago / "A Quiet Place") en la banda dulce de 2,5 a 4,2 kHz.
     *
     * Cada chasquido produce una correlación con el sonido directo y sus ecos. Se aísla el reflector
     * de rescate (0,35 m a 3,0 m) y se demodula la fase de su portadora relativa al sonido directo,
     * eliminando cualquier deriva o jitter de audio del sistema operativo.
     *
     * El movimiento rítmico del tórax al respirar modula la fase del eco en un patrón sinusoidal
     * que la autocorrelación normalizada de `periodicidad()` reconoce entre 12 y 36 respiraciones por minuto.
     */
    fun respiracion(
        encender: Boolean,
        onProgreso: (String) -> Unit,
        onResultado: (String) -> Unit
    ) {
        if (!encender) {
            tonoVivo = false; quienTono = ""
            try { bucleActivo?.close() } catch (_: Exception) {}
            bucleActivo = null
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
        reg("Buscando respiración (biosonar por chasquidos): ciclos de $segundos s a ${CADENCIA_RESP_HZ} Hz.")

        val sr = mic.sr
        val click = chirp(sr, dur = DUR_BIO_CHASQUIDO, f0 = F_BIO_0, f1 = F_BIO_1)
        val paso = sr / CADENCIA_RESP_HZ
        val pcmTren = ShortArray(sr)
        val volFactor = volTono.coerceIn(0.2, 1.0)
        for (k in 0 until CADENCIA_RESP_HZ) {
            val start = k * paso
            for (i in click.indices) {
                if (start + i < sr) {
                    pcmTren[start + i] = (click[i] * Short.MAX_VALUE * 0.95 * volFactor).toInt().toShort()
                }
            }
        }

        thread(name = "respiracion-biosonar", isDaemon = true) {
            var bucle: AutoCloseable? = null
            try {
                tonoVivo = true
                bucle = Altavoz.iniciarBucle(pcmTren, sr)
                bucleActivo = bucle

                // Pausa breve para calentar el pipeline de audio
                Thread.sleep(500)

                var ciclo = 0
                var mejor: Ritmo? = null
                var mejorBpm = 0.0
                while (tonoVivo && ciclo < INTENTOS) {
                    ciclo++
                    val serie = ArrayList<Double>(segundos * CADENCIA_RESP_HZ)
                    var faseAnt = 0.0
                    var faseAcum = 0.0
                    var inicializado = false
                    val t0 = System.currentTimeMillis()

                    val fc = (F_BIO_0 + F_BIO_1) / 2.0
                    val w = 2.0 * PI * fc / sr
                    val span = (sr * 0.003).roundToInt().coerceAtLeast(2)
                    val ciego = (sr * 0.35 * 2 / C_AIRE).roundToInt()
                    val maxLag = (sr * 3.0 * 2 / C_AIRE).roundToInt()

                    while (tonoVivo && System.currentTimeMillis() - t0 < segundos * 1000L) {
                        Thread.sleep(1000L / CADENCIA_RESP_HZ)
                        val rec = mic.cola((sr * 0.15).roundToInt())
                        val corr = correlar(rec, click)
                        if (corr.isEmpty()) continue

                        var d0 = 0; var mx0 = 0.0
                        for (i in corr.indices) if (corr[i] > mx0) { mx0 = corr[i]; d0 = i }

                        val startSearch = d0 + ciego
                        val endSearch = minOf(corr.size - 1, d0 + maxLag)
                        var bestPeak = startSearch
                        var bestVal = 0.0
                        for (i in startSearch..endSearch) {
                            if (corr[i] > bestVal) {
                                bestVal = corr[i]
                                bestPeak = i
                            }
                        }

                        var si = 0.0; var sq = 0.0
                        for (k in -span..span) {
                            val idx = bestPeak + k
                            if (idx in rec.indices) {
                                val ang = w * (idx - d0)
                                val s = rec[idx].toDouble()
                                si += s * cos(ang)
                                sq += s * sin(ang)
                            }
                        }
                        val faseEcho = atan2(sq, si)
                        val ampEcho = hypot(si, sq)
                        nivelDoppler = ampEcho

                        if (!inicializado) {
                            faseAnt = faseEcho
                            inicializado = true
                        }
                        var d = faseEcho - faseAnt
                        while (d > PI) d -= 2 * PI
                        while (d < -PI) d += 2 * PI
                        faseAcum += d
                        faseAnt = faseEcho
                        serie.add(faseAcum)

                        val queda = segundos - (System.currentTimeMillis() - t0) / 1000
                        val n = ciclo
                        h.post {
                            onProgreso(
                                linea(
                                    "Escuchando si alguien respira (biosonar)…",
                                    "Quedan $queda s (ciclo $n de $INTENTOS)",
                                    "Chasquidos bio-acústicos a ${CADENCIA_RESP_HZ} Hz",
                                    "No toques el móvil y no hables"
                                )
                            )
                        }
                    }

                    if (!tonoVivo && serie.size < segundos * CADENCIA_RESP_HZ / 2) {
                        h.post { onResultado("Medida cancelada: hacen falta los $segundos s enteros.") }
                        break
                    }

                    val r = periodicidad(serie)
                    val bpm = if (r.lag > 0) 60.0 * CADENCIA_RESP_HZ / r.lag else 0.0
                    if (mejor == null || r.fuerza > mejor!!.fuerza) { mejor = r; mejorBpm = bpm }

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
                }

                val completo = ciclo >= INTENTOS
                tonoVivo = false
                nivelDoppler = 1.0
                val m = mejor
                val vf = when {
                    m == null -> "Sin medida completa."
                    m.energia < ENERGIA_MIN -> "NADA SE MUEVE AHÍ. Ni un cuerpo ni un escombro."
                    m.fuerza >= UMBRAL_RESP && m.lag > 0 ->
                        "PROBABLE CUERPO HUMANO. ${mejorBpm.roundToInt()} respiraciones por minuto."
                    else -> "Algo se mueve, pero sin ritmo de respiración."
                }
                reg("Respiración biosonar: $vf")
                h.post {
                    onResultado(
                        linea(
                            vf,
                            if (completo) "Resultado tras $INTENTOS intentos." else "Detenido en el intento $ciclo.",
                            if (m != null) "Periodicidad %.2f, hace falta %.2f.".format(m.fuerza, UMBRAL_RESP) else "",
                            if (m != null) "Movimiento medido: %.3f.".format(m.energia) else "",
                            "Chasquidos de 2,5-4,2 kHz (alcance hasta 3 m)."
                        )
                    )
                }
            } catch (ex: Exception) {
                Log.e(TAG, "respiracion biosonar falló", ex)
                h.post { onResultado("La búsqueda falló: ${ex.message}") }
            } finally {
                tonoVivo = false
                quienTono = ""
                nivelDoppler = 1.0
                mic.cerrar(Microfono.USA_SONDA)
                try { bucle?.close() } catch (_: Exception) {}
                bucleActivo = null
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
    /**
     * Cuánto vuelve de verdad de una pared a [d] metros, en tanto por uno de la
     * amplitud del sonido directo.
     *
     * Esto es el número que faltaba, y por no tenerlo la sonda no servía. El
     * autotest inyectaba un eco al 35 % y con eso cualquier umbral pasa; una
     * pared real está un orden de magnitud por debajo:
     *
     *   amplitud ∝ 1/r, camino directo ≈ 15 cm (altavoz→micrófono por el cuerpo
     *   del móvil), camino del eco = 2d, por el coeficiente de reflexión.
     *
     *     1,2 m de hormigón (R≈0,9) →  5,6 %
     *     1,2 m de tabique  (R≈0,5) →  3,1 %
     *     2,0 m de hormigón         →  3,4 %
     *
     * El umbral estaba en el 12 %. O sea que la sonda **no podía ver una pared a
     * más de medio metro**, y ninguna prueba lo decía porque ninguna prueba usaba
     * una amplitud real. Esto no es una calibración de campo: es aritmética, y se
     * podía haber hecho sin salir de casa.
     */
    fun ecoFisico(d: Double, r: Double = 0.9): Float =
        ((CAMINO_DIRECTO_M / (2 * d)) * r).toFloat()

    private fun simular(sr: Int, tpl: FloatArray, d: Double, eco: Float, ruido: Double,
                        artefacto: Boolean = false): FloatArray {
        val rec = FloatArray((sr * 0.25).roundToInt())
        for (i in rec.indices) rec[i] = ((Math.random() - 0.5) * ruido).toFloat()
        val off = (sr * 0.02).roundToInt()
        val lag = (2 * d / C_AIRE * sr).roundToInt()
        for (i in tpl.indices) {
            if (off + i < rec.size) rec[off + i] += tpl[i]                          // directo
            if (eco > 0 && off + lag + i < rec.size) rec[off + lag + i] += tpl[i] * eco
        }
        /* La firma del propio móvil: dos rebotes fijos por la carcasa, siempre en
           el mismo sitio y siempre presentes. Es lo que en campo salía como
           «4 superficies» pasara lo que pasara. */
        if (artefacto) {
            /* CUATRO, no dos: en el móvil real llenaban la lista de cuatro y por
               eso salía siempre «4 superficies» y la pared no entraba nunca. Con
               dos, la simulación no reproducía el síntoma y la prueba no probaba
               nada. Todos por encima del eco de una pared a 2 m (3,4 %). */
            for ((lagFijo, amp) in listOf(
                (sr * 0.0020).roundToInt() to 0.20f, (sr * 0.0031).roundToInt() to 0.16f,
                (sr * 0.0045).roundToInt() to 0.12f, (sr * 0.0060).roundToInt() to 0.09f
            )) {
                for (i in tpl.indices) {
                    if (off + lagFijo + i < rec.size) rec[off + lagFijo + i] += tpl[i] * amp
                }
            }
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

        /* -1) La firma del propio móvil. Es la prueba de lo que se vio en campo:
               con dos rebotes fijos de la carcasa —más fuertes que cualquier
               pared— la lista de cuatro se llena con ellos y la pared de verdad
               no entra. Aprendiendo la firma con el móvil "al aire" y restándola,
               la pared aparece. Sin esto, la sonda mide el teléfono, no la sala. */
        run {
            val dPared = 2.0
            val amp = ecoFisico(dPared, 0.9)
            fun rafaga(conPared: Boolean): List<DoubleArray> {
                val l = ArrayList<DoubleArray>()
                for (r in 0 until CHASQUIDOS) {
                    val rec = simular(sr, tpl, dPared, if (conPared) amp else 0f, 0.10, artefacto = true)
                    tramo(correlar(rec, tpl), maxLag)?.let { l.add(it) }
                }
                return l
            }
            firmaBorrar()
            val sinRestar = apilar(rafaga(true), sr)
            val veSinRestar = sinRestar.any { abs(it.distancia - dPared) < 0.12 }
            aprenderFirma(rafaga(false))                 // el móvil "al aire"
            val conRestar = apilar(rafaga(true), sr)
            val veConRestar = conRestar.any { abs(it.distancia - dPared) < 0.12 }
            firmaBorrar()
            /* Y lo que de verdad hay que exigir: que los artefactos DESAPAREZCAN.
               Que la pared se vea es la consecuencia; que el móvil deje de
               reportarse a sí mismo es la causa. Además, exigimos que sin calibración
               (sinRestar) el Auto-Zero NLMS elimine completamente cualquier fantasma a < 50 cm (AUD-04). */
            val fantasmas = conRestar.count { it.distancia < 1.1 }
            val fantasmasAntes = sinRestar.count { it.distancia < 1.1 }
            val fantasmasCercaAntes = sinRestar.count { it.distancia < 0.50 }
            if (!veConRestar || fantasmas > 0 || fantasmasCercaAntes > 0) todo = false
            partes.add("firma del móvil → antes: pared=$veSinRestar y $fantasmasAntes fantasmas ($fantasmasCercaAntes <50cm) · " +
                "después: pared=$veConRestar y $fantasmas fantasmas " +
                if (veConRestar && fantasmas == 0 && fantasmasCercaAntes == 0) "OK" else "FALLÓ")
        }

        /* 0) LO PRIMERO: paredes con la amplitud que devuelven de verdad.
              Estos cuatro casos son los que faltaban, y son los que dicen si esta
              herramienta sirve para algo. Un eco al 35 % no existe en la
              naturaleza: a 1,2 m el hormigón devuelve 5,6 % y el tabique 3,1 %.
              Si estos fallan, la sonda no ve una pared por mucho que pasen los
              demás. */
        for ((dist, refl, mat) in listOf(
            Triple(1.0, 0.9, "hormigón a 1 m"), Triple(2.0, 0.9, "hormigón a 2 m"),
            Triple(1.0, 0.5, "tabique a 1 m"), Triple(1.5, 0.5, "tabique a 1,5 m")
        )) {
            val amp = ecoFisico(dist, refl)
            val t = ArrayList<DoubleArray>()
            for (r in 0 until CHASQUIDOS) {
                tramo(correlar(simular(sr, tpl, dist, amp, 0.10), tpl), maxLag)?.let { t.add(it) }
            }
            val v = apilar(t, sr).firstOrNull { abs(it.distancia - dist) < 0.12 }
            /* Por debajo del umbral no se exige verla: es el BORDE de la
               herramienta, no un defecto. Decir que falla cuando hace justo lo
               que tiene que hacer deja el diagnóstico en rojo para siempre, y un
               rojo permanente deja de mirarse. */
            val exigible = amp >= UMBRAL_PILA
            if (exigible && v == null) todo = false
            partes.add("$mat (${"%.1f".format(amp * 100)} %) → " +
                when {
                    v != null -> "%.2f m OK".format(v.distancia)
                    exigible -> "NO LA VE, FALLÓ"
                    else -> "no la ve (por debajo del umbral, es el límite)"
                })
        }

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
        reg("autotest sonda · " + partes.joinToString(" | "))
        System.err.println("AUTOTEST SONDA: " + partes.joinToString(" | "))
        /* Los dos, SIEMPRE. Con `todo && autotestRespiracion()` el `&&` corta: en
           cuanto un caso de la sonda salía mal, la batería entera de respiración
           no llegaba a correr y nadie se enteraba. Un banco de pruebas que se
           salta pruebas en silencio es peor que no tenerlo. */
        val resp = autotestRespiracion()
        System.err.println("AUTOTEST TODO=$todo, RESP=$resp")
        return todo && resp
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
        /* Este caso ha cambiado de bando, y por una medida real: un ventilador
           oscilante da exactamente esto y el detector lo llamó cuerpo humano.
           Ahora 9/min tiene que salir NEGATIVO — es la regresión que impide que
           alguien vuelva a bajar la banda sin saber lo que cuesta. */
        caso("ventilador oscilante a 9/min (era el borde de la banda)", serie(0.15, 0.4, 0.15), false)
        caso("respira 12/min, el más lento que se admite", serie(0.20, 0.4, 0.15), true, 12.0)
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
