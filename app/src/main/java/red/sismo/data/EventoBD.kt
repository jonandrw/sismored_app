package red.sismo.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "eventos")
data class EventoBD(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val tipo: Int, // 1 = Detección de Nodo, 2 = Auxilio/Rescate, 3 = Sismógrafo
    val fechaMs: Long,
    val mensaje: String,
    val extraDato: String? = null // ID del nodo o información adicional
)
