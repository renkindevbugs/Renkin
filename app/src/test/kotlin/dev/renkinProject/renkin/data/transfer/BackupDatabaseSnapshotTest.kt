package dev.renkinProject.renkin.data.transfer

import android.app.Application
import androidx.room.Room
import androidx.room.withTransaction
import dev.renkinProject.renkin.data.RenkinPackDatabase
import dev.renkinProject.renkin.data.StorageRecoverySchemaException
import dev.renkinProject.renkin.data.createDatabaseFromExportedSchema
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowStatFs

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
class BackupDatabaseSnapshotTest {
    @Test fun version18SnapshotRestoresAllOldColumnsAndDefaultsRecipeInCurrentDatabase() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        ShadowStatFs.registerStats(context.filesDir, 1_000_000, 1_000_000, 1_000_000)
        val file = context.filesDir.resolve("v18-snapshot.json")
        var expected = emptyMap<String, String?>()
        val name = "snapshot-v18"
        try {
            createDatabaseFromExportedSchema(context, RenkinPackDatabase::class.java.canonicalName!!, 18, name) { db ->
                db.execSQL("INSERT INTO Profile (id, name, prefsSnapshot) VALUES (1, 'Original', 'prefs')")
                db.execSQL("INSERT INTO DbApplication " +
                    "(packageName, activityName, isAdaptiveIcon, isXml, drawable, baseDrawable, " +
                    "sourcePackName, isCustomIcon, calendarEnabled, calendarPrefix, profileId) " +
                    "VALUES ('app.one', 'Main', 0, 0, 'rendered', 'base', 'pack.one', 1, 1, 'day_', 1)")
                db.query("SELECT * FROM DbApplication").use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    expected = cursor.columnNames.associateWith { cursor.getString(cursor.getColumnIndexOrThrow(it)) }
                }
                // The helper invokes populate inside onCreate, before SQLite stamps user_version.
                db.version = 18
                runBlocking { BackupDatabaseSnapshot.write(db, file) }
            }
            val database = Room.inMemoryDatabaseBuilder(context, RenkinPackDatabase::class.java).allowMainThreadQueries().build()
            try {
                val sql = database.openHelper.writableDatabase
                BackupDatabaseSnapshot.checkCompatibility(sql, file)
                database.withTransaction { BackupDatabaseSnapshot.restore(sql, file) }
                sql.query("SELECT * FROM DbApplication").use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    expected.forEach { (column, value) -> assertEquals(column, value, cursor.getString(cursor.getColumnIndexOrThrow(column))) }
                    assertEquals("", cursor.getString(cursor.getColumnIndexOrThrow("modifierRecipe")))
                }
                val document = JSONObject(file.readText())
                document.put("version", 17)
                file.writeText(document.toString())
                assertTrue(runCatching { BackupDatabaseSnapshot.checkCompatibility(sql, file) }.exceptionOrNull() is StorageRecoverySchemaException)
                document.put("version", 18)
                val tables = document.getJSONArray("tables")
                for (index in 0 until tables.length()) {
                    val table = tables.getJSONObject(index)
                    if (table.getString("name") == "DbApplication") table.getJSONArray("columns").put(0, "changed")
                }
                file.writeText(document.toString())
                assertTrue(runCatching { BackupDatabaseSnapshot.checkCompatibility(sql, file) }.exceptionOrNull() is StorageRecoverySchemaException)
            } finally { database.close() }
        } finally { context.deleteDatabase(name); file.delete() }
    }
}
