package moe.antimony.hoshi.features.audio

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import moe.antimony.hoshi.importing.ImportFileType
import moe.antimony.hoshi.importing.validateImportFile
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import javax.inject.Inject
import javax.inject.Singleton
import moe.antimony.hoshi.di.FilesDir

data class LocalAudioImportProgress(
    val copiedBytes: Long,
    val totalBytes: Long?,
)

sealed interface LocalAudioDatabaseState {
    data object None : LocalAudioDatabaseState

    data class Imported(val sizeBytes: Long) : LocalAudioDatabaseState

    data class Linked(val sizeBytes: Long?, val isAvailable: Boolean) : LocalAudioDatabaseState
}

/** A user-selected android.db read in place through a persisted SAF read grant. */
@Serializable
internal data class LocalAudioLink(
    val uri: String,
    val sizeBytes: Long? = null,
)

class UnreadableLocalAudioDatabaseException : IOException("Selected file is not a readable local audio database.")

@Singleton
class LocalAudioRepository @Inject constructor(
    @param:FilesDir private val filesDir: File,
    private val contentResolver: ContentResolver?,
) {
    constructor(filesDir: File) : this(filesDir, null)

    private val privateDbFile: File
        get() = File(filesDir, AudioSettings.LocalAudioPath)
    private val sourceConfigFile: File
        get() = File(filesDir, AudioSettings.LocalAudioSourceConfigPath)
    private val linkFile: File
        get() = File(filesDir, AudioSettings.LocalAudioLinkPath)
    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
    }
    private val sourceConfigCache: LocalAudioSourceConfigCache =
        synchronized(SourceConfigCaches) {
            SourceConfigCaches.getOrPut(sourceConfigFile.absolutePath) {
                LocalAudioSourceConfigCache {
                    loadSourceConfig(reset = false)
                }
            }
        }

    val dbFile: File
        get() = privateDbFile

    /** Deletes the private copy, or forgets a linked file without touching the user's original. */
    fun deleteDatabase() {
        readLink()?.let { link ->
            closeLinkedDatabase(link.uri)
            contentResolver?.let { resolver ->
                runCatching {
                    resolver.releasePersistableUriPermission(Uri.parse(link.uri), Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            }
        }
        linkFile.delete()
        privateDbFile.delete()
        sourceConfigFile.delete()
        sourceConfigCache.clear()
    }

    fun databaseSizeBytes(): Long? =
        dbFile.takeIf { it.isFile }?.length()

    /** Reads link availability through the database, so call it off the main thread. */
    fun databaseState(): LocalAudioDatabaseState {
        readLink()?.let { link ->
            return LocalAudioDatabaseState.Linked(
                sizeBytes = link.sizeBytes,
                isAvailable = withReadOnlyDatabase { it.hasLocalAudioTables() } == true,
            )
        }
        return databaseSizeBytes()?.let { LocalAudioDatabaseState.Imported(it) } ?: LocalAudioDatabaseState.None
    }

    fun canOpenDatabase(): Boolean =
        withReadOnlyDatabase { db -> db.hasLocalAudioTables() } == true

    /**
     * Links [uri] in place instead of copying it. Keeps a persisted read grant and
     * rejects files that are not readable local audio databases.
     */
    fun linkDatabase(uri: Uri): Long? {
        val resolver = checkNotNull(contentResolver) { "Linking local audio requires a ContentResolver." }
        resolver.validateImportFile(uri, ImportFileType.LocalAudioDatabase)
        resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val key = uri.toString()
        try {
            val readable = runCatching { linkedDatabase(resolver, key, reopen = true).hasLocalAudioTables() }
                .onFailure { error -> Log.w("HoshiLocalAudio", "Unable to read linked local audio database.", error) }
                .getOrDefault(false)
            if (!readable) throw UnreadableLocalAudioDatabaseException()
            val size = resolver.sizeBytes(uri)
            writeLink(LocalAudioLink(uri = key, sizeBytes = size))
            ensureSourceConfig(reset = true)
            return size
        } catch (error: Throwable) {
            linkFile.delete()
            closeLinkedDatabase(key)
            runCatching { resolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            throw error
        }
    }

    fun importDatabase(contentResolver: ContentResolver, uri: Uri, onProgress: (LocalAudioImportProgress) -> Unit = {}): Long {
        contentResolver.validateImportFile(uri, ImportFileType.LocalAudioDatabase)
        val expectedSize = contentResolver.sizeBytes(uri)
        return contentResolver.openInputStream(uri)?.use { input ->
            replacePrivateDatabase(input, expectedSize, onProgress).also {
                ensureSourceConfig(reset = true)
            }
        } ?: error("Unable to open audio database.")
    }

    fun ensureSourceConfig(reset: Boolean = false): LocalAudioSourceConfig =
        if (reset) {
            sourceConfigCache.clear()
            loadSourceConfig(reset = true).also(sourceConfigCache::replace)
        } else {
            sourceConfigCache.get()
        }

    private fun loadSourceConfig(reset: Boolean): LocalAudioSourceConfig {
        if (!reset) {
            readSourceConfig()
                ?.takeIf { it.version == LocalAudioSourceConfig.CurrentVersion && it.sourceOrder.isNotEmpty() }
                ?.let { return it }
        }
        val availableSources = audioSourcesFromDatabase().toSet()
        if (availableSources.isEmpty()) {
            sourceConfigFile.delete()
            return LocalAudioSourceConfig()
        }
        val current = if (reset) null else readSourceConfig()
        val repaired = if (current?.version == LocalAudioSourceConfig.CurrentVersion) {
            current.repair(availableSources)
        } else {
            LocalAudioSourceConfig.defaultFor(availableSources)
        }
        if (current != repaired) {
            writeSourceConfig(repaired)
        }
        return repaired
    }

    fun updateSourceOrder(sourceOrder: List<String>): LocalAudioSourceConfig {
        val current = ensureSourceConfig()
        val availableSources = current.sourceOrder.toSet()
        if (availableSources.isEmpty()) {
            sourceConfigFile.delete()
            sourceConfigCache.clear()
            return LocalAudioSourceConfig()
        }
        val next = current.copy(sourceOrder = sourceOrder).repair(availableSources)
        writeSourceConfig(next)
        sourceConfigCache.replace(next)
        return next
    }

    fun updateSourceEnabled(source: String, enabled: Boolean): LocalAudioSourceConfig {
        val current = ensureSourceConfig()
        if (source !in current.sourceOrder) return current
        val next = current.copy(
            disabledSources = if (enabled) {
                current.disabledSources - source
            } else {
                current.disabledSources + source
            },
        )
        writeSourceConfig(next)
        sourceConfigCache.replace(next)
        return next
    }

    fun findAudio(term: String, reading: String): LocalAudioEntry? {
        val normalizedReading = LocalAudioResolver.katakanaToHiragana(reading)
        val sourceConfig = ensureSourceConfig()
        return queryAudioRows(term, normalizedReading, sourceConfig)
            ?.let { rows ->
                LocalAudioResolver.resolve(
                    term = term,
                    reading = normalizedReading,
                    rows = rows,
                    sourceOrder = sourceConfig.sourceOrder,
                    disabledSources = sourceConfig.disabledSources,
                )
            }
    }

    fun findAudioCandidates(term: String, reading: String): List<LocalAudioCandidate> {
        val normalizedReading = LocalAudioResolver.katakanaToHiragana(reading)
        val sourceConfig = ensureSourceConfig()
        return queryAudioRows(term, normalizedReading, sourceConfig)
            ?.let { rows ->
                LocalAudioResolver.resolveCandidates(
                    term = term,
                    reading = normalizedReading,
                    rows = rows,
                    sourceOrder = sourceConfig.sourceOrder,
                    disabledSources = sourceConfig.disabledSources,
                )
            }
            .orEmpty()
    }

    private fun queryAudioRows(
        term: String,
        normalizedReading: String,
        sourceConfig: LocalAudioSourceConfig,
    ): List<LocalAudioEntry>? {
        if (sourceConfig.sourceOrder.all { it in sourceConfig.disabledSources }) return emptyList()
        return withReadOnlyDatabase { db -> db.findEntries(term, normalizedReading) }
    }

    fun audioSourcesFromDatabase(): List<String> {
        val sources = withReadOnlyDatabase { db -> db.audioSources() }.orEmpty()
        return LocalAudioSourceOrder.defaultOrder(sources)
    }

    fun loadAudio(file: LocalAudioFile): ByteArray? {
        return withReadOnlyDatabase { db -> db.loadAudio(source = file.source, file = file.file) }
    }

    private inline fun <T> withReadOnlyDatabase(block: (LocalAudioDatabase) -> T): T? {
        return runCatching {
            val link = readLink()
            if (link != null) {
                val resolver = contentResolver ?: return null
                try {
                    block(linkedDatabase(resolver, link.uri, reopen = false))
                } catch (_: IOException) {
                    // Providers can invalidate long-lived descriptors; retry once with a fresh one.
                    block(linkedDatabase(resolver, link.uri, reopen = true))
                }
            } else {
                if (!privateDbFile.isFile) return null
                PlatformLocalAudioDatabase.open(privateDbFile).use(block)
            }
        }.onFailure { error ->
            Log.w("HoshiLocalAudio", "Unable to open local audio database.", error)
        }.getOrNull()
    }

    private fun linkedDatabase(resolver: ContentResolver, uri: String, reopen: Boolean): LinkedLocalAudioDatabase =
        synchronized(LinkedDatabases) {
            if (reopen) LinkedDatabases.remove(uri)?.close()
            LinkedDatabases.getOrPut(uri) { LinkedLocalAudioDatabase.open(resolver, Uri.parse(uri)) }
        }

    private fun closeLinkedDatabase(uri: String) {
        synchronized(LinkedDatabases) {
            LinkedDatabases.remove(uri)?.close()
        }
    }

    private fun readLink(): LocalAudioLink? =
        runCatching {
            linkFile
                .takeIf { it.isFile }
                ?.readText()
                ?.let { json.decodeFromString<LocalAudioLink>(it) }
        }.onFailure { error ->
            Log.w("HoshiLocalAudio", "Unable to read local audio link.", error)
        }.getOrNull()

    private fun writeLink(link: LocalAudioLink) {
        linkFile.parentFile?.mkdirs()
        linkFile.writeText(json.encodeToString(link))
    }

    private fun readSourceConfig(): LocalAudioSourceConfig? =
        runCatching {
            sourceConfigFile
                .takeIf { it.isFile }
                ?.readText()
                ?.let { json.decodeFromString<LocalAudioSourceConfig>(it) }
        }.onFailure { error ->
            Log.w("HoshiLocalAudio", "Unable to read local audio source config.", error)
        }.getOrNull()

    private fun writeSourceConfig(config: LocalAudioSourceConfig) {
        sourceConfigFile.parentFile?.mkdirs()
        sourceConfigFile.writeText(json.encodeToString(config))
    }

    internal fun replacePrivateDatabase(
        input: InputStream,
        expectedSizeBytes: Long?,
        onProgress: (LocalAudioImportProgress) -> Unit = {},
    ): Long {
        privateDbFile.parentFile?.mkdirs()
        val tempFile = File(privateDbFile.parentFile, "${privateDbFile.name}.tmp")
        tempFile.delete()
        var copied = 0L
        val buffer = ByteArray(DatabaseCopyBufferSizeBytes)
        try {
            input.use { source ->
                FileOutputStream(tempFile).use { output ->
                    while (true) {
                        val read = source.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        copied += read
                        onProgress(LocalAudioImportProgress(copiedBytes = copied, totalBytes = expectedSizeBytes))
                    }
                    output.fd.sync()
                }
            }
            if (expectedSizeBytes != null && copied != expectedSizeBytes) {
                error("Incomplete audio database copy: copied $copied of $expectedSizeBytes bytes.")
            }
            moveReplacing(tempFile, privateDbFile)
            return copied
        } catch (error: Throwable) {
            tempFile.delete()
            throw error
        }
    }

    private fun moveReplacing(source: File, target: File) {
        runCatching {
            Files.move(
                source.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        }.recoverCatching { error ->
            if (error !is AtomicMoveNotSupportedException) throw error
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }.getOrThrow()
    }

    private fun ContentResolver.sizeBytes(uri: Uri): Long? {
        openAssetFileDescriptor(uri, "r")?.use { descriptor ->
            descriptor.length.takeIf { it >= 0 }?.let { return it }
        }
        return query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            val column = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (column < 0 || cursor.isNull(column)) null else cursor.getLong(column).takeIf { it >= 0 }
        }
    }

    companion object {
        private const val DatabaseCopyBufferSizeBytes = 1024 * 1024
        private val SourceConfigCaches = mutableMapOf<String, LocalAudioSourceConfigCache>()

        // One process-wide reader per linked file, shared by every repository instance.
        private val LinkedDatabases = mutableMapOf<String, LinkedLocalAudioDatabase>()

        fun fromContext(context: Context): LocalAudioRepository =
            LocalAudioRepository(context.filesDir, context.applicationContext.contentResolver)
    }
}
