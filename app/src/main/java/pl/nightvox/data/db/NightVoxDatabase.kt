package pl.nightvox.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [SessionEntity::class, ClipEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class NightVoxDatabase : RoomDatabase() {
    abstract fun sessionDao(): SessionDao
    abstract fun clipDao(): ClipDao

    companion object {
        fun build(context: Context): NightVoxDatabase =
            Room.databaseBuilder(
                context.applicationContext,
                NightVoxDatabase::class.java,
                "nightvox.db",
            ).build()
    }
}
