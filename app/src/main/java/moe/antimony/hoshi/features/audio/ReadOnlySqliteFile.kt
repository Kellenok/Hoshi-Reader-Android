package moe.antimony.hoshi.features.audio

import java.io.EOFException
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.Charset

/** Positional reads into a SQLite database file, such as pread() on a SAF descriptor. */
internal fun interface SqlitePageSource {
    fun read(position: Long, target: ByteArray, offset: Int, length: Int)
}

internal class FileChannelPageSource(private val channel: FileChannel) : SqlitePageSource {
    override fun read(position: Long, target: ByteArray, offset: Int, length: Int) {
        val buffer = ByteBuffer.wrap(target, offset, length)
        var at = position
        while (buffer.hasRemaining()) {
            val read = channel.read(buffer, at)
            if (read < 0) throw EOFException("Unexpected end of SQLite file.")
            at += read
        }
    }
}

internal class SqliteFormatException(message: String) : IOException(message)

internal sealed interface SqliteValue {
    data object Null : SqliteValue

    data class Integer(val value: Long) : SqliteValue

    data class Real(val value: Double) : SqliteValue

    class Text(val bytes: ByteArray, private val charset: Charset) : SqliteValue {
        fun decode(): String = String(bytes, charset)
    }

    class Blob(val bytes: ByteArray) : SqliteValue
}

internal fun SqliteValue.textOrNull(): String? = (this as? SqliteValue.Text)?.decode()

/**
 * Minimal read-only reader for the SQLite 3 file format: table and index b-trees,
 * records, and overflow chains. It never writes and ignores journals and WAL files,
 * which matches opening the main database file as immutable.
 *
 * See https://www.sqlite.org/fileformat2.html.
 */
internal class ReadOnlySqliteFile(
    private val source: SqlitePageSource,
    cachedPages: Int = DefaultCachedPages,
) {
    val pageSize: Int
    private val usableSize: Int
    val charset: Charset
    private val pageCache = object : LinkedHashMap<Int, ByteArray>(cachedPages, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, ByteArray>): Boolean = size > cachedPages
    }

    init {
        val header = ByteArray(HeaderSize)
        source.read(0, header, 0, HeaderSize)
        if (!header.copyOfRange(0, Magic.size).contentEquals(Magic)) {
            throw SqliteFormatException("Not a SQLite 3 database.")
        }
        val rawPageSize = header.u16(16)
        pageSize = if (rawPageSize == 1) 65536 else rawPageSize
        if (pageSize < 512 || pageSize > 65536 || pageSize and (pageSize - 1) != 0) {
            throw SqliteFormatException("Invalid SQLite page size $rawPageSize.")
        }
        usableSize = pageSize - header.u8(20)
        if (usableSize < 480) throw SqliteFormatException("Invalid SQLite reserved space.")
        charset = when (header.u32(56)) {
            0L, 1L -> Charsets.UTF_8
            2L -> Charsets.UTF_16LE
            3L -> Charsets.UTF_16BE
            else -> throw SqliteFormatException("Unsupported SQLite text encoding.")
        }
    }

    fun encode(text: String): ByteArray = text.toByteArray(charset)

    /** Visits every row of the table b-tree rooted at [rootPage] in rowid order. */
    fun scanTable(rootPage: Int, visit: (rowid: Long, record: List<SqliteValue>) -> Unit) {
        scanTablePage(rootPage, depth = 0, visit)
    }

    /** Returns the record for [rowid] in the table b-tree rooted at [rootPage]. */
    fun findRow(rootPage: Int, rowid: Long): List<SqliteValue>? {
        var pageNumber = rootPage
        repeat(MaxTreeDepth) {
            val page = page(pageNumber)
            when (page.type) {
                TableInterior -> {
                    pageNumber = page.rightMostPointer
                    for (cell in 0 until page.cellCount) {
                        val offset = page.cellOffset(cell)
                        val key = page.data.varint(offset + 4)
                        if (rowid <= key.value) {
                            pageNumber = page.data.u32(offset).toInt()
                            break
                        }
                    }
                }
                TableLeaf -> {
                    for (cell in 0 until page.cellCount) {
                        val leaf = tableLeafCell(page, page.cellOffset(cell))
                        if (leaf.rowid == rowid) return decodeRecord(leaf.payload())
                        if (leaf.rowid > rowid) return null
                    }
                    return null
                }
                else -> throw SqliteFormatException("Expected a table b-tree page.")
            }
        }
        throw SqliteFormatException("SQLite table b-tree is too deep.")
    }

    /**
     * Visits, in index order, every entry of the index b-tree rooted at [rootPage] whose
     * first column equals [key] under BINARY collation. Return false to stop early.
     */
    fun scanIndexEqual(rootPage: Int, key: ByteArray, visit: (record: List<SqliteValue>) -> Boolean) {
        scanIndexPage(rootPage, key, depth = 0, visit)
    }

    private fun scanTablePage(pageNumber: Int, depth: Int, visit: (Long, List<SqliteValue>) -> Unit) {
        if (depth > MaxTreeDepth) throw SqliteFormatException("SQLite table b-tree is too deep.")
        val page = page(pageNumber)
        when (page.type) {
            TableInterior -> {
                for (cell in 0 until page.cellCount) {
                    scanTablePage(page.data.u32(page.cellOffset(cell)).toInt(), depth + 1, visit)
                }
                scanTablePage(page.rightMostPointer, depth + 1, visit)
            }
            TableLeaf -> {
                for (cell in 0 until page.cellCount) {
                    val leaf = tableLeafCell(page, page.cellOffset(cell))
                    visit(leaf.rowid, decodeRecord(leaf.payload()))
                }
            }
            else -> throw SqliteFormatException("Expected a table b-tree page.")
        }
    }

    /** Returns false once an entry greater than [key] is seen, or the visitor asks to stop. */
    private fun scanIndexPage(
        pageNumber: Int,
        key: ByteArray,
        depth: Int,
        visit: (List<SqliteValue>) -> Boolean,
    ): Boolean {
        if (depth > MaxTreeDepth) throw SqliteFormatException("SQLite index b-tree is too deep.")
        val page = page(pageNumber)
        val interior = when (page.type) {
            IndexInterior -> true
            IndexLeaf -> false
            else -> throw SqliteFormatException("Expected an index b-tree page.")
        }
        for (cell in 0 until page.cellCount) {
            val offset = page.cellOffset(cell)
            val payloadStart = if (interior) offset + 4 else offset
            val record = decodeRecord(indexPayload(page, payloadStart))
            val comparison = compareFirstColumn(record, key)
            // An interior cell's left subtree holds entries ordered before the cell itself.
            if (interior && comparison >= 0) {
                if (!scanIndexPage(page.data.u32(offset).toInt(), key, depth + 1, visit)) return false
            }
            if (comparison > 0) return false
            if (comparison == 0 && !visit(record)) return false
        }
        return if (interior) scanIndexPage(page.rightMostPointer, key, depth + 1, visit) else true
    }

    /** SQLite sort order: NULL < numbers < text (BINARY) < blobs. */
    private fun compareFirstColumn(record: List<SqliteValue>, key: ByteArray): Int =
        when (val value = record.firstOrNull() ?: SqliteValue.Null) {
            SqliteValue.Null, is SqliteValue.Integer, is SqliteValue.Real -> -1
            is SqliteValue.Text -> compareUnsigned(value.bytes, key)
            is SqliteValue.Blob -> 1
        }

    private class TableLeafCell(val rowid: Long, val payload: () -> ByteArray)

    private fun tableLeafCell(page: Page, offset: Int): TableLeafCell {
        val payloadSize = page.data.varint(offset)
        val rowid = page.data.varint(offset + payloadSize.length)
        val payloadStart = offset + payloadSize.length + rowid.length
        val maxLocal = usableSize - 35
        return TableLeafCell(rowid.value) {
            readPayload(page, payloadStart, payloadSize.value, maxLocal)
        }
    }

    private fun indexPayload(page: Page, offset: Int): ByteArray {
        val payloadSize = page.data.varint(offset)
        val maxLocal = (usableSize - 12) * 64 / 255 - 23
        return readPayload(page, offset + payloadSize.length, payloadSize.value, maxLocal)
    }

    private fun readPayload(page: Page, start: Int, size: Long, maxLocal: Int): ByteArray {
        if (size < 0 || size > MaxPayloadBytes) throw SqliteFormatException("Invalid SQLite payload size.")
        val total = size.toInt()
        if (total <= maxLocal) return page.data.copyOfRange(start, start + total)
        val minLocal = (usableSize - 12) * 32 / 255 - 23
        val spill = minLocal + (total - minLocal) % (usableSize - 4)
        val local = if (spill <= maxLocal) spill else minLocal
        val payload = ByteArray(total)
        System.arraycopy(page.data, start, payload, 0, local)
        var written = local
        var overflowPage = page.data.u32(start + local).toInt()
        val overflow = ByteArray(pageSize)
        while (written < total) {
            if (overflowPage <= 0) throw SqliteFormatException("Truncated SQLite overflow chain.")
            // Overflow pages hold blob data that is read once, so they bypass the page cache.
            source.read((overflowPage - 1).toLong() * pageSize, overflow, 0, pageSize)
            val chunk = minOf(usableSize - 4, total - written)
            System.arraycopy(overflow, 4, payload, written, chunk)
            written += chunk
            overflowPage = overflow.u32(0).toInt()
        }
        return payload
    }

    private fun decodeRecord(payload: ByteArray): List<SqliteValue> {
        val headerSize = payload.varint(0)
        val serialTypes = ArrayList<Long>()
        var headerOffset = headerSize.length
        while (headerOffset < headerSize.value) {
            val serialType = payload.varint(headerOffset)
            serialTypes += serialType.value
            headerOffset += serialType.length
        }
        var offset = headerSize.value.toInt()
        return serialTypes.map { serialType ->
            val length = serialTypeLength(serialType)
            if (offset + length > payload.size) throw SqliteFormatException("Truncated SQLite record.")
            val value = when {
                serialType == 0L -> SqliteValue.Null
                serialType in 1L..6L -> SqliteValue.Integer(payload.signedInt(offset, length))
                serialType == 7L -> SqliteValue.Real(Double.fromBits(payload.signedInt(offset, 8)))
                serialType == 8L -> SqliteValue.Integer(0)
                serialType == 9L -> SqliteValue.Integer(1)
                serialType >= 12 && serialType % 2 == 0L ->
                    SqliteValue.Blob(payload.copyOfRange(offset, offset + length))
                serialType >= 13 -> SqliteValue.Text(payload.copyOfRange(offset, offset + length), charset)
                else -> throw SqliteFormatException("Invalid SQLite serial type $serialType.")
            }
            offset += length
            value
        }
    }

    private fun serialTypeLength(serialType: Long): Int = when (serialType) {
        0L, 8L, 9L -> 0
        1L -> 1
        2L -> 2
        3L -> 3
        4L -> 4
        5L -> 6
        6L, 7L -> 8
        10L, 11L -> throw SqliteFormatException("Invalid SQLite serial type $serialType.")
        else -> ((serialType - if (serialType % 2 == 0L) 12 else 13) / 2).toInt()
    }

    private class Page(val data: ByteArray, private val headerOffset: Int) {
        val type: Int = data.u8(headerOffset)
        val cellCount: Int = data.u16(headerOffset + 3)
        private val isLeaf = type == TableLeaf || type == IndexLeaf
        val rightMostPointer: Int get() = data.u32(headerOffset + 8).toInt()
        private val cellPointers = headerOffset + if (isLeaf) 8 else 12

        fun cellOffset(index: Int): Int = data.u16(cellPointers + 2 * index)
    }

    private fun page(number: Int): Page {
        if (number < 1) throw SqliteFormatException("Invalid SQLite page number $number.")
        val data = synchronized(pageCache) { pageCache[number] } ?: ByteArray(pageSize).also { bytes ->
            source.read((number - 1).toLong() * pageSize, bytes, 0, pageSize)
            synchronized(pageCache) { pageCache[number] = bytes }
        }
        return Page(data, if (number == 1) HeaderSize else 0)
    }

    private class Varint(val value: Long, val length: Int)

    private companion object {
        const val HeaderSize = 100
        const val DefaultCachedPages = 256
        const val MaxTreeDepth = 64
        const val MaxPayloadBytes = 256L * 1024 * 1024
        const val IndexInterior = 2
        const val TableInterior = 5
        const val IndexLeaf = 10
        const val TableLeaf = 13
        val Magic = "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)

        fun ByteArray.u8(offset: Int): Int = this[offset].toInt() and 0xFF

        fun ByteArray.u16(offset: Int): Int = (u8(offset) shl 8) or u8(offset + 1)

        fun ByteArray.u32(offset: Int): Long =
            (u8(offset).toLong() shl 24) or (u8(offset + 1).toLong() shl 16) or
                (u8(offset + 2).toLong() shl 8) or u8(offset + 3).toLong()

        fun ByteArray.signedInt(offset: Int, length: Int): Long {
            var value = this[offset].toLong() // sign-extends the most significant byte
            for (index in 1 until length) value = (value shl 8) or u8(offset + index).toLong()
            return value
        }

        fun ByteArray.varint(offset: Int): Varint {
            var value = 0L
            for (index in 0 until 8) {
                if (offset + index >= size) throw SqliteFormatException("Truncated SQLite varint.")
                val byte = u8(offset + index)
                value = (value shl 7) or (byte and 0x7F).toLong()
                if (byte and 0x80 == 0) return Varint(value, index + 1)
            }
            if (offset + 8 >= size) throw SqliteFormatException("Truncated SQLite varint.")
            return Varint((value shl 8) or u8(offset + 8).toLong(), 9)
        }

        fun compareUnsigned(left: ByteArray, right: ByteArray): Int {
            for (index in 0 until minOf(left.size, right.size)) {
                val difference = (left[index].toInt() and 0xFF) - (right[index].toInt() and 0xFF)
                if (difference != 0) return difference
            }
            return left.size - right.size
        }
    }
}
