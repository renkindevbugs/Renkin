package dev.renkinProject.renkin.data

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
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
    @ColumnInfo(defaultValue = "0") val hasUnbuiltChanges: Boolean = false,
    // "Don't show again" for the missing-icon-packs dialog, chosen per profile.
    @ColumnInfo(defaultValue = "0") val hideMissingPackWarning: Boolean = false
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
    // Which profile this icon belongs to. Kept after the earlier columns so the positional
    // constructor calls predating profiles stay valid.
    @ColumnInfo(defaultValue = "1") val profileId: Long = DEFAULT_PROFILE_ID,
    // Drawable name inside sourcePackName, written only by profile/backup import for icons
    // shared as references (no image data). A row with an empty [drawable] and a non-empty
    // [sourcePackName] is such a reference: the icon is rebuilt from the installed pack.
    @ColumnInfo(defaultValue = "") val sourceDrawableName: String = "",
    // True when the icon was hand-picked/edited by the user (per-app dialog, upload, vector,
    // watch-apply) rather than produced by a bulk refresh. Splits the global-options preview
    // grid into generated vs custom icons. Unknown upgraded rows are separately protected.
    @ColumnInfo(defaultValue = "0") val isCustomIcon: Boolean = false,
    // Rows upgraded from before persistent classification are protected instead of guessed.
    @ColumnInfo(defaultValue = "0") val isLegacyIcon: Boolean = false,
    // Non-destructive source for re-rendering global modifiers. drawable remains the rendered
    // compatibility/export payload, so older importers still receive the visible result.
    @ColumnInfo(defaultValue = "") val baseDrawable: String = "",
    @ColumnInfo(defaultValue = "0") val baseIsAdaptiveIcon: Boolean = false,
    @ColumnInfo(defaultValue = "0") val baseIsXml: Boolean = false
)

/**
 * What this device knows about an icon pack referenced by stored icons — drives the
 * paid-pack lock on imported profiles/backups. [seenInstalled] is the ownership record:
 * a pack ever seen installed on THIS device stays usable forever, even after uninstall.
 * It is deliberately device-local — backups/shares never carry it, otherwise forwarding
 * a file would forward the ownership too.
 */
@Entity
data class PackVerdict(
    @PrimaryKey val packageName: String,
    // One of the VERDICT_* constants; UNKNOWN until a Play Store lookup succeeds.
    @ColumnInfo(defaultValue = VERDICT_UNKNOWN) val verdict: String = VERDICT_UNKNOWN,
    // Human-readable pack name, recorded while installed — shown when the pack is missing.
    @ColumnInfo(defaultValue = "") val label: String = "",
    @ColumnInfo(defaultValue = "0") val seenInstalled: Boolean = false,
    @ColumnInfo(defaultValue = "0") val checkedAt: Long = 0
)

const val VERDICT_UNKNOWN = "unknown"
const val VERDICT_FREE = "free"
const val VERDICT_PAID = "paid"
// Found on a store the price of which we don't parse (F-Droid) — installable, so shared
// icons stay locked until the recipient installs the pack (respects the pack's developer).
const val VERDICT_LISTED = "listed"
// Found on NO known store (Play, F-Droid). The pack can't be installed anywhere, so its
// shared icons stay usable — losing them would help nobody. NOTE: Icon Pack Studio exports
// also resolve here, but are locked by package pattern regardless (see PackVerdictManager).
const val VERDICT_UNLISTED = "unlisted"

@Dao
interface PackVerdictDao {
    @Query("SELECT * FROM PackVerdict WHERE packageName IN (:packages)")
    fun get(packages: List<String>): List<PackVerdict>

    @Query("SELECT * FROM PackVerdict")
    fun getAll(): List<PackVerdict>

    @Insert(onConflict = androidx.room.OnConflictStrategy.REPLACE)
    fun upsert(verdicts: List<PackVerdict>)

    @Query("DELETE FROM PackVerdict")
    fun deleteEverything()
}

@Dao
interface RenkinPackDao {
    @Query("SELECT * FROM DbApplication WHERE profileId = :profileId")
    fun getAll(profileId: Long): List<DbApplication>

    /** Every profile's rows in one query — backup export. */
    @Query("SELECT * FROM DbApplication")
    fun getAllProfiles(): List<DbApplication>

    @Insert
    fun insertAll(apps: List<DbApplication>)

    @Query("DELETE FROM DbApplication WHERE profileId = :profileId")
    fun deleteAllApplications(profileId: Long)

    @Query("DELETE FROM DbApplication")
    fun deleteEverything()

    /** Every pack any stored icon (any profile) came from — the packs needing a verdict. */
    @Query("SELECT DISTINCT sourcePackName FROM DbApplication WHERE sourcePackName != ''")
    fun distinctSourcePacks(): List<String>
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

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insertIfMissing(profile: Profile): Long

    @Update
    fun update(profile: Profile)

    @Query("DELETE FROM Profile WHERE id = :id")
    fun delete(id: Long)

    @Query("DELETE FROM Profile")
    fun deleteEverything()
}

// Version 7 has the same schema as version 5: 6 briefly added an isCustomIcon column during
// development (never released), so 7 exists only to give both 5 and 6 a forward path.
// Version 8 adds profiles: the Profile table plus DbApplication.profileId (part of the PK).
// Version 9 adds Profile.hasUnbuiltChanges (saved-but-not-built marker).
// Version 10 adds the PackVerdict table (paid-pack locks), DbApplication.sourceDrawableName
// (imported icon references) and Profile.hideMissingPackWarning.
// Version 11 adds DbApplication.isCustomIcon (hand-picked vs refresh-generated).
// Version 12 adds isLegacyIcon plus an immutable base drawable for non-destructive global
// rendering. Non-custom v11 rows are marked legacy because false may have been guessed during
// 10→11; true was only ever written by an explicit user edit.
@Database(
    entities = [DbApplication::class, Profile::class, PackVerdict::class],
    version = 12
)
abstract class RenkinPackDatabase : RoomDatabase() {
    abstract fun renkinPackDao(): RenkinPackDao
    abstract fun profileDao(): ProfileDao
    abstract fun packVerdictDao(): PackVerdictDao

    companion object {
        @Volatile
        private var instance: RenkinPackDatabase? = null

        internal val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE DbApplication ADD COLUMN calendarEnabled INTEGER NOT NULL DEFAULT 0")
            }
        }

        internal val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE DbApplication ADD COLUMN calendarPrefix TEXT NOT NULL DEFAULT ''")
            }
        }

        internal val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE DbApplication ADD COLUMN calendarPackName TEXT NOT NULL DEFAULT ''")
            }
        }

        internal val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE DbApplication ADD COLUMN sourcePackName TEXT NOT NULL DEFAULT ''")
            }
        }

        // 5 and 7 are schema-identical (see the @Database note), so this is a version-stamp bump.
        internal val MIGRATION_5_7 = object : Migration(5, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {}
        }

        // 6 was a development-only schema with an extra isCustomIcon column; rebuild the table
        // without it (SQLite can't reliably drop columns on older APIs).
        internal val MIGRATION_6_7 = object : Migration(6, 7) {
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
        internal val MIGRATION_7_8 = object : Migration(7, 8) {
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

        internal val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE Profile ADD COLUMN hasUnbuiltChanges INTEGER NOT NULL DEFAULT 0")
            }
        }

        internal val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE DbApplication ADD COLUMN sourceDrawableName TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE Profile ADD COLUMN hideMissingPackWarning INTEGER NOT NULL DEFAULT 0")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `PackVerdict` (`packageName` TEXT NOT NULL, " +
                        "`verdict` TEXT NOT NULL DEFAULT 'unknown', `label` TEXT NOT NULL DEFAULT '', " +
                        "`seenInstalled` INTEGER NOT NULL DEFAULT 0, `checkedAt` INTEGER NOT NULL DEFAULT 0, " +
                        "PRIMARY KEY(`packageName`))"
                )
            }
        }

        internal val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE DbApplication ADD COLUMN isCustomIcon INTEGER NOT NULL DEFAULT 0")
            }
        }

        internal val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE DbApplication ADD COLUMN isLegacyIcon INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE DbApplication ADD COLUMN baseDrawable TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE DbApplication ADD COLUMN baseIsAdaptiveIcon INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE DbApplication ADD COLUMN baseIsXml INTEGER NOT NULL DEFAULT 0")
                db.execSQL(
                    "UPDATE DbApplication SET baseDrawable = drawable, " +
                        "baseIsAdaptiveIcon = isAdaptiveIcon, baseIsXml = isXml"
                )
                db.execSQL("UPDATE DbApplication SET isLegacyIcon = 1 WHERE isCustomIcon = 0")
            }
        }

        internal val ALL_MIGRATIONS = arrayOf(
            MIGRATION_1_2,
            MIGRATION_2_3,
            MIGRATION_3_4,
            MIGRATION_4_5,
            MIGRATION_5_7,
            MIGRATION_6_7,
            MIGRATION_7_8,
            MIGRATION_8_9,
            MIGRATION_9_10,
            MIGRATION_10_11,
            MIGRATION_11_12
        )

        private fun insertDefaultProfile(db: SupportSQLiteDatabase) {
            db.execSQL(
                "INSERT OR IGNORE INTO `Profile` (`id`, `name`, `packLabel`) " +
                    "VALUES (1, 'Renkin', 'Renkin Pack')"
            )
        }

        // The default profile is a database invariant. Repair it on open as well as on create:
        // an interrupted import or a damaged row must not strand the default profile's icons.
        private val ensureDefaultProfile = object : Callback() {
            override fun onCreate(db: SupportSQLiteDatabase) {
                insertDefaultProfile(db)
            }

            override fun onOpen(db: SupportSQLiteDatabase) {
                insertDefaultProfile(db)
            }
        }

        // Fresh physical file name: the app id changed to dev.renkinProject.renkin, so there
        // are no existing installs whose data a rename could strand.
        internal fun open(context: Context, name: String): RenkinPackDatabase =
            Room.databaseBuilder(
                context.applicationContext,
                RenkinPackDatabase::class.java,
                name
            ).addMigrations(*ALL_MIGRATIONS)
                .addCallback(ensureDefaultProfile)
                .build()

        fun get(context: Context): RenkinPackDatabase {
            return instance ?: synchronized(this) {
                instance ?: open(context, "renkinPack").also { instance = it }
            }
        }
    }
}
