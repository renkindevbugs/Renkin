package dev.renkinProject.renkin.data.watch

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        WatchRule::class,
        WatchRuleApp::class,
        WatchRulePack::class,
        WatchState::class,
        IconSuggestion::class,
        IconSuggestionCandidate::class
    ],
    version = 1
)
abstract class WatchDatabase : RoomDatabase() {
    abstract fun watchDao(): WatchDao

    companion object {
        @Volatile
        private var instance: WatchDatabase? = null

        fun get(context: Context): WatchDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    WatchDatabase::class.java,
                    "watch"
                ).build().also { instance = it }
            }
        }
    }
}
