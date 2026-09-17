package red.sismo

import android.os.Bundle
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import red.sismo.data.EventoBD
import red.sismo.data.SismoDatabase

class HistorialActivity : AppCompatActivity() {

    private lateinit var adapter: HistorialAdapter
    private lateinit var db: SismoDatabase
    private var todos: List<EventoBD> = emptyList()
    private var soloSismos = true

    companion object {
        /**
         * Qué cuenta como sismo en este registro.
         *
         * El registro lo guarda todo, y eso está bien para diagnosticar, pero
         * hace que lo que importa se pierda: el 17 de septiembre, entre las
         * 16:00 y las 16:25 hubo 51 apuntes y 45 eran «VOZ HUMANA CERCA». El
         * M4.6 de Sipí estaba ahí en medio, en dos renglones.
         *
         * Se filtra por el texto y no por un tipo nuevo a propósito: los
         * eventos ya guardados en los móviles seguirían sin tipo, y son once
         * días de campo que no se pueden reetiquetar hacia atrás.
         */
        /**
         * Solo lo que ha confirmado un servicio sismológico.
         *
         * Esto es un historial de terremotos, no un registro de sensores. Lo
         * que mide el acelerómetro de un móvil no es un sismo hasta que
         * alguien con sismómetros de verdad lo dice, y mezclarlo aquí daría a
         * entender lo contrario. El registro completo sigue estando detrás del
         * botón de al lado.
         *
         * Se filtra por el texto y no por un tipo nuevo porque los eventos ya
         * guardados no se pueden reetiquetar hacia atrás, y son once días de
         * campo.
         */
        private val SISMICO = Regex("""alerta externa \(""")

        fun esSismico(e: EventoBD): Boolean = SISMICO.containsMatchIn(e.mensaje)

        /** Lo que identifica al sismo, sin la hora: misma magnitud y mismo sitio. */
        private val HUELLA = Regex("""M[\d.,]+ en .+?\(~\d+ km\)""")

        /**
         * El mismo terremoto una sola vez.
         *
         * Un catálogo revisa y republica, y la app vuelve a avisar cuando
         * reinicia mientras el suceso sigue en su ventana. Eso deja el mismo
         * sismo tres veces en la lista, y en un historial eso no es un
         * detalle: parece que ha temblado tres veces.
         *
         * Se agrupa al pintar y no solo al guardar porque lo ya guardado no
         * se puede arreglar hacia atrás, y son los únicos sismos que hay.
         */
        fun agrupar(lista: List<EventoBD>): List<EventoBD> {
            val vistos = HashMap<String, Long>()
            val out = ArrayList<EventoBD>()
            for (e in lista) {
                val h = HUELLA.find(e.mensaje)?.value
                if (h == null) { out.add(e); continue }
                val antes = vistos[h]
                if (antes != null && Math.abs(antes - e.fechaMs) < 30 * 60_000L) continue
                vistos[h] = e.fechaMs
                out.add(e)
            }
            return out
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        /* Sin modo inmersivo, igual que el resto de la app: esconder la barra
           del sistema aquí quitaba de la vista el reloj y la batería justo
           encima de una lista de eventos con hora. */
        setContentView(R.layout.activity_historial)

        findViewById<ImageButton>(R.id.btn_volver).setOnClickListener { finish() }

        val rvHistorial = findViewById<RecyclerView>(R.id.rv_historial)
        adapter = HistorialAdapter()
        rvHistorial.layoutManager = LinearLayoutManager(this)
        rvHistorial.adapter = adapter

        findViewById<TextView>(R.id.btn_limpiar)?.setOnClickListener {
            /* No borra nada: marca hasta dónde se ha mirado. La base de
               datos es el único registro de campo que hay y llevamos once
               días midiendo con ella; vaciarla por limpiar una lista sería
               perder eso. Los sismos siguen estando bajo TODO. */
            getSharedPreferences("sismored", MODE_PRIVATE).edit()
                .putLong("sismos_vistos_hasta", System.currentTimeMillis()).apply()
            pintar()
        }
        val btnFiltro = findViewById<TextView>(R.id.btn_filtro)
        btnFiltro.setOnClickListener { soloSismos = !soloSismos; pintar() }

        db = SismoDatabase.getDatabase(this)
        cargarHistorial()
    }

    private fun cargarHistorial() {
        lifecycleScope.launch(Dispatchers.IO) {
            val eventos = db.eventoDao().obtenerRecientes()
            withContext(Dispatchers.Main) { todos = eventos; pintar() }
        }
    }

    /** Se abre filtrado: quien entra al registro viene buscando el sismo. */
    private fun pintar() {
        val desde = getSharedPreferences("sismored", MODE_PRIVATE)
            .getLong("sismos_vistos_hasta", 0L)
        val lista = if (soloSismos)
            agrupar(todos.filter { esSismico(it) && it.fechaMs > desde })
        else todos
        adapter.submitList(lista)
        findViewById<TextView>(R.id.tv_vacio)?.setText(
            if (desde > 0L) R.string.hist_limpio else R.string.hist_sin_sismos)
        findViewById<TextView>(R.id.tv_vacio)?.visibility =
            if (lista.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE
        findViewById<TextView>(R.id.btn_limpiar)?.visibility =
            if (soloSismos) android.view.View.VISIBLE else android.view.View.GONE
        val chip = findViewById<TextView>(R.id.btn_filtro)
        chip.text = getString(if (soloSismos) R.string.hist_todo else R.string.hist_solo_sismos)
        /* Verde mientras el filtro esté puesto: que se vea que lo que hay
           delante no es todo lo que hay. */
        chip.setTextColor(getColor(if (soloSismos) R.color.gr else R.color.lectura))
    }
}
