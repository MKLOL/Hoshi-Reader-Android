package moe.antimony.hoshi.features.sync.http

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.core.graphics.scale
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import moe.antimony.hoshi.storage.writeSidecarAtomically
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max

/**
 * A manga the server holds that this device has not downloaded. Sync records the title, size
 * and cover; the archive is downloaded only when the user taps the book.
 */
@Serializable
data class HttpSyncCloudBookRecord(
    val syncId: String,
    val title: String,
    val format: HttpSyncContentType = HttpSyncContentType.Mokuro,
    val sizeBytes: Long = 0,
    /** `sha256:` of the remote archive this record describes. */
    val archiveSha256: String,
    val contentSha256: String? = null,
    val shelfName: String? = null,
    val discoveredAt: String,
    /** File name in the covers directory. */
    val coverFile: String? = null,
    /** Archive the cover was read from, or whose cover could not be read. */
    val coverArchiveSha256: String? = null,
    /** Set by a tap; the next full sync downloads the book. */
    val downloadRequestedAt: String? = null,
    /** Why the last requested download failed. */
    val downloadError: String? = null,
)

/** What one sync listing says about a remote-only manga. */
data class HttpSyncCloudBookListing(
    val syncId: String,
    val title: String,
    val format: HttpSyncContentType,
    val sizeBytes: Long,
    val archiveSha256: String,
    val contentSha256: String?,
    val shelfName: String?,
)

/**
 * The catalogue of manga that exist on the HTTP sync server but not on this device
 * (`Books/.http_sync_cloud/`). The bookshelf shows them with their cover and a cloud badge;
 * tapping one records a download request that the next full sync carries out, reporting
 * byte progress through [downloads].
 *
 * The directory starts with a dot, so the library never mistakes it for a book.
 */
class HttpSyncCloudBooks(
    private val booksDirectory: File,
    private val coverThumbnail: (ByteArray) -> ByteArray? = ::cloudCoverThumbnail,
    private val now: () -> Instant = Instant::now,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private val directory: File get() = booksDirectory.resolve(DIRECTORY_NAME)
    private val indexFile: File get() = directory.resolve(INDEX_FILE_NAME)
    private val coversDirectory: File get() = directory.resolve(COVERS_DIRECTORY_NAME)
    private val lock = Any()
    private val mutableBooks = MutableStateFlow<List<HttpSyncCloudBookRecord>>(emptyList())
    private val mutableDownloads = MutableStateFlow<Map<String, Float?>>(emptyMap())
    /** The requests (syncId to stamp) the latest sync plan took; see [hasUnplannedRequests]. */
    @Volatile private var plannedRequests: Map<String, String> = emptyMap()
    private val interruptedDownloadsClaimed = AtomicBoolean(false)

    /** The catalogue as last read or written. Call [reload] once before relying on it. */
    val books: StateFlow<List<HttpSyncCloudBookRecord>> = mutableBooks.asStateFlow()

    /** Downloads in flight: syncId to the fraction received, or null before bytes arrive. */
    val downloads: StateFlow<Map<String, Float?>> = mutableDownloads.asStateFlow()

    suspend fun reload() = withContext(ioDispatcher) {
        synchronized(lock) { mutableBooks.value = loadLocked() }
    }

    /** Where [record]'s cover is stored, without touching the disk (the shelf decodes lazily). */
    fun coverFile(record: HttpSyncCloudBookRecord): File? =
        record.coverFile?.let(coversDirectory::resolve)

    /** Marks [syncId] for download. Returns false when it is not in the catalogue. */
    suspend fun requestDownload(syncId: String): Boolean = withContext(ioDispatcher) {
        var found = false
        mutate { records ->
            records.map { record ->
                if (record.syncId != syncId) return@map record
                found = true
                record.copy(downloadRequestedAt = now().toString(), downloadError = null)
            }
        }
        found
    }

    /** The tap could not start a sync (offline): the book is not left waiting for a later one. */
    suspend fun cancelRequest(syncId: String) = withContext(ioDispatcher) {
        mutate { records -> records.map { if (it.syncId == syncId) it.copy(downloadRequestedAt = null) else it } }
    }

    /**
     * True once per process: a request still saved at launch is a download the app was closed
     * during, and the shelf resumes it.
     */
    fun claimInterruptedDownloads(): Boolean = interruptedDownloadsClaimed.compareAndSet(false, true)

    /** How many listed manga were discovered at or after [since], by any sync. */
    suspend fun countDiscoveredSince(since: Instant): Int = withContext(ioDispatcher) {
        records().count { record ->
            runCatching { !Instant.parse(record.discoveredAt).isBefore(since) }.getOrDefault(false)
        }
    }

    /** The user stopped the sync: nothing tapped downloads until it is tapped again. */
    suspend fun withdrawRequests() = withContext(ioDispatcher) {
        mutate { records -> records.map { it.copy(downloadRequestedAt = null) } }
        mutableDownloads.value = emptyMap()
    }

    suspend fun hasRequestedDownloads(): Boolean = withContext(ioDispatcher) {
        records().any { it.downloadRequestedAt != null }
    }

    /**
     * The requests a sync pass is about to plan (syncId to request stamp). Requests made after
     * this call are reported by [hasUnplannedRequests] so the caller can run another pass.
     */
    fun takeRequestsForPlan(): Map<String, String> = synchronized(lock) {
        loadLocked().mapNotNull { record -> record.downloadRequestedAt?.let { record.syncId to it } }.toMap()
            .also { plannedRequests = it }
    }

    /**
     * True when a request is waiting that no plan has taken yet: a tap during a pass, or one left
     * from an earlier process. A planned request stops counting once its pass answers it.
     */
    suspend fun hasUnplannedRequests(): Boolean = withContext(ioDispatcher) {
        val planned = plannedRequests
        records().any { record ->
            record.downloadRequestedAt != null && planned[record.syncId] != record.downloadRequestedAt
        }
    }

    fun records(): List<HttpSyncCloudBookRecord> = synchronized(lock) { loadLocked() }

    /**
     * Adds newly listed manga and refreshes known ones, keeping covers and requests. Returns how
     * many were new.
     */
    internal fun recordListed(listed: List<HttpSyncCloudBookListing>): Int {
        if (listed.isEmpty()) return 0
        var added = 0
        mutate { records ->
            val bySyncId = records.associateBy { it.syncId }.toMutableMap()
            for (listing in listed) {
                val existing = bySyncId[listing.syncId]
                if (existing == null) added += 1
                bySyncId[listing.syncId] = existing?.copy(
                    title = listing.title,
                    format = listing.format,
                    sizeBytes = listing.sizeBytes,
                    archiveSha256 = listing.archiveSha256,
                    contentSha256 = listing.contentSha256,
                    shelfName = listing.shelfName,
                ) ?: HttpSyncCloudBookRecord(
                    syncId = listing.syncId,
                    title = listing.title,
                    format = listing.format,
                    sizeBytes = listing.sizeBytes,
                    archiveSha256 = listing.archiveSha256,
                    contentSha256 = listing.contentSha256,
                    shelfName = listing.shelfName,
                    discoveredAt = now().toString(),
                )
            }
            bySyncId.values.toList()
        }
        return added
    }

    /** True when no cover was read (or attempted) for the archive the server holds now. */
    internal fun needsCover(syncId: String, archiveSha256: String): Boolean =
        records().firstOrNull { it.syncId == syncId }?.coverArchiveSha256 != archiveSha256

    /**
     * Stores the cover image read from [archiveSha256]. A null [image] records that this
     * archive has no readable cover, so it is not fetched again until the archive changes.
     */
    internal fun saveCover(syncId: String, archiveSha256: String, image: ByteArray?) {
        val thumbnail = image?.let { runCatching { coverThumbnail(it) }.getOrNull() }
        synchronized(lock) {
            val records = loadLocked()
            val record = records.firstOrNull { it.syncId == syncId } ?: return
            val coverName = thumbnail?.let {
                val name = "${hashPrefix(syncId)}-${hashPrefix(archiveSha256)}.jpg"
                coversDirectory.mkdirs()
                val target = coversDirectory.resolve(name)
                val staged = coversDirectory.resolve("$name.tmp")
                staged.writeBytes(it)
                if (!staged.renameTo(target)) {
                    staged.delete()
                    return
                }
                name
            }
            val updated = record.copy(
                // An unreadable new archive keeps the old cover rather than showing none.
                coverFile = coverName ?: record.coverFile,
                coverArchiveSha256 = archiveSha256,
            )
            if (coverName != null && record.coverFile != null && record.coverFile != coverName) {
                coversDirectory.resolve(record.coverFile).delete()
            }
            saveLocked(records.map { if (it.syncId == syncId) updated else it })
        }
    }

    /** The book is installed: it leaves the catalogue at once so the shelf shows the real book. */
    internal fun markDownloaded(syncId: String) {
        mutate { records ->
            val record = records.firstOrNull { it.syncId == syncId } ?: return@mutate records
            record.coverFile?.let { coversDirectory.resolve(it).delete() }
            records - record
        }
        clearProgress(syncId)
    }

    /**
     * Ends a sync pass: manga no longer listed (deleted, or installed some other way) leave the
     * catalogue, and requests that were attempted and failed are cleared with their reason. A
     * request made again after the plan was taken is left for the next pass.
     */
    internal fun finishPass(listed: Set<String>, failedRequests: Map<String, FailedCloudRequest>) {
        mutate { records ->
            records.filter { it.syncId in listed }.map { record ->
                val failure = failedRequests[record.syncId]
                if (failure != null && record.downloadRequestedAt == failure.requestedAt) {
                    record.copy(downloadRequestedAt = null, downloadError = failure.message)
                } else {
                    record
                }
            }
        }
        synchronized(lock) {
            val referenced = loadLocked().mapNotNullTo(mutableSetOf()) { it.coverFile }
            coversDirectory.listFiles()?.forEach { file ->
                if (file.name !in referenced) file.delete()
            }
        }
        // The pass is over: no download it reported is still running.
        mutableDownloads.value = emptyMap()
    }

    internal fun reportProgress(syncId: String, fraction: Float?) {
        // Whole-percent steps: a multi-GB archive reports every 64 KiB.
        val stepped = fraction?.coerceIn(0f, 1f)?.let { (it * 100).toInt() / 100f }
        mutableDownloads.update { current ->
            if (current.containsKey(syncId) && current[syncId] == stepped) current else current + (syncId to stepped)
        }
    }

    internal fun clearProgress(syncId: String) {
        mutableDownloads.update { it - syncId }
    }

    private fun mutate(change: (List<HttpSyncCloudBookRecord>) -> List<HttpSyncCloudBookRecord>) {
        synchronized(lock) {
            val records = loadLocked()
            val updated = change(records)
            if (updated != records) saveLocked(updated) else mutableBooks.value = records
        }
    }

    private fun loadLocked(): List<HttpSyncCloudBookRecord> {
        if (!indexFile.isFile) return emptyList()
        return runCatching {
            json.decodeFromString(serializer, indexFile.readText())
        }.getOrDefault(emptyList())
    }

    private fun saveLocked(records: List<HttpSyncCloudBookRecord>) {
        val sorted = records.sortedBy { it.syncId }
        directory.mkdirs()
        writeSidecarAtomically(indexFile, json.encodeToString(serializer, sorted))
        mutableBooks.value = sorted
    }

    private fun hashPrefix(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
            .take(8).joinToString("") { "%02x".format(it) }

    companion object {
        const val DIRECTORY_NAME = ".http_sync_cloud"
        private const val INDEX_FILE_NAME = "books.json"
        private const val COVERS_DIRECTORY_NAME = "covers"
        private val serializer = ListSerializer(HttpSyncCloudBookRecord.serializer())
    }
}

/** A requested download that failed; [requestedAt] is the request the attempt answered. */
data class FailedCloudRequest(val requestedAt: String, val message: String)

/** Longest side of a stored cover, matching what the bookshelf decodes. */
private const val CLOUD_COVER_MAX_DIMENSION_PX = 768

/** Shrinks a full page image to a shelf-sized JPEG; null when Android cannot decode it. */
internal fun cloudCoverThumbnail(image: ByteArray): ByteArray? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(image, 0, image.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sampleSize = 1
    while (max(bounds.outWidth, bounds.outHeight) / (sampleSize * 2) >= CLOUD_COVER_MAX_DIMENSION_PX) {
        sampleSize *= 2
    }
    val decoded = BitmapFactory.decodeByteArray(
        image, 0, image.size,
        BitmapFactory.Options().apply { inSampleSize = sampleSize },
    ) ?: return null
    val longest = max(decoded.width, decoded.height)
    val bitmap = if (longest > CLOUD_COVER_MAX_DIMENSION_PX) {
        val scale = CLOUD_COVER_MAX_DIMENSION_PX.toFloat() / longest
        decoded.scale(
            max(1, (decoded.width * scale).toInt()),
            max(1, (decoded.height * scale).toInt()),
        ).also { if (it !== decoded) decoded.recycle() }
    } else {
        decoded
    }
    return try {
        ByteArrayOutputStream().use { out ->
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)) return null
            out.toByteArray()
        }
    } finally {
        bitmap.recycle()
    }
}
