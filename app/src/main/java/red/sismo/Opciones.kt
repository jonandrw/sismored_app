package red.sismo

import android.content.Context

/**
 * Lo que el usuario deja puesto y tiene que seguir puesto mañana.
 *
 * Son las seis herramientas de respuesta de la PWA (`S.opts`), más el umbral
 * sísmico, la frecuencia del doppler y el consentimiento de envío por internet.
 * Van en `SharedPreferences` y no en memoria por una razón concreta: si alguien
 * apaga la sirena porque duerme al lado del móvil, no puede volver a sonar sola
 * a las tres de la mañana porque el sistema reinició el servicio.
 *
 * La pantalla y el servicio leen la misma instancia de disco, no un objeto
 * compartido: el servicio puede morir y volver, y la pantalla puede no existir.
 * Quien cambia algo avisa al servicio con `ACCION_OPCIONES` para que lo aplique
 * en caliente si la alarma ya está sonando.
 */
class Opciones(ctx: Context) {

    private val p = ctx.getSharedPreferences("sismored", Context.MODE_PRIVATE)

    private fun leer(k: String, def: Boolean) = p.getBoolean(k, def)
    private fun poner(k: String, v: Boolean) = p.edit().putBoolean(k, v).apply()

    /** Las cinco casillas de la vista Respuesta. Todas encendidas de fábrica:
     *  quien no ha tocado nada tiene que tener la respuesta completa. */
    var linterna: Boolean
        get() = leer("op_linterna", true); set(v) = poner("op_linterna", v)
    var vibracion: Boolean
        get() = leer("op_vibracion", true); set(v) = poner("op_vibracion", v)
    var pantalla: Boolean
        get() = leer("op_pantalla", true); set(v) = poner("op_pantalla", v)
    var baliza: Boolean
        get() = leer("op_baliza", true); set(v) = poner("op_baliza", v)
    var mantener: Boolean
        get() = leer("op_mantener", true); set(v) = poner("op_mantener", v)

    /** La sirena vive en el DETECTOR SÍSMICO, no en Respuesta, igual que en la
     *  PWA: es la que se apaga para dormir sin renunciar a todo lo demás. */
    var sirena: Boolean
        get() = leer("op_sirena", true); set(v) = poner("op_sirena", v)

    var confirmarAntesDeSirena: Boolean
        get() = leer("op_confirmar_sirena", false); set(v) = poner("op_confirmar_sirena", v)

    var arrancarAlIniciar: Boolean
        get() = leer("op_arrancar_iniciar", true); set(v) = poner("op_arrancar_iniciar", v)

    var descartarCaidas: Boolean
        get() = leer("op_descartar_caidas", true); set(v) = poner("op_descartar_caidas", v)

    var avisarMallaAlDisparar: Boolean
        get() = leer("op_avisar_malla_disparo", true); set(v) = poner("op_avisar_malla_disparo", v)

    /** Vigilancia sísmica automática. Arranca armada: es la razón de existir de
     *  la versión nativa, que es la que puede vigilar con la pantalla apagada. */
    var armado: Boolean
        get() = leer("op_armado", true); set(v) = poner("op_armado", v)

    /**
     * El umbral con el móvil ENCIMA de alguien. Es el conservador, y sigue
     * existiendo por lo mismo de siempre: andar y correr sacuden un bolsillo más
     * que un terremoto sacude una mesa, y ahí no hay forma de afinar.
     *
     * **6,0 m/s² horizontales.** Aquí no hace falta afinar y afinar sale caro:
     * con el móvil encima de alguien, andar y correr producen más aceleración
     * horizontal que un terremoto, así que el listón alto es el que evita
     * llenarle el bolsillo de falsas alarmas. Lo que de verdad protege el caso
     * fino no es este número sino la puerta de la mano
     * (`Sismografo.hayMano`), que se entera en ochenta milisegundos de que
     * alguien ha cogido el teléfono.
     *
     * Clave nueva (`op_umbral_h`) por el mismo motivo que en el de reposo: la
     * escala cambió al medir solo lo perpendicular a la gravedad, y heredar un
     * valor de la escala vieja dejaría el detector mal puesto sin que se note.
     */
    var umbral: Double
        get() = p.getFloat("op_umbral_h", 6.0f).toDouble().coerceIn(0.3, 9.0)
        set(v) = p.edit().putFloat("op_umbral_h", v.coerceIn(0.3, 9.0).toFloat()).apply()

    /**
     * El usuario ha apagado SismoRed del todo, a mano.
     *
     * No es un interruptor más: es el que dice «no quiero esto corriendo». Se
     * respeta al arrancar la app y al arrancar el móvil, porque una app de
     * emergencia que se vuelve a encender sola después de que la apaguen deja de
     * ser una herramienta y pasa a ser algo de lo que hay que defenderse.
     */
    /**
     * Cuándo se supo por última vez que el servicio estaba vivo.
     *
     * Es lo que permite descubrir que **el sistema lo mató**. En un móvil
     * normal el servicio sobrevive a cerrar la app —está declarado con
     * `stopWithTask="false"`—, pero HyperOS lo mata al deslizarla fuera de
     * recientes con su limpiador de tareas (`OneKeyClean` en el registro del
     * sistema), y lo hace 200 ms después de que la app haya hecho todo bien.
     *
     * Contra eso no hay código. Lo que sí se puede es enterarse y decirlo, en
     * vez de que alguien crea que está vigilado cuando no lo está.
     */
    var latido: Long
        get() = p.getLong("op_latido", 0L)
        set(v) = p.edit().putLong("op_latido", v).apply()

    /** El sistema mató el servicio y el watchdog no pudo levantarlo por falta de
     *  la exención de batería. Se guarda para poder decírselo al usuario: creerse
     *  vigilado sin estarlo es peor que saber que no lo estás. */
    var watchdogImpotente: Boolean
        get() = p.getBoolean("op_watchdog_impotente", false)
        set(v) = p.edit().putBoolean("op_watchdog_impotente", v).apply()

    /** Cuándo se avisó por última vez de que el sistema mató la app. Sin esto, el
     *  aviso salía en cada arranque y se convertía en ruido — y un aviso que se
     *  repite siempre es un aviso que se deja de leer. */
    var ultimoAvisoMuerte: Long
        get() = p.getLong("op_aviso_muerte", 0L)
        set(v) = p.edit().putLong("op_aviso_muerte", v).apply()

    /** El usuario quiere que esto esté vigilando. Si esto es true y el servicio
     *  no está, alguien lo ha matado por detrás. */
    var deberiaVigilar: Boolean
        get() = p.getBoolean("op_deberia", false)
        set(v) = p.edit().putBoolean("op_deberia", v).apply()

    var apagada: Boolean
        get() = p.getBoolean("op_apagada", false)
        set(v) = p.edit().putBoolean("op_apagada", v).apply()

    /**
     * El umbral con el móvil EN REPOSO, quieto sobre algo.
     *
     * **0,25 m/s² de aceleración HORIZONTAL**, y la clave está en esa palabra:
     * desde que el sismógrafo mide la componente perpendicular a la gravedad en
     * vez del módulo del vector, este número está en otra escala y no se puede
     * comparar con el 0,8 de antes. Por eso la clave del ajuste cambió de
     * `op_umbral_reposo` a `op_umbral_reposo_h`: un valor guardado en la escala
     * vieja aplicado a la nueva dejaría el detector sordo, y en silencio.
     *
     * La tabla de aceleración de pico del USGS sigue mandando:
     *
     *     MMI IV   0,14-0,38 m/s²   se nota dentro de casa
     *     MMI V    0,38-0,90 m/s²   lo nota todo el mundo, se despierta la gente
     *     MMI VI   0,90-1,77 m/s²   los muebles se mueven, daño leve
     *
     * El umbral se compara contra la media rápida, que es más baja que el pico:
     * un MMI V de 0,7 m/s² de pico deja la media en torno a 0,46. Con 0,25 se
     * coge MMI V entero, y aun así queda cinco veces por encima de lo que
     * produce una mesa con alguien tecleando (0,05).
     *
     * Lo que hace seguro bajarlo tanto no es la amplitud: es que **lo vertical
     * ya no cuenta**. El portazo, el martillazo en la mesa y el tirón de la mano
     * llegan por la vertical y ahora dan cero. Y el ciclo de trabajo:
     * `Sismografo.CICLO_MIN` exige que el 15 % de dos segundos esté por encima,
     * y un golpe es una muestra.
     *
     * Y este umbral solo se aplica si el móvil estaba quieto **justo antes** del
     * suceso: ver `Sismografo.quietoAntesDelEvento`.
     */
    var umbralReposo: Double
        get() = p.getFloat("op_umbral_reposo_h", 0.25f).toDouble().coerceIn(0.05, 3.0)
        set(v) = p.edit().putFloat("op_umbral_reposo_h", v.coerceIn(0.05, 3.0).toFloat()).apply()

    /** kHz del tono del doppler. Por encima de 18 kHz para no pisar la malla más
     *  de lo imprescindible; ver el aviso de `Sonda.doppler()`. */
    var dopplerKhz: Double
        get() = p.getFloat("op_doppler", 18.5f).toDouble().coerceIn(DOPPLER_MIN, DOPPLER_MAX)
        set(v) = p.edit().putFloat("op_doppler", v.coerceIn(DOPPLER_MIN, DOPPLER_MAX).toFloat()).apply()

    /** Cuánto se empuja el tono que emiten el doppler, la respiración y el
     *  barrido, de 1 a 10. No es un capricho: el altavoz de cada móvil rinde
     *  distinto en agudos, y por un escombro grueso hace falta todo lo que dé.
     *  A cambio, subirlo deja la malla más sorda mientras suene. */
    var volSenal: Double
        get() = p.getFloat("op_volsenal", 6f).toDouble().coerceIn(VOL_MIN, VOL_MAX)
        set(v) = p.edit().putFloat("op_volsenal", v.coerceIn(VOL_MIN, VOL_MAX).toFloat()).apply()

    /** El aviso de proximidad de la busqueda: destello de pantalla, flash y
     *  vibracion. Encendido de fabrica, porque es lo que guia. Se apaga a mano
     *  cuando estorba: cavando con guantes, un movil que vibra sin parar en el
     *  bolsillo no dice nada nuevo. */
    var avisoBusqueda: Boolean
        get() = leer("op_aviso_busqueda", true); set(v) = poner("op_aviso_busqueda", v)

    /** Envío diferido de partes. Apagado de fábrica y siempre: nada sale del
     *  móvil sin que alguien lo encienda a mano. */
    var envio: Boolean
        get() = leer("op_envio", false); set(v) = poner("op_envio", v)

    /**
     * Firma acústica del chasis del móvil para la sonda bio-sonar.
     * Se almacena serializada en Base64 para que la calibración ("Chitón")
     * sobreviva reinicios del servicio, de la app y del dispositivo.
     */
    var sondaFirma: DoubleArray?
        get() {
            val s = p.getString("op_sonda_firma", null) ?: return null
            return try {
                val bytes = java.util.Base64.getDecoder().decode(s)
                val buf = java.nio.ByteBuffer.wrap(bytes).asFloatBuffer()
                val arr = DoubleArray(buf.remaining()) { buf.get().toDouble() }
                if (arr.isEmpty()) null else arr
            } catch (_: Exception) { null }
        }
        set(v) {
            if (v == null || v.isEmpty()) {
                p.edit().remove("op_sonda_firma").apply()
            } else {
                try {
                    val bytes = ByteArray(v.size * 4)
                    val buf = java.nio.ByteBuffer.wrap(bytes).asFloatBuffer()
                    for (x in v) buf.put(x.toFloat())
                    val b64 = java.util.Base64.getEncoder().encodeToString(bytes)
                    p.edit().putString("op_sonda_firma", b64).apply()
                } catch (_: Exception) {}
            }
        }

    companion object {
        /* 0,2 y no 0,5: en reposo, sobre una mesa que se mueve 0,04, un umbral
           de 0,5 ya son doce veces la calma. El margen útil está más abajo. */
        const val UMBRAL_MIN = 0.2
        const val UMBRAL_MAX = 8.0
        const val UMBRAL_PASO = 0.1
        const val DOPPLER_MIN = 10.0
        const val DOPPLER_MAX = 21.0
        const val DOPPLER_PASO = 0.5
        const val VOL_MIN = 1.0
        const val VOL_MAX = 10.0
        const val VOL_PASO = 1.0
    }
}
