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

    @Query("SELECT COUNT(*) FROM eventos")
    suspend fun cuantos(): Int

    /* La tabla no tenía tope: se insertaba en cada evento y no se borraba
       nunca. En un móvil que puede pasar días vigilando, y que anota cada
       baliza oída y cada marco de malla, eso crece sin fin — y lo hace en el
       aparato que tiene que aguantar encendido justo cuando ya no puedes
       liberar espacio a mano. Se queda con los MAX más recientes. */
    @Query("DELETE FROM eventos WHERE id NOT IN (SELECT id FROM eventos ORDER BY fechaMs DESC LIMIT :max)")
    suspend fun podar(max: Int = MAX_EVENTOS)

    @Query("DELETE FROM eventos")
    suspend fun borrarTodos()

    companion object {
        /** Con qué se queda la poda. Cabe una emergencia larga entera. */
        const val MAX_EVENTOS = 2000
    }
}
