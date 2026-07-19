package dev.renkinProject.renkin.data.watch

import android.app.Application
import android.content.Context
import dev.renkinProject.renkin.data.createDatabaseFromExportedSchema
import dev.renkinProject.renkin.data.useDatabase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
class WatchMigrationTest {
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
        "watch-migration-$suffix".also { name ->
            databaseNames += name
            createDatabaseFromExportedSchema(
                context,
                checkNotNull(WatchDatabase::class.java.canonicalName),
                version,
                name,
                populate
            )
        }

    @Test
    fun everyReleasedSchemaMigratesToCurrent() {
        listOf(1, 2).forEach { version ->
            WatchDatabase.open(context, historical(version, "from-$version")).useDatabase { database ->
                database.openHelper.writableDatabase
            }
        }
    }

    @Test
    fun version1RuleAndBaselineSurviveWholeUpgrade() {
        val name = historical(1, "v1-data") { db ->
            db.execSQL(
                "INSERT INTO watch_rule " +
                    "(id, watchAllPacks, completed, createdAt, completedAt) VALUES (4, 0, 0, 10, NULL)"
            )
            db.execSQL(
                "INSERT INTO watch_rule_app (ruleId, packageName, activityName) " +
                    "VALUES (4, 'com.example', 'com.example.Main')"
            )
            db.execSQL("INSERT INTO watch_rule_pack (ruleId, iconPackPackage) VALUES (4, 'pack.example')")
            db.execSQL(
                "INSERT INTO watch_state " +
                    "(packageName, activityName, iconPackPackage, lastPackVersionCode, " +
                    "lastIconName, lastIconHash, lastCheckedAt) VALUES " +
                    "('com.example', 'com.example.Main', 'pack.example', 9, 'icon', 'hash', 20)"
            )
        }

        WatchDatabase.open(context, name).useDatabase { database ->
            val migrated = database.openHelper.writableDatabase
            migrated.query("SELECT profileId, completed FROM watch_rule WHERE id = 4").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(1L, cursor.getLong(0))
                assertEquals(0, cursor.getInt(1))
            }
            migrated.query(
                "SELECT ruleId, lastPackVersionCode, lastIconName, lastIconHash " +
                    "FROM watch_state WHERE packageName = 'com.example'"
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(4L, cursor.getLong(0))
                assertEquals(9L, cursor.getLong(1))
                assertEquals("icon", cursor.getString(2))
                assertEquals("hash", cursor.getString(3))
            }
        }
    }
}
