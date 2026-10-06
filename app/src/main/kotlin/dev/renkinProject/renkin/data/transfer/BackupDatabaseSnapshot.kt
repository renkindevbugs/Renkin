package dev.renkinProject.renkin.data.transfer

import android.database.Cursor
import android.util.Base64
import android.util.JsonReader
import android.util.JsonToken
import android.util.JsonWriter
import androidx.sqlite.db.SupportSQLiteDatabase
import dev.renkinProject.renkin.data.StorageRecoverySchemaException
import dev.renkinProject.renkin.data.StorageRecoverySnapshotException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStreamWriter
import java.io.OutputStream

internal object BackupDatabaseSnapshot {
    suspend fun checkCompatibility(db: SupportSQLiteDatabase, file: File) {
        file.reader(Charsets.UTF_8).use { input ->
            JsonReader(input).use { reader ->
                reader.beginObject()
                requireField(reader, "version")
                val snapshotVersion = reader.nextInt()
                requireField(reader, "tables")
                val expected = tables(db).toSet()
                val addsModifierRecipe = addsModifierRecipe(snapshotVersion, db.version, expected)
                val found = mutableSetOf<String>()
                reader.beginArray()
                while (reader.hasNext()) {
                    currentCoroutineContext().ensureActive()
                    reader.beginObject()
                    requireField(reader, "name")
                    val table = reader.nextString()
                    if (table !in expected || !found.add(table)) throw StorageRecoverySnapshotException("Invalid recovery table")
                    requireField(reader, "columns")
                    val columns = mutableListOf<String>()
                    reader.beginArray()
                    while (reader.hasNext()) columns.add(reader.nextString())
                    reader.endArray()
                    checkColumns(db, table, columns, addsModifierRecipe)
                    requireField(reader, "rows")
                    reader.skipValue()
                    reader.endObject()
                }
                reader.endArray()
                reader.endObject()
                if (found != expected || reader.peek() != JsonToken.END_DOCUMENT) throw StorageRecoverySnapshotException("Incomplete recovery snapshot")
            }
        }
    }

    suspend fun write(db: SupportSQLiteDatabase, file: File) {
        FileOutputStream(file).use { output ->
            val snapshotContext = currentCoroutineContext()
            val checked = object : OutputStream() {
                private var bytesUntilSpaceCheck = 0L
                override fun write(value: Int) = write(byteArrayOf(value.toByte()), 0, 1)
                override fun write(buffer: ByteArray, offset: Int, count: Int) {
                    snapshotContext.ensureActive()
                    if (bytesUntilSpaceCheck <= 0) {
                        ensureBackupSpace(file.parentFile!!, 1024 * 1024L)
                        bytesUntilSpaceCheck = 1024 * 1024L
                    }
                    output.write(buffer, offset, count)
                    bytesUntilSpaceCheck -= count
                }
            }
            // Android JsonWriter emits strings character by character. Buffer before UTF-8
            // encoding so large icon payloads do not cause millions of encoder calls.
            val writer = JsonWriter(OutputStreamWriter(checked, Charsets.UTF_8).buffered())
            writer.beginObject().name("version").value(db.version.toLong()).name("tables").beginArray()
            for (table in tables(db)) {
                currentCoroutineContext().ensureActive()
                db.query("SELECT * FROM ${quoted(table)}").use { cursor ->
                    writer.beginObject().name("name").value(table).name("columns").beginArray()
                    cursor.columnNames.forEach { writer.value(it) }
                    writer.endArray().name("rows").beginArray()
                    while (cursor.moveToNext()) {
                        currentCoroutineContext().ensureActive()
                        writer.beginArray()
                        for (column in 0 until cursor.columnCount) {
                            writer.beginArray().value(cursor.getType(column).toLong())
                            when (cursor.getType(column)) {
                                Cursor.FIELD_TYPE_NULL -> writer.nullValue()
                                Cursor.FIELD_TYPE_INTEGER -> writer.value(cursor.getLong(column))
                                Cursor.FIELD_TYPE_FLOAT -> writer.value(cursor.getDouble(column))
                                Cursor.FIELD_TYPE_STRING -> writer.value(cursor.getString(column))
                                Cursor.FIELD_TYPE_BLOB -> writer.value(Base64.encodeToString(cursor.getBlob(column), Base64.NO_WRAP))
                            }
                            writer.endArray()
                        }
                        writer.endArray()
                    }
                    writer.endArray().endObject()
                }
            }
            writer.endArray().endObject()
            writer.flush()
            output.fd.sync()
        }
    }

    // The caller holds a Room transaction. This internal format preserves every column,
    // row identity and sqlite_sequence; it never passes through portable-backup normalization.
    suspend fun restore(db: SupportSQLiteDatabase, file: File) {
        file.reader(Charsets.UTF_8).use { input ->
            JsonReader(input).use { reader ->
                reader.beginObject()
                requireField(reader, "version")
                val snapshotVersion = reader.nextInt()
                requireField(reader, "tables")
                val expected = tables(db).toSet()
                val addsModifierRecipe = addsModifierRecipe(snapshotVersion, db.version, expected)
                db.execSQL("PRAGMA defer_foreign_keys = ON")
                expected.forEach { db.execSQL("DELETE FROM ${quoted(it)}") }
                val restored = mutableSetOf<String>()
                reader.beginArray()
                while (reader.hasNext()) {
                    currentCoroutineContext().ensureActive()
                    reader.beginObject()
                    requireField(reader, "name")
                    val table = reader.nextString()
                    if (table !in expected || !restored.add(table)) throw StorageRecoverySnapshotException("Invalid recovery table")
                    if (table == "sqlite_sequence") db.execSQL("DELETE FROM sqlite_sequence")
                    requireField(reader, "columns")
                    val columns = mutableListOf<String>()
                    reader.beginArray()
                    while (reader.hasNext()) columns.add(reader.nextString())
                    reader.endArray()
                    checkColumns(db, table, columns, addsModifierRecipe)
                    // A v18 snapshot omits only modifierRecipe. The v19 SQL default restores
                    // that metadata as empty while preserving all previous values and IDs.
                    val sql = "INSERT INTO ${quoted(table)} (${columns.joinToString(",", transform = ::quoted)}) " +
                        "VALUES (${columns.joinToString(",") { "?" }})"
                    requireField(reader, "rows")
                    reader.beginArray()
                    while (reader.hasNext()) {
                        currentCoroutineContext().ensureActive()
                        reader.beginArray()
                        val values = Array<Any?>(columns.size) {
                            reader.beginArray()
                            val value = when (reader.nextInt()) {
                                Cursor.FIELD_TYPE_NULL -> { reader.nextNull(); null }
                                Cursor.FIELD_TYPE_INTEGER -> reader.nextLong()
                                Cursor.FIELD_TYPE_FLOAT -> reader.nextDouble()
                                Cursor.FIELD_TYPE_STRING -> reader.nextString()
                                Cursor.FIELD_TYPE_BLOB -> Base64.decode(reader.nextString(), Base64.NO_WRAP)
                                else -> throw StorageRecoverySnapshotException("Invalid recovery value")
                            }
                            reader.endArray()
                            value
                        }
                        reader.endArray()
                        db.execSQL(sql, values)
                    }
                    reader.endArray()
                    reader.endObject()
                }
                reader.endArray()
                reader.endObject()
                if (restored != expected || reader.peek() != JsonToken.END_DOCUMENT) throw StorageRecoverySnapshotException("Incomplete recovery snapshot")
            }
        }
    }

    private fun addsModifierRecipe(snapshotVersion: Int, currentVersion: Int, tables: Set<String>): Boolean {
        if (snapshotVersion == currentVersion) return false
        if (snapshotVersion == 18 && currentVersion == 19 && "DbApplication" in tables) return true
        throw StorageRecoverySchemaException("Recovery database schema has changed")
    }

    private fun checkColumns(
        db: SupportSQLiteDatabase,
        table: String,
        columns: List<String>,
        addsModifierRecipe: Boolean
    ) {
        db.query("SELECT * FROM ${quoted(table)} LIMIT 0").use { cursor ->
            val current = cursor.columnNames.toList()
            val expected = if (addsModifierRecipe && table == "DbApplication") {
                if (current.lastOrNull() != "modifierRecipe") {
                    throw StorageRecoverySchemaException("Recovery columns have changed")
                }
                current.dropLast(1)
            } else current
            if (columns != expected) throw StorageRecoverySchemaException("Recovery columns have changed")
        }
    }

    private fun tables(db: SupportSQLiteDatabase): List<String> = db.query(
        "SELECT name FROM sqlite_master WHERE type = 'table' " +
            "AND (name NOT LIKE 'sqlite_%' OR name = 'sqlite_sequence') " +
            "AND name NOT IN ('android_metadata', 'room_master_table') ORDER BY name"
    ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.getString(0)) } }

    fun flush(db: SupportSQLiteDatabase) {
        db.query("PRAGMA wal_checkpoint(FULL)").use { cursor ->
            if (!cursor.moveToFirst() || cursor.getInt(0) != 0) throw IOException("Cannot flush restored database")
        }
    }

    private fun quoted(name: String): String = "\"${name.replace("\"", "\"\"")}\""

    private fun requireField(reader: JsonReader, expected: String) {
        if (reader.nextName() != expected) throw StorageRecoverySnapshotException("Invalid recovery field")
    }
}
