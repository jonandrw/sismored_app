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
        /**
         * Con qué se queda la poda.
         *
         * Medido en el Redmi: 14.010 eventos en 6,6 días, unos 88 por hora y
         * 95 bytes cada uno. Con 2.000 no llegaba ni a un día, y un falso
         * positivo de madrugada se descubre por la mañana: para entonces la
         * causa ya se habría podado. Con 20.000 hay diez días de historia y
         * ocupa menos de 2 MB, que en el móvil no es nada.
         */
        const val MAX_EVENTOS = 20000
    }
}
