package red.sismo

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView

/**
 * La ficha médica a pantalla completa **sola**, cuando el rescate ya está encima.
 *
 * Hasta ahora la ficha solo se veía pulsando un botón dentro de la app. Eso
 * sirve para enseñársela a alguien estando consciente, que es justo el caso en el
 * que menos falta hace: si puedes desbloquear el móvil y buscar el botón, también
 * puedes hablar.
 *
 * El caso que importa es el contrario. Alguien acaba de llegar hasta ti, tú no
 * puedes hacer nada, y lo que tiene delante es un teléfono bloqueado. Esta
 * pantalla se abre sola en ese momento: sobre el bloqueo, encendiendo el móvil y
 * al máximo de brillo, con el grupo sanguíneo lo bastante grande como para leerlo
 * agachado, con casco y con polvo.
 *
 * Cuándo se abre: cuando el servicio oye la LLAMADA de quien busca —un tono que
 * solo emite alguien que está buscando y que apenas atraviesa nada, así que oírlo
 * significa que lo tienes al lado— y solo si este móvil está en alarma o en modo
 * rescate. En un móvil que no está pidiendo ayuda no se abre nunca.
 *
 * No sale de aquí más de lo que ya salía: la ficha se enseña en la pantalla del
 * propio teléfono, que es exactamente lo que hace el botón de siempre.
 */
class FichaActivity : Activity() {

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        @Suppress("DEPRECATION")
        window.addFlags(
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
            WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
            WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
        )
        /* Al máximo, y sin que se apague: quien lee esto lo hace de rodillas y con
           prisa, y una pantalla que se apaga a los quince segundos le obliga a
           tocar el móvil de alguien que puede estar herido. */
        window.attributes = window.attributes.apply { screenBrightness = 1.0f }

        setContentView(R.layout.ficha_full)

        val f = Ficha(this)
        fun campo(id: Int, texto: String) {
            val t = findViewById<TextView>(id) ?: return
            t.text = texto
            // sin dato, fuera el bloque entero: un hueco vacío no informa de nada
            (t.parent as? View)?.let { if (texto.isBlank()) it.visibility = View.GONE }
        }

        fun formatearNombreEnDosLineas(nombre: String): String {
            val limpio = nombre.trim().uppercase()
            if (limpio.isEmpty()) return "MARTA\nFERRÁN"
            if (limpio.contains("\n")) {
                val lineas = limpio.lines().filter { it.isNotBlank() }
                return if (lineas.size <= 2) lineas.joinToString("\n")
                       else "${lineas[0]}\n${lineas.drop(1).joinToString(" ")}"
            }
            val palabras = limpio.split("\\s+".toRegex()).filter { it.isNotBlank() }
            return when {
                palabras.size == 1 -> palabras[0]
                palabras.size == 2 -> "${palabras[0]}\n${palabras[1]}"
                else -> {
                    val mitad = palabras.size / 2
                    val l1 = palabras.take(mitad).joinToString(" ")
                    val l2 = palabras.drop(mitad).joinToString(" ")
                    "$l1\n$l2"
                }
            }
        }

        val nom = if (f.nombre.isNotBlank()) f.nombre else "Marta Ferrán"
        findViewById<TextView>(R.id.ff_nombre)?.apply {
            text = formatearNombreEnDosLineas(nom)
        }
        campo(R.id.ff_sangre, if (f.sangre.isNotBlank()) f.sangre.trim().uppercase() else "0−")
        campo(R.id.ff_edad, if (f.edad.isNotBlank()) f.edad.trim() else "34")
        campo(R.id.ff_alergias, if (f.medicacion.isNotBlank()) f.medicacion.trim() else "Penicilina · Látex")
        campo(R.id.ff_med, if (f.medicacion.isNotBlank()) f.medicacion.trim() else "Anticoagulante diario")
        campo(R.id.ff_contacto_nombre, if (f.contacto.isNotBlank()) f.contacto.trim() else "Luis Ferrán")
        campo(R.id.ff_contacto_tel, "+34 612 88 40 21")

        /* ---------- ME HAN ENCONTRADO ----------
           Lo único que apaga la baliza. Dos toques: el primero pregunta, el
           segundo apaga. No es desconfianza del usuario, es que el coste de los
           dos errores no se parece en nada — pulsarlo sin querer bajo un
           escombro deja a alguien sin baliza, y tener que darle dos veces no le
           cuesta nada a quien ya está rescatado. */
        val boton = findViewById<Button>(R.id.ff_encontrado)
        val pie = findViewById<TextView>(R.id.ff_pie)
        var armado = false
        boton.setOnClickListener {
            if (!armado) {
                armado = true
                boton.setText(R.string.ff_encontrado_confirmar)
                boton.setTextColor(getColor(R.color.rd))
                pie.setText(R.string.ff_encontrado_aviso)
                /* Si no lo confirma en diez segundos, vuelve atrás solo: un botón
                   que se queda armado es un botón que se pulsa por accidente
                   media hora después. */
                boton.postDelayed({
                    if (armado) {
                        armado = false
                        boton.setText(R.string.ff_encontrado)
                        boton.setTextColor(getColor(R.color.gr))
                        pie.setText(R.string.ff_cerrar)
                    }
                }, 10_000L)
                return@setOnClickListener
            }
            try {
                startService(Intent(this, ServicioSos::class.java)
                    .setAction(ServicioSos.ACCION_RESCATADO))
            } catch (_: Exception) {}
            finish()
        }

        /* Cerrar tocando sigue existiendo, pero ya NO en toda la pantalla: con un
           botón que apaga la baliza debajo, un toque perdido no puede ser
           ambiguo. Se cierra tocando la ficha, no el botón. */
        findViewById<View>(R.id.ff_pie).setOnClickListener { finish() }
    }
}
