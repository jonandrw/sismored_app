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
}
