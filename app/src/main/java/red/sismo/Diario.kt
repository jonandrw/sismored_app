package red.sismo

import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import androidx.recyclerview.widget.RecyclerView
import red.sismo.data.EventoBD
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * El diario de la pestaña Registro: lo que alguien quiere revisar después
 * —«¿qué pasó anoche?»— sacado del registro entero.
 *
 * Medido en el Redmi del 11 al 22 de septiembre de 2026: 20.390 eventos, y más
 * del 90 % eran «VOZ HUMANA CERCA», líneas del sismógrafo y cambios de postura.
 * Lo que importa —alarmas, preguntas y cómo acabaron, sismos, la malla— eran
 * unos pocos cientos, perdidos en un muro de texto. El registro crudo sigue
 * entero en Ajustes → Consola.
 */
object Diario {

    enum class Tipo(val color: Int) {
        ALARMA(0xFFE53035.toInt()),
        PREGUNTA(0xFFF0A02A.toInt()),
        SISMO(0xFF90CA50.toInt()),
        MALLA(0xFF64D2FF.toInt()),
        RUIDO(0xFF4E565D.toInt())
    }

    /**
     * Qué es cada apunte, por cómo empieza. Por el texto y no por un campo
     * nuevo porque lo ya guardado no se puede reetiquetar, y son once días de
     * campo.
     */
    fun tipo(m: String): Tipo = when {
        m.startsWith("ALARMA") || m.startsWith("modo rescate") ||
            m.startsWith("nadie ha reaccionado") || m.startsWith("MÓVIL DE ALGUIEN") ||
            m.startsWith("SE OYE A ALGUIEN") || m.startsWith("SOLO UN MÓVIL") ||
            m.startsWith("RESCATADO") || m.startsWith("TE ESTÁN BUSCANDO") -> Tipo.ALARMA
        m.startsWith("TERREMOTO ·") || m.startsWith("Estás bien") ||
            m.startsWith("aviso discreto expiró") || m.startsWith("nadie ha contestado") ||
            m.startsWith("usuario descartó") -> Tipo.PREGUNTA
        m.startsWith("alerta externa (") || m.startsWith("ALERTA SÍSMICA") -> Tipo.SISMO
        m.startsWith("ALERTA RECIBIDA") || m.startsWith("TE HAN OÍDO") ||
            m.contains("VECINO PIDE AYUDA") || m.startsWith("un vecino pide ayuda") ||
            m.startsWith("FICHA COMPLETA recibida") -> Tipo.MALLA
        else -> Tipo.RUIDO
    }

    /** Lo que tienen en común dos apuntes repetidos: el texto sin cifras. */
    private val CIFRAS = Regex("""[\d.,:%]+""")
    private fun firma(m: String): String = m.replace(CIFRAS, "#").take(60)

    /** Dos iguales más separados que esto ya no son la misma racha. */
    private const val RACHA_MS = 30 * 60_000L

    sealed class Fila {
        class Dia(val texto: String, val corto: String) : Fila()
        /** [grupo] va del más reciente al más viejo; son iguales y seguidos. */
        class Suceso(val tipo: Tipo, val grupo: List<EventoBD>) : Fila()
    }

    /**
     * De la lista cruda (la más reciente primero) a filas: con [todo] a false
     * solo lo que importa, lo repetido y seguido plegado en una fila, y una
     * cabecera cada vez que cambia el día.
     */
    fun filas(eventos: List<EventoBD>, todo: Boolean, dia: (Long) -> Pair<String, String>): List<Fila> {
        val out = ArrayList<Fila>()
        var diaActual = ""
        var grupo = ArrayList<EventoBD>()
        var tipoGrupo = Tipo.RUIDO

        fun cerrar() {
            if (grupo.isNotEmpty()) out.add(Fila.Suceso(tipoGrupo, grupo))
            grupo = ArrayList()
        }

        for (e in eventos) {
            val t = tipo(e.mensaje)
            if (!todo && t == Tipo.RUIDO) continue
            val (largo, corto) = dia(e.fechaMs)
            if (largo != diaActual) {
                cerrar()
                out.add(Fila.Dia(largo, corto))
                diaActual = largo
            }
            val ultimo = grupo.lastOrNull()
            val sigue = ultimo != null && firma(ultimo.mensaje) == firma(e.mensaje) &&
                ultimo.fechaMs - e.fechaMs < RACHA_MS
            if (!sigue) { cerrar(); tipoGrupo = t }
            grupo.add(e)
        }
        cerrar()
        return out
    }

    /** La lista, con las rachas plegadas que se abren al tocarlas. */
    class Adaptador : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        var filas: List<Fila> = emptyList()
            private set
        /** Rachas abiertas, por el id de su apunte más reciente. */
        private val abiertas = HashSet<Long>()
        private val hora = SimpleDateFormat("HH:mm", Locale.getDefault())
        private val horaSeg = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

        fun poner(nuevas: List<Fila>) {
            filas = nuevas
            notifyDataSetChanged()
        }

        override fun getItemCount() = filas.size
        override fun getItemViewType(position: Int) = if (filas[position] is Fila.Dia) 0 else 1

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            if (viewType == 0) return object : RecyclerView.ViewHolder(
                LayoutInflater.from(parent.context).inflate(R.layout.item_dia, parent, false)) {}
            val tv = TextView(parent.context).apply {
                layoutParams = RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                val d = resources.displayMetrics.density
                setPadding((18 * d).toInt(), (5 * d).toInt(), (8 * d).toInt(), (5 * d).toInt())
                typeface = ResourcesCompat.getFont(context, R.font.mono)
                textSize = 11.5f
                setLineSpacing(0f, 1.3f)
                setTextColor(0xFFBCC3C9.toInt())
                ellipsize = TextUtils.TruncateAt.END
            }
            return object : RecyclerView.ViewHolder(tv) {}
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val tv = holder.itemView as TextView
            when (val f = filas[position]) {
                is Fila.Dia -> tv.findViewById<TextView>(R.id.tv_dia).text = f.texto
                is Fila.Suceso -> pintar(tv, f)
            }
        }

        private fun pintar(tv: TextView, f: Fila.Suceso) {
            val e = f.grupo.first()
            val abierta = e.id in abiertas
            val s = SpannableStringBuilder()
            fun color(desde: Int, c: Int) =
                s.setSpan(ForegroundColorSpan(c), desde, s.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)

            var i = s.length
            s.append(hora.format(Date(e.fechaMs))).append("  "); color(i, 0xFF4E565D.toInt())
            i = s.length
            s.append("● "); color(i, f.tipo.color)
            i = s.length
            s.append(e.mensaje)
            if (f.tipo != Tipo.RUIDO) {
                color(i, f.tipo.color)
                s.setSpan(StyleSpan(Typeface.BOLD), i, s.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            if (f.grupo.size > 1) {
                i = s.length
                s.append("  ×").append(f.grupo.size.toString()).append(" · ")
                    .append(hora.format(Date(f.grupo.last().fechaMs))).append("–")
                    .append(hora.format(Date(e.fechaMs)))
                    .append(if (abierta) "  ▴" else "  ▾")
                color(i, 0xFF7C858D.toInt())
            }
            if (abierta) for (o in f.grupo.drop(1)) {
                i = s.length
                s.append("\n   ").append(horaSeg.format(Date(o.fechaMs))).append("  ").append(o.mensaje)
                color(i, 0xFF7C858D.toInt())
            }
            tv.text = s
            tv.maxLines = if (abierta) Int.MAX_VALUE else 3
            tv.setOnClickListener(if (f.grupo.size > 1 || e.mensaje.length > 90) { _ ->
                val id = e.id
                if (!abiertas.remove(id)) abiertas.add(id)
                notifyItemChanged(filas.indexOf(f))
            } else null)
        }
    }
}
