package dev.renkinProject.renkin.data

import android.app.Application
import android.content.Context
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
        listOf(1, 2, 3, 4, 5, 7, 8, 9, 10, 11, 12, 13).forEach { version ->
            RenkinPackDatabase.open(context, historical(version, "from-$version")).useDatabase { database ->
                database.openHelper.writableDatabase
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
