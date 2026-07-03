package dev.renkinProject.renkin.data

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Entity(primaryKeys = ["packageName", "activityName"])
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
    @ColumnInfo(defaultValue = "") val sourcePackName: String = ""
)

@Dao
interface RenkinPackDao {
    @Query("SELECT * FROM DbApplication")
    fun getAll(): List<DbApplication>

    @Query("SELECT * FROM DbApplication WHERE packageName = :packageName")
    fun get(packageName: String): DbApplication

    @Insert
    fun insertAll(vararg apps: DbApplication)

    @Insert
    fun insertAll(apps: List<DbApplication>)

    @Delete
    fun delete(vararg apps: DbApplication)

    @Delete
    fun delete(apps: List<DbApplication>)

    @Query("DELETE FROM DbApplication")
    fun deleteAllApplications()
}

// Version 7 has the same schema as version 5: 6 briefly added an isCustomIcon column during
// development (never released), so 7 exists only to give both 5 and 6 a forward path.
@Database(
    entities = [DbApplication::class],
    version = 7
)
abstract class RenkinPackDatabase : RoomDatabase() {
    abstract fun renkinPackDao(): RenkinPackDao

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

        // Physical file name stays "alchemiconPack" so existing installs keep their
        // saved generated icons across the rename.
        fun get(context: Context): RenkinPackDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    RenkinPackDatabase::class.java,
                    "alchemiconPack"
                ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_7, MIGRATION_6_7).build().also { instance = it }
            }
        }
    }
}