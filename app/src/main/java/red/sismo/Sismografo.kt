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
    var umbral = 6.0                 // m/s², ajustable por el usuario
    var armado = false

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

        // 1) media lenta congelada durante el evento
        val dev0 = abs(mag - lta)
        if (dev0 < umbral) lta += (mag - lta) * 0.004
        val dev = abs(mag - lta)
        if (dev > 0.6) ultimoMovimiento = System.currentTimeMillis()

        // 3) media rápida con recorte y bajada más rápida que la subida
        val devc = min(dev, umbral * 3)
        sta += (devc - sta) * (if (devc > sta) 0.25 else 0.5)
        sacudida = sta
        // medio umbral: el suelo se mueve, aunque todavía no sea para disparar
        if (sta > umbral * 0.5) ultimoTemblor = System.currentTimeMillis()
        historia[hi] = sta.toFloat(); hi = (hi + 1) % historia.size

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
