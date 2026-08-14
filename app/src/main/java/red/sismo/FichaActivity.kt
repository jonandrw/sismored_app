package red.sismo

import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowManager
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
            val t = findViewById<TextView>(id)
            t.text = texto
            // sin dato, fuera el bloque entero: un hueco vacío no informa de nada
            (t.parent as? View)?.let { if (texto.isBlank()) it.visibility = View.GONE }
        }
        campo(R.id.ff_nombre, f.nombre.trim())
        campo(R.id.ff_sangre, f.sangre.trim().uppercase())
        campo(R.id.ff_edad, f.edad.trim().let { if (it.isBlank()) "" else "$it años" })
        campo(R.id.ff_med, f.medicacion.trim())
        campo(R.id.ff_contacto, f.contacto.trim())

        // se cierra tocando: quien la ha leído ya no la necesita
        findViewById<View>(android.R.id.content).setOnClickListener { finish() }
    }
}
