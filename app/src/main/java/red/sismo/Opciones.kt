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

    /** Vigilancia sísmica automática. Arranca armada: es la razón de existir de
     *  la versión nativa, que es la que puede vigilar con la pantalla apagada. */
    var armado: Boolean
        get() = leer("op_armado", true); set(v) = poner("op_armado", v)

    /** m/s². 3,0 medido en campo con el móvil en el bolsillo — ver CONTINUAR.md.
     *  Ajustable de 0,5 a 8 porque un móvil en una mesa y otro en un bolsillo no
     *  aguantan lo mismo, pero el valor de fábrica no se toca sin volver a medir. */
    var umbral: Double
        get() = p.getFloat("op_umbral", 6.0f).toDouble().coerceIn(0.5, 8.0)
        set(v) = p.edit().putFloat("op_umbral", v.coerceIn(0.5, 8.0).toFloat()).apply()

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

    companion object {
        const val UMBRAL_MIN = 0.5
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
