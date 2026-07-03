package dev.renkinProject.renkin.data

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** The always-present default profile: undeletable, builds the base Renkin pack package. */
const val DEFAULT_PROFILE_ID = 1L

/**
 * A named icon set. Every profile owns its own icon assignments ([DbApplication.profileId]),
 * builds its own icon pack APK (a per-profile package name, so several packs can be installed
 * side by side) and keeps its own generation preferences ([prefsSnapshot], swapped in/out of
 * the shared DataStore when the active profile changes).
 */
@Entity
data class Profile(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    @ColumnInfo(defaultValue = "") val description: String = "",
    // Launcher-visible label of this profile's built pack (e.g. "Renkin Dark").
    @ColumnInfo(defaultValue = "") val packLabel: String = "",
    // JSON snapshot of the generation preferences, captured when switching away.
    @ColumnInfo(defaultValue = "") val prefsSnapshot: String = "",
    // True when the profile's icons were saved (e.g. before switching away) but not built
    // into the pack APK since — the UI marks these so the save isn't mistaken for a build.
    @ColumnInfo(defaultValue = "0") val hasUnbuiltChanges: Boolean = false
)

@Entity(primaryKeys = ["packageName", "activityName", "profileId"])
data class DbApplication(
    val packageName: String,
    val activityName: String,
    val isAdaptiveIcon: Boolean,
    val isXml: Boolean,
    val drawable: String,
    @ColumnInfo(defaultValue = "0") val calendarEnabled: Boolean = false,
    @ColumnInfo(defaultValue = "") val calendarPrefix: String = "",
    @ColumnInfo(defaultValue = "") val calendarPackName: String = "",
    // Package name of the icon pack this icon was taken from (empty when the icon doesn't
    // come from a pack — app-icon, app-name, upload, hand-edited vector or fallback styling).
    // Used to order packs by how often they're used in the per-app icon picker.
    @ColumnInfo(defaultValue = "") val sourcePackName: String = "",
    // Which profile this icon belongs to. Kept last so the positional constructor calls
    // predating profiles stay valid.
    @ColumnInfo(defaultValue = "1") val profileId: Long = DEFAULT_PROFILE_ID
)

@Dao
interface RenkinPackDao {
    @Query("SELECT * FROM DbApplication WHERE profileId = :profileId")
    fun getAll(profileId: Long): List<DbApplication>

    @Insert
    fun insertAll(apps: List<DbApplication>)

    @Query("DELETE FROM DbApplication WHERE profileId = :profileId")
    fun deleteAllApplications(profileId: Long)
}

@Dao
interface ProfileDao {
    @Query("SELECT * FROM Profile ORDER BY id")
    fun getAllFlow(): kotlinx.coroutines.flow.Flow<List<Profile>>

    @Query("SELECT * FROM Profile ORDER BY id")
    fun getAll(): List<Profile>

    @Query("SELECT * FROM Profile WHERE id = :id")
    fun get(id: Long): Profile?

    @Insert
    fun insert(profile: Profile): Long

    @Update
    fun update(profile: Profile)

    @Query("DELETE FROM Profile WHERE id = :id")
    fun delete(id: Long)
}

// Version 7 has the same schema as version 5: 6 briefly added an isCustomIcon column during
// development (never released), so 7 exists only to give both 5 and 6 a forward path.
// Version 8 adds profiles: the Profile table plus DbApplication.profileId (part of the PK).
// Version 9 adds Profile.hasUnbuiltChanges (saved-but-not-built marker).
@Database(
    entities = [DbApplication::class, Profile::class],
    version = 9
)
abstract class RenkinPackDatabase : RoomDatabase() {
    abstract fun renkinPackDao(): RenkinPackDao
    abstract fun profileDao(): ProfileDao

    companion object {
        @Volatile
        private var instance: RenkinPackDatabase? = null

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE DbApplication ADD COLUMN calendarEnabled INTEGER NOT NULL DEFAULT 0")
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE DbApplication ADD COLUMN calendarPrefix TEXT NOT NULL DEFAULT ''")
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE DbApplication ADD COLUMN calendarPackName TEXT NOT NULL DEFAULT ''")
            }
        }

        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE DbApplication ADD COLUMN sourcePackName TEXT NOT NULL DEFAULT ''")
            }
        }

        // 5 and 7 are schema-identical (see the @Database note), so this is a version-stamp bump.
        private val MIGRATION_5_7 = object : Migration(5, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {}
        }

        // 6 was a development-only schema with an extra isCustomIcon column; rebuild the table
        // without it (SQLite can't reliably drop columns on older APIs).
        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `DbApplication_new` (`packageName` TEXT NOT NULL, " +
                        "`activityName` TEXT NOT NULL, `isAdaptiveIcon` INTEGER NOT NULL, " +
                        "`isXml` INTEGER NOT NULL, `drawable` TEXT NOT NULL, " +
                        "`calendarEnabled` INTEGER NOT NULL DEFAULT 0, " +
                        "`calendarPrefix` TEXT NOT NULL DEFAULT '', " +
                        "`calendarPackName` TEXT NOT NULL DEFAULT '', " +
                        "`sourcePackName` TEXT NOT NULL DEFAULT '', " +
                        "PRIMARY KEY(`packageName`, `activityName`))"
                )
                db.execSQL(
                    "INSERT INTO `DbApplication_new` SELECT packageName, activityName, " +
                        "isAdaptiveIcon, isXml, drawable, calendarEnabled, calendarPrefix, " +
                        "calendarPackName, sourcePackName FROM `DbApplication`"
                )
                db.execSQL("DROP TABLE `DbApplication`")
                db.execSQL("ALTER TABLE `DbApplication_new` RENAME TO `DbApplication`")
            }
        }

        // Profiles: the Profile table (with the default row) and profileId on DbApplication,
        // which joins the primary key (table rebuild — SQLite can't alter a PK in place).
        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `Profile` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`name` TEXT NOT NULL, `description` TEXT NOT NULL DEFAULT '', " +
                        "`packLabel` TEXT NOT NULL DEFAULT '', `prefsSnapshot` TEXT NOT NULL DEFAULT '')"
                )
                db.execSQL("INSERT INTO `Profile` (`id`, `name`, `packLabel`) VALUES (1, 'Renkin', 'Renkin Pack')")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `DbApplication_new` (`packageName` TEXT NOT NULL, " +
                        "`activityName` TEXT NOT NULL, `isAdaptiveIcon` INTEGER NOT NULL, " +
                        "`isXml` INTEGER NOT NULL, `drawable` TEXT NOT NULL, " +
                        "`calendarEnabled` INTEGER NOT NULL DEFAULT 0, " +
                        "`calendarPrefix` TEXT NOT NULL DEFAULT '', " +
                        "`calendarPackName` TEXT NOT NULL DEFAULT '', " +
                        "`sourcePackName` TEXT NOT NULL DEFAULT '', " +
                        "`profileId` INTEGER NOT NULL DEFAULT 1, " +
                        "PRIMARY KEY(`packageName`, `activityName`, `profileId`))"
                )
                db.execSQL(
                    "INSERT INTO `DbApplication_new` SELECT packageName, activityName, " +
                        "isAdaptiveIcon, isXml, drawable, calendarEnabled, calendarPrefix, " +
                        "calendarPackName, sourcePackName, 1 FROM `DbApplication`"
                )
                db.execSQL("DROP TABLE `DbApplication`")
                db.execSQL("ALTER TABLE `DbApplication_new` RENAME TO `DbApplication`")
            }
        }

        private val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE Profile ADD COLUMN hasUnbuiltChanges INTEGER NOT NULL DEFAULT 0")
            }
        }

        // The default profile row must exist even on a fresh database (migrations don't run there).
        private val seedDefaultProfile = object : Callback() {
            override fun onCreate(db: SupportSQLiteDatabase) {
                db.execSQL("INSERT INTO `Profile` (`id`, `name`, `packLabel`) VALUES (1, 'Renkin', 'Renkin Pack')")
            }
        }

        // Physical file name stays "alchemiconPack" so existing installs keep their
        // saved generated icons across the rename.
        fun get(context: Context): RenkinPackDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    RenkinPackDatabase::class.java,
                    "alchemiconPack"
                ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_7, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9)
                    .addCallback(seedDefaultProfile)
                    .build().also { instance = it }
            }
        }
    }
}