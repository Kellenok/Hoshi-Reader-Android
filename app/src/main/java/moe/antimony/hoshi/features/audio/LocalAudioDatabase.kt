package moe.antimony.hoshi.features.audio

import android.content.ContentResolver
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.util.Locale

/** Read-only android.db operations shared by the private copy and a linked SAF file. */
internal interface LocalAudioDatabase {
    fun hasLocalAudioTables(): Boolean

    /** Rows whose expression equals [term] or, when not blank, whose reading equals [reading]. */
    fun findEntries(term: String, reading: String): List<LocalAudioEntry>

    /** Distinct sources that have at least one MP3, Opus, or Ogg file. */
    fun audioSources(): List<String>

    fun loadAudio(source: String, file: String): ByteArray?
}

internal class PlatformLocalAudioDatabase private constructor(
    private val database: SQLiteDatabase,
) : LocalAudioDatabase, Closeable {
    override fun hasLocalAudioTables(): Boolean =
        database.rawQuery(
            "SELECT name FROM sqlite_master WHERE type = 'table' AND name IN ('entries', 'android')",
            null,
        ).use { it.count == 2 }

    override fun findEntries(term: String, reading: String): List<LocalAudioEntry> {
        val (selection, args) = if (reading.isBlank()) {
            "expression = ?" to arrayOf(term)
        } else {
            "(expression = ? OR reading = ?)" to arrayOf(term, reading)
        }
        return database.rawQuery(
            "SELECT source, expression, reading, file, display FROM entries WHERE $selection",
            args,
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        LocalAudioEntry(
                            source = cursor.getString(0).orEmpty(),
                            expression = cursor.getString(1).orEmpty(),
                            reading = cursor.getString(2),
                            file = cursor.getString(3).orEmpty(),
                            display = cursor.getString(4).orEmpty(),
                        ),
                    )
                }
            }
        }
    }

    override fun audioSources(): List<String> =
        database.rawQuery(
            "SELECT DISTINCT source FROM entries WHERE lower(file) LIKE ? OR lower(file) LIKE ? OR lower(file) LIKE ?",
            arrayOf("%.mp3", "%.opus", "%.ogg"),
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) cursor.getString(0)?.let(::add)
            }
        }

    override fun loadAudio(source: String, file: String): ByteArray? =
        database.rawQuery(
            "SELECT data FROM android WHERE source = ? AND file = ? LIMIT 1",
            arrayOf(source, file),
        ).use { cursor ->
            if (!cursor.moveToFirst() || cursor.isNull(0)) null else cursor.getBlob(0)
        }

    override fun close() {
        database.close()
    }

    companion object {
        fun open(file: File): PlatformLocalAudioDatabase =
            PlatformLocalAudioDatabase(
                SQLiteDatabase.openDatabase(
                    file.absolutePath,
                    null,
                    SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS,
                ),
            )
    }
}

/**
 * Reads a user-selected android.db in place through its SAF file descriptor with
 * positional reads, so the database is neither copied nor reopened by path.
 */
internal class LinkedLocalAudioDatabase private constructor(
    private val input: ParcelFileDescriptor.AutoCloseInputStream,
    private val reader: LocalAudioSqliteReader,
) : LocalAudioDatabase by reader, Closeable {
    override fun close() {
        input.close()
    }

    companion object {
        fun open(contentResolver: ContentResolver, uri: Uri): LinkedLocalAudioDatabase {
            val descriptor = contentResolver.openFileDescriptor(uri, "r")
                ?: throw IOException("Unable to open local audio database.")
            val input = ParcelFileDescriptor.AutoCloseInputStream(descriptor)
            return try {
                LinkedLocalAudioDatabase(
                    input,
                    LocalAudioSqliteReader(ReadOnlySqliteFile(FileChannelPageSource(input.channel))),
                )
            } catch (error: Throwable) {
                input.close()
                throw error
            }
        }
    }
}

/**
 * Answers the local audio queries with the same results as [PlatformLocalAudioDatabase],
 * using the file's own b-tree indexes when present and table scans otherwise.
 */
internal class LocalAudioSqliteReader(private val file: ReadOnlySqliteFile) : LocalAudioDatabase {
    private val entries: TableSchema?
    private val android: TableSchema?

    init {
        val objects = mutableListOf<SchemaObject>()
        file.scanTable(1) { _, record ->
            val rootPage = (record.getOrNull(3) as? SqliteValue.Integer)?.value?.toInt() ?: 0
            objects += SchemaObject(
                type = record.getOrNull(0)?.textOrNull().orEmpty(),
                name = record.getOrNull(1)?.textOrNull().orEmpty(),
                tableName = record.getOrNull(2)?.textOrNull().orEmpty(),
                rootPage = rootPage,
                sql = record.getOrNull(4)?.textOrNull().orEmpty(),
            )
        }
        entries = TableSchema.find(objects, "entries")
        android = TableSchema.find(objects, "android")
    }

    @Synchronized
    override fun hasLocalAudioTables(): Boolean = entries != null && android != null

    @Synchronized
    override fun findEntries(term: String, reading: String): List<LocalAudioEntry> {
        val table = entries ?: throw SqliteFormatException("Missing entries table.")
        val expression = table.column("expression")
        val readingColumn = table.column("reading")
        val matches = sortedMapOf<Long, List<SqliteValue>>()
        fun collect(column: Int?, value: String) {
            val index = table.indexStartingWith(column)
            if (index != null && column != null) {
                val rowids = mutableListOf<Long>()
                file.scanIndexEqual(index.rootPage, file.encode(value)) { record ->
                    (record.lastOrNull() as? SqliteValue.Integer)?.let { rowids += it.value }
                    true
                }
                rowids.forEach { rowid -> file.findRow(table.rootPage, rowid)?.let { matches[rowid] = it } }
            } else {
                file.scanTable(table.rootPage) { rowid, record ->
                    if (table.text(record, rowid, column) == value) matches[rowid] = record
                }
            }
        }
        collect(expression, term)
        if (reading.isNotBlank()) collect(readingColumn, reading)
        return matches.map { (rowid, record) ->
            LocalAudioEntry(
                source = table.text(record, rowid, table.column("source")).orEmpty(),
                expression = table.text(record, rowid, expression).orEmpty(),
                reading = table.text(record, rowid, readingColumn),
                file = table.text(record, rowid, table.column("file")).orEmpty(),
                display = table.text(record, rowid, table.column("display")).orEmpty(),
            )
        }
    }

    @Synchronized
    override fun audioSources(): List<String> {
        val table = entries ?: throw SqliteFormatException("Missing entries table.")
        val sourceColumn = table.column("source")
        val fileColumn = table.column("file")
        val sources = linkedSetOf<String>()
        file.scanTable(table.rootPage) { rowid, record ->
            val name = table.text(record, rowid, fileColumn)?.lowercase(Locale.ROOT) ?: return@scanTable
            if (AudioExtensions.any(name::endsWith)) {
                table.text(record, rowid, sourceColumn)?.let(sources::add)
            }
        }
        return sources.toList()
    }

    @Synchronized
    override fun loadAudio(source: String, file: String): ByteArray? {
        val table = android ?: throw SqliteFormatException("Missing android table.")
        val fileColumn = table.column("file")
        val sourceColumn = table.column("source")
        val dataColumn = table.column("data") ?: return null
        val index = table.indexStartingWith(fileColumn)
        var match: Pair<Long, List<SqliteValue>>? = null
        if (index != null) {
            val rowids = mutableListOf<Long>()
            this.file.scanIndexEqual(index.rootPage, this.file.encode(file)) { record ->
                (record.lastOrNull() as? SqliteValue.Integer)?.let { rowids += it.value }
                true
            }
            for (rowid in rowids) {
                val record = this.file.findRow(table.rootPage, rowid) ?: continue
                if (table.text(record, rowid, sourceColumn) == source) {
                    match = rowid to record
                    break
                }
            }
        } else {
            this.file.scanTable(table.rootPage) { rowid, record ->
                if (match == null && table.text(record, rowid, fileColumn) == file &&
                    table.text(record, rowid, sourceColumn) == source
                ) {
                    match = rowid to record
                }
            }
        }
        val (_, record) = match ?: return null
        return (record.getOrNull(dataColumn) as? SqliteValue.Blob)?.bytes
    }

    private class SchemaObject(
        val type: String,
        val name: String,
        val tableName: String,
        val rootPage: Int,
        val sql: String,
    )

    private class IndexSchema(val rootPage: Int, val firstColumn: String)

    private class TableSchema(
        val rootPage: Int,
        private val columns: List<String>,
        private val rowidAlias: Int?,
        private val indexes: List<IndexSchema>,
    ) {
        fun column(name: String): Int? = columns.indexOfFirst { it.equals(name, ignoreCase = true) }.takeIf { it >= 0 }

        fun indexStartingWith(column: Int?): IndexSchema? {
            val name = column?.let(columns::get) ?: return null
            return indexes.firstOrNull { it.firstColumn.equals(name, ignoreCase = true) }
        }

        /** Matches SQLite's `column = ?` for text: only stored TEXT values compare equal. */
        fun text(record: List<SqliteValue>, rowid: Long, column: Int?): String? {
            if (column == null) return null
            if (column == rowidAlias) return rowid.toString()
            // Columns added after a row was written read as NULL.
            return record.getOrNull(column)?.textOrNull()
        }

        companion object {
            fun find(objects: List<SchemaObject>, name: String): TableSchema? {
                val table = objects.firstOrNull { it.type == "table" && it.name.equals(name, ignoreCase = true) }
                    ?: return null
                val definitions = columnDefinitions(table.sql)
                val columns = definitions.map { it.first }
                val rowidAlias = definitions.indexOfFirst { (_, definition) ->
                    definition.contains(Regex("""^\S+\s+integer\s+primary\s+key\b""", RegexOption.IGNORE_CASE)) &&
                        !definition.contains(Regex("""\bdesc\b""", RegexOption.IGNORE_CASE))
                }.takeIf { it >= 0 }
                val indexes = objects
                    .filter { it.type == "index" && it.tableName.equals(table.name, ignoreCase = true) && it.rootPage > 0 }
                    .mapNotNull { index -> usableIndex(index) }
                return TableSchema(table.rootPage, columns, rowidAlias, indexes)
            }

            /** Plain BINARY-collated indexes only; partial or collated indexes fall back to scans. */
            private fun usableIndex(index: SchemaObject): IndexSchema? {
                val sql = index.sql
                if (sql.isBlank() || Regex("""\b(collate|where)\b""", RegexOption.IGNORE_CASE).containsMatchIn(sql)) {
                    return null
                }
                val body = sql.substringAfter('(', "").substringBeforeLast(')', "")
                val first = body.split(',').firstOrNull()?.trim()?.split(Regex("""\s+"""))?.firstOrNull()
                    ?.let(::unquote)
                    ?: return null
                return IndexSchema(index.rootPage, first)
            }

            private fun columnDefinitions(sql: String): List<Pair<String, String>> {
                val body = sql.substringAfter('(', "").substringBeforeLast(')', "")
                val parts = mutableListOf<String>()
                var depth = 0
                var start = 0
                body.forEachIndexed { index, char ->
                    when (char) {
                        '(' -> depth++
                        ')' -> depth--
                        ',' -> if (depth == 0) {
                            parts += body.substring(start, index)
                            start = index + 1
                        }
                    }
                }
                parts += body.substring(start)
                return parts.map(String::trim)
                    .filter { it.isNotEmpty() && !TableConstraint.containsMatchIn(it) }
                    .map { definition -> unquote(definition.split(Regex("""\s+""")).first()) to definition }
            }

            private fun unquote(name: String): String = name.trim('"', '`', '[', ']', '\'')

            private val TableConstraint = Regex("""^(constraint|primary|unique|check|foreign)\b""", RegexOption.IGNORE_CASE)
        }
    }

    private companion object {
        val AudioExtensions = listOf(".mp3", ".opus", ".ogg")
    }
}
