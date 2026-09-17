package red.sismo

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import red.sismo.data.EventoBD
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class HistorialAdapter : RecyclerView.Adapter<HistorialAdapter.EventoViewHolder>() {

    private var eventos: List<EventoBD> = emptyList()
    private val hora = SimpleDateFormat("dd/MM HH:mm", Locale.getDefault())
    private val completa = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.getDefault())

    companion object {
        /** `alerta externa (SGC): M4.4 en Sipí - Chocó, Colombia (~74 km)` */
        private val SISMO = Regex(
            """alerta externa \(([^)]+)\): M([\d.,]+) en (.+?) \(~(\d+) km\)"""
        )
    }

    fun submitList(nuevos: List<EventoBD>) {
        eventos = nuevos
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): EventoViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_historial, parent, false)
        return EventoViewHolder(view)
    }

    override fun onBindViewHolder(holder: EventoViewHolder, position: Int) {
        val e = eventos[position]
        val m = SISMO.find(e.mensaje)
        if (m != null) {
            /* Partido en sus piezas: la magnitud manda, el sitio se lee y la
               fuente va detrás porque quien pregunta «¿de dónde sale esto?»
               lo pregunta después, no antes. */
            val (fuente, mag, lugar, km) = m.destructured
            holder.tvMag.text = "M$mag"
            holder.tvMag.visibility = View.VISIBLE
            holder.tvMensaje.text = lugar
            /* La fuente en verde: es la que convierte una lectura en un
               hecho confirmado, y el verde es el color que la app ya usa
               para lo que está verificado. */
            val meta = "$km km · ${hora.format(Date(e.fechaMs))} · $fuente"
            val sp = android.text.SpannableString(meta)
            sp.setSpan(
                android.text.style.ForegroundColorSpan(
                    androidx.core.content.ContextCompat.getColor(
                        holder.itemView.context, R.color.gr)),
                meta.length - fuente.length, meta.length,
                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            holder.tvFecha.text = sp
        } else {
            /* Cualquier otra línea, en la vista completa: sin magnitud que
               enseñar, el mensaje ocupa la fila entera. */
            holder.tvMag.visibility = View.GONE
            holder.tvMensaje.text = e.mensaje
            holder.tvFecha.text = completa.format(Date(e.fechaMs))
        }
    }

    override fun getItemCount(): Int = eventos.size

    class EventoViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvFecha: TextView = view.findViewById(R.id.tv_fecha)
        val tvMensaje: TextView = view.findViewById(R.id.tv_mensaje)
        val tvMag: TextView = view.findViewById(R.id.tv_mag)
    }
}
