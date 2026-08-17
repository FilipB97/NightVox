package pl.nightvox.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [SessionEntity::class, ClipEntity::class],
    version = 2,
    exportSchema = true,
)
abstract class NightVoxDatabase : RoomDatabase() {
    abstract fun sessionDao(): SessionDao
    abstract fun clipDao(): ClipDao

    companion object {
        /**
         * Kosz „Odrzucone”. Migracja, nie `fallbackToDestructiveMigration` — na telefonie
         * są już nagrania z przespanych nocy i skasowanie ich przy aktualizacji byłoby
         * najgorszą możliwą reakcją na dodanie kolumny.
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE clips ADD COLUMN isDiscarded INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE clips ADD COLUMN discardReason TEXT")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_clips_isDiscarded_startedAt ON clips (isDiscarded, startedAt)")
            }
        }

        fun build(context: Context): NightVoxDatabase =
            Room.databaseBuilder(
                context.applicationContext,
                NightVoxDatabase::class.java,
                "nightvox.db",
            )
                .addMigrations(MIGRATION_1_2)
                .build()
    }
}
