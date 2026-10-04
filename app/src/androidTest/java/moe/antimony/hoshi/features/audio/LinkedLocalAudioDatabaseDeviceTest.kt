package moe.antimony.hoshi.features.audio

import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Compares the linked SAF reader with the platform SQLite used for private copies. */
@RunWith(AndroidJUnit4::class)
class LinkedLocalAudioDatabaseDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var databaseFile: File

    @Before
    fun createDatabase() {
        databaseFile = File(context.cacheDir, "linked-local-audio-test.db").also { it.delete() }
        SQLiteDatabase.openOrCreateDatabase(databaseFile, null).use { db ->
            // Match generated android.db files, which use rollback journaling.
            db.disableWriteAheadLogging()
            db.execSQL(
                "CREATE TABLE entries (id integer PRIMARY KEY NOT NULL, expression text NOT NULL, reading text, " +
                    "source text NOT NULL, speaker text, display text, file text NOT NULL)",
            )
            db.execSQL("CREATE INDEX idx_expr_reading ON entries(expression, reading)")
            db.execSQL("CREATE INDEX idx_reading ON entries(reading)")
            db.execSQL("CREATE TABLE android (id integer PRIMARY KEY NOT NULL, file text NOT NULL, source text NOT NULL, data blob NOT NULL)")
            db.execSQL("CREATE INDEX idx_android ON android(file, source)")
            db.beginTransaction()
            try {
                for (index in 0 until 3000) {
                    db.execSQL(
                        "INSERT INTO entries (expression, reading, source, display, file) VALUES (?, ?, ?, ?, ?)",
                        arrayOf(
                            "語$index",
                            if (index % 10 == 0) null else "よみ${index % 100}",
                            Sources[index % Sources.size],
                            if (index % 3 == 0) null else "表示$index",
                            "$index.${if (index % 4 == 0) "MP3" else "opus"}",
                        ),
                    )
                    if (index % 20 == 0) {
                        db.execSQL(
                            "INSERT INTO android (file, source, data) VALUES (?, ?, ?)",
                            arrayOf<Any>("$index.${if (index % 4 == 0) "MP3" else "opus"}", Sources[index % Sources.size], blob(index)),
                        )
                    }
                }
                db.execSQL(
                    "INSERT INTO entries (expression, reading, source, display, file) VALUES (?, ?, ?, ?, ?)",
                    arrayOf("𠮟る", "しかる", "nhk16", "叱る", "shikaru.opus"),
                )
                db.execSQL("INSERT INTO android (file, source, data) VALUES (?, ?, ?)", arrayOf<Any>("shikaru.opus", "nhk16", blob(200_000)))
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }
    }

    @After
    fun deleteDatabase() {
        databaseFile.delete()
    }

    @Test
    fun linkedReaderMatchesPlatformSqlite() {
        val linked = LinkedLocalAudioDatabase.open(context.contentResolver, Uri.fromFile(databaseFile))
        val platform = PlatformLocalAudioDatabase.open(databaseFile)
        try {
            assertTrue(linked.hasLocalAudioTables())
            val queries = (0 until 3000 step 37).map { "語$it" to "よみ${it % 100}" } +
                listOf("𠮟る" to "しかる", "語7" to "", "存在しない" to "よみ5", "存在しない" to "")
            for ((term, reading) in queries) {
                assertEquals(
                    "$term / $reading",
                    platform.findEntries(term, reading).sortedWith(EntryOrder),
                    linked.findEntries(term, reading).sortedWith(EntryOrder),
                )
            }
            assertEquals(platform.audioSources().toSet(), linked.audioSources().toSet())
            for (index in 0 until 3000 step 20) {
                val file = "$index.${if (index % 4 == 0) "MP3" else "opus"}"
                val source = Sources[index % Sources.size]
                assertArrayEquals(file, platform.loadAudio(source, file), linked.loadAudio(source, file))
            }
            assertArrayEquals(blob(200_000), linked.loadAudio("nhk16", "shikaru.opus"))
            assertNull(linked.loadAudio("forvo", "shikaru.opus"))
        } finally {
            linked.close()
            platform.close()
        }
    }

    private companion object {
        val Sources = listOf("nhk16", "daijisen", "forvo", "jpod", "shinmeikai8")
        val EntryOrder = compareBy<LocalAudioEntry>({ it.source }, { it.expression }, { it.file })

        fun blob(seed: Int): ByteArray = ByteArray(100 + seed % 5000) { ((seed + it * 7) % 256).toByte() }
    }
}
