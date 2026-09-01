package red.sismo

import android.content.Context

/**
 * Ficha médica de emergencia.
 *
 * Se guarda **solo en este móvil**: no hay red, no hay cuenta y no hay servidor
 * al que mandarla, así que tampoco hay nada que filtrar. Existe para que quien
 * te encuentre inconsciente sepa qué no puede darte.
 *
 * Va en `SharedPreferences` y no en una base de datos porque son cinco campos y
 * meter SQLite aquí solo añadiría una migración que mantener.
 */
class Ficha(ctx: Context) {

    private val p = ctx.getSharedPreferences("ficha", Context.MODE_PRIVATE)

    var nombre: String
        get() = p.getString("nombre", "") ?: ""
        set(v) { p.edit().putString("nombre", v).apply() }

    var sangre: String
        get() = p.getString("sangre", "") ?: ""
        set(v) { p.edit().putString("sangre", v).apply() }

    var edad: String
        get() = p.getString("edad", "") ?: ""
        set(v) { p.edit().putString("edad", v).apply() }

    /* Alergias y medicación son dos cosas distintas y son las dos que más
       importan: una dice qué NO puede darte quien te atienda y la otra qué
       llevas ya en el cuerpo. Estaban en el mismo campo, así que la pantalla
       enseñaba el mismo texto en las dos tarjetas y no había forma de editar la
       segunda. Lo que hubiera guardado se queda en alergias, que es donde
       estaba escrito el rótulo. */
    var alergias: String
        get() = p.getString("med", "") ?: ""
        set(v) { p.edit().putString("med", v).apply() }

    var medicacion: String
        get() = p.getString("medicacion", "") ?: ""
        set(v) { p.edit().putString("medicacion", v).apply() }

    var contacto: String
        get() = p.getString("contacto", "") ?: ""
        set(v) { p.edit().putString("contacto", v).apply() }

    /** El teléfono al que llamar, aparte del nombre: quien te encuentra tiene
     *  que poder marcarlo sin descifrar una cadena. */
    var telefono: String
        get() = p.getString("telefono", "") ?: ""
        set(v) { p.edit().putString("telefono", v).apply() }

    fun vacia() = nombre.isBlank() && sangre.isBlank() && edad.isBlank() &&
                  alergias.isBlank() && medicacion.isBlank() &&
                  contacto.isBlank() && telefono.isBlank()

    fun borrar() = p.edit().clear().apply()

    /** Lo que se enseña a pantalla completa. Sin adornos: lo urgente arriba. */
    fun comoTexto(): String = buildString {
        if (nombre.isNotBlank()) append(nombre).append("\n\n")
        if (sangre.isNotBlank()) append("SANGRE\n").append(sangre).append("\n\n")
        if (alergias.isNotBlank()) append("ALERGIAS\n").append(alergias).append("\n\n")
        if (medicacion.isNotBlank()) append("MEDICACIÓN\n").append(medicacion).append("\n\n")
        if (edad.isNotBlank()) append("EDAD\n").append(edad).append("\n\n")
        if (contacto.isNotBlank() || telefono.isNotBlank()) {
            append("CONTACTO\n").append(contacto)
            if (telefono.isNotBlank()) append("\n").append(telefono)
        }
    }.trim()
}
