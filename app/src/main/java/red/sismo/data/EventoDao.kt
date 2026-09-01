package red.sismo.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface EventoDao {
    @Insert
    suspend fun insertar(evento: EventoBD)

    @Query("SELECT * FROM eventos ORDER BY fechaMs DESC LIMIT 500")
    suspend fun obtenerRecientes(): List<EventoBD>
    
    @Query("DELETE FROM eventos")
    suspend fun borrarTodos()
}
