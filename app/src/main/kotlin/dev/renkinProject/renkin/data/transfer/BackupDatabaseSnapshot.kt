package dev.renkinProject.renkin.data.transfer

import android.database.Cursor
import android.util.Base64
import android.util.JsonReader
import android.util.JsonToken
import android.util.JsonWriter
import androidx.sqlite.db.SupportSQLiteDatabase
import dev.renkinProject.renkin.data.StorageRecoverySchemaException
import dev.renkinProject.renkin.data.StorageRecoverySnapshotException
import dev.renkinProject.renkin.icon.creator.StoredIconModifierInput
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
                val omittedColumns = supportedOmittedColumns(snapshotVersion, db.version, expected)
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
                    checkColumns(db, table, columns, omittedColumns[table].orEmpty())
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

    suspend fun write(db: SupportSQLiteDatabase, file: File) = writeTables(db, file, tables(db))

    // Diagnostic-only copy. Compatibility and restore still require the complete table set.
    suspend fun writeProfileDiagnostics(db: SupportSQLiteDatabase, file: File) =
        writeTables(db, file, listOf("Profile"))

    private suspend fun writeTables(db: SupportSQLiteDatabase, file: File, tableNames: List<String>) {
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
            for (table in tableNames) {
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
                val omittedColumns = supportedOmittedColumns(snapshotVersion, db.version, expected)
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
                    checkColumns(db, table, columns, omittedColumns[table].orEmpty())
                    // Only audited additions can be omitted. SQL defaults retain old artwork
                    // and leave pre-v21 profiles unconverted for the later shared bootstrap.
                    val sql = "INSERT INTO ${quoted(table)} (${columns.joinToString(",", transform = ::quoted)}) " +
                        "VALUES (${columns.joinToString(",") { "?" }})"
                    requireField(reader, "rows")
                    reader.beginArray()
                    while (reader.hasNext()) {
                        currentCoroutineContext().ensureActive()
                        reader.beginArray()
                        val values = Array<Any?>(columns.size) { column ->
                            reader.beginArray()
                            val value = when (reader.nextInt()) {
                                Cursor.FIELD_TYPE_NULL -> { reader.nextNull(); null }
                                Cursor.FIELD_TYPE_INTEGER -> reader.nextLong()
                                Cursor.FIELD_TYPE_FLOAT -> reader.nextDouble()
                                Cursor.FIELD_TYPE_STRING -> reader.nextString().let { text ->
                                    if (table == "DbApplication" && columns[column] == "modifierInput") {
                                        // Obsolete inline v20 metadata must not reintroduce huge rows.
                                        StoredIconModifierInput.normalize(text).orEmpty()
                                    } else text
                                }
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

    private fun supportedOmittedColumns(snapshotVersion: Int, currentVersion: Int, tables: Set<String>): Map<String, List<String>> {
        if (snapshotVersion == currentVersion) return emptyMap()
        if (snapshotVersion > currentVersion) {
            throw StorageRecoverySchemaException("Recovery database schema has changed")
        }
        // Compose only audited, table-specific ADD COLUMN migrations. A prefix/default
        // match cannot prove compatibility with migrations that backfill or reinterpret data.
        val auditedAdditions = mapOf(
            18 to mapOf("DbApplication" to listOf("modifierRecipe")),
            19 to mapOf("DbApplication" to listOf("modifierInput")),
            20 to mapOf("Profile" to listOf("profileStyle"))
        )
        val omitted = mutableMapOf<String, List<String>>()
        for (version in snapshotVersion until currentVersion) {
            val additions = auditedAdditions[version]
                ?: throw StorageRecoverySchemaException("Recovery database schema has changed")
            for ((table, columns) in additions) {
                if (table !in tables) throw StorageRecoverySchemaException("Recovery database schema has changed")
                omitted[table] = omitted[table].orEmpty() + columns
            }
        }
        return omitted
    }

    private fun checkColumns(
        db: SupportSQLiteDatabase,
        table: String,
        columns: List<String>,
        omittedColumns: List<String>
    ) {
        db.query("SELECT * FROM ${quoted(table)} LIMIT 0").use { cursor ->
            val current = cursor.columnNames.toList()
            val expected = if (omittedColumns.isNotEmpty()) {
                if (current.takeLast(omittedColumns.size) != omittedColumns) {
                    throw StorageRecoverySchemaException("Recovery columns have changed")
                }
                current.dropLast(omittedColumns.size)
            } else current
            if (columns != expected) throw StorageRecoverySchemaException("Recovery columns have changed")
        }
        if (omittedColumns.isNotEmpty()) {
            val validDefaults = mutableSetOf<String>()
            db.query("PRAGMA table_info(${quoted(table)})").use { cursor ->
                while (cursor.moveToNext()) {
                    if (cursor.getString(1) in omittedColumns && cursor.getString(2).equals("TEXT", ignoreCase = true) &&
                        cursor.getInt(3) == 1 && cursor.getString(4) == "''") validDefaults.add(cursor.getString(1))
                }
            }
            if (validDefaults != omittedColumns.toSet()) throw StorageRecoverySchemaException("Recovery metadata defaults have changed")
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
