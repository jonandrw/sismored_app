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
        private val SISMICO = Regex(
            "alerta externa|ALERTA SÍSMICA|sismógrafo .*m/s²|TERREMOTO|" +
            "vigilia nocturna|SISMO EN REPOSO|MÓVIL DE ALGUIEN",
            RegexOption.IGNORE_CASE
        )

        /**
         * Las líneas de diagnóstico no son un sismo.
         *
         * Cada alerta de catálogo deja cuatro apuntes —el crudo, el traducido,
         * la línea `pruebas ·` y la decisión— y en una lista de sismos eso es
         * el mismo terremoto cuatro veces. `pruebas ·` además está escrita para
         * depurar, con banderas y porcentajes que no significan nada para
         * quien solo quiere saber si tembló. Se queda en la vista completa.
         */
        private val DIAGNOSTICO = Regex("^(pruebas ·|decision ·|cascada)", RegexOption.IGNORE_CASE)

        fun esSismico(e: EventoBD): Boolean =
            SISMICO.containsMatchIn(e.mensaje) && !DIAGNOSTICO.containsMatchIn(e.mensaje.trimStart())
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
        val lista = if (soloSismos) todos.filter { esSismico(it) } else todos
        adapter.submitList(lista)
        findViewById<TextView>(R.id.btn_filtro).text =
            getString(if (soloSismos) R.string.hist_todo else R.string.hist_solo_sismos)
    }
}
