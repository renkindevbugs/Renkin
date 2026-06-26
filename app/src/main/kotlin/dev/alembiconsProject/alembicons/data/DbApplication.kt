package dev.alembiconsProject.alembicons.data

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
    @ColumnInfo(defaultValue = "") val calendarPackName: String = ""
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

@Database(
    entities = [DbApplication::class],
    version = 4
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

        // Physical file name stays "alchemiconPack" so existing installs keep their
        // saved generated icons across the rename.
        fun get(context: Context): RenkinPackDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    RenkinPackDatabase::class.java,
                    "alchemiconPack"
                ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4).build().also { instance = it }
            }
        }
    }
}