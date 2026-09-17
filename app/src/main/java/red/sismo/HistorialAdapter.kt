package red.sismo

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import red.sismo.data.EventoBD
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * La lista de sismos, con separadores de día.
 *
 * Dos tipos de fila porque siete días de sismos sin separar son una lista de
 * fechas que hay que leer una por una para saber cuál es de hoy.
 */
class HistorialAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    /** Lo que se pinta: o una cabecera de día, o un sismo. */
    sealed class Fila {
        data class Dia(val texto: String) : Fila()
        data class Suceso(val e: EventoBD) : Fila()
    }

    private var filas: List<Fila> = emptyList()
    /** Todo lo posterior a esto está sin ver. */
    private var sinVerDesde = 0L
    private val completa = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.getDefault())
    private val soloHora = SimpleDateFormat("HH:mm", Locale.getDefault())

    companion object {
        private const val DIA = 0
        private const val SUCESO = 1

        /** `alerta externa (SGC): M4.4 en Sipí - Chocó, Colombia (~74 km)` */
        private val SISMO = Regex(
            """alerta externa \(([^)]+)\): M([\d.,]+) en (.+?) \(~(-?\d+) km\)"""
        )

        /** El rótulo largo de antes del arreglo de la fuente, acortado al vuelo. */
        private fun fuenteCorta(f: String): String =
            if (f.contains("EMSC")) "EMSC" else f
    }

    fun submitList(nuevas: List<Fila>, desde: Long = 0L) {
        filas = nuevas
        sinVerDesde = desde
        notifyDataSetChanged()
    }

    override fun getItemViewType(position: Int) =
        if (filas[position] is Fila.Dia) DIA else SUCESO

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inf = LayoutInflater.from(parent.context)
        return if (viewType == DIA) DiaVH(inf.inflate(R.layout.item_dia, parent, false))
        else EventoViewHolder(inf.inflate(R.layout.item_historial, parent, false))
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val f = filas[position]) {
            is Fila.Dia -> (holder as DiaVH).tv.text = f.texto
            is Fila.Suceso -> pintarSuceso(holder as EventoViewHolder, f.e)
        }
    }

    private fun pintarSuceso(h: EventoViewHolder, e: EventoBD) {
        h.punto.visibility = if (e.fechaMs > sinVerDesde) View.VISIBLE else View.INVISIBLE
        val m = SISMO.find(e.mensaje)
        if (m != null) {
            val (fuente, mag, lugar, km) = m.destructured
            h.tvMag.text = mag
            h.cajaMag.visibility = View.VISIBLE
            h.tvMensaje.text = lugar
            /* La hora exacta, porque el día ya lo dice la cabecera. La fuente
               en verde: es la que convierte una lectura en un hecho. */
            val dist = if (km.startsWith("-")) "" else "$km km · "
            val f = fuenteCorta(fuente)
            val meta = "$dist${soloHora.format(Date(e.fechaMs))} · $f"
            val sp = android.text.SpannableString(meta)
            sp.setSpan(
                android.text.style.ForegroundColorSpan(
                    ContextCompat.getColor(h.itemView.context, R.color.gr)),
                meta.length - f.length, meta.length,
                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            h.tvFecha.text = sp
        } else {
            h.cajaMag.visibility = View.GONE
            h.tvMensaje.text = e.mensaje
            h.tvFecha.text = completa.format(Date(e.fechaMs))
        }
    }

    override fun getItemCount(): Int = filas.size

    class DiaVH(v: View) : RecyclerView.ViewHolder(v) {
        val tv: TextView = v.findViewById(R.id.tv_dia)
    }

    class EventoViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvFecha: TextView = view.findViewById(R.id.tv_fecha)
        val tvMensaje: TextView = view.findViewById(R.id.tv_mensaje)
        val tvMag: TextView = view.findViewById(R.id.tv_mag)
        val cajaMag: View = view.findViewById<View>(R.id.tv_mag).parent as View
        val punto: View = view.findViewById(R.id.punto_nuevo)
    }
}
