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
    private var sismos: List<EventoBD> = emptyList()
    /** Los del catálogo, que no están guardados: solo se enseñan. */
    private var delCatalogo: List<EventoBD> = emptyList()
    /** El día elegido en la fila de chips, o null para todos. */
    private var diaElegido: String? = null
    /** Mientras la consulta al catálogo está en vuelo. */
    private var consultando = true

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

        /* Con targetSdk 35 la ventana se dibuja de borde a borde, asi que
           el hueco de la barra de estado hay que pedirlo. Sin esto el
           titulo se solapaba con el reloj en el Redmi. */
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(
            findViewById(R.id.raiz_sismos)
        ) { v, insets ->
            val b = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            v.setPadding(0, b.top, 0, b.bottom)
            insets
        }

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

        db = SismoDatabase.getDatabase(this)
        cargarHistorial()
    }

    private fun cargarHistorial() {
        lifecycleScope.launch(Dispatchers.IO) {
            /* Dos consultas: los sismos salen de la suya para que el corte
               de 500 apuntes del registro no se los coma. */
            val s = db.eventoDao().obtenerSismos()
            val t = db.eventoDao().obtenerRecientes()
            withContext(Dispatchers.Main) { sismos = s; todos = t; pintar() }

            /* Y el catálogo oficial, que es otra cosa: la app solo guarda los
               sismos por los que avisó —cerca y por encima del umbral—, y aquí
               se quiere ver TODO lo que han publicado hoy los servicios que
               consulta, incluido lo que no merecía una notificación. No se
               guarda en la base de datos: se enseña y ya. */
            val oficiales = try {
                ReceptorSismicoOnline(
                    getUbicacion = { ServicioSos.ultimaUbicacion },
                    onAlertaSismica = { _, _, _, _ -> }
                ).reportesRecientes(7).map {
                    EventoBD(
                        tipo = 1, fechaMs = it.fechaMs,
                        /* Locale.US para el punto decimal: los guardados
                           salen con punto y mezclarlos con comas se lee mal. */
                        mensaje = "alerta externa (%s): M%.1f en %s (~%d km)".format(
                            java.util.Locale.US,
                            it.fuente, it.magnitud, it.lugar, it.distanciaKm.toInt())
                    )
                }
            } catch (_: Exception) { emptyList() }
            withContext(Dispatchers.Main) { consultando = false }
            if (oficiales.isNotEmpty()) withContext(Dispatchers.Main) {
                delCatalogo = oficiales
                consultando = false
                pintar()
            }
        }
    }

    /** Medianoche de hoy. */
    private fun hoy0(): Long = java.util.Calendar.getInstance().apply {
        set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0)
        set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
    }.timeInMillis

    /** «HOY», «AYER», «HACE 3 DÍAS». Un número de días no se lee igual. */
    private fun nombreDelDia(ms: Long): String {
        val d = ((hoy0() - ms) / 86_400_000L).toInt() + 1
        return when {
            ms >= hoy0() -> getString(R.string.dia_hoy)
            d <= 1 -> getString(R.string.dia_ayer)
            else -> getString(R.string.dia_hace, d)
        }
    }

    /** Mete una cabecera cada vez que cambia el día. */
    private fun conDias(lista: List<EventoBD>): List<HistorialAdapter.Fila> {
        val out = ArrayList<HistorialAdapter.Fila>()
        var ultimo = ""
        for (e in lista) {
            val n = nombreDelDia(e.fechaMs)
            if (n != ultimo) { out.add(HistorialAdapter.Fila.Dia(n)); ultimo = n }
            out.add(HistorialAdapter.Fila.Suceso(e))
        }
        return out
    }

    /**
     * Una sola lista.
     *
     * Antes esto tenía tres vistas que se turnaban —nuevos, siete días,
     * todo— y era un error: lo nuevo no es otra pantalla, es una marca. Y el
     * registro completo ya existe en su propia pestaña, así que aquí sobra.
     * Siete días, agrupados por día, y un punto rojo en lo que no se ha visto.
     */
    /** Los chips de día, construidos con los días que hay de verdad. */
    private fun pintarDias(lista: List<EventoBD>) {
        val fila = findViewById<android.widget.LinearLayout>(R.id.fila_dias) ?: return
        fila.removeAllViews()
        val dias = lista.map { nombreDelDia(it.fechaMs) }.distinct()
        if (dias.size < 2) return   // con un solo día, filtrar no filtra nada
        for (d in listOf<String?>(null) + dias) {
            val t = TextView(this)
            t.text = d ?: getString(R.string.hist_todos_dias)
            t.setTextColor(getColor(if (d == diaElegido) R.color.bg else R.color.lectura))
            t.setBackgroundResource(if (d == diaElegido) R.drawable.chip_activo else R.drawable.chip)
            t.textSize = 10f
            t.isAllCaps = true
            t.letterSpacing = 0.08f
            t.typeface = androidx.core.content.res.ResourcesCompat.getFont(this, R.font.mono)
            t.setPadding(dp(12), dp(6), dp(12), dp(6))
            val lp = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.marginEnd = dp(7)
            t.layoutParams = lp
            t.setOnClickListener { diaElegido = d; pintar() }
            fila.addView(t)
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    /**
     * Salir del panel es haberlos visto.
     *
     * Se marca al salir y no al entrar para que los puntos rojos sigan
     * ahí mientras se mira: marcarlos al abrir los borraría delante de los
     * ojos y el usuario no sabría cuáles eran los nuevos.
     */
    override fun onPause() {
        super.onPause()
        getSharedPreferences("sismored", MODE_PRIVATE).edit()
            .putLong("sismos_vistos_hasta", System.currentTimeMillis()).apply()
    }

    private fun pintar() {
        val limpiadoHasta = getSharedPreferences("sismored", MODE_PRIVATE)
            .getLong("sismos_vistos_hasta", 0L)
        val semana = hoy0() - 6 * 86_400_000L

        val semanaEntera = agrupar(
            (sismos + delCatalogo).filter { it.fechaMs >= semana }
                .sortedByDescending { it.fechaMs })
        pintarDias(semanaEntera)
        /* El día elegido puede haber desaparecido al recargar. */
        if (diaElegido != null && semanaEntera.none { nombreDelDia(it.fechaMs) == diaElegido })
            diaElegido = null
        val lista = if (diaElegido == null) semanaEntera
                    else semanaEntera.filter { nombreDelDia(it.fechaMs) == diaElegido }
        adapter.submitList(conDias(lista), limpiadoHasta)

        val nuevos = lista.count { it.fechaMs > limpiadoHasta }
        /* Que se sepa que aún falta por llegar: la lista crece unos
           milisegundos después y sin avisar parece un salto raro. */
        findViewById<TextView>(R.id.tv_sub)?.text = when {
            consultando -> getString(R.string.hist_consultando)
            lista.isEmpty() -> getString(R.string.hist_sub_vacio)
            nuevos > 0 -> getString(R.string.hist_sub_con_nuevos, nuevos, lista.size)
            else -> resources.getQuantityString(R.plurals.hist_sub_semana, lista.size, lista.size)
        }
        /* Limpiar solo tiene sentido si hay algo que marcar como visto. */
        findViewById<TextView>(R.id.btn_limpiar)?.visibility =
            if (nuevos > 0) android.view.View.VISIBLE else android.view.View.GONE

        findViewById<android.view.View>(R.id.caja_vacio)?.visibility =
            if (lista.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE
        findViewById<RecyclerView>(R.id.rv_historial)?.visibility =
            if (lista.isEmpty()) android.view.View.GONE else android.view.View.VISIBLE
    }
}
