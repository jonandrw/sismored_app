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
    private val format = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.US)

    fun submitList(nuevos: List<EventoBD>) {
        eventos = nuevos
        notifyDataSetChanged() // In a real app, DiffUtil is better, but this suffices for simple logs
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): EventoViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_historial, parent, false)
        return EventoViewHolder(view)
    }

    override fun onBindViewHolder(holder: EventoViewHolder, position: Int) {
        val evento = eventos[position]
        holder.tvFecha.text = format.format(Date(evento.fechaMs))
        holder.tvMensaje.text = evento.mensaje
    }

    override fun getItemCount(): Int = eventos.size

    class EventoViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvFecha: TextView = view.findViewById(R.id.tv_fecha)
        val tvMensaje: TextView = view.findViewById(R.id.tv_mensaje)
    }
}
