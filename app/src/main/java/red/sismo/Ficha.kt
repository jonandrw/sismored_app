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

    /**
     * Cuándo autorizó el titular que esto se emita. 0 = todavía no.
     *
     * El grupo sanguíneo, las alergias y la medicación son **datos sensibles
     * de salud** —artículo 5 de la Ley 1581 de 2012— y el artículo 6 prohíbe
     * tratarlos sin autorización explícita del titular. Guardarlos en el
     * propio móvil cae en el ámbito doméstico que el artículo 2 excluye, pero
     * esta app los EMITE a terceros por Bluetooth y por wifi, y ahí la
     * exclusión deja de valer.
     *
     * Por eso se pide una vez, antes de rellenar nada, y se guarda la fecha:
     * una autorización que no consta no sirve de nada. Se revoca borrando la
     * ficha.
     */
    var autorizadoEn: Long
        get() = p.getLong("autorizado_en", 0L)
        set(v) { p.edit().putLong("autorizado_en", v).apply() }

    val autorizada: Boolean get() = autorizadoEn > 0L

    /* Nombres y apellidos, separados. Estaban en un campo solo y la pantalla
       adivinaba dónde partirlo: con cuatro palabras acertaba, con tres dejaba
       un nombre arriba y todo lo demás abajo, y con nombres compuestos o
       apellidos de dos palabras se equivocaba siempre. No hay heurística que
       acierte —«María del Carmen Ruiz» y «Ana Ruiz de Lara» se parten distinto
       y se escriben igual—, así que lo dice la persona y ya está. */
    var nombre: String
        get() = p.getString("nombre", "") ?: ""
        set(v) { p.edit().putString("nombre", v).apply() }

    var apellidos: String
        get() = p.getString("apellidos", "") ?: ""
        set(v) { p.edit().putString("apellidos", v).apply() }

    /** Las dos líneas de la tarjeta. Si la ficha viene de una versión anterior
     *  —todo en `nombre` y `apellidos` vacío— se parte una vez con la regla de
     *  [Nombres], que es lo único que se puede hacer con lo que hay guardado. */
    fun nombreEnDosLineas(): String =
        if (apellidos.isNotBlank()) "${nombre.trim().uppercase()}\n${apellidos.trim().uppercase()}"
        else Nombres.enDosLineas(nombre)

    /** El nombre entero en una línea, para la baliza y para la ficha por Wi-Fi. */
    fun nombreCompleto(): String = listOf(nombre.trim(), apellidos.trim())
        .filter { it.isNotBlank() }.joinToString(" ")

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

    fun vacia() = nombre.isBlank() && apellidos.isBlank() && sangre.isBlank() && edad.isBlank() &&
                  alergias.isBlank() && medicacion.isBlank() &&
                  contacto.isBlank() && telefono.isBlank()

    fun borrar() = p.edit().clear().apply()

    /** Lo que se enseña a pantalla completa. Sin adornos: lo urgente arriba. */
    fun comoTexto(): String = buildString {
        if (nombreCompleto().isNotBlank()) append(nombreCompleto()).append("\n\n")
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
