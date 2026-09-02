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

        /* Esta es la pantalla que lee quien te encuentra inconsciente, y era la
           que más se inventaba: sin ficha rellenada enseñaba «MARTA FERRÁN ·
           0− · 34 años · Penicilina · Anticoagulante diario · Luis Ferrán», y
           el teléfono «+34 612 88 40 21» estaba puesto fijo, sin mirar la
           ficha siquiera. Un rescatista habría marcado un número inventado y
           habría descartado darte penicilina por una alergia que no tienes.
           Ahora cada hueco vacío desaparece —de eso se encarga `campo`— y lo
           que no se sabe no se dice. */
        findViewById<TextView>(R.id.ff_nombre)?.apply {
            val vacio = f.nombre.isBlank() && f.apellidos.isBlank()
            text = if (vacio) getString(R.string.ficha_sin_nombre) else f.nombreEnDosLineas()
            if (vacio) alpha = 0.45f
            Nombres.ajustar(this)
        }
        campo(R.id.ff_sangre, f.sangre.trim().uppercase())
        campo(R.id.ff_edad, f.edad.trim())
        campo(R.id.ff_alergias, f.alergias.trim())
        campo(R.id.ff_med, f.medicacion.trim())
        campo(R.id.ff_contacto_nombre, f.contacto.trim())
        campo(R.id.ff_contacto_tel, f.telefono.trim())

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
