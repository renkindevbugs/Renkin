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
    version = 3
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

        // Baselines used to be shared by every rule watching the same app/pack pair. Re-key
        // them by rule and copy the existing fingerprint to each active matching rule so an
        // upgrade does not turn already-known artwork into a fresh suggestion.
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `watch_state_new` (" +
                        "`ruleId` INTEGER NOT NULL, `packageName` TEXT NOT NULL, " +
                        "`activityName` TEXT NOT NULL, `iconPackPackage` TEXT NOT NULL, " +
                        "`lastPackVersionCode` INTEGER NOT NULL, `lastIconName` TEXT, " +
                        "`lastIconHash` TEXT, `lastCheckedAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`ruleId`, `packageName`, `activityName`, `iconPackPackage`))"
                )
                db.execSQL(
                    "INSERT OR IGNORE INTO `watch_state_new` " +
                        "SELECT r.id, s.packageName, s.activityName, s.iconPackPackage, " +
                        "s.lastPackVersionCode, s.lastIconName, s.lastIconHash, s.lastCheckedAt " +
                        "FROM `watch_state` s " +
                        "JOIN `watch_rule_app` a ON a.packageName = s.packageName " +
                        "AND a.activityName = s.activityName " +
                        "JOIN `watch_rule` r ON r.id = a.ruleId " +
                        "WHERE r.completed = 0 AND (r.watchAllPacks = 1 OR EXISTS (" +
                        "SELECT 1 FROM `watch_rule_pack` p WHERE p.ruleId = r.id " +
                        "AND p.iconPackPackage = s.iconPackPackage))"
                )
                db.execSQL("DROP TABLE `watch_state`")
                db.execSQL("ALTER TABLE `watch_state_new` RENAME TO `watch_state`")
            }
        }

        fun get(context: Context): WatchDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    WatchDatabase::class.java,
                    "watch"
                ).addMigrations(MIGRATION_1_2, MIGRATION_2_3).build().also { instance = it }
            }
        }
    }
}
