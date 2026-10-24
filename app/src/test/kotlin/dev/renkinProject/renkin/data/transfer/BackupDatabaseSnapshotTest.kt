package dev.renkinProject.renkin.data.transfer

import android.app.Application
import androidx.room.Room
import androidx.room.withTransaction
import dev.renkinProject.renkin.data.RenkinPackDatabase
import dev.renkinProject.renkin.data.StorageRecoverySchemaException
import dev.renkinProject.renkin.data.createDatabaseFromExportedSchema
import dev.renkinProject.renkin.icon.creator.IconModifierInput
import dev.renkinProject.renkin.icon.creator.ModifierInputOrigin
import dev.renkinProject.renkin.icon.creator.StoredIconModifierInput
import dev.renkinProject.renkin.icon.creator.StoredModifierArtwork
import dev.renkinProject.renkin.icon.creator.modifierInputTestOptions
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
    private suspend fun oldSnapshot(version: Int, file: java.io.File) {
        val context = RuntimeEnvironment.getApplication()
        ShadowStatFs.registerStats(context.filesDir, 1_000_000, 1_000_000, 1_000_000)
        val name = "snapshot-old-$version"
        try {
            createDatabaseFromExportedSchema(context, RenkinPackDatabase::class.java.canonicalName!!, version, name) { db ->
                db.execSQL("INSERT INTO Profile (id, name, description, packLabel, prefsSnapshot, hasUnbuiltChanges, hideMissingPackWarning) " +
                    "VALUES (7, 'Original', 'description', 'Pack', 'legacy preferences', 1, 1)")
                db.execSQL("INSERT INTO DbApplication (packageName, activityName, isAdaptiveIcon, isXml, drawable, baseDrawable, profileId) " +
                    "VALUES ('app.one', 'Main', 0, 0, 'rendered', 'base', 7)")
                if (version >= 19) db.execSQL("UPDATE DbApplication SET modifierRecipe = 'kept recipe'")
                db.version = version
                runBlocking { BackupDatabaseSnapshot.write(db, file) }
            }
        } finally { context.deleteDatabase(name) }
    }

    @Test fun allAuditedOlderSnapshotsReplaceProfilesAndDefaultOnlyTheirMissingColumns() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val file = context.filesDir.resolve("old-profile-snapshot.json")
        val database = Room.inMemoryDatabaseBuilder(context, RenkinPackDatabase::class.java).allowMainThreadQueries().build()
        try {
            val sql = database.openHelper.writableDatabase
            for (version in listOf(18, 19, 20, 21)) {
                oldSnapshot(version, file)
                sql.execSQL("DELETE FROM Profile")
                sql.execSQL("INSERT INTO Profile (id, name, profileStyle, appTargets) VALUES (7, 'Newer', 'must not survive', 'must not survive')")
                BackupDatabaseSnapshot.checkCompatibility(sql, file)
                database.withTransaction { BackupDatabaseSnapshot.restore(sql, file) }
                val profile = database.profileDao().get(7)!!
                assertEquals("v$version", "", profile.profileStyle)
                assertEquals("v$version", "", profile.appTargets)
                assertEquals("Original", profile.name)
                assertEquals("description", profile.description)
                assertEquals("Pack", profile.packLabel)
                assertEquals("legacy preferences", profile.prefsSnapshot)
                assertTrue(profile.hasUnbuiltChanges)
                assertTrue(profile.hideMissingPackWarning)
                val icon = database.renkinPackDao().getAllInline(7).single()
                assertEquals("rendered", icon.drawable)
                assertEquals("base", icon.baseDrawable)
                assertEquals(if (version >= 19) "kept recipe" else "", icon.modifierRecipe)
                assertEquals("", icon.modifierInput)
                val next = database.profileDao().insert(dev.renkinProject.renkin.data.Profile(name = "Next"))
                assertEquals(8L, next)
            }
        } finally { database.close(); file.delete() }
    }

    @Test fun currentSnapshotRestoresProfileStyleExactlyWithoutLegacyFallback() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        ShadowStatFs.registerStats(context.filesDir, 1_000_000, 1_000_000, 1_000_000)
        val file = context.filesDir.resolve("v21-style-snapshot.json")
        val database = Room.inMemoryDatabaseBuilder(context, RenkinPackDatabase::class.java).allowMainThreadQueries().build()
        try {
            val sql = database.openHelper.writableDatabase
            sql.execSQL("INSERT INTO Profile (id, name, prefsSnapshot, profileStyle, appTargets) VALUES (7, 'Original', 'legacy', 'exact typed document', 'exact targets')")
            BackupDatabaseSnapshot.write(sql, file)
            sql.execSQL("UPDATE Profile SET profileStyle = 'changed', prefsSnapshot = 'changed', appTargets = 'changed'")
            BackupDatabaseSnapshot.checkCompatibility(sql, file)
            database.withTransaction { BackupDatabaseSnapshot.restore(sql, file) }
            assertEquals("exact typed document", database.profileDao().get(7)!!.profileStyle)
            assertEquals("legacy", database.profileDao().get(7)!!.prefsSnapshot)
            assertEquals("exact targets", database.profileDao().get(7)!!.appTargets)
        } finally { database.close(); file.delete() }
    }

    @Test fun profileAdapterRejectsUnexpectedDefaultsTypesNullabilityAndAdditionalColumns() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val file = context.filesDir.resolve("profile-schema-check.json")
        oldSnapshot(20, file)
        try {
            val invalidColumns = listOf(
                "profileStyle TEXT NOT NULL DEFAULT 'wrong'", "profileStyle TEXT DEFAULT ''",
                "profileStyle INTEGER NOT NULL DEFAULT ''", "profileStyle TEXT NOT NULL DEFAULT '', unexpected TEXT",
                "wrongName TEXT NOT NULL DEFAULT ''"
            )
            for ((index, columns) in invalidColumns.withIndex()) {
                val name = "invalid-profile-$index"
                try {
                    createDatabaseFromExportedSchema(context, RenkinPackDatabase::class.java.canonicalName!!, 21, name) { db ->
                        db.execSQL("DROP TABLE Profile")
                        db.execSQL("CREATE TABLE Profile (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, name TEXT NOT NULL, " +
                            "description TEXT NOT NULL DEFAULT '', packLabel TEXT NOT NULL DEFAULT '', prefsSnapshot TEXT NOT NULL DEFAULT '', " +
                            "hasUnbuiltChanges INTEGER NOT NULL DEFAULT 0, hideMissingPackWarning INTEGER NOT NULL DEFAULT 0, $columns)")
                        db.version = 21
                        val error = runBlocking { runCatching { BackupDatabaseSnapshot.checkCompatibility(db, file) }.exceptionOrNull() }
                        assertTrue(columns, error is StorageRecoverySchemaException)
                    }
                } finally { context.deleteDatabase(name) }
            }
        } finally { file.delete() }
    }

    @Test fun unauditedVersionTransitionsAndProfileColumnRenamesAreRejected() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val file = context.filesDir.resolve("unaudited-profile.json")
        oldSnapshot(20, file)
        val database = Room.inMemoryDatabaseBuilder(context, RenkinPackDatabase::class.java).allowMainThreadQueries().build()
        try {
            val sql = database.openHelper.writableDatabase
            sql.version = 23
            assertTrue(runCatching { BackupDatabaseSnapshot.checkCompatibility(sql, file) }.exceptionOrNull() is StorageRecoverySchemaException)
            sql.version = 22
            val root = JSONObject(file.readText())
            val tables = root.getJSONArray("tables")
            for (index in 0 until tables.length()) {
                val table = tables.getJSONObject(index)
                if (table.getString("name") == "Profile") table.getJSONArray("columns").put(1, "renamed")
            }
            file.writeText(root.toString())
            assertTrue(runCatching { BackupDatabaseSnapshot.checkCompatibility(sql, file) }.exceptionOrNull() is StorageRecoverySchemaException)
        } finally { database.close(); file.delete() }
    }

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
                    assertEquals("", cursor.getString(cursor.getColumnIndexOrThrow("modifierInput")))
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

    @Test fun version19SnapshotPreservesRecipeAndDefaultsOnlyInput() = runBlocking {
        val context: Application = RuntimeEnvironment.getApplication()
        ShadowStatFs.registerStats(context.filesDir, 1_000_000, 1_000_000, 1_000_000)
        val file = context.filesDir.resolve("v19-snapshot.json")
        val name = "snapshot-v19"
        try {
            createDatabaseFromExportedSchema(context, RenkinPackDatabase::class.java.canonicalName!!, 19, name) { db ->
                db.execSQL("INSERT INTO Profile (id, name) VALUES (1, 'Original')")
                db.execSQL("INSERT INTO DbApplication (packageName, activityName, isAdaptiveIcon, isXml, drawable, baseDrawable, modifierRecipe) " +
                    "VALUES ('app.one', 'Main', 0, 0, 'rendered', 'base', 'original recipe')")
                db.version = 19
                runBlocking { BackupDatabaseSnapshot.write(db, file) }
            }
            val database = Room.inMemoryDatabaseBuilder(context, RenkinPackDatabase::class.java).allowMainThreadQueries().build()
            try {
                val sql = database.openHelper.writableDatabase
                BackupDatabaseSnapshot.checkCompatibility(sql, file)
                database.withTransaction { BackupDatabaseSnapshot.restore(sql, file) }
                val row = database.renkinPackDao().getAllInline(1).single()
                assertEquals("rendered", row.drawable)
                assertEquals("base", row.baseDrawable)
                assertEquals("original recipe", row.modifierRecipe)
                assertEquals("", row.modifierInput)
            } finally { database.close() }
        } finally { context.deleteDatabase(name); file.delete() }
    }

    @Test fun currentSnapshotRestoresSmallReferencesAndDropsObsoleteInlineInputOnly() = runBlocking {
        val context: Application = RuntimeEnvironment.getApplication()
        ShadowStatFs.registerStats(context.filesDir, 1_000_000, 1_000_000, 1_000_000)
        val file = context.filesDir.resolve("v20-snapshot.json")
        val database = Room.inMemoryDatabaseBuilder(context, RenkinPackDatabase::class.java).allowMainThreadQueries().build()
        try {
            val sql = database.openHelper.writableDatabase
            sql.execSQL("INSERT INTO Profile (id, name) VALUES (1, 'Original')")
            val valid = checkNotNull(StoredIconModifierInput.encode(
                IconModifierInput(modifierInputTestOptions(), false, ModifierInputOrigin.UPLOAD),
                StoredModifierArtwork("a".repeat(64), false, false)
            ))
            for ((name, document) in mapOf("valid" to valid, "inline" to "{\"version\":1,\"input\":\"old pixels\"}")) {
                sql.execSQL("INSERT INTO DbApplication (packageName, activityName, isAdaptiveIcon, isXml, drawable, baseDrawable, modifierRecipe, modifierInput) " +
                    "VALUES (?, 'Main', 0, 0, 'rendered', 'base', 'original recipe', ?)", arrayOf(name, document))
            }
            BackupDatabaseSnapshot.write(sql, file)
            database.withTransaction { BackupDatabaseSnapshot.restore(sql, file) }
            val rows = database.renkinPackDao().getAllInline(1).associateBy { it.packageName }
            assertEquals(valid, rows.getValue("valid").modifierInput)
            assertEquals("", rows.getValue("inline").modifierInput)
            rows.values.forEach {
                assertEquals("rendered", it.drawable)
                assertEquals("base", it.baseDrawable)
                assertEquals("original recipe", it.modifierRecipe)
            }
        } finally { database.close(); file.delete() }
    }
}
