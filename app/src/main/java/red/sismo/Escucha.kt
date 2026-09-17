package red.sismo

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlin.concurrent.thread
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Escucha forense: cinco detectores sobre el micrófono compartido.
 *
 * Todo son heurísticas de señal, sin modelos ni red. Cada detección va con su
 * confianza precisamente porque se equivocan: aquí un falso positivo cuesta
 * batería y un falso negativo cuesta una vida, así que el sesgo es hacia oír de
 * más. Es el port de `SismoRed/listen.js`, ya afinado contra sonidos reales.
 *
 * Las dos decisiones que no son obvias, y que costaron encontrar:
 *
 *  - **La voz se separa por el tono, no por la banda.** A una voz masculina no
 *    se le puede exigir energía en 400-3000 Hz: se le concentra en el
 *    fundamental, por debajo de 150 Hz. Lo que la distingue de un derrumbe no es
 *    dónde está la energía, es que la voz tiene tono y el derrumbe es ruido.
 *  - **De una máquina se separa porque la voz oscila.** Un motor, un generador o
 *    un transformador sostienen la nota clavada; una persona nunca mantiene el
 *    tono exacto. Se mide la desviación relativa del fundamental.
 */
class Escucha(
    private val mic: Microfono,
    private val onEstruendo: () -> Unit,
    private val onRegistro: (String) -> Unit = {}
) {

    companion object {
        private const val TAG = "SismoRed"
        private const val N = Microfono.N
        /** Un tick cada ~100 ms, como el `setInterval(forenseTick, 100)` de la PWA. */
        private const val MARCOS_POR_TICK = 5

        /** Los cinco detectores en el orden en que se pintan. El orden importa:
         *  es el mismo de la rejilla de la PWA y el de todo lo que se publica
         *  hacia la pantalla, así que se declara una sola vez y aquí. */
        val CLAVES = arrayOf("estruendo", "grito", "voz", "animal", "golpes")

        /* Cortes de [motorCerca]. Sin medir todavía: un motor de camión
           ronda los -50 dB a pocos metros, pone más de la mitad de su
           energía por debajo de 400 Hz y tiene tono, o sea planitud baja. */
        private const val MOTOR_DB = -55.0
        private const val MOTOR_GRAVE = 0.55
        private const val MOTOR_PLANITUD = 0.35
        val ROTULOS = arrayOf("DERRUMBE", "GRITOS", "VOZ", "ANIMALES", "GOLPES")

        /** Cuánto se queda «caliente» un detector después de disparar. */
        const val CALIENTE_MS = 3000L

        /** Ticks de envolvente que se guardan: ~2,5 s, tres o cuatro sílabas. */
        private const val ENV_N = 24
        /** Cuánto tiene que sacarle el ganador al segundo para que cuente. */
        private const val MARGEN = 0.15
        /** Regularidad mínima del ritmo para dar unos golpes por buenos. */
        private const val RITMO_MIN = 0.45

        /** Rapidez del fondo adaptativo: constante de tiempo de unos 5 s. */
        private const val FONDO_TAU = 0.02
        /** Ticks antes de fiarse del fondo, para no disparar al encender. */
        private const val CALIENTA_N = 12
        /** dB por encima del fondo para que algo cuente como suceso. */
        private const val NOV_EVENTO = 6.0
        /** dB por encima del fondo para un colapso: un derrumbe es un cataclismo acústico (+20 dB). */
        private const val NOV_ESTRUENDO = 20.0
        private const val MOD_ESTRUENDO_MAX = 0.25
        private const val SOSTENIDO_ESTRUENDO_MAX = 35
        private const val NOV_VOZ = 3.0
        /** Una voz real oscila mucho más de lo que yo creía: en la biblioteca
         *  mide entre 0,40 y 0,75, y con el tope en 0,35 no se encendía NUNCA. */
        private const val VIB_MIN = 0.005
        private const val VIB_MAX = 1.2
        /** Ticks de silencio que se meten antes de cada prueba. */
        private const val SILENCIO_N = 16
    }

    /** Cada detector con su histéresis y su tiempo de espera propios. */
    private class Evento(val txt: String, val need: Int, val cd: Long) {
        var n = 0
        var last = 0L
    }

    private val ev = mapOf(
        "estruendo" to Evento("ESTRUENDO / DERRUMBE", 4, 8000),
        "grito" to Evento("GRITO DE AUXILIO", 3, 4000),
        "voz" to Evento("VOZ HUMANA CERCA", 4, 6000),
        "animal" to Evento("ANIMAL (ladrido / chillido)", 3, 6000),
        "golpes" to Evento("GOLPES RÍTMICOS", 1, 5000)
    )

    @Volatile var escuchando = false; private set
    @Volatile var nivelDb = -90.0; private set
    @Volatile var tonoHz = 0.0; private set
    @Volatile var impactos = 0; private set

    /**
     * Suena un motor cerca: grave y con el tono concentrado.
     *
     * Es lo que separa un camión de un terremoto cuando los dos hacen vibrar
     * el edificio tres segundos. Un motor pone la energía abajo y en pocos
     * bins —tiene tono—; un sismo haciendo crujir la casa la reparte por todo
     * el espectro. La planitud ya distinguía eso para clasificar sonidos; aquí
     * se usa para lo contrario, para dudar de lo que mide el acelerómetro.
     *
     * **Los tres cortes son una primera aproximación, no una medida.** Van al
     * registro con sus números precisamente para poder calibrarlos el día que
     * pase un camión de verdad.
     */
    @Volatile var motorCerca = false; private set
    /** Los números de arriba, crudos, para poder ajustarlos con datos. */
    @Volatile var graveFrac = 0.0; private set
    @Volatile var planitudEsp = 1.0; private set

    /** Últimas detecciones con su hora, para pintarlas. Lo que la PWA muestra en
     *  la tarjeta de detección en tiempo real: sin esto los detectores corren a
     *  ciegas y nadie sabe si están viendo algo. */
    private val ultimos = java.util.concurrent.ConcurrentLinkedDeque<String>()
    fun ultimasDetecciones(): List<String> = ultimos.toList()

    /**
     * Cuánta evidencia lleva acumulada cada detector, de 0 a 1, en el orden de
     * [CLAVES]. Es el acumulador con fuga dividido por su umbral — el mismo
     * `e.n / (e.need*2)` que pinta la PWA.
     *
     * Esto no es un adorno: sin ello los cinco detectores solo se ven cuando ya
     * han disparado, y no hay forma de saber si están cerca de hacerlo, que es
     * justo lo que hace falta para calibrarlos en campo. El de los golpes no
     * pasa por el acumulador sino por la cuenta de ataques, así que se informa
     * de lo suyo: los impactos que lleva de los tres que necesita.
     */
    fun progreso(): DoubleArray = DoubleArray(CLAVES.size) { i ->
        val k = CLAVES[i]
        val e = ev[k] ?: return@DoubleArray 0.0
        if (k == "golpes") (impactos / 3.0).coerceIn(0.0, 1.0)
        else (e.n.toDouble() / (e.need * 2)).coerceIn(0.0, 1.0)
    }

    /** Cuándo disparó cada detector por última vez, en el orden de [CLAVES].
     *  0 = todavía nunca. */
    fun cuandoPorTipo(): LongArray = LongArray(CLAVES.size) { ev[CLAVES[it]]?.last ?: 0L }

    /**
     * La onda para el osciloscopio: [puntos] valores de 0 a 1 con el pico de
     * cada bloque. Se decima aquí y no en la pantalla porque quien tiene el
     * micrófono es el servicio, y mandar 8192 muestras cada medio segundo a la
     * actividad sería tirar batería para pintar 128 píxeles.
     */
    fun onda(puntos: Int = 128): FloatArray {
        // 4096 muestras son 85 ms a 48 kHz: de sobra para un osciloscopio, y la
        // mitad de copia que 8192 ahora que esto se pide dieciséis veces por segundo
        val x = mic.cola(4096)
        val paso = max(1, x.size / puntos)
        return FloatArray(puntos) { p ->
            var mx = 0f
            var i = p * paso
            val fin = min(x.size, i + paso)
            while (i < fin) { val a = abs(x[i]); if (a > mx) mx = a; i++ }
            min(1f, mx * 3f)                       // x3, como el drawScope de la PWA
        }
    }

    private val h = Handler(Looper.getMainLooper())
    private var lastDb = -90.0
    private var lastHz = 0.0
    private var onsets = ArrayList<Long>()
    private val wander = ArrayList<Double>()
    private var cuenta = 0
    /** Últimos ~2,5 s de nivel, para medir la modulación silábica. */
    private val envolvente = ArrayList<Double>()
    /** Ticks seguidos con algo sonando. Distingue un estallido de algo continuo. */
    private var sostenido = 0
    /** Nivel de fondo, media lenta del propio nivel. Es lo que convierte «suena
     *  fuerte» en «suena fuerte PARA LO QUE HABÍA», que es lo que importa. */
    private var fondo = -90.0
    private var calienta = 0

    private val ventana = DoubleArray(N) { 0.5 - 0.5 * cos(2.0 * PI * it / (N - 1)) }
    private val espectro = DoubleArray(N / 2)

    /** Mientras esto no haya pasado, los detectores no miran nada: lo está
     *  usando el altavoz de este mismo móvil. Lo levanta el interfono, que saca
     *  la voz maximizada por el altavoz — y una voz amplificada es exactamente lo
     *  que el detector de gritos busca. Sin esta puerta, hablar hacia abajo
     *  disparaba «GRITO DE AUXILIO» y, con el estruendo, podía llegar a lanzar la
     *  alarma él solo. */
    @Volatile private var puertaHasta = 0L

    /** Ensordece los detectores [ms] milisegundos. */
    fun ensordecer(ms: Long) {
        puertaHasta = maxOf(puertaHasta, System.currentTimeMillis() + ms)
    }

    private val oyente = Microfono.Oyente { marco ->
        if (System.currentTimeMillis() < puertaHasta) return@Oyente
        if (++cuenta >= MARCOS_POR_TICK) {
            cuenta = 0
            // el PCM crudo va por parámetro y no se lee de dentro: así el
            // clasificador se puede autocomprobar inyectándole sonidos
            try { tick(marco, mic.cola(2048)) } catch (e: Exception) { Log.e(TAG, "escucha forense", e) }
        }
    }

    /** En pruebas, disparar no puede encender la sirena ni contestar por el
     *  altavoz: solo se anota qué habría disparado. */
    @Volatile private var probando = false
    private var ultimoDisparo: String? = null
    private var ultRasgos = ""

    fun arrancar(): Boolean {
        if (escuchando) return true
        if (!mic.abrir(Microfono.USA_FORENSE)) { reg("no puedo escuchar el entorno: falta el permiso del micrófono"); return false }
        mic.registrar(oyente)
        escuchando = true
        reg("escuchando el entorno")
        return true
    }

    fun parar() {
        if (!escuchando) return
        escuchando = false
        mic.quitar(oyente)
        mic.cerrar(Microfono.USA_FORENSE)
        reg("he dejado de escuchar el entorno")
    }

    /* ================= análisis ================= */

    /**
     * Energía TOTAL de la banda, no la media: las bandas tienen anchos muy
     * distintos y con la media una banda estrecha y grave se llevaba todo el
     * reparto.
     */
    private fun bandPow(f0: Double, f1: Double): Double {
        val res = mic.sr.toDouble() / N
        val a = max(1, (f0 / res).toInt())
        val b = min((f1 / res).toInt(), espectro.size - 1)
        var s = 0.0
        for (i in a..b) s += espectro[i] * espectro[i]
        return s
    }

    /**
     * Planitud espectral (entropía de Wiener): media geométrica entre media
     * aritmética de la potencia por bin.
     *
     * Es lo que separa **ruido de nota**. Cerca de 1 la energía está repartida
     * por igual — un derrumbe, un golpe, el viento. Cerca de 0 está concentrada
     * en unos pocos bins — una voz, un ladrido, un motor. La regla de bandas que
     * había antes no podía distinguir eso: un derrumbe grave y una voz grave
     * ponen la energía en la misma banda.
     */
    private fun planitud(f0: Double = 200.0, f1: Double = 6000.0): Double {
        val res = mic.sr.toDouble() / N
        val a = max(1, (f0 / res).toInt())
        val b = min((f1 / res).toInt(), espectro.size - 1)
        if (b <= a) return 1.0
        var logs = 0.0
        var suma = 0.0
        for (i in a..b) {
            val p = espectro[i] * espectro[i] + 1e-15
            logs += ln(p)
            suma += p
        }
        val n = b - a + 1
        val geo = exp(logs / n)
        val ari = suma / n
        return (geo / (ari + 1e-15)).coerceIn(0.0, 1.0)
    }

    /** Centroide espectral en Hz: dónde está el «centro de gravedad» del sonido.
     *  Un ladrido lo tiene mucho más arriba que una voz hablando. */
    private fun centroide(): Double {
        val res = mic.sr.toDouble() / N
        var num = 0.0; var den = 0.0
        for (i in 1 until espectro.size) {
            val p = espectro[i] * espectro[i]
            num += i * res * p
            den += p
        }
        return if (den > 1e-15) num / den else 0.0
    }

    /**
     * Modulación silábica: qué fracción de la variación del nivel cae entre 2 y
     * 4,5 Hz.
     *
     * Esta es **la** medida que faltaba. Una persona hablando abre y cierra la
     * boca entre tres y seis veces por segundo, y eso deja una huella clarísima
     * en la envolvente. Un grito sostenido no la tiene, un ladrido es un golpe
     * único, un motor es plano y un derrumbe es ruido continuo. Sin esto, «voz»
     * y «animal» se diferenciaban solo por la altura del tono, que es justo lo
     * que se solapa entre una mujer gritando y un perro ladrando.
     */
    private fun modulacion(): Double {
        val n = envolvente.size
        if (n < 12) return 0.0
        val m = envolvente.average()
        var tot = 0.0
        for (v in envolvente) tot += (v - m) * (v - m)
        if (tot < 1e-6) return 0.0

        // la envolvente se muestrea una vez por tick
        val fs = mic.sr.toDouble() / (MARCOS_POR_TICK * Microfono.SALTO)
        var sil = 0.0
        var f = 2.0
        while (f <= min(4.5, fs / 2 - 0.2)) {
            var re = 0.0; var im = 0.0
            for (i in 0 until n) {
                val a = 2.0 * PI * f * i / fs
                re += (envolvente[i] - m) * cos(a)
                im += (envolvente[i] - m) * sin(a)
            }
            sil += (re * re + im * im) * 2.0 / n
            f += 0.5
        }
        return (sil / tot).coerceIn(0.0, 1.0)
    }

    /** Regularidad de los ataques: desviación relativa de los huecos entre
     *  golpes. Alguien pidiendo ayuda golpea a un ritmo; una viga que cruje no. */
    private fun regularidad(t: List<Long>): Double {
        if (t.size < 3) return 0.0
        val huecos = (1 until t.size).map { (t[it] - t[it - 1]).toDouble() }
        /* Cadencia a la que golpea una persona. Los topes son anchos a
           propósito: por arriba, alguien agotado bajo una losa golpea cada dos
           segundos, y por abajo, alguien desesperado golpea metal a seis por
           segundo. Cerrar esta ventana deja fuera justo a los dos que más
           falta hacen. La carpeta `Golpes` del banco está vacía, así que
           ninguno de los dos límites está medido contra grabaciones reales. */
        if (huecos.any { it < 150.0 || it > 2000.0 }) return 0.0
        val m = huecos.average()
        if (m <= 0) return 0.0
        val sd = sqrt(huecos.sumOf { (it - m) * (it - m) } / huecos.size)
        return (1.0 - sd / m).coerceIn(0.0, 1.0)
    }

    private fun tick(marco: ShortArray, crudo: FloatArray) {
        Fft.magnitudes(marco, ventana, espectro)

        val db = rmsDb(crudo)
        val sub = bandPow(20.0, 150.0); val low = bandPow(150.0, 400.0)
        val sp = bandPow(400.0, 3000.0); val hi = bandPow(3000.0, 8000.0)
        val tot = sub + low + sp + hi + 1e-12
        val rumble = (sub + low) / tot
        val speech = sp / tot
        val high = hi / tot

        val p = tono(crudo)
        val jump = db - lastDb
        lastDb = db
        nivelDb = db
        tonoHz = p.hz
        val dhz = if (lastHz > 0.0) abs(p.hz - lastHz) else 0.0
        if (p.hz > 0.0) lastHz = p.hz

        /* Acumulador con fuga: +2 por marco que cumple, −1 por marco que no. El
           habla tiene pausas y consonantes sordas; con +1/−1 el contador se
           quedaba clavado a la mitad y no disparaba nunca. */
        fun step(k: String, cond: Boolean, conf: Double, extra: () -> String) {
            val e = ev[k] ?: return
            if (cond) {
                e.n += 2
                if (e.n >= e.need * 2) { e.n = 0; fire(k, conf, extra()) }
            } else e.n = max(0, e.n - 1)
        }

        /* ---------- rasgos temporales ----------
           Lo que faltaba. Con solo el reparto de energía por bandas no hay forma
           de separar una voz de un ladrido: los dos ponen energía en el medio y
           los dos tienen tono. Lo que los separa es CÓMO evolucionan. */
        val plano = planitud()
        val centro = centroide()
        graveFrac = rumble
        planitudEsp = plano
        motorCerca = db > MOTOR_DB && rumble > MOTOR_GRAVE && plano < MOTOR_PLANITUD

        /* Fondo adaptativo y NOVEDAD sobre él.
           Medido contra la biblioteca real: sin esto, la respiración de una
           persona, la voz de un rescatista y una sirena acababan clasificadas
           como animal — ocho archivos de veintiocho. Un motor, un helicóptero o
           una sirena que se acerca se vuelven fondo en unos segundos y dejan de
           contar; un derrumbe o un grito no le dan tiempo. Es el mismo principio
           que ya usan la malla (margen sobre el ruido) y el sismógrafo (media
           lenta congelada durante el evento). */
        if (calienta == 0) fondo = db
        calienta++
        val novedad = if (calienta < CALIENTA_N) 0.0 else db - fondo
        fondo += (db - fondo) * FONDO_TAU
        // últimos rasgos medidos, para que el autotest pueda decir POR QUÉ falla
        ultRasgos = "db=%.0f grave=%.2f plano=%.2f cl=%.2f sost=%d".format(db, rumble, plano, p.clarity, sostenido)
        envolvente.add(db)
        while (envolvente.size > ENV_N) envolvente.removeAt(0)
        val mod = modulacion()
        if (db > -55) sostenido++ else sostenido = 0

        if (p.hz > 60) { wander.add(p.hz); if (wander.size > 12) wander.removeAt(0) }
        val vib = spread(wander)   // una voz nunca sostiene el tono exacto; una máquina sí

        /* ---------- clasificación exclusiva ----------
           Antes cada detector decidía por su cuenta, y con el mismo sonido podían
           encenderse tres a la vez — que es justo lo que se veía: un golpe
           encendía GOLPES, DERRUMBE y ANIMALES. Ahora se puntúan los cuatro
           continuos y solo acumula el que gana, y solo si le saca margen al
           segundo. Ante la duda no se adivina: no acumula ninguno. */
        val puntos = HashMap<String, Double>()

        /* DERRUMBE: cataclismo acústico sónico (+20 dB sobre fondo adaptativo),
           muy grave (rumble > 0.60), no armónico (clarity < 0.25), transitorio
           violento (sostenido <= 35 ticks) y aperiódico en su envolvente (mod < 0.25)
           para descartar motores diésel, maquinaria pesada y generadores con giro fijo. */
        if (db > -25 && rumble > 0.60 && p.clarity < 0.25 &&
            sostenido in 3..SOSTENIDO_ESTRUENDO_MAX &&
            novedad > NOV_ESTRUENDO && mod < MOD_ESTRUENDO_MAX)
            puntos["estruendo"] = min(1.0, rumble * (db + 60) / 40)

        // VOZ: habla humana natural (70-480 Hz, modulación silábica 0.07-0.48, sin saltos de ladrido)
        val esRangoVoz = p.hz in 70.0..480.0
        val esModHabla = (mod in 0.07..0.48) || (speech > 0.40 && mod in 0.03..0.48)
        if (db > -52 && p.clarity > 0.28 && esRangoVoz && plano < 0.45 &&
            vib in VIB_MIN..VIB_MAX && esModHabla && novedad > NOV_VOZ)
            puntos["voz"] = min(1.0, p.clarity * 0.45 + speech * 0.35 + mod * 0.3)

        // GRITO: grito humano o llanto de auxilio (sostenido, sin rumble de perro < 0.20, sin saltos de ladrido < 14 dB)
        val esGritoEstable = abs(jump) < 14.0 && vib < 0.40
        if (db > -38 && p.clarity > 0.35 && p.hz in 300.0..1600.0 && speech > 0.30 &&
            rumble < 0.20 && plano < 0.35 && mod < 0.35 && sostenido >= 3 &&
            novedad > NOV_EVENTO && esGritoEstable)
            puntos["grito"] = min(1.0, p.clarity + 0.20 - mod)

        // ANIMAL: perros (ladrido impulsivo / gruñido grave) y gatos (armónico agudo con variación tonal)
        val esLadrido = p.clarity > 0.25 && p.hz in 150.0..900.0 &&
                (abs(jump) > 10.0 || mod > 0.48) && (rumble > 0.15 || abs(jump) > 12.0)
        val esGrunido = p.clarity > 0.35 && rumble > 0.60 && vib > 0.50 && p.hz in 70.0..500.0
        val esGato = p.clarity > 0.65 && p.hz in 250.0..1800.0 && rumble < 0.12 && plano < 0.05 &&
                (dhz > 45.0 || vib > 0.20) && sostenido <= 12
        val esChillido = high > 0.45 && jump > 8.0 && centro > 2500.0
        val esHablaHumana = speech > 0.60 && plano < 0.08 && dhz < 35.0 && sostenido > 6 && abs(jump) < 8.0
        if (db > -45 && novedad > NOV_EVENTO && !esHablaHumana) {
            if (esLadrido || esGrunido || esGato || esChillido) {
                val conf = when {
                    esGato -> p.clarity * 0.80
                    esGrunido -> rumble * 0.85
                    esLadrido -> p.clarity * 0.75
                    else -> 0.50
                }
                puntos["animal"] = min(1.0, conf)
            }
        }

        val mejor = puntos.maxByOrNull { it.value }
        val ganador = mejor?.takeIf { m ->
            puntos.none { it.key != m.key && it.value > m.value - MARGEN }
        }?.key

        for (k in CLAVES) {
            if (k == "golpes") continue
            step(k, k == ganador, puntos[k] ?: 0.0) { detalle(k, p, rumble, high, vib, mod) }
        }

        /* GOLPES: ataques bruscos, planos y RÍTMICOS. La regularidad es lo que
           separa a alguien pidiendo ayuda de una viga que cruje sola, y va
           aparte de los otros cuatro porque no se decide marco a marco sino por
           lo que ha pasado en los últimos cinco segundos. Se exige cuerpo espectral
           (rumble > 0.10) para descartar crepitaciones de fuego y chasquidos agudos. */
        val ahora = System.currentTimeMillis()
        if (jump > 9 && db > -50 && plano > 0.30 && rumble > 0.10) onsets.add(ahora)
        /* La poda va en cada tick, no solo cuando llega un ataque nuevo. Si no,
           dos golpes sueltos dejan la cuenta clavada en 2 hasta el siguiente, y
           tanto «Ataques en los últimos 5 s» como la barra del detector estarían
           enseñando algo que ya no está pasando. */
        if (onsets.isNotEmpty()) {
            onsets = ArrayList(onsets.filter { ahora - it < 5000 })
            impactos = onsets.size
            val ritmo = regularidad(onsets)
            if (onsets.size >= 3 && ritmo > RITMO_MIN) {
                onsets.clear(); impactos = 0
                fire("golpes", 0.5 + ritmo * 0.5, "(3+ impactos, ritmo ${(ritmo * 100).toInt()}%)")
            }
        }
    }

    /** El paréntesis que se anota al disparar: por qué se ha decidido eso. */
    private fun detalle(k: String, p: Tono, rumble: Double, high: Double, vib: Double, mod: Double) =
        when (k) {
            "estruendo" -> "(grave ${(rumble * 100).toInt()}%, ruido)"
            "grito" -> "(${p.hz.toInt()} Hz sostenido, sílabas ${(mod * 100).toInt()}%)"
            "voz" -> "(${p.hz.toInt()} Hz, oscila ${"%.1f".format(vib * 100)}%, sílabas ${(mod * 100).toInt()}%)"
            "animal" -> if (p.hz > 0) "(${p.hz.toInt()} Hz, sin sílabas)" else "(banda alta ${(high * 100).toInt()}%)"
            else -> ""
        }

    private fun fire(k: String, conf: Double, extra: String) {
        val e = ev[k] ?: return
        val now = System.currentTimeMillis()
        if (now - e.last < e.cd) return
        e.last = now
        // en pruebas se anota y se para aquí: ni sirena ni pitidos por el altavoz
        if (probando) { ultimoDisparo = k; return }
        val hora = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date(now))
        ultimos.addFirst("$hora  ${e.txt} · ${(conf * 100).toInt()}%")
        while (ultimos.size > 5) ultimos.pollLast()
        Log.i(TAG, "${e.txt} · ${(conf * 100).toInt()}% $extra")
        reg("${e.txt} · seguridad ${(conf * 100).toInt()}%")

        /* Un derrumbe cerca dispara la alarma entera: si el edificio se está
           cayendo, nadie va a estar mirando el móvil para pulsar nada. */
        if (k == "estruendo" && !ServicioSos.enAlarma && !ServicioSos.enRescate) h.post { onEstruendo() }

        /* Contacto acústico: si alguien grita, golpea o habla cerca mientras
           estamos pidiendo ayuda, se le contesta. Saber que al otro lado hay
           algo que responde cambia lo que hace una persona atrapada. En modo
           rescate también: es justo cuando más falta hace contestar. */
        if ((k == "grito" || k == "golpes" || k == "voz") &&
            (ServicioSos.enAlarma || ServicioSos.enRescate)) responder()
    }

    /** Desviación relativa del tono: separa una voz (siempre oscila) de un motor. */
    private fun spread(a: List<Double>): Double {
        if (a.size < 5) return 0.0
        val m = a.average()
        if (m <= 0) return 0.0
        return sqrt(a.sumOf { (it - m) * (it - m) } / a.size) / m
    }

    private fun rmsDb(x: FloatArray): Double {
        var s = 0.0
        for (v in x) s += v.toDouble() * v
        return 10.0 * log10(s / x.size + 1e-12)
    }

    class Tono(val hz: Double, val clarity: Double)

    /**
     * Tono fundamental por NSDF (McLeod), diezmando x2 para no fundir la batería.
     *
     * La autocorrelación cruda normalizada por el solapamiento se va a lags
     * largos y da errores de octava; la NSDF queda acotada en [-1,1] y es
     * comparable entre lags. Se elige el **primer** pico que llega al 85 % del
     * máximo, no el máximo: ese es justo el paso que evita confundir 700 Hz
     * con 100 Hz.
     */
    fun tono(): Tono = tono(mic.cola(2048))

    /** Con el buffer explícito no toca estado compartido, así que el autotest
     *  puede correr con el micrófono ya grabando sin leer una mezcla. */
    fun tono(x: FloatArray): Tono {
        val d = 2; val n = 1024
        if (x.size < n * d) return Tono(0.0, 0.0)
        val y = DoubleArray(n)
        for (i in 0 until n) {
            var s = 0.0
            for (k in 0 until d) s += x[i * d + k]
            y[i] = s / d
        }
        var mean = 0.0
        for (v in y) mean += v
        mean /= n
        var e0 = 0.0
        for (i in 0 until n) { y[i] -= mean; e0 += y[i] * y[i] }
        if (e0 < 1e-7) return Tono(0.0, 0.0)

        val srd = mic.sr.toDouble() / d
        val lagMin = max(2, (srd / 2000).toInt())
        val lagMax = min(n - 64, (srd / 70).toInt())
        if (lagMax <= lagMin + 1) return Tono(0.0, 0.0)
        val nsdf = DoubleArray(lagMax + 2)
        for (lag in lagMin..lagMax) {
            var r = 0.0; var m = 0.0
            for (i in 0 until n - lag) {
                val a = y[i]; val b = y[i + lag]
                r += a * b; m += a * a + b * b
            }
            nsdf[lag] = if (m > 1e-12) 2 * r / m else 0.0
        }
        var gmax = 0.0
        for (l in lagMin..lagMax) if (nsdf[l] > gmax) gmax = nsdf[l]
        if (gmax <= 0.2) return Tono(0.0, 0.0)
        var big = 0
        for (l in lagMin + 1 until lagMax) {
            if (nsdf[l] > nsdf[l - 1] && nsdf[l] >= nsdf[l + 1] && nsdf[l] >= 0.85 * gmax) { big = l; break }
        }
        if (big == 0) return Tono(0.0, 0.0)
        val a = nsdf[big - 1]; val b = nsdf[big]; val c = nsdf[big + 1]
        val den = a - 2 * b + c                                   // interpolación parabólica
        val lag = big + if (abs(den) > 1e-9) 0.5 * (a - c) / den else 0.0
        return Tono(srd / lag, b.coerceIn(0.0, 1.0))
    }

    /* ================= respuesta ================= */

    @Volatile private var respondiendo = false

    /** Dos pitidos de 1 kHz: «te oigo». */
    fun responder() {
        if (respondiendo) return
        respondiendo = true
        thread(name = "responder", isDaemon = true) {
            try {
                val sr = 48000
                val nTot = (sr * 0.44).toInt()
                val pcm = ShortArray(nTot)
                for (rep in 0..1) {
                    val ini = (rep * 0.2 * sr).toInt()
                    val dur = (0.12 * sr).toInt()
                    val rampa = (0.005 * sr).toInt()
                    for (i in 0 until dur) {
                        if (ini + i >= nTot) break
                        val env = when {
                            i < rampa -> i.toDouble() / rampa
                            i > dur - rampa -> (dur - i).toDouble() / rampa
                            else -> 1.0
                        }
                        // onda cuadrada: se distingue de cualquier ruido natural
                        val s = if (sin(2 * PI * 1000.0 * i / sr) >= 0) 0.9 else -0.9
                        pcm[ini + i] = (s * env * Short.MAX_VALUE).toInt().toShort()
                    }
                }
                Altavoz.reproducir(pcm, sr)
                reg("he contestado con dos pitidos para que sepan que les oigo")
            } catch (e: Exception) {
                Log.e(TAG, "responder falló", e)
            } finally {
                respondiendo = false
            }
        }
    }

    /* ================= autotest del clasificador =================
       Cinco sonidos sintéticos, uno por detector, y se comprueba que se enciende
       el que toca y solo ese. Es lo que convierte «creo que distingue una voz de
       un ladrido» en un dato.

       No sustituye a la calibración en campo — un ladrido real no es un tono de
       1400 Hz —, pero sí atrapa lo que fallaba de verdad: que el mismo sonido
       encendiera tres detectores a la vez. */

    fun autotestClasificador(): Boolean {
        val sr = mic.sr
        val dt = MARCOS_POR_TICK * Microfono.SALTO.toDouble() / sr      // ~107 ms por tick

        fun arm(f: Double, t0: Double, i: Int): Double {
            val t = t0 + i.toDouble() / sr
            return sin(2 * PI * f * t) * 0.6 + sin(4 * PI * f * t) * 0.25 + sin(6 * PI * f * t) * 0.15
        }

        /** Mete [ticks] marcos sintéticos y devuelve el primer detector que dispara. */
        fun correr(ticks: Int, gen: (Int, Int, Double) -> Double): String? {
            for (e in ev.values) { e.n = 0; e.last = 0 }
            envolvente.clear(); wander.clear(); onsets.clear()
            sostenido = 0; lastDb = -90.0; lastHz = 0.0; impactos = 0
            ultimoDisparo = null
            probando = true
            try {
                val marco = ShortArray(N)
                val crudo = FloatArray(2048)
                for (t in 0 until ticks) {
                    /* Silencio antes del suceso. Sin él no hay novedad contra la
                       que medir, y la prueba estaría comprobando algo que no
                       pasa nunca en la vida real: un sonido que empieza sin que
                       existiera un antes. */
                    if (t < SILENCIO_N) {
                        java.util.Arrays.fill(marco, 0)
                        java.util.Arrays.fill(crudo, 0f)
                        tick(marco, crudo)
                        continue
                    }
                    val t0 = (t - SILENCIO_N) * dt
                    for (i in 0 until N) {
                        marco[i] = (gen(t, i, t0).coerceIn(-1.0, 1.0) * Short.MAX_VALUE * 0.9).toInt().toShort()
                    }
                    for (i in 0 until 2048) crudo[i] = marco[i] / 32768f
                    tick(marco, crudo)
                    if (ultimoDisparo != null) return ultimoDisparo
                }
            } finally { probando = false }
            return null
        }

        val casos = listOf(
            // voz: fundamental de habla y sílabas a 3,5 Hz — abrir y cerrar la boca
            /* Una voz de verdad NUNCA sostiene el tono exacto: oscila un pequeño
               porcentaje todo el rato, y el detector cuenta con ello. Un tono
               sintético clavado no es una voz, es un timbre — así que la prueba
               tiene que oscilar o no está probando lo que dice probar. */
            Triple("voz de hombre", "voz") { _: Int, i: Int, t0: Double ->
                val f0 = 130.0 * (1.0 + 0.04 * sin(2 * PI * 1.7 * t0))
                arm(f0, t0, i) * (0.08 + 0.92 * (0.5 + 0.5 * sin(2 * PI * 3.5 * t0)))
            },
            // grito: 700 Hz clavado y sin sílabas
            Triple("grito sostenido", "grito") { _: Int, i: Int, t0: Double -> arm(700.0, t0, i) },
            // animal: 1400 Hz a ráfagas de medio segundo con modulación biológica
            Triple("chillido a ráfagas", "animal") { t: Int, i: Int, t0: Double ->
                if ((t / 5) % 2 == 0) {
                    val f0 = 1400.0 * (1.0 + 0.08 * sin(2 * PI * 6.0 * t0))
                    arm(f0, t0, i)
                } else 0.0
            },
            // derrumbe: ruido grave, fuerte y continuo
            Triple("derrumbe (ruido grave)", "estruendo") { _: Int, _: Int, _: Double ->
                grave.siguiente()
            },
            // golpes: impactos secos a ritmo regular
            Triple("golpes rítmicos", "golpes") { t: Int, i: Int, _: Double ->
                if (t % 4 == 0 && i < 900) (Math.random() - 0.5) * 2.0 * (1.0 - i / 900.0) else 0.0
            }
        )

        val partes = ArrayList<String>()
        var todo = true
        for ((nombre, esperado, gen) in casos) {
            grave.reiniciar()
            val salio = correr(60 + SILENCIO_N, gen)
            val ok = salio == esperado
            if (!ok) todo = false
            partes.add("$nombre → ${salio ?: "nada"} " +
                if (ok) "OK" else "FALLÓ (esperaba $esperado; $ultRasgos)")
        }
        Log.i(TAG, "autotest detectores · " + partes.joinToString(" | "))
        return todo
    }

    /** Ruido de derrumbe: sobre todo grave, pero con banda ancha encima. Un
     *  derrumbe real no es solo retumbe — también hay cristal, roce y crujido, y
     *  un simple paso bajo no se le parece. Se guarda el estado del filtro entre
     *  muestras, de ahí que sea un objeto y no una función. */
    private val grave = object {
        private var y = 0.0
        fun reiniciar() { y = 0.0 }
        fun siguiente(): Double {
            val u = (Math.random() - 0.5) * 2.0
            y += 0.02 * (u - y)                              // paso bajo ~150 Hz
            return (y * 10.0 + u * 0.12).coerceIn(-1.0, 1.0)
        }
    }

    /* ================= autotest del tono =================
       El mismo del botón de la PWA: se le inyectan tonos sintéticos al anillo y
       se comprueba que el detector de tono los lee. Sin altavoz y sin micrófono,
       porque si esto falla no hace falta salir a probar nada. */
    fun autotest(): Boolean {
        val sr = mic.sr
        val n = 2048
        fun mk(f: Double) = FloatArray(n) {
            (0.5 * (sin(2 * PI * f * it / sr) + 0.4 * sin(4 * PI * f * it / sr))).toFloat()
        }
        val ruido = FloatArray(n) { ((Math.random() - 0.5) * 0.6).toFloat() }
        val casos = listOf(
            Triple("voz 110 Hz", mk(110.0), 99.0 to 121.0),
            Triple("voz 220 Hz", mk(220.0), 198.0 to 242.0),
            Triple("grito 700 Hz", mk(700.0), 630.0 to 770.0),
            Triple("chillido 1400 Hz", mk(1400.0), 1260.0 to 1540.0),
            Triple("ruido (no debe dar tono)", ruido, 0.0 to 0.0)
        )
        var todo = true
        val partes = ArrayList<String>()
        for ((nom, x, rango) in casos) {
            val p = tono(x)
            val ok = if (rango.first > 0) p.hz > rango.first && p.hz < rango.second && p.clarity > 0.3
                     else p.clarity < 0.5
            if (!ok) todo = false
            partes.add("$nom → ${p.hz.toInt()} Hz cl=${"%.2f".format(p.clarity)} ${if (ok) "OK" else "FALLÓ"}")
        }
        Log.i(TAG, "autotest tono · " + partes.joinToString(" | "))
        return todo
    }

    private fun reg(m: String) {
        Log.i(TAG, "escucha: $m")
        h.post { onRegistro(m) }
    }
}

/**
 * FFT radix-2 en el sitio. Hace falta para repartir la energía en bandas, que es
 * lo que distingue un derrumbe (todo grave) de un chillido (todo agudo).
 * Se escribe a mano porque Android no trae ninguna y no se va a meter una
 * dependencia de terceros en una app que tiene que funcionar sin red ni cuentas.
 */
object Fft {
    private var re = DoubleArray(0)
    private var im = DoubleArray(0)

    /** Deja en [salida] la magnitud lineal de cada bin (N/2 bins). */
    @Synchronized
    fun magnitudes(x: ShortArray, ventana: DoubleArray, salida: DoubleArray) {
        val n = ventana.size
        if (re.size != n) { re = DoubleArray(n); im = DoubleArray(n) }
        for (i in 0 until n) { re[i] = x[i] / 32768.0 * ventana[i]; im[i] = 0.0 }

        // reordenación por inversión de bits
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j or bit
            if (i < j) {
                val tr = re[i]; re[i] = re[j]; re[j] = tr
                val ti = im[i]; im[i] = im[j]; im[j] = ti
            }
        }

        var len = 2
        while (len <= n) {
            val ang = -2.0 * PI / len
            val wr = cos(ang); val wi = sin(ang)
            var i = 0
            while (i < n) {
                var cr = 1.0; var ci = 0.0
                for (k in 0 until len / 2) {
                    val ur = re[i + k]; val ui = im[i + k]
                    val vr = re[i + k + len / 2] * cr - im[i + k + len / 2] * ci
                    val vi = re[i + k + len / 2] * ci + im[i + k + len / 2] * cr
                    re[i + k] = ur + vr; im[i + k] = ui + vi
                    re[i + k + len / 2] = ur - vr; im[i + k + len / 2] = ui - vi
                    val nr = cr * wr - ci * wi
                    ci = cr * wi + ci * wr; cr = nr
                }
                i += len
            }
            len = len shl 1
        }

        val esc = 2.0 / n
        for (b in salida.indices) salida[b] = sqrt(re[b] * re[b] + im[b] * im[b]) * esc
    }
}
