package red.sismo

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log
import kotlin.math.abs
import kotlin.math.hypot
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

    private val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val acel: Sensor? = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    /** El umbral que se aplica AHORA, en m/s² de aceleración HORIZONTAL. Lo
     *  elige el servicio según la postura: con el móvil encima manda el
     *  conservador (2,5), en reposo el fino (0,25). En [Opciones.umbralReposo]
     *  está por qué estos números no se pueden comparar con los de antes. */
    var umbral = 2.5
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
    private val calma = DoubleArray(256)
    private var ci = 0
    private var cn = 0

    /* La dirección de la gravedad, filtrada, y las de los últimos 15 s. El
       filtro es lento a propósito: lo que interesa es hacia dónde apunta el
       móvil, no la sacudida. */
    private var gx = 0.0; private var gy = 0.0; private var gz = 0.0
    private val dirX = DoubleArray(30); private val dirY = DoubleArray(30); private val dirZ = DoubleArray(30)
    private var di = 0; private var dn = 0
    private var ultimaDir = 0L

    private var lta = 9.81
    private var sta = 0.0
    private var caidaLibre = 0
    private var ultimoMovimiento = System.currentTimeMillis()

    /* El anillo de los últimos dos segundos: cuándo se tomó cada muestra y si
       estaba por encima del umbral. Se cuenta por tiempo y no por número de
       muestras porque la tasa del acelerómetro la decide el móvil, no nosotros:
       SENSOR_DELAY_GAME da 50 Hz en unos y 100 en otros, y un contador de
       muestras fijo significaría media ventana en la mitad de los teléfonos. */
    private val anilloT = LongArray(ANILLO)
    private val anilloAlto = BooleanArray(ANILLO)
    private var ai = 0
    private var an = 0

    /** Qué parte de los últimos dos segundos ha estado por encima del umbral.
     *  Es lo que se compara con [CICLO_MIN], y se enseña en Diagnóstico. */
    @Volatile var cicloTrabajo = 0.0; private set

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

        /** Tamaño del anillo: dos segundos caben de sobra hasta 128 Hz. */
        private const val ANILLO = 256
    }

    fun arrancar() {
        reiniciar()
        acel?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
    }

    fun parar() {
        sm.unregisterListener(this)
    }

    /** El estado acumulado se pone a cero al armar y al detener: si no, el
     *  detector arranca con lo que arrastraba y dispara cuando no debe. */
    fun reiniciar() {
        lta = 9.81; sta = 0.0; caidaLibre = 0
        ai = 0; an = 0; cicloTrabajo = 0.0
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
        if (gx == 0.0 && gy == 0.0 && gz == 0.0) { gx = ax; gy = ay; gz = az }
        gx += (ax - gx) * 0.02; gy += (ay - gy) * 0.02; gz += (az - gz) * 0.02
        val gn = sqrt(gx * gx + gy * gy + gz * gz)
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
        val dev = if (gn > 1e-3) {
            val ux = gx / gn; val uy = gy / gn; val uz = gz / gn
            val lx = ax - gx; val ly = ay - gy; val lz = az - gz
            val vert = lx * ux + ly * uy + lz * uz          // lo que va con la gravedad
            hypot(hypot(lx - vert * ux, ly - vert * uy), lz - vert * uz)
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
        val devc = min(dev, umbral * 3)
        sta += (devc - sta) * (if (devc > sta) 0.25 else 0.5)
        sacudida = sta
        // medio umbral: el suelo se mueve, aunque todavía no sea para disparar
        if (sta > umbral * 0.5) ultimoTemblor = System.currentTimeMillis()
        historia[hi] = sta.toFloat(); hi = (hi + 1) % historia.size

        /* La calma se mide SOLO cuando no está pasando nada: si se dejara correr
           durante un evento, aprendería que el terremoto es normal. Y se guarda
           un percentil alto, no la media: lo que hay que superar no es el ruido
           típico de la mesa, es su peor rato. */
        if (sta < umbral * 0.5) {
            calma[ci] = sta; ci = (ci + 1) % calma.size
            if (cn < calma.size) cn++
            if (cn >= 64 && ci % 32 == 0) {
                val v = calma.copyOf(cn).sortedArray()
                calmaMedida = v[(v.size * 0.98).toInt().coerceAtMost(v.size - 1)]
            }
        }

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
        anilloAlto[ai] = sta > umbral
        ai = (ai + 1) % ANILLO
        if (an < ANILLO) an++
        var total = 0; var altos = 0
        for (k in 0 until an) {
            val j = (ai - 1 - k + ANILLO) % ANILLO
            if (ahoraMs - anilloT[j] > VENTANA_MS) break
            total++
            if (anilloAlto[j]) altos++
        }
        cicloTrabajo = if (total > 0) altos.toDouble() / total else 0.0

        /* Media ventana de muestras como mínimo: recién arrancado el anillo está
           casi vacío y tres muestras altas de tres darían un ciclo de 1,00. */
        if (armado && total > 20 && cicloTrabajo >= CICLO_MIN) {
            an = 0; ai = 0                       // el anillo se vacía tras disparar
            Log.i("SismoRed", "sismografo %.2f m/s2 horizontal · ciclo %.2f".format(sta, cicloTrabajo))
            alDisparar("sismógrafo %.2f m/s² · %d%% de dos segundos".format(sta, (cicloTrabajo * 100).toInt()))
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
    private fun escena(
        semilla: Long,
        horizontal: Double = 0.0, vertical: Double = 0.0,
        frecs: DoubleArray = doubleArrayOf(0.7, 1.3, 2.1, 3.4, 4.6),
        desde: Double = 3.0, dura: Double = 8.0,
        golpes: DoubleArray = DoubleArray(0), golpeAmp: Double = 0.0,
        tiron: Double = 0.0
    ): Array<DoubleArray> {
        val sr = 50.0
        val n = (12.0 * sr).toInt()
        val rnd = java.util.Random(semilla)
        val out = Array(n) { doubleArrayOf(0.0, 0.0, 9.81) }
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
        // tirón: levantarlo de golpe, todo por la vertical
        if (tiron > 0.0) {
            val i0 = (4.0 * sr).toInt(); val largo = (0.18 * sr).toInt()
            for (k in 0 until largo) {
                if (i0 + k < n) out[i0 + k][2] += tiron
                if (i0 + largo + k < n) out[i0 + largo + k][2] -= tiron
            }
        }
        for (i in 0 until n) for (e in 0..2) out[i][e] += rnd.nextGaussian() * 0.02
        return out
    }

    /** Pasa una escena por la misma lógica de [onSensorChanged] y dice si dispara. */
    private fun correrEscena(datos: Array<DoubleArray>, umbralPrueba: Double): Boolean {
        var lgx = datos[0][0]; var lgy = datos[0][1]; var lgz = datos[0][2]
        var s = 0.0
        val altos = BooleanArray(datos.size)
        for (i in datos.indices) {
            val (x, y, z) = Triple(datos[i][0], datos[i][1], datos[i][2])
            lgx += (x - lgx) * 0.02; lgy += (y - lgy) * 0.02; lgz += (z - lgz) * 0.02
            val g = sqrt(lgx * lgx + lgy * lgy + lgz * lgz)
            val h = if (g > 1e-3) {
                val ux = lgx / g; val uy = lgy / g; val uz = lgz / g
                val lx = x - lgx; val ly = y - lgy; val lz = z - lgz
                val v = lx * ux + ly * uy + lz * uz
                hypot(hypot(lx - v * ux, ly - v * uy), lz - v * uz)
            } else 0.0
            val hc = min(h, umbralPrueba * 3)
            s += (hc - s) * (if (hc > s) 0.25 else 0.5)
            altos[i] = s > umbralPrueba
        }
        // ventana de 2 s = 100 muestras a 50 Hz
        val w = 100
        for (i in w until datos.size) {
            var c = 0
            for (k in 0 until w) if (altos[i - k]) c++
            if (c.toDouble() / w >= CICLO_MIN) return true
        }
        return false
    }

    /**
     * Los escenarios de una mesa real contra un terremoto de verdad.
     *
     * Se corre sobre una instancia aparte o con el sensor parado: no toca el
     * estado del detector vivo porque trabaja con sus propias variables.
     */
    fun autotest(): Pair<Boolean, String> {
        val u = 0.25
        val casos = listOf(
            Triple("teclear en la mesa", false,
                escena(3, golpes = doubleArrayOf(3.1, 3.4, 3.7, 4.0, 4.4, 4.8, 5.1, 5.5), golpeAmp = 0.9)),
            Triple("martillazos en la mesa", false,
                escena(19, golpes = doubleArrayOf(4.0, 4.5, 5.1, 5.6), golpeAmp = 3.0)),
            Triple("levantarlo de golpe", false, escena(5, tiron = 3.0)),
            Triple("lavadora centrifugando", false,
                escena(13, horizontal = 0.25, vertical = 0.2,
                    frecs = doubleArrayOf(11.5), desde = 1.0, dura = 10.0)),
            Triple("camión pasando", false,
                escena(17, horizontal = 0.15, vertical = 0.12,
                    frecs = doubleArrayOf(2.2, 3.1, 4.4), dura = 6.0)),
            Triple("TERREMOTO MMI V", true, escena(11, horizontal = 0.7, vertical = 0.42)),
            Triple("TERREMOTO MMI VI", true, escena(23, horizontal = 1.3, vertical = 0.78)),
            Triple("TERREMOTO MMI VII", true, escena(31, horizontal = 2.5, vertical = 1.5))
        )
        val partes = ArrayList<String>()
        var todo = true
        for ((nombre, debe, datos) in casos) {
            val dispara = try { correrEscena(datos, u) } catch (e: Exception) { false }
            val ok = dispara == debe
            if (!ok) todo = false
            partes.add("$nombre → ${if (dispara) "dispara" else "no"}" + if (ok) " OK" else " FALLÓ")
        }
        val txt = partes.joinToString(" | ")
        Log.i("SismoRed", "autotest sismógrafo · $txt")
        return todo to txt
    }
}
