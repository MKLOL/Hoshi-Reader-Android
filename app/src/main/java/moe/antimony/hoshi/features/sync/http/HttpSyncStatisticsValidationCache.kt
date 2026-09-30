package moe.antimony.hoshi.features.sync.http

import moe.antimony.hoshi.epub.ContentType
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime

/**
 * Avoid reparsing converged histories on every poll. Only a successful unchanged validation
 * is reusable; this is never an acknowledgement of remote data or a cached dirty decision.
 * The exchange marker matters because its presence also controls legacy device attribution.
 */
internal class HttpSyncStatisticsValidationCache(private val capacity: Int = 4_096) {
    private sealed interface Stamp {
        data object Missing : Stamp
        data class Present(val modified: FileTime, val size: Long, val fileKey: Any) : Stamp
    }

    private data class Inputs(
        val syncId: String,
        val contentType: ContentType,
        val remoteReadingPresent: Boolean,
        val remoteMangaPresent: Boolean,
        val reading: Stamp,
        val manga: Stamp?,
        val exchange: Stamp,
    )

    private val unchanged = LinkedHashMap<String, Inputs>(16, 0.75f, true)

    init { require(capacity > 0) }

    suspend fun hasChanges(
        bookRoot: File,
        syncId: String,
        contentType: ContentType,
        remoteReadingPresent: Boolean,
        remoteMangaPresent: Boolean,
        validate: suspend () -> Boolean,
    ): Boolean {
        val path = bookRoot.absolutePath
        // Capture BEFORE validation: repository reads may migrate old statistics or wait for
        // another writer. Saving post-validation stamps could certify bytes we never read.
        val inputs = capture(bookRoot, syncId, contentType, remoteReadingPresent, remoteMangaPresent)
        if (inputs != null && synchronized(unchanged) { unchanged[path] == inputs }) return false
        val changed = validate()
        synchronized(unchanged) {
            if (changed || inputs == null) {
                unchanged.remove(path)
            } else {
                unchanged[path] = inputs
                while (unchanged.size > capacity) unchanged.remove(unchanged.keys.first())
            }
        }
        return changed
    }

    private fun capture(
        root: File,
        syncId: String,
        contentType: ContentType,
        remoteReadingPresent: Boolean,
        remoteMangaPresent: Boolean,
    ): Inputs? {
        val reading = stamp(root.resolve("statistics.json")) ?: return null
        val manga = if (contentType == ContentType.Mokuro) {
            stamp(root.resolve("manga_statistics.json")) ?: return null
        } else null
        val exchange = stamp(root.resolve(STATISTICS_SYNC_STATE_FILENAME)) ?: return null
        return Inputs(syncId, contentType, remoteReadingPresent, remoteMangaPresent, reading, manga, exchange)
    }

    private fun stamp(file: File): Stamp? = try {
        val attributes = Files.readAttributes(file.toPath(), BasicFileAttributes::class.java)
        // Without file identity, an atomic same-size replacement with a restored timestamp
        // cannot be distinguished. Fall back to full validation on such filesystems.
        attributes.fileKey()?.takeIf { attributes.isRegularFile }?.let {
            Stamp.Present(attributes.lastModifiedTime(), attributes.size(), it)
        }
    } catch (_: NoSuchFileException) {
        Stamp.Missing
    } catch (_: IOException) {
        null
    } catch (_: SecurityException) {
        null
    }
}
