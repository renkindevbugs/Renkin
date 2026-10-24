package dev.renkinProject.renkin.data

import android.app.Application
import android.content.Context
import dev.renkinProject.renkin.icon.creator.*
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
class RenkinPackMigrationTest {
    private lateinit var context: Context
    private val databaseNames = mutableListOf<String>()

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
    }

    @After
    fun tearDown() {
        databaseNames.forEach(context::deleteDatabase)
    }

    private fun historical(version: Int, suffix: String, populate: (androidx.sqlite.db.SupportSQLiteDatabase) -> Unit = {}) =
        "renkin-migration-$suffix".also { name ->
            databaseNames += name
            createDatabaseFromExportedSchema(
                context,
                checkNotNull(RenkinPackDatabase::class.java.canonicalName),
                version,
                name,
                populate
            )
        }

    @Test
    fun everyReleasedSchemaMigratesToCurrent() {
        listOf(1, 2, 3, 4, 5, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21).forEach { version ->
            RenkinPackDatabase.open(context, historical(version, "from-$version")).useDatabase { database ->
                database.openHelper.writableDatabase
            }
        }
    }

    @Test fun version20AddsUnconvertedStyleWithoutChangingAnyProfileValue() = kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.Default) {
        var expected = emptyMap<String, String?>()
        val name = historical(20, "v20-style") { db ->
            db.execSQL("INSERT INTO Profile (id, name, description, packLabel, prefsSnapshot, hasUnbuiltChanges, hideMissingPackWarning) " +
                "VALUES (7, 'Dark', 'kept description', 'Dark Pack', '{\"PRIMARY_SOURCE\":-17}', 1, 1)")
            db.query("SELECT * FROM Profile WHERE id = 7").use { cursor ->
                assertTrue(cursor.moveToFirst())
                expected = cursor.columnNames.associateWith { cursor.getString(cursor.getColumnIndexOrThrow(it)) }
            }
        }
        RenkinPackDatabase.open(context, name).useDatabase { database ->
            val sql = database.openHelper.writableDatabase
            assertEquals(22, sql.version)
            sql.query("SELECT * FROM Profile WHERE id = 7").use { cursor ->
                assertTrue(cursor.moveToFirst())
                expected.forEach { (column, value) -> assertEquals(column, value, cursor.getString(cursor.getColumnIndexOrThrow(column))) }
                assertTrue("profileStyle" in cursor.columnNames)
                assertEquals("", cursor.getString(cursor.getColumnIndexOrThrow("profileStyle")))
            }
            assertEquals("", database.profileDao().get(DEFAULT_PROFILE_ID)!!.profileStyle)
            val id = database.profileDao().insert(Profile(name = "New"))
            assertEquals("", database.profileDao().get(id)!!.profileStyle)
            sql.execSQL("INSERT INTO Profile (id, name) VALUES (9, 'SQL default')")
            assertEquals("", database.profileDao().get(9)!!.profileStyle)
        }
    }

    @Test fun version21AddsEmptyAppTargetsWithoutChangingAnyProfileValue() = kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.Default) {
        var expected = emptyMap<String, String?>()
        val name = historical(21, "v21-targets") { db ->
            db.execSQL("INSERT INTO Profile (id, name, description, packLabel, prefsSnapshot, hasUnbuiltChanges, hideMissingPackWarning, profileStyle) " +
                "VALUES (7, 'Dark', 'kept description', 'Dark Pack', 'prefs', 1, 1, 'typed document')")
            db.query("SELECT * FROM Profile WHERE id = 7").use { cursor ->
                assertTrue(cursor.moveToFirst())
                expected = cursor.columnNames.associateWith { cursor.getString(cursor.getColumnIndexOrThrow(it)) }
            }
        }
        RenkinPackDatabase.open(context, name).useDatabase { database ->
            val sql = database.openHelper.writableDatabase
            assertEquals(22, sql.version)
            sql.query("SELECT * FROM Profile WHERE id = 7").use { cursor ->
                assertTrue(cursor.moveToFirst())
                expected.forEach { (column, value) -> assertEquals(column, value, cursor.getString(cursor.getColumnIndexOrThrow(column))) }
                assertEquals("appTargets", cursor.columnNames.last())
                assertEquals("", cursor.getString(cursor.getColumnIndexOrThrow("appTargets")))
            }
            assertEquals(1, database.profileDao().updateAppTargets(7, "[]"))
            assertEquals("[]", database.profileDao().get(7)!!.appTargets)
        }
    }

    @Test fun currentProfileColumnSurvivesDaoCopiesAndDatabaseReopen() =kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.Default) {
        val name = historical(21, "v21-style") { db ->
            db.execSQL("INSERT INTO Profile (id, name, profileStyle) VALUES (7, 'Original', 'retained typed document')")
        }
        RenkinPackDatabase.open(context, name).useDatabase { database ->
            val profile = database.profileDao().get(7)!!
            database.profileDao().update(profile.copy(name = "Renamed", hideMissingPackWarning = true))
        }
        RenkinPackDatabase.open(context, name).useDatabase { database ->
            assertEquals("Renamed", database.profileDao().get(7)!!.name)
            assertEquals("retained typed document", database.profileDao().get(7)!!.profileStyle)
        }
    }

    @Test fun version19AddsEmptyInputAndPreservesEveryExistingColumn() {
        var expected = emptyMap<String, String?>()
        val name = historical(19, "v19-input") { db ->
            db.execSQL("INSERT INTO DbApplication " +
                "(packageName, activityName, isAdaptiveIcon, isXml, drawable, baseDrawable, modifierRecipe, " +
                "sourcePackName, sourceDrawableName, isCustomIcon, isFallbackIcon, calendarEnabled, calendarPrefix, profileId) " +
                "VALUES ('com.custom', 'Main', 1, 1, 'rendered', 'base', 'kept recipe', 'pack.source', 'alternate', 1, 1, 1, 'day_', 1)")
            db.query("SELECT * FROM DbApplication").use { cursor ->
                assertTrue(cursor.moveToFirst())
                expected = cursor.columnNames.associateWith { cursor.getString(cursor.getColumnIndexOrThrow(it)) }
            }
        }
        RenkinPackDatabase.open(context, name).useDatabase { database ->
            database.openHelper.writableDatabase.query("SELECT * FROM DbApplication").use { cursor ->
                assertTrue(cursor.moveToFirst())
                expected.forEach { (column, value) -> assertEquals(column, value, cursor.getString(cursor.getColumnIndexOrThrow(column))) }
                assertEquals("", cursor.getString(cursor.getColumnIndexOrThrow("modifierInput")))
                assertFalse(cursor.moveToNext())
            }
        }
    }

    @Test fun version20OpenClearsObsoleteInlineAndOversizedInputsWithoutTouchingArtworkOrRecipe() {
        val state = IconModifierInput(modifierInputTestOptions(), false, ModifierInputOrigin.UPLOAD)
        val valid = checkNotNull(StoredIconModifierInput.encode(state, StoredModifierArtwork("a".repeat(64), false, false)))
        val oversized = org.json.JSONObject(valid).put("unknown", "汉".repeat(23_000)).toString()
        val name = historical(20, "v20-inline") { db ->
            for ((key, input) in listOf("inline" to "{\"version\":1,\"input\":\"legacy\"}", "oversized" to oversized, "valid" to valid)) {
                db.execSQL("INSERT INTO DbApplication (packageName, activityName, isAdaptiveIcon, isXml, drawable, baseDrawable, modifierRecipe, modifierInput) " +
                    "VALUES (?, 'Main', 0, 0, 'rendered', 'base', 'kept recipe', ?)", arrayOf(key, input))
            }
        }
        RenkinPackDatabase.open(context, name).useDatabase { database ->
            database.openHelper.writableDatabase.query("SELECT packageName, drawable, baseDrawable, modifierRecipe, modifierInput FROM DbApplication").use { cursor ->
                var rows = 0
                while (cursor.moveToNext()) {
                    rows++
                    assertEquals("rendered", cursor.getString(1))
                    assertEquals("base", cursor.getString(2))
                    assertEquals("kept recipe", cursor.getString(3))
                    assertEquals(if (cursor.getString(0) == "valid") valid else "", cursor.getString(4))
                }
                assertEquals(3, rows)
            }
        }
    }

    @Test
    fun version18AddsEmptyRecipeAndPreservesEveryExistingIconColumn() {
        var expected = emptyMap<String, String?>()
        val name = historical(18, "v18-recipe") { db ->
            db.execSQL("INSERT INTO DbApplication " +
                "(packageName, activityName, isAdaptiveIcon, isXml, drawable, baseDrawable, " +
                "baseIsAdaptiveIcon, calendarEnabled, calendarPrefix, calendarPackName, " +
                "sourcePackName, sourceUrl, isCustomIcon, isFallbackIcon, profileId) " +
                "VALUES ('com.custom', 'Main', 1, 0, 'rendered', 'base', 1, 1, 'day_', " +
                "'pack.calendar', 'pack.source', 'https://example.com/icon', 1, 1, 1)")
            db.query("SELECT * FROM DbApplication").use { cursor ->
                assertTrue(cursor.moveToFirst())
                expected = cursor.columnNames.associateWith { cursor.getString(cursor.getColumnIndexOrThrow(it)) }
            }
        }
        RenkinPackDatabase.open(context, name).useDatabase { database ->
            database.openHelper.writableDatabase.query("SELECT * FROM DbApplication").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("", cursor.getString(cursor.getColumnIndexOrThrow("modifierRecipe")))
                expected.forEach { (column, value) ->
                    assertEquals(column, value, cursor.getString(cursor.getColumnIndexOrThrow(column)))
                }
                assertFalse(cursor.moveToNext())
            }
        }
    }

    @Test
    fun preV13RowsGetAnEmptyOnlineSourceReference() {
        val name = historical(12, "v12-source-url") { db ->
            db.execSQL(
                "INSERT INTO DbApplication " +
                    "(packageName, activityName, isAdaptiveIcon, isXml, drawable, profileId) " +
                    "VALUES ('com.plain', 'com.plain.Main', 0, 0, 'pixels', 1)"
            )
        }

        RenkinPackDatabase.open(context, name).useDatabase { database ->
            database.openHelper.writableDatabase.query(
                "SELECT sourceUrl, drawable FROM DbApplication WHERE packageName = 'com.plain'"
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("", cursor.getString(0))
                assertEquals("pixels", cursor.getString(1))
            }
        }
    }

    @Test
    fun version17CreatesModifierPresetTable() {
        RenkinPackDatabase.open(context, historical(17, "v17-modifier-presets")).useDatabase { database ->
            val migrated = database.openHelper.writableDatabase
            migrated.execSQL(
                "INSERT INTO ModifierPreset " +
                    "(name, payload, schemaVersion, createdAt, lastUsedAt) " +
                    "VALUES ('Reusable', 'v=1', 1, 10, 20)"
            )
            migrated.query("SELECT name, payload, schemaVersion FROM ModifierPreset").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("Reusable", cursor.getString(0))
                assertEquals("v=1", cursor.getString(1))
                assertEquals(1, cursor.getInt(2))
            }
        }
    }

    @Test
    fun aVersion16CarryingTheDroppedIconHashColumnStillOpens() {
        // A development build of 16 added DbApplication.iconHash before the fingerprint moved to
        // being derived. Devices that ran it must reach 17 with their icons intact, not crash on
        // the schema check.
        val name = historical(16, "v16-icon-hash") { db ->
            db.execSQL("ALTER TABLE DbApplication ADD COLUMN iconHash TEXT NOT NULL DEFAULT ''")
            db.execSQL(
                "INSERT INTO DbApplication " +
                    "(packageName, activityName, isAdaptiveIcon, isXml, drawable, profileId, " +
                    "iconHash) " +
                    "VALUES ('com.plain', 'com.plain.Main', 0, 0, 'pixels', 1, 'abc123')"
            )
        }

        RenkinPackDatabase.open(context, name).useDatabase { database ->
            database.openHelper.writableDatabase.query(
                "SELECT drawable, isFallbackIcon FROM DbApplication WHERE packageName = 'com.plain'"
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("pixels", cursor.getString(0))
                assertFalse(cursor.getInt(1) != 0)
            }
        }
    }

    @Test
    fun preV14RowsDefaultToNotFallbackStyled() {
        val name = historical(13, "v13-fallback") { db ->
            db.execSQL(
                "INSERT INTO DbApplication " +
                    "(packageName, activityName, isAdaptiveIcon, isXml, drawable, profileId) " +
                    "VALUES ('com.plain', 'com.plain.Main', 0, 0, 'pixels', 1)"
            )
        }

        RenkinPackDatabase.open(context, name).useDatabase { database ->
            database.openHelper.writableDatabase.query(
                "SELECT isFallbackIcon, drawable FROM DbApplication WHERE packageName = 'com.plain'"
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertFalse(cursor.getInt(0) != 0)
                assertEquals("pixels", cursor.getString(1))
            }
        }
    }

    @Test
    fun preV12RowsKeepAnExplicitHistoricalOriginMarker() {
        val name = historical(10, "v10-origin") { db ->
            db.execSQL(
                "INSERT INTO DbApplication " +
                    "(packageName, activityName, isAdaptiveIcon, isXml, drawable, profileId) " +
                    "VALUES ('com.old', 'com.old.Main', 0, 0, 'pixels', 1)"
            )
        }

        RenkinPackDatabase.open(context, name).useDatabase { database ->
            database.openHelper.writableDatabase.query(
                "SELECT isCustomIcon, isLegacyIcon, drawable, baseDrawable, " +
                    "baseIsAdaptiveIcon, baseIsXml FROM DbApplication " +
                    "WHERE packageName = 'com.old'"
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertFalse(cursor.getInt(0) != 0)
                assertTrue(cursor.getInt(1) != 0)
                assertEquals("pixels", cursor.getString(2))
                assertEquals("pixels", cursor.getString(3))
                assertFalse(cursor.getInt(4) != 0)
                assertFalse(cursor.getInt(5) != 0)
            }
        }
    }

    @Test
    fun explicitV11CustomRowsKeepTheirKnownOrigin() {
        val name = historical(11, "v11-custom") { db ->
            db.execSQL(
                "INSERT INTO DbApplication " +
                    "(packageName, activityName, isAdaptiveIcon, isXml, drawable, profileId, isCustomIcon) " +
                    "VALUES ('com.custom', 'com.custom.Main', 0, 0, 'pixels', 1, 1)"
            )
        }

        RenkinPackDatabase.open(context, name).useDatabase { database ->
            database.openHelper.writableDatabase.query(
                "SELECT isCustomIcon, isLegacyIcon FROM DbApplication " +
                    "WHERE packageName = 'com.custom'"
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertTrue(cursor.getInt(0) != 0)
                assertFalse(cursor.getInt(1) != 0)
            }
        }
    }

    @Test
    fun version1IconSurvivesWholeUpgradeAndMovesToDefaultProfile() {
        val name = historical(1, "v1-data") { db ->
            db.execSQL(
                "INSERT INTO DbApplication " +
                    "(packageName, activityName, isAdaptiveIcon, isXml, drawable) " +
                    "VALUES ('com.example', 'com.example.Main', 1, 0, 'pixels')"
            )
        }

        RenkinPackDatabase.open(context, name).useDatabase { database ->
            val migrated = database.openHelper.writableDatabase
            migrated.query(
                "SELECT drawable, profileId, sourceDrawableName FROM DbApplication " +
                    "WHERE packageName = 'com.example'"
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("pixels", cursor.getString(0))
                assertEquals(DEFAULT_PROFILE_ID, cursor.getLong(1))
                assertEquals("", cursor.getString(2))
            }
            migrated.query("SELECT name, packLabel FROM Profile WHERE id = 1").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("Renkin", cursor.getString(0))
                assertEquals("Renkin Pack", cursor.getString(1))
            }
        }
    }

    @Test
    fun version8ProfilesAndIconsKeepTheirOwnershipAndReceiveSafeDefaults() {
        val name = historical(8, "v8-data") { db ->
            db.execSQL(
                "INSERT INTO Profile (id, name, description, packLabel, prefsSnapshot) " +
                    "VALUES (7, 'Dark', 'kept', 'Dark Pack', '{\"x\":1}')"
            )
            db.execSQL(
                "INSERT INTO DbApplication " +
                    "(packageName, activityName, isAdaptiveIcon, isXml, drawable, profileId) " +
                    "VALUES ('com.dark', 'com.dark.Main', 0, 1, 'xml', 7)"
            )
        }

        RenkinPackDatabase.open(context, name).useDatabase { database ->
            val migrated = database.openHelper.writableDatabase
            migrated.query(
                "SELECT name, description, prefsSnapshot, hasUnbuiltChanges, " +
                    "hideMissingPackWarning FROM Profile WHERE id = 7"
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("Dark", cursor.getString(0))
                assertEquals("kept", cursor.getString(1))
                assertEquals("{\"x\":1}", cursor.getString(2))
                assertFalse(cursor.getInt(3) != 0)
                assertFalse(cursor.getInt(4) != 0)
            }
            migrated.query("SELECT profileId, drawable FROM DbApplication WHERE packageName = 'com.dark'")
                .use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals(7L, cursor.getLong(0))
                    assertEquals("xml", cursor.getString(1))
                }
        }
    }
}
