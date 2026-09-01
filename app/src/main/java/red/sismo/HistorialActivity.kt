package red.sismo

import android.os.Bundle
import android.widget.ImageButton
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import red.sismo.data.SismoDatabase

class HistorialActivity : AppCompatActivity() {

    private lateinit var adapter: HistorialAdapter
    private lateinit var db: SismoDatabase

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        /* Sin modo inmersivo, igual que el resto de la app: esconder la barra
           del sistema aquí quitaba de la vista el reloj y la batería justo
           encima de una lista de eventos con hora. */
        setContentView(R.layout.activity_historial)

        val btnVolver = findViewById<ImageButton>(R.id.btn_volver)
        btnVolver.setOnClickListener {
            finish()
        }

        val rvHistorial = findViewById<RecyclerView>(R.id.rv_historial)
        adapter = HistorialAdapter()
        rvHistorial.layoutManager = LinearLayoutManager(this)
        rvHistorial.adapter = adapter

        db = SismoDatabase.getDatabase(this)

        cargarHistorial()
    }

    private fun cargarHistorial() {
        lifecycleScope.launch(Dispatchers.IO) {
            val eventos = db.eventoDao().obtenerRecientes()
            withContext(Dispatchers.Main) {
                adapter.submitList(eventos)
            }
        }
    }
}
