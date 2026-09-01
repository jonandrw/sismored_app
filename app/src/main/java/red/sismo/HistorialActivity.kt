package red.sismo

import android.os.Build
import android.os.Bundle
import android.widget.ImageButton
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
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
        
        // Modo inmersivo
        WindowCompat.setDecorFitsSystemWindows(window, false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.let {
                it.hide(WindowInsetsCompat.Type.systemBars())
                it.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        }

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
