package moe.antimony.hoshi.features.sync.http

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import moe.antimony.hoshi.storage.writeSidecarAtomically
import java.io.EOFException
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

@Serializable
private data class DownloadCheckpoint(val identity: String, val etag: String, val size: Long)

/** Stable partial files survive connection loss and process death; only validated ranges append. */
internal suspend fun downloadHttpSyncFile(
    target: File,
    identity: String,
    openConnection: () -> HttpURLConnection,
    progress: ((Long, Long) -> Unit)?,
): HttpSyncKvFileFetched? {
    val checkpointFile = File(target.path + ".resume")
    val json = Json { ignoreUnknownKeys = true }
    repeat(3) { attempt ->
        currentCoroutineContext().ensureActive()
        val saved = runCatching {
            json.decodeFromString(DownloadCheckpoint.serializer(), checkpointFile.readText())
        }.getOrNull()?.takeIf {
            it.identity == identity && it.etag.isNotBlank() && !it.etag.startsWith("W/") &&
                target.isFile && target.length() in 1..it.size
        }
        val offset = if (saved == null) 0L else target.length()
        val connection = openConnection()
        connection.setRequestProperty("Accept-Encoding", "identity")
        if (offset > 0) {
            connection.setRequestProperty("Range", "bytes=$offset-")
            connection.setRequestProperty("If-Range", saved!!.etag)
        }
        try {
            val context = currentCoroutineContext()
            return suspendCancellableCoroutine { continuation ->
                continuation.invokeOnCancellation { connection.disconnect() }
                try {
                    context.ensureActive()
                    val code = connection.responseCode
                    if (code == 404) {
                        target.delete()
                        checkpointFile.delete()
                        continuation.resume(null)
                        return@suspendCancellableCoroutine
                    }
                    if (code == 416 && offset > 0) {
                        // Complete-but-not-verified files are verified by the payload codec before
                        // calling us. Any other 416 means the object changed or the checkpoint is bad.
                        target.delete()
                        checkpointFile.delete()
                        throw IOException("Download range is no longer available")
                    }
                    if (code != 200 && code != 206) {
                        if (code in 500..599 || code == 408 || code == 429) throw IOException("HTTP $code")
                        throw HttpSyncException(moe.antimony.hoshi.R.string.http_sync_download_failed)
                    }
                    val etag = connection.getHeaderField("ETag").orEmpty()
                    val range = connection.getHeaderField("Content-Range")
                        ?.let { Regex("bytes (\\d+)-(\\d+)/(\\d+)").matchEntire(it) }
                    val start = range?.groupValues?.get(1)?.toLongOrNull()
                    val end = range?.groupValues?.get(2)?.toLongOrNull()
                    val rangeTotal = range?.groupValues?.get(3)?.toLongOrNull()
                    val append = code == 206
                    if (append && (offset == 0L || start != offset || end == null || rangeTotal == null ||
                            end < offset || end >= rangeTotal || rangeTotal != saved?.size || etag != saved.etag ||
                            (connection.contentLengthLong >= 0 && connection.contentLengthLong != end - offset + 1))) {
                        target.delete()
                        checkpointFile.delete()
                        throw IOException("Invalid partial download response")
                    }
                    val total = if (append) rangeTotal!! else connection.contentLengthLong
                    target.parentFile?.mkdirs()
                    // A server may ignore Range or replace the object: 200 always truncates first.
                    FileOutputStream(target, append).use { output ->
                        writeSidecarAtomically(checkpointFile, json.encodeToString(
                            DownloadCheckpoint.serializer(), DownloadCheckpoint(identity, etag, total),
                        ))
                        var transferred = if (append) offset else 0L
                        progress?.invoke(transferred, total)
                        connection.inputStream.use { input ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                context.ensureActive()
                                val read = input.read(buffer)
                                if (read < 0) break
                                if (total >= 0 && transferred + read > total) {
                                    throw IOException("Download exceeded declared length")
                                }
                                output.write(buffer, 0, read)
                                transferred += read
                                progress?.invoke(transferred, total)
                            }
                        }
                        if (total >= 0 && transferred != total) throw EOFException("Incomplete download")
                        output.fd.sync()
                    }
                    context.ensureActive()
                    continuation.resume(HttpSyncKvFileFetched(
                        contentType = connection.getHeaderField("Content-Type") ?: "application/octet-stream",
                        lastModified = connection.getHeaderField("Last-Modified").orEmpty(),
                        etag = etag,
                    ))
                } catch (error: Throwable) {
                    if (continuation.isActive) continuation.resumeWithException(error)
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: IOException) {
            currentCoroutineContext().ensureActive()
            if (attempt == 2) throw error
        } finally {
            connection.disconnect()
        }
        delay(500L * (attempt + 1))
    }
    error("Unreachable")
}
