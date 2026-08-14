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
 * Detector sísmico. Portado tal cual desde la versión web, donde se calibró
 * contra andar, correr, correr fuerte, saltar y golpes sueltos.
 *
 * Las tres decisiones que costó encontrar, y que no hay que tocar sin volver a
 * medir:
 *
 *  1. La media lenta SOLO se actualiza en calma. Si se deja correr durante los
 *     picos, las pisadas la arrastran hacia arriba y la calma entre zancadas ya
 *     parece sacudida: correr acababa disparando la alarma.
 *  2. El contador sube de uno en uno y baja de cuatro en cuatro. Un sismo sacude
 *     de forma continua y llega; correr son picos con calma en medio y nunca
 *     acumula. Con -1 sí acumulaba.
 *  3. La media rápida recorta el pico a 3x el umbral y baja más rápido de lo que
 *     sube. Sin eso, tres zancadas fuertes la dejaban saturada con una cola tan
 *     larga que no bajaba del umbral entre pasos.
 */
class Sismografo(
    ctx: Context,
    private val alDisparar: (String) -> Unit
) : SensorEventListener {

    private val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val acel: Sensor? = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    /* 3,0 m/s² es el valor que aguanta el uso común sin dispararse: medido en
       campo con el móvil en el bolsillo, andando, corriendo y en el día a día.
       Por debajo de 3 hay falsos positivos en uso normal, aunque en las pruebas
       con señales sintéticas 1,2 pareciera suficiente. Manda el móvil real. */
    /** El umbral que se aplica AHORA. Lo elige el servicio según la postura:
     *  con el móvil encima manda el conservador, en reposo el fino. */
    var umbral = 6.0                 // m/s², ajustable por el usuario
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
    private var over = 0
    private var caidaLibre = 0
    private var ultimoMovimiento = System.currentTimeMillis()

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
        private const val MUESTRAS_DISPARO = 36      // ~0,6 s a 60 Hz
        private const val CAIDA_MIN = 6              // ~100 ms de gravedad casi nula
        private const val IMPACTO = 25.0             // m/s²
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
        lta = 9.81; sta = 0.0; over = 0; caidaLibre = 0
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

        // 1) media lenta congelada durante el evento
        val dev0 = abs(mag - lta)
        if (dev0 < umbral) lta += (mag - lta) * 0.004
        val dev = abs(mag - lta)
        /* «Quieto» quiere decir quieto de verdad: medido con el móvil sobre una
           mesa, esto no salta ni una vez en ochenta segundos, y la postura pasa a
           EN_REPOSO como debe. Es el respaldo del que depende todo, porque ni el
           Redmi (Android 15) ni el A10s (Android 11) tienen los detectores de un
           disparo de AOSP: `TYPE_STATIONARY_DETECT` y `TYPE_MOTION_DETECT` no
           existen en ninguno de los dos. */
        if (dev > 0.6) ultimoMovimiento = System.currentTimeMillis()

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

        // 2) contador asimétrico
        if (armado && sta > umbral) {
            // el instante del cruce: aquí, y solo aquí, se sabe de dónde venía
            over++
            if (over > MUESTRAS_DISPARO) {
                over = 0
                Log.i("SismoRed", "sismografo ${"%.2f".format(sta)} m/s2")
                alDisparar("sismógrafo %.2f m/s²".format(sta))
            }
        } else {
            over = maxOf(0, over - 4)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
