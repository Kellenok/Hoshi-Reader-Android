package moe.antimony.hoshi.features.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.file.Files
import java.util.Locale

class LocalAudioSqliteReaderTest {
    @Test
    fun smallPagesMatchSqliteAcrossMultiLevelIndexesAndOverflowChains() {
        assertMatchesFixture("android-small-pages.db", count = 1500)
    }

    @Test
    fun utf16DatabaseMatchesSqlite() {
        assertMatchesFixture("android-utf16.db", count = 200)
    }

    @Test
    fun databaseWithoutIndexesFallsBackToTableScans() {
        assertMatchesFixture("android-no-indexes.db", count = 120)
    }

    @Test
    fun lookupsUseIndexesInsteadOfScanningTheDatabase() {
        val file = fixture("android-small-pages.db")
        val totalPages = file.length() / 512
        RandomAccessFile(file, "r").channel.use { channel ->
            val channelSource = FileChannelPageSource(channel)
            var reads = 0
            val reader = LocalAudioSqliteReader(
                ReadOnlySqliteFile(
                    source = { position, target, offset, length ->
                        reads++
                        channelSource.read(position, target, offset, length)
                    },
                    cachedPages = 0,
                ),
            )
            reads = 0

            assertEquals(16, reader.findEntries("語0005", "よみ05").size)
            assertEquals(64, reader.loadAudio(source = "taas", file = "dup.mp3")?.size)

            assertTrue("read $reads of $totalPages pages", reads < totalPages / 4)
        }
    }

    @Test
    fun rejectsFilesThatAreNotSqliteDatabases() {
        val file = Files.createTempFile("not-sqlite", ".db").toFile().apply { writeBytes(ByteArray(4096) { 7 }) }

        val result = runCatching { open(file) }

        assertTrue(result.exceptionOrNull() is SqliteFormatException)
    }

    @Test
    fun truncatedDatabaseFailsWithIoErrorInsteadOfWrongResults() {
        val source = fixture("android-small-pages.db")
        val truncated = Files.createTempFile("truncated", ".db").toFile().apply {
            writeBytes(source.readBytes().copyOf(source.length().toInt() / 2))
        }

        val result = runCatching {
            open(truncated).use { reader -> (0 until 1500).forEach { reader.findEntries(LocalAudioFixture.expression(it), "") } }
        }

        assertTrue(result.exceptionOrNull() is IOException)
    }

    private fun assertMatchesFixture(name: String, count: Int) {
        val rows = LocalAudioFixture.entries(count)
        open(fixture(name)).use { reader ->
            assertTrue(reader.hasLocalAudioTables())

            val queries = (0 until count step 7).map { LocalAudioFixture.expression(it) to LocalAudioFixture.reading(it).orEmpty() } +
                listOf(
                    "𠮟る" to "しかる",
                    "語0005" to "よみ05",
                    "長".repeat(300) to "ながい",
                    "存在しない" to "よみ42",
                    "存在しない" to "",
                    LocalAudioFixture.expression(count - 1) to "",
                )
            for ((term, reading) in queries) {
                val expected = rows.filter { it.expression == term || (reading.isNotBlank() && it.reading == reading) }
                assertEquals("$name: $term / $reading", expected, reader.findEntries(term, reading))
            }

            assertEquals(
                rows.filter { row -> AudioExtensions.any { row.file.lowercase(Locale.ROOT).endsWith(it) } }.map { it.source }.toSet(),
                reader.audioSources().toSet(),
            )

            for ((file, source, data) in LocalAudioFixture.audio(count)) {
                assertArrayEquals("$name: $source/$file", data, reader.loadAudio(source = source, file = file))
            }
            assertNull(reader.loadAudio(source = "forvo", file = "shikaru.opus"))
            assertNull(reader.loadAudio(source = "nhk16", file = "missing.opus"))
        }
    }

    private fun fixture(name: String): File =
        File(requireNotNull(javaClass.classLoader?.getResource("local-audio/$name")) { "Missing fixture $name" }.toURI())

    private fun open(file: File): ClosableReader {
        val channel = RandomAccessFile(file, "r").channel
        return try {
            ClosableReader(LocalAudioSqliteReader(ReadOnlySqliteFile(FileChannelPageSource(channel))), channel)
        } catch (error: Throwable) {
            channel.close()
            throw error
        }
    }

    private class ClosableReader(
        reader: LocalAudioSqliteReader,
        private val channel: AutoCloseable,
    ) : LocalAudioDatabase by reader, AutoCloseable {
        override fun close() = channel.close()
    }

    private companion object {
        val AudioExtensions = listOf(".mp3", ".opus", ".ogg")
    }
}

/** Mirrors the row formulas in src/test/resources/local-audio/generate_fixtures.py. */
private object LocalAudioFixture {
    private val sources = listOf("nhk16", "daijisen", "forvo", "jpod", "shinmeikai8")
    private val extensions = listOf("mp3", "opus", "ogg", "MP3")

    fun expression(index: Int): String = String.format(Locale.ROOT, "語%04d", index)

    fun reading(index: Int): String? = if (index % 10 == 0) null else String.format(Locale.ROOT, "よみ%02d", index % 100)

    private fun file(index: Int): String = String.format(Locale.ROOT, "%04d.%s", index, extensions[index % 4])

    fun entries(count: Int): List<LocalAudioEntry> =
        (0 until count).map { index ->
            LocalAudioEntry(
                source = sources[index % 5],
                expression = expression(index),
                reading = reading(index),
                file = file(index),
                display = if (index % 3 == 0) "" else "表示$index",
            )
        } + listOf(
            LocalAudioEntry("nhk16", "𠮟る", "しかる", "shikaru.opus", "叱る"),
            LocalAudioEntry("images", "画像", "がぞう", "picture.png", ""),
            LocalAudioEntry("taas", "語0005", "よみ05", "dup.mp3", "重複"),
            LocalAudioEntry("forvo", "長".repeat(300), "ながい", "long.mp3", ""),
        )

    fun audio(count: Int): List<Triple<String, String, ByteArray>> =
        (0 until count step 25).map { index ->
            Triple(file(index), sources[index % 5], blob(index, 50 + (index * 37) % 3000))
        } + listOf(
            Triple("shikaru.opus", "nhk16", blob(9001, 20000)),
            Triple("dup.mp3", "taas", blob(9002, 64)),
        )

    private fun blob(seed: Int, size: Int): ByteArray = ByteArray(size) { k -> ((seed * 31 + k * 7) % 256).toByte() }
}
