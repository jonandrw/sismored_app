package red.sismo

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.TriggerEvent
import android.hardware.TriggerEventListener
import android.os.BatteryManager
import android.os.Build
import android.util.Log

/**
 * De quién me fío: en qué estado está el móvil ANTES de creerse ningún sensor.
 *
 * Este es el paso que faltaba, y es el que decide todo lo que viene detrás. Un
 * móvil no es un sensor: son dos sensores distintos según dónde esté.
 *
 *  - **En la mesa** (quieto, y mejor si carga) el acelerómetro es un sismógrafo
 *    de verdad. Es el único estado en el que una sacudida significa lo que dice.
 *  - **Encima de una persona** el acelerómetro mide a la persona, no al suelo.
 *    Andar da 3 m/s² con facilidad, y ahí una sacudida no significa nada.
 *
 * No es una teoría: es exactamente lo que hace el sistema de alerta sísmica de
 * Android, que solo vigila con el móvil quieto y enchufado. Con tres años de
 * funcionamiento y ~312 terremotos detectados al mes, lleva **tres** alertas
 * falsas. No es que su umbral sea mejor: es que no se fía del acelerómetro
 * cuando el móvil va en un bolsillo, y nosotros sí nos fiábamos.
 *
 * Y de paso recoge las señales de vida que ya están ahí y no costaban nada:
 *
 *  - **Los pasos.** Android los cuenta en el coprocesador de sensores, con el
 *    SoC dormido y la pantalla apagada, y no es una estimación: es un contador
 *    en hardware. Que alguien siga andando después del terremoto es la mejor
 *    prueba de que está bien, y ya estaba contada.
 *  - **La pantalla desbloqueada.** Si alguien ha desbloqueado el móvil después
 *    del temblor, está consciente y tiene las manos libres. Cuesta cero.
 *  - **La luz.** Bajo escombros es de noche a las tres de la tarde. No decide
 *    nada solo —un bolsillo también es oscuro—, pero suma.
 *
 * Ninguna de estas señales sale del móvil. Se leen aquí y se mueren aquí.
 */
class Postura(
    private val ctx: Context,
    private val onRegistro: (String) -> Unit = {}
) {

    enum class Regimen {
        /** No hay dato suficiente todavía. Se trata como el peor caso. */
        DESCONOCIDO,
        /** Quieto sobre algo. El acelerómetro vale como sismógrafo. */
        EN_REPOSO,
        /** Lo lleva una persona. El acelerómetro mide a la persona. */
        ENCIMA
    }

    companion object {
        private const val TAG = "SismoRed"
        /** Pasos en el último minuto que bastan para decir «lo lleva encima». */
        const val PASOS_ENCIMA = 3
        /** Ventana en la que se miran los pasos para decidir el régimen. */
        const val VENTANA_PASOS_MS = 60_000L
        /** Por debajo de esto está oscuro de verdad, no en penumbra. */
        const val LUX_OSCURO = 3.0f
        /** Cuánto se recuerda que estaba en reposo. Dos minutos cubren un
         *  terremoto entero y sus primeras réplicas. */
        const val MEMORIA_REPOSO_MS = 120_000L
        /** Grados de giro en los últimos 15 s a partir de los cuales hay una mano
         *  detrás. Una mesa se mantiene por debajo de 2-3°; sostener un móvil sin
         *  pasar de 10° no lo consigue nadie. */
        const val GIRO_MANO = 10.0
    }

    private val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    private val sContador: Sensor? = sm.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
    private val sQuieto: Sensor? = sm.getDefaultSensor(Sensor.TYPE_STATIONARY_DETECT)
    private val sMovido: Sensor? = sm.getDefaultSensor(Sensor.TYPE_MOTION_DETECT)
    private val sLuz: Sensor? = sm.getDefaultSensor(Sensor.TYPE_LIGHT)

    /** Pasos desde el último arranque del móvil. −1 mientras no haya llegado uno. */
    @Volatile var pasos = -1L; private set
    /** Si no hay contador o falta el permiso, esto es false y nadie debe fingir
     *  que el silencio de pasos significa algo. Un dato que no existe no es un
     *  dato que valga cero. */
    @Volatile var hayPasos = false; private set

    @Volatile var luz = -1.0f; private set
    @Volatile var ultimaInteraccion = 0L; private set
    @Volatile private var avisoQuieto = 0L
    @Volatile private var avisoMovido = 0L

    /* Historial (instante, contador) de los últimos minutos, para poder
       preguntar «cuántos pasos en los últimos 60 s». El contador de Android es
       acumulado desde el arranque, así que sin historial solo se sabe el total
       de la vida del móvil, que no sirve para nada aquí. */
    private val hT = LongArray(64)
    private val hP = LongArray(64)
    private var hi = 0
    private var hn = 0

    private val oyente = object : SensorEventListener {
        override fun onSensorChanged(e: SensorEvent) {
            when (e.sensor.type) {
                Sensor.TYPE_STEP_COUNTER -> {
                    val n = e.values[0].toLong()
                    pasos = n
                    hayPasos = true
                    synchronized(hT) {
                        hT[hi] = System.currentTimeMillis(); hP[hi] = n
                        hi = (hi + 1) % hT.size
                        if (hn < hT.size) hn++
                    }
                }
                Sensor.TYPE_LIGHT -> luz = e.values[0]
            }
        }
        override fun onAccuracyChanged(s: Sensor?, a: Int) {}
    }

    /* Los detectores de quieto y de movimiento son de UN SOLO DISPARO: se apagan
       solos al saltar y hay que volver a pedirlos. Por eso cada uno se re-arma
       dentro de su propio callback. Si se olvida, el estado se congela en el
       primero que llegó y no vuelve a cambiar nunca — y encima no da ningún
       síntoma, porque el sensor sigue existiendo. */
    private val alQuedarQuieto = object : TriggerEventListener() {
        override fun onTrigger(e: TriggerEvent?) {
            avisoQuieto = System.currentTimeMillis()
            try { sQuieto?.let { sm.requestTriggerSensor(this, it) } } catch (_: Exception) {}
        }
    }
    private val alMoverse = object : TriggerEventListener() {
        override fun onTrigger(e: TriggerEvent?) {
            avisoMovido = System.currentTimeMillis()
            try { sMovido?.let { sm.requestTriggerSensor(this, it) } } catch (_: Exception) {}
        }
    }

    /* Desbloquear la pantalla es la señal de «estoy bien» más barata que hay, y
       no se puede declarar en el manifiesto: ACTION_USER_PRESENT solo llega a un
       receptor registrado en caliente. */
    private val receptor = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            when (i?.action) {
                Intent.ACTION_USER_PRESENT, Intent.ACTION_SCREEN_ON ->
                    ultimaInteraccion = System.currentTimeMillis()
            }
        }
    }
    private var receptorPuesto = false

    /** Si falta el permiso, el contador de pasos no entrega nada. Desde Android
     *  10 hay que pedirlo, y es el séptimo permiso de la app. */
    fun hayPermisoDePasos(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
            ctx.checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION) ==
                PackageManager.PERMISSION_GRANTED

    fun arrancar() {
        if (sContador != null && hayPermisoDePasos()) {
            try { sm.registerListener(oyente, sContador, SensorManager.SENSOR_DELAY_NORMAL) }
            catch (e: Exception) { Log.w(TAG, "contador de pasos: ${e.message}") }
        }
        try { sQuieto?.let { sm.requestTriggerSensor(alQuedarQuieto, it) } } catch (_: Exception) {}
        try { sMovido?.let { sm.requestTriggerSensor(alMoverse, it) } } catch (_: Exception) {}
        if (!receptorPuesto) {
            try {
                val f = IntentFilter().apply {
                    addAction(Intent.ACTION_USER_PRESENT)
                    addAction(Intent.ACTION_SCREEN_ON)
                }
                ctx.registerReceiver(receptor, f)
                receptorPuesto = true
            } catch (_: Exception) {}
        }
        Log.i(TAG, "postura: pasos=${sContador != null} quieto=${sQuieto != null} " +
            "movido=${sMovido != null} luz=${sLuz != null} permiso=${hayPermisoDePasos()}")
    }

    fun parar() {
        try { sm.unregisterListener(oyente) } catch (_: Exception) {}
        try { sQuieto?.let { sm.cancelTriggerSensor(alQuedarQuieto, it) } } catch (_: Exception) {}
        try { sMovido?.let { sm.cancelTriggerSensor(alMoverse, it) } } catch (_: Exception) {}
        vigilarLuz(false)
        if (receptorPuesto) {
            try { ctx.unregisterReceiver(receptor) } catch (_: Exception) {}
            receptorPuesto = false
        }
    }

    /* La luz solo se mira cuando hace falta —después de un suceso—, porque es el
       único sensor de aquí que entrega a chorro y no es gratis. */
    private var luzPuesta = false
    fun vigilarLuz(v: Boolean) {
        if (v == luzPuesta || sLuz == null) return
        luzPuesta = v
        try {
            if (v) sm.registerListener(oyente, sLuz, SensorManager.SENSOR_DELAY_NORMAL)
            else { sm.unregisterListener(oyente, sLuz); luz = -1.0f }
        } catch (_: Exception) {}
    }

    /** Pasos dados en los últimos [ventanaMs]. −1 si no se sabe. */
    fun pasosEn(ventanaMs: Long): Int {
        if (!hayPasos) return -1
        val corte = System.currentTimeMillis() - ventanaMs
        var base = -1L
        synchronized(hT) {
            // el más antiguo que todavía esté dentro de la ventana
            for (k in 0 until hn) {
                val i = (hi - 1 - k + hT.size * 2) % hT.size
                if (hT[i] < corte) break
                base = hP[i]
            }
        }
        if (base < 0) return 0
        return (pasos - base).coerceAtLeast(0L).toInt()
    }

    /** Pasos desde un instante concreto: los de después del terremoto. */
    fun pasosDesde(t: Long): Int = pasosEn((System.currentTimeMillis() - t).coerceAtLeast(0L))

    fun cargando(): Boolean = try {
        val i = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        when (i?.getIntExtra(BatteryManager.EXTRA_STATUS, -1)) {
            BatteryManager.BATTERY_STATUS_CHARGING, BatteryManager.BATTERY_STATUS_FULL -> true
            else -> false
        }
    } catch (_: Exception) { false }

    /** Milisegundos desde que alguien desbloqueó o encendió la pantalla. */
    fun interaccionHace(): Long =
        if (ultimaInteraccion == 0L) Long.MAX_VALUE
        else System.currentTimeMillis() - ultimaInteraccion

    fun oscuro(): Boolean = luz in 0.0f..LUX_OSCURO

    /**
     * El régimen. [quietoMs] lo pone el sismógrafo —milisegundos sin que el
     * acelerómetro se mueva— y es el respaldo que siempre está: hay móviles sin
     * contador de pasos y sin detectores de un disparo, y el Redmi de pruebas ni
     * siquiera concede algunos permisos por consola. Sin respaldo, esos móviles
     * se quedarían en DESCONOCIDO para siempre.
     */
    fun regimen(quietoMs: Long): Regimen {
        val p = pasosEn(VENTANA_PASOS_MS)
        if (p >= PASOS_ENCIMA) return Regimen.ENCIMA
        val ahora = System.currentTimeMillis()
        // el aviso de «se ha movido» manda sobre el de «está quieto» si es más nuevo
        if (avisoMovido > avisoQuieto && ahora - avisoMovido < 60_000L) return Regimen.ENCIMA
        if (quietoMs > 30_000L) return Regimen.EN_REPOSO
        if (avisoQuieto > avisoMovido && ahora - avisoQuieto < 60_000L) return Regimen.EN_REPOSO
        return Regimen.DESCONOCIDO
    }

    @Volatile private var ultimoReposo = 0L

    /**
     * El régimen que había **antes de que empezara esto**.
     *
     * Es la corrección de un fallo que solo se ve en campo: sacudir un móvil que
     * está en una mesa reinicia el reloj de quietud, así que en cuanto empieza el
     * terremoto el móvil deja de considerarse «en reposo» — y con ello pierde el
     * umbral fino y pasa a exigir corroboración. **El propio suceso descalificaba
     * al sensor que tenía que detectarlo.**
     *
     * Es el mismo principio que ya usa el sismógrafo con su media lenta: se
     * congela durante el evento. Aquí se recuerda: si hace menos de dos minutos
     * estaba en reposo **y no ha dado un paso desde entonces**, sigue siendo un
     * móvil en una mesa al que alguien está sacudiendo, no un móvil en un
     * bolsillo. Los pasos son lo que separa los dos casos, y no hay forma de
     * sacudir un móvil dando cero pasos si lo llevas encima.
     */
    fun regimenRecordado(quietoMs: Long, giroGrados: Double = 0.0): Regimen {
        /* Si ha girado, hay una mano. Un móvil en una mesa no gira ni durante un
           terremoto —el suelo se mueve, la mesa no da la vuelta—, y una mano no
           puede sostener nada sin girarlo. Esto manda sobre todo lo demás, y es lo
           que impide que una sacudida en la mano se trate como un sismo. */
        if (giroGrados > GIRO_MANO) return Regimen.ENCIMA
        val r = regimen(quietoMs)
        if (r == Regimen.EN_REPOSO) { ultimoReposo = System.currentTimeMillis(); return r }
        /* Y solo entonces vale el recuerdo: estaba en la mesa, no ha girado y no
           ha dado un paso, así que sigue en la mesa aunque el propio terremoto
           haya reiniciado el reloj de quietud. */
        val p = pasosEn(MEMORIA_REPOSO_MS)
        if (ultimoReposo > 0 && System.currentTimeMillis() - ultimoReposo < MEMORIA_REPOSO_MS &&
            p <= 0) return Regimen.EN_REPOSO
        return r
    }

    /** Una línea para el registro y el diagnóstico. */
    fun resumen(quietoMs: Long): String {
        val p = pasosEn(VENTANA_PASOS_MS)
        return "postura ${regimen(quietoMs)} · quieto ${quietoMs / 1000} s · " +
            (if (p < 0) "sin contador de pasos" else "$p pasos en 1 min") +
            (if (cargando()) " · cargando" else "") +
            (if (luz >= 0) " · %.0f lux".format(luz) else "")
    }
}
