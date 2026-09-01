package red.sismo.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [EventoBD::class], version = 1, exportSchema = false)
abstract class SismoDatabase : RoomDatabase() {
    abstract fun eventoDao(): EventoDao

    companion object {
        @Volatile
        private var INSTANCE: SismoDatabase? = null

        fun getDatabase(context: Context): SismoDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    SismoDatabase::class.java,
                    "sismored_db"
                ).build()
                INSTANCE = instance
                instance
            }
        }
    }
}
