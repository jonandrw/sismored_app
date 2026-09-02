package red.sismo

/**
 * Cómo se parte un nombre para la ficha médica.
 *
 * Vive aquí porque estaba copiado en `MainActivity` y en `FichaActivity`, con
 * el mismo reparto y dos ejemplos inventados distintos metidos dentro. Las dos
 * pantallas enseñan el mismo dato a la misma persona y no pueden partirlo de
 * dos maneras.
 */
object Nombres {

    /**
     * Nombres en la primera línea, apellidos en la segunda.
     *
     * En español lo normal son dos nombres y dos apellidos —«Juan Andrés /
     * Torres Orozco»—, así que con cuatro palabras se parte por la mitad. Con
     * tres, el reparto que acierta más veces es un nombre y dos apellidos:
     * «Juan / Torres Orozco». Con cinco o más se vuelve a partir por la mitad,
     * porque ya no hay regla que valga y al menos las dos líneas quedan
     * parejas.
     *
     * Lo que **no** se hace es cortar por número de letras: eso partiría «Juan
     * Andrés Torres / Orozco» y separaría los dos apellidos, que es justo el
     * par que quien te busca necesita leer junto.
     */
    fun enDosLineas(nombre: String): String {
        val limpio = nombre.trim().uppercase()
        if (limpio.isEmpty()) return ""

        // si ya viene partido a mano, se respeta
        if (limpio.contains('\n')) {
            val lineas = limpio.lines().filter { it.isNotBlank() }
            return if (lineas.size <= 2) lineas.joinToString("\n")
            else "${lineas[0]}\n${lineas.drop(1).joinToString(" ")}"
        }

        val palabras = limpio.split("\\s+".toRegex()).filter { it.isNotBlank() }
        return when (palabras.size) {
            0 -> ""
            1 -> palabras[0]
            2 -> "${palabras[0]}\n${palabras[1]}"
            3 -> "${palabras[0]}\n${palabras[1]} ${palabras[2]}"
            else -> {
                val corte = palabras.size / 2
                palabras.take(corte).joinToString(" ") + "\n" +
                    palabras.drop(corte).joinToString(" ")
            }
        }
    }

    /**
     * Encoge el nombre hasta que las dos lineas caben de verdad.
     *
     * El autoajuste de Android (`autoSizeTextType`) **no funciona con
     * `wrap_content`**: la vista crece con el texto, la altura siempre cabe y
     * el ancho se recorta en silencio. Es lo que dejaba «TORRES OROZCO» sin el
     * ultimo apellido en la tarjeta que lee quien te encuentra.
     *
     * Se mide la linea mas larga con la tipografia y el espaciado reales y se
     * baja de dos en dos puntos hasta que entra. Nunca por debajo de [minSp],
     * porque un nombre a diez puntos ya no se lee a un metro con polvo encima
     * — si ni asi cabe, mejor que se vea pequeño que recortado.
     */
    fun ajustar(t: android.widget.TextView, maxSp: Float = 58f, minSp: Float = 22f) {
        val hueco = t.width - t.paddingStart - t.paddingEnd
        if (hueco <= 0) {
            // todavia no esta medida: se reintenta cuando lo este
            t.post { if (t.width > 0) ajustar(t, maxSp, minSp) }
            return
        }
        val lineas = t.text.toString().lines()
        var sp = maxSp
        val p = android.text.TextPaint(t.paint)
        val d = t.resources.displayMetrics.scaledDensity
        while (sp > minSp) {
            p.textSize = sp * d
            if (lineas.all { p.measureText(it) <= hueco }) break
            sp -= 2f
        }
        t.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, sp)
    }
}
