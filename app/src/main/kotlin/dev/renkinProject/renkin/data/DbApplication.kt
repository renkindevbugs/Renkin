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
import dev.renkinProject.renkin.icon.creator.StoredIconModifierInput

/** The always-present default profile: undeletable, builds the base Renkin pack package. */
const val DEFAULT_PROFILE_ID = 1L

/**
 * A named icon set. Every profile owns its own icon assignments ([DbApplication.profileId]),
 * builds its own icon pack APK (a per-profile package name, so several packs can be installed
 * side by side). [profileStyle] owns configured settings after bootstrap; [prefsSnapshot] and
 * the active DataStore values are compatibility projections.
 */
@Entity
data class Profile(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    @ColumnInfo(defaultValue = "") val description: String = "",
    // Launcher-visible label of this profile's built pack (e.g. "Renkin Dark").
    @ColumnInfo(defaultValue = "") val packLabel: String = "",
    // Legacy JSON projection, atomically updated with profileStyle by the style owner.
    @ColumnInfo(defaultValue = "") val prefsSnapshot: String = "",
    // True when the profile's icons were saved (e.g. before switching away) but not built
    // into the pack APK since — the UI marks these so the save isn't mistaken for a build.
    @ColumnInfo(defaultValue = "0") val hasUnbuiltChanges: Boolean = false,
    // "Don't show again" for the missing-icon-packs dialog, chosen per profile.
    @ColumnInfo(defaultValue = "0") val hideMissingPackWarning: Boolean = false,
    // Empty only for unconverted legacy rows; bootstrap fills it before storage becomes ready.
    @ColumnInfo(defaultValue = "") val profileStyle: String = "",
    // Hand-added launcher components (AppTargetCodec JSON). Only updateAppTargets writes it.
    @ColumnInfo(defaultValue = "") val appTargets: String = "",
    @ColumnInfo(defaultValue = "") val customPackageName: String = "",
    @ColumnInfo(defaultValue = "") val packIcon: String = ""
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
    // watch-apply) rather than produced by a bulk refresh. Together with the in-memory refresh
    // marker, splits global options into generated, existing and custom icons.
    @ColumnInfo(defaultValue = "0") val isCustomIcon: Boolean = false,
    // Records that the generated-vs-custom origin predates reliable classification.
    @ColumnInfo(defaultValue = "0") val isLegacyIcon: Boolean = false,
    // Non-destructive source for re-rendering global modifiers. drawable remains the rendered
    // compatibility/export payload, so older importers still receive the visible result.
    @ColumnInfo(defaultValue = "") val baseDrawable: String = "",
    @ColumnInfo(defaultValue = "0") val baseIsAdaptiveIcon: Boolean = false,
    @ColumnInfo(defaultValue = "0") val baseIsXml: Boolean = false,
    // Attribution reference for icons picked from an online FOSS library: the source file's
    // public URL (GitHub via jsDelivr). Purely informational — the drawable itself is stored;
    // this only records where it was taken from. Never fed into the pack-verdict/lock logic.
    @ColumnInfo(defaultValue = "") val sourceUrl: String = "",
    // True when the bulk refresh could not find this app in the source pack and generated the
    // icon with the pack's fallback styling. Persisted so the home card's fallback count and
    // the Fallback list filter survive profile switches and restarts (the flag can't be
    // re-derived from the stored bitmap).
    @ColumnInfo(defaultValue = "0") val isFallbackIcon: Boolean = false,
    // Last local modifier stage that produced baseDrawable, not a recipe to apply onto it.
    // Empty for older icons or edits containing brush geometry outside the portable codec.
    @ColumnInfo(defaultValue = "") val modifierRecipe: String = "",
    // Bounded settings and content hash; source artwork lives in filesDir/icon-inputs.
    @ColumnInfo(defaultValue = "") val modifierInput: String = ""
)

/**
 * What the last successful build of [profileId] actually shipped, one row per exported icon.
 * Written only by a build, so it stays a record of the installed pack while the DbApplication
 * rows move on with every save — the two together are what "changes since last build" compares.
 */
@Entity(primaryKeys = ["profileId", "packageName", "activityName"])
data class BuiltIcon(
    val profileId: Long,
    val packageName: String,
    val activityName: String,
    val iconHash: String
)

@Dao
interface BuiltIconDao {
    @Query("SELECT * FROM BuiltIcon WHERE profileId = :profileId")
    fun get(profileId: Long): List<BuiltIcon>

    @Query("DELETE FROM BuiltIcon WHERE profileId = :profileId")
    fun deleteForProfile(profileId: Long)

    @Query("DELETE FROM BuiltIcon WHERE profileId NOT IN (SELECT id FROM Profile)")
    fun deleteForMissingProfiles()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertAll(rows: List<BuiltIcon>)
}

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

/**
 * A saved colour or gradient the user can reapply anywhere the colour sheet appears. Deliberately
 * NOT per profile: a palette you built is yours across every icon set.
 */
@Entity
data class ColorPreset(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    // The encoded ColorizerStyle; see encodeColorizerStyle().
    val style: String,
    val createdAt: Long = System.currentTimeMillis()
)

/**
 * A saved Modifier-tab recipe. Like [ColorPreset] it belongs to the device, not to a profile: a
 * look you built is worth reusing in every icon set. The payload is the encoded
 * [dev.renkinProject.renkin.icon.creator.ModifierPresetPayload]; [schemaVersion] is stored
 * alongside it so a future reader can tell a stale row from a damaged one without parsing.
 */
@Entity
data class ModifierPreset(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val payload: String,
    val schemaVersion: Int,
    val createdAt: Long = System.currentTimeMillis(),
    // Drives the recent list in the Modifier tab; seeded with createdAt so a fresh
    // preset is immediately the newest one.
    val lastUsedAt: Long = createdAt
)

@Dao
interface ModifierPresetDao {
    @Query("SELECT * FROM ModifierPreset ORDER BY lastUsedAt DESC, id DESC")
    fun getAllFlow(): kotlinx.coroutines.flow.Flow<List<ModifierPreset>>

    @Query("SELECT * FROM ModifierPreset ORDER BY lastUsedAt DESC, id DESC")
    fun getAll(): List<ModifierPreset>

    @Insert
    fun insert(preset: ModifierPreset): Long

    @Query("UPDATE ModifierPreset SET name = :name WHERE id = :id")
    fun rename(id: Long, name: String)

    @Query(
        "UPDATE ModifierPreset SET payload = :payload, schemaVersion = :schemaVersion, " +
            "lastUsedAt = :updatedAt WHERE id = :id"
    )
    fun update(id: Long, payload: String, schemaVersion: Int, updatedAt: Long)

    @Query("UPDATE ModifierPreset SET lastUsedAt = :usedAt WHERE id = :id")
    fun markUsed(id: Long, usedAt: Long)

    @Query("DELETE FROM ModifierPreset WHERE id = :id")
    fun delete(id: Long)

    @Query("DELETE FROM ModifierPreset")
    fun deleteEverything()
}

@Dao
interface ColorPresetDao {
    @Query("SELECT * FROM ColorPreset ORDER BY createdAt")
    fun getAllFlow(): kotlinx.coroutines.flow.Flow<List<ColorPreset>>

    @Query("SELECT * FROM ColorPreset ORDER BY createdAt")
    fun getAll(): List<ColorPreset>

    @Insert
    fun insert(preset: ColorPreset): Long

    @Query("DELETE FROM ColorPreset WHERE id = :id")
    fun delete(id: Long)

    @Query("DELETE FROM ColorPreset")
    fun deleteEverything()
}

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
    // Row reads return over-long artwork empty; RenkinPackRepository completes it in chunks.
    @Query("SELECT $ICON_ROW_COLUMNS FROM DbApplication WHERE profileId = :profileId AND packageName = :packageName AND activityName = :activityName")
    fun editorBaselineInline(profileId: Long, packageName: String, activityName: String): DbApplication?

    @Query("SELECT $FINGERPRINT_COLUMNS FROM DbApplication WHERE profileId = :profileId")
    fun fingerprintRowsInline(profileId: Long): List<IconFingerprintRow>

    @Query("SELECT modifierInput FROM DbApplication WHERE modifierInput != ''")
    fun modifierInputDocuments(): List<String>

    @Query("SELECT $ICON_ROW_COLUMNS FROM DbApplication WHERE profileId = :profileId")
    fun getAllInline(profileId: Long): List<DbApplication>

    /** Every profile's rows in one query — backup export. */
    @Query("SELECT $ICON_ROW_COLUMNS FROM DbApplication")
    fun getAllProfilesInline(): List<DbApplication>

    @Query("SELECT profileId, packageName, activityName FROM DbApplication ORDER BY rowid")
    fun exportKeys(): List<IconExportKey>

    @Query("SELECT DISTINCT sourcePackName FROM DbApplication WHERE sourcePackName != '' " +
        "AND (:profileId IS NULL OR profileId = :profileId) " +
        "AND (:excludedProfileId IS NULL OR profileId != :excludedProfileId)")
    fun exportSourcePacks(profileId: Long?, excludedProfileId: Long?): List<String>

    @Query("SELECT $OVERSIZED_ARTWORK_COLUMNS FROM DbApplication WHERE $OVERSIZED_ARTWORK_FILTER AND profileId = :profileId")
    fun oversizedArtwork(profileId: Long): List<OversizedIconArtwork>

    @Query("SELECT $OVERSIZED_ARTWORK_COLUMNS FROM DbApplication WHERE $OVERSIZED_ARTWORK_FILTER")
    fun oversizedArtworkAllProfiles(): List<OversizedIconArtwork>

    @Query("SELECT substr(drawable, :start, :count) FROM DbApplication WHERE profileId = :profileId AND packageName = :packageName AND activityName = :activityName")
    fun drawableChunk(profileId: Long, packageName: String, activityName: String, start: Int, count: Int): String?

    @Query("SELECT substr(baseDrawable, :start, :count) FROM DbApplication WHERE profileId = :profileId AND packageName = :packageName AND activityName = :activityName")
    fun baseDrawableChunk(profileId: Long, packageName: String, activityName: String, start: Int, count: Int): String?

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

/** Only the exported fields used by the saved-versus-built comparison; excludes source artwork. */
data class IconFingerprintRow(
    val packageName: String,
    val activityName: String,
    val drawable: String,
    val calendarEnabled: Boolean,
    val calendarPrefix: String
)

data class IconExportKey(
    val profileId: Long,
    val packageName: String,
    val activityName: String
)

@Dao
interface ProfileDao {
    @Query("UPDATE Profile SET profileStyle = :document, prefsSnapshot = :legacySnapshot WHERE id = :id")
    fun updateStyle(id: Long, document: String, legacySnapshot: String): Int

    @Query("UPDATE Profile SET appTargets = :targets WHERE id = :id")
    fun updateAppTargets(id: Long, targets: String): Int

    @Query("UPDATE Profile SET name = :name, description = :description, packLabel = :packLabel, customPackageName = :customPackageName, packIcon = :packIcon, hasUnbuiltChanges = :unbuilt WHERE id = :id")
    fun updateDetails(id: Long, name: String, description: String, packLabel: String, customPackageName: String, packIcon: String, unbuilt: Boolean): Int

    @Query("UPDATE Profile SET hasUnbuiltChanges = :unbuilt WHERE id = :id")
    fun setUnbuilt(id: Long, unbuilt: Boolean): Int

    @Query("UPDATE Profile SET hideMissingPackWarning = :hide WHERE id = :id")
    fun setHideMissingPackWarning(id: Long, hide: Boolean): Int

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
// Version 13 adds DbApplication.sourceUrl (attribution reference for online-library icons).
// Version 14 adds DbApplication.isFallbackIcon (fallback-styled refresh output) so the
// fallback count/filter survive restarts.
// Version 15 adds the ColorPreset table (saved colours/gradients, shared by every profile).
// Version 16 adds the BuiltIcon table: what the last build actually shipped, so "changes since
// last build" survives saving and restarting.
// Version 17 rebuilds DbApplication without the iconHash column a development build of 16 had
// added: fingerprints are derived from the stored rows instead, and the rebuild makes both
// shapes of 16 (with and without the column) end up identical.
// Version 18 adds the ModifierPreset table (saved Modifier-tab recipes, shared by every profile).
// Version 19 adds optional per-icon modifier metadata without changing the baked artwork.
// Version 20 adds a reference to the retained input for local modifier replay.
// Version 21 reserves Profile.profileStyle; DataStore still owns active settings until cutover.
// Version 22 adds Profile.appTargets (hand-added launcher components, issue #24).
// Version 23 adds Profile.customPackageName and Profile.packIcon (issue #26).
// Version 24 adds the GlobalStyleHistoryEntry table (Global style Apply history).
// Recovery from older snapshots is audited in BackupDatabaseSnapshot (omitted columns and added
// tables); review it on every bump.
@Database(
    entities = [
        DbApplication::class,
        Profile::class,
        PackVerdict::class,
        ColorPreset::class,
        BuiltIcon::class,
        ModifierPreset::class,
        GlobalStyleHistoryEntry::class
    ],
    version = 24
)
abstract class RenkinPackDatabase : RoomDatabase() {
    abstract fun renkinPackDao(): RenkinPackDao
    abstract fun profileDao(): ProfileDao
    abstract fun packVerdictDao(): PackVerdictDao
    abstract fun colorPresetDao(): ColorPresetDao
    abstract fun modifierPresetDao(): ModifierPresetDao
    abstract fun builtIconDao(): BuiltIconDao
    abstract fun globalStyleHistoryDao(): GlobalStyleHistoryDao

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

        internal val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE DbApplication ADD COLUMN sourceUrl TEXT NOT NULL DEFAULT ''")
            }
        }

        internal val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE DbApplication ADD COLUMN isFallbackIcon INTEGER NOT NULL DEFAULT 0")
            }
        }

        internal val MIGRATION_14_15 = object : Migration(14, 15) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `ColorPreset` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`name` TEXT NOT NULL, " +
                        "`style` TEXT NOT NULL, " +
                        "`createdAt` INTEGER NOT NULL)"
                )
            }
        }

        internal val MIGRATION_15_16 = object : Migration(15, 16) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `BuiltIcon` (" +
                        "`profileId` INTEGER NOT NULL, " +
                        "`packageName` TEXT NOT NULL, " +
                        "`activityName` TEXT NOT NULL, " +
                        "`iconHash` TEXT NOT NULL, " +
                        "PRIMARY KEY(`profileId`, `packageName`, `activityName`))"
                )
            }
        }

        internal val MIGRATION_16_17 = object : Migration(16, 17) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Copy by explicit column list: a device that ran the development 16 has an extra
                // iconHash column, one coming straight from 15 does not, and both must land on
                // the same shape. SQLite before 3.35 cannot drop a column, hence the rebuild.
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `DbApplication_new` (" +
                        "`packageName` TEXT NOT NULL, `activityName` TEXT NOT NULL, " +
                        "`isAdaptiveIcon` INTEGER NOT NULL, `isXml` INTEGER NOT NULL, " +
                        "`drawable` TEXT NOT NULL, " +
                        "`calendarEnabled` INTEGER NOT NULL DEFAULT 0, " +
                        "`calendarPrefix` TEXT NOT NULL DEFAULT '', " +
                        "`calendarPackName` TEXT NOT NULL DEFAULT '', " +
                        "`sourcePackName` TEXT NOT NULL DEFAULT '', " +
                        "`profileId` INTEGER NOT NULL DEFAULT 1, " +
                        "`sourceDrawableName` TEXT NOT NULL DEFAULT '', " +
                        "`isCustomIcon` INTEGER NOT NULL DEFAULT 0, " +
                        "`isLegacyIcon` INTEGER NOT NULL DEFAULT 0, " +
                        "`baseDrawable` TEXT NOT NULL DEFAULT '', " +
                        "`baseIsAdaptiveIcon` INTEGER NOT NULL DEFAULT 0, " +
                        "`baseIsXml` INTEGER NOT NULL DEFAULT 0, " +
                        "`sourceUrl` TEXT NOT NULL DEFAULT '', " +
                        "`isFallbackIcon` INTEGER NOT NULL DEFAULT 0, " +
                        "PRIMARY KEY(`packageName`, `activityName`, `profileId`))"
                )
                db.execSQL(
                    "INSERT INTO `DbApplication_new` (" +
                        "packageName, activityName, isAdaptiveIcon, isXml, drawable, " +
                        "calendarEnabled, calendarPrefix, calendarPackName, sourcePackName, " +
                        "profileId, sourceDrawableName, isCustomIcon, isLegacyIcon, " +
                        "baseDrawable, baseIsAdaptiveIcon, baseIsXml, sourceUrl, isFallbackIcon) " +
                        "SELECT packageName, activityName, isAdaptiveIcon, isXml, drawable, " +
                        "calendarEnabled, calendarPrefix, calendarPackName, sourcePackName, " +
                        "profileId, sourceDrawableName, isCustomIcon, isLegacyIcon, " +
                        "baseDrawable, baseIsAdaptiveIcon, baseIsXml, sourceUrl, isFallbackIcon " +
                        "FROM `DbApplication`"
                )
                db.execSQL("DROP TABLE `DbApplication`")
                db.execSQL("ALTER TABLE `DbApplication_new` RENAME TO `DbApplication`")
            }
        }

        internal val MIGRATION_17_18 = object : Migration(17, 18) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `ModifierPreset` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`name` TEXT NOT NULL, " +
                        "`payload` TEXT NOT NULL, " +
                        "`schemaVersion` INTEGER NOT NULL, " +
                        "`createdAt` INTEGER NOT NULL, " +
                        "`lastUsedAt` INTEGER NOT NULL)"
                )
            }
        }

        internal val MIGRATION_18_19 = object : Migration(18, 19) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE DbApplication ADD COLUMN modifierRecipe TEXT NOT NULL DEFAULT ''")
            }
        }

        internal val MIGRATION_19_20 = object : Migration(19, 20) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE DbApplication ADD COLUMN modifierInput TEXT NOT NULL DEFAULT ''")
            }
        }

        internal val MIGRATION_20_21 = object : Migration(20, 21) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE Profile ADD COLUMN profileStyle TEXT NOT NULL DEFAULT ''")
            }
        }

        internal val MIGRATION_21_22 = object : Migration(21, 22) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE Profile ADD COLUMN appTargets TEXT NOT NULL DEFAULT ''")
            }
        }

        internal val MIGRATION_22_23 = object : Migration(22, 23) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE Profile ADD COLUMN customPackageName TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE Profile ADD COLUMN packIcon TEXT NOT NULL DEFAULT ''")
            }
        }

        internal val MIGRATION_23_24 = object : Migration(23, 24) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `GlobalStyleHistoryEntry` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`profileId` INTEGER NOT NULL, `appliedAt` INTEGER NOT NULL, `style` TEXT NOT NULL)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_GlobalStyleHistoryEntry_profileId` ON `GlobalStyleHistoryEntry` (`profileId`)"
                )
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
            MIGRATION_11_12,
            MIGRATION_12_13,
            MIGRATION_13_14,
            MIGRATION_14_15,
            MIGRATION_15_16,
            MIGRATION_16_17,
            MIGRATION_17_18,
            MIGRATION_18_19,
            MIGRATION_19_20,
            MIGRATION_20_21,
            MIGRATION_21_22,
            MIGRATION_22_23,
            MIGRATION_23_24
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
                // Pre-release v20 builds embedded optional source artwork in this column.
                // Remove that obsolete representation before SELECT * can fill a CursorWindow.
                if (db.version >= 20) db.execSQL(
                    "UPDATE DbApplication SET modifierInput = '' WHERE length(CAST(modifierInput AS BLOB)) > " +
                        StoredIconModifierInput.MAX_DOCUMENT_CHARS + " OR instr(modifierInput, '\"input\":') > 0"
                )
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
