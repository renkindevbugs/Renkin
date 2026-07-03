package dev.renkinProject.renkin.data.watch

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        WatchRule::class,
        WatchRuleApp::class,
        WatchRulePack::class,
        WatchState::class,
        IconSuggestion::class,
        IconSuggestionCandidate::class
    ],
    version = 2
)
abstract class WatchDatabase : RoomDatabase() {
    abstract fun watchDao(): WatchDao

    companion object {
        @Volatile
        private var instance: WatchDatabase? = null

        // Profiles: rules gain an owning profileId (existing rules belong to the default).
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE watch_rule ADD COLUMN profileId INTEGER NOT NULL DEFAULT 1")
            }
        }

        fun get(context: Context): WatchDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    WatchDatabase::class.java,
                    "watch"
                ).addMigrations(MIGRATION_1_2).build().also { instance = it }
            }
        }
    }
}
