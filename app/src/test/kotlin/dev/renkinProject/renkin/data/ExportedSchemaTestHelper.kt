package dev.renkinProject.renkin.data

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.room.RoomDatabase
import org.json.JSONObject
import java.io.File

/** Creates a real SQLite file from one of Room's checked-in exported JSON schemas. */
internal fun createDatabaseFromExportedSchema(
    context: Context,
    schemaName: String,
    version: Int,
    databaseName: String,
    populate: (SupportSQLiteDatabase) -> Unit = {}
) {
    val roots = listOf(File("app/schemas"), File("schemas"))
    val schemaFile = roots.asSequence()
        .map { File(it, "$schemaName/$version.json") }
        .firstOrNull(File::isFile)
        ?: error("Exported Room schema not found for $schemaName version $version")
    val schema = JSONObject(schemaFile.readText()).getJSONObject("database")

    context.deleteDatabase(databaseName)
    val helper = FrameworkSQLiteOpenHelperFactory().create(
        SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(databaseName)
            .callback(object : SupportSQLiteOpenHelper.Callback(version) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    schema.getJSONArray("entities").forEachObject { entity ->
                        val tableName = entity.getString("tableName")
                        db.execSQL(entity.getString("createSql").replace("${'$'}{TABLE_NAME}", tableName))
                    }
                    schema.getJSONArray("views").forEachObject { view ->
                        db.execSQL(view.getString("createSql"))
                    }
                    schema.getJSONArray("setupQueries").forEachString(db::execSQL)
                    populate(db)
                }

                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
            })
            .build()
    )
    helper.writableDatabase
    helper.close()
}

private inline fun org.json.JSONArray.forEachObject(action: (JSONObject) -> Unit) {
    for (index in 0 until length()) action(getJSONObject(index))
}

private inline fun org.json.JSONArray.forEachString(action: (String) -> Unit) {
    for (index in 0 until length()) action(getString(index))
}

internal inline fun <T : RoomDatabase, R> T.useDatabase(block: (T) -> R): R =
    try {
        block(this)
    } finally {
        close()
    }
