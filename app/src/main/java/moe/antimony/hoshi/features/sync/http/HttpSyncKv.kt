package moe.antimony.hoshi.features.sync.http

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.InputStream
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Generic key/value blob transport for the v2 sync protocol (see `docs/HTTP_SYNC_KV.md`).
 *
 * This file is intentionally **schema-free** — it knows about keys, bytes, content-types,
 * timestamps and etags, but not about bookmarks, chat entries, manga or EPUB. Hoshi-specific
 * blob shapes live in [HttpSyncBlobs]; the reconciliation logic lives in [HttpSyncReconciler]
 * and the reader-hot fire-and-forget pushes in [HttpSyncPusher].
 * Splitting it this way keeps the network layer easy to fake in tests and isolates upstream
 * merges to a single fork-owned directory.
 */

// ----- Wire records (mirror the server JSON) ---------------------------------------------

/** One entry in a `GET /v1/kv` list response. Mirrors the server's per-key metadata shape. */
@Serializable
data class HttpSyncKvKeyMeta(
    val key: String,
    val lastModified: String,
    val etag: String,
    val size: Int,
    val contentType: String,
)

/** Body of a `GET /v1/kv` list response. */
@Serializable
data class HttpSyncKvList(
    val keys: List<HttpSyncKvKeyMeta> = emptyList(),
    val truncated: Boolean = false,
    val nextCursor: String? = null,
)

/** Body of a successful `PUT /v1/kv/{key}` response. */
@Serializable
data class HttpSyncKvWriteResponse(
    val key: String,
    val lastModified: String,
    val etag: String,
    val size: Int,
    val contentType: String,
)

/** Body + headers from a successful `GET /v1/kv/{key}`. */
data class HttpSyncKvFetched(
    val body: ByteArray,
    val contentType: String,
    val lastModified: String,
    val etag: String,
) {
    // ByteArray identity equality is the default and that's what we want for this struct —
    // tests compare via `key` or by decoding the body; nothing relies on structural equality.
    override fun equals(other: Any?): Boolean = this === other
    override fun hashCode(): Int = System.identityHashCode(this)
}

/** Headers from a successful `GET /v1/kv/{key}` streamed directly to disk. */
data class HttpSyncKvFileFetched(
    val contentType: String,
    val lastModified: String,
    val etag: String,
)

@Serializable
data class HttpSyncBookmarkMapEntry(
    val etag: String,
    val lastModified: String,
    val value: HttpSyncBookmarkBlob? = null,
)

@Serializable
private data class HttpSyncKvError(val error: String? = null)

@Serializable
private data class HttpSyncMultipartStartRequest(
    val key: String,
    val contentType: String,
)

@Serializable
private data class HttpSyncMultipartStartResponse(
    val uploadId: String,
)

@Serializable
private data class HttpSyncMultipartCompleteRequest(
    val parts: List<Int>,
)

@Serializable
private data class HttpSyncMultipartCompleteResponse(
    val key: String,
    val lastModified: String,
    val etag: String,
    val size: Long,
    val contentType: String? = null,
)

class HttpSyncException(message: String, val httpCode: Int? = null) : Exception(message)

private fun InputStream.readBytesBounded(key: String, maxBytes: Int): ByteArray {
    val output = ByteArrayOutputStream(minOf(maxBytes, DEFAULT_BUFFER_SIZE))
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var total = 0
    while (true) {
        val read = read(buffer)
        if (read < 0) break
        if (read == 0) continue
        if (total > maxBytes - read) {
            throw HttpSyncException("Blob at $key exceeds the $maxBytes-byte download limit.")
        }
        output.write(buffer, 0, read)
        total += read
    }
    return output.toByteArray()
}

// ----- Transport interface ---------------------------------------------------------------

/**
 * The four blob-store operations the sync code needs. An interface so unit tests fake the
 * network with an in-memory map and never touch [HttpSyncKvClient].
 */
interface HttpSyncKvTransport {
    suspend fun put(key: String, contentType: String, body: ByteArray): HttpSyncKvWriteResponse

    /**
     * Uploads [file] without requiring callers to materialize it as a [ByteArray].
     * The default keeps older fakes simple; production transports should stream.
     *
     * [onByteProgress] is invoked as bytes leave the wire so callers can drive a
     * per-file progress bar. It's optional and defaults to `null` — existing callers
     * and fakes are unaffected.
     */
    suspend fun putFile(
        key: String,
        contentType: String,
        file: File,
        onByteProgress: ((bytesTransferred: Long, totalBytes: Long) -> Unit)? = null,
    ): HttpSyncKvWriteResponse = put(key, contentType, file.readBytes())

    /** Returns `null` on `404` (key not present). All other non-2xx responses throw. */
    suspend fun get(key: String): HttpSyncKvFetched?

    /** Like [get], but aborts before retaining more than [maxBytes] in memory. */
    suspend fun getBounded(key: String, maxBytes: Int): HttpSyncKvFetched? {
        require(maxBytes >= 0)
        val fetched = get(key) ?: return null
        if (fetched.body.size > maxBytes) {
            throw HttpSyncException("Blob at $key exceeds the $maxBytes-byte download limit.")
        }
        return fetched
    }

    /**
     * Downloads [key] into [targetFile] without requiring callers to keep the body in memory.
     * The default keeps older fakes simple; production transports should stream.
     *
     * [onByteProgress] is invoked as bytes arrive so callers can drive a per-file
     * progress bar. It's optional and defaults to `null` — existing callers and fakes
     * are unaffected.
     */
    suspend fun downloadToFile(
        key: String,
        targetFile: File,
        onByteProgress: ((bytesTransferred: Long, totalBytes: Long) -> Unit)? = null,
    ): HttpSyncKvFileFetched? {
        val fetched = get(key) ?: return null
        targetFile.parentFile?.mkdirs()
        targetFile.writeBytes(fetched.body)
        return HttpSyncKvFileFetched(
            contentType = fetched.contentType,
            lastModified = fetched.lastModified,
            etag = fetched.etag,
        )
    }

    suspend fun list(
        prefix: String? = null,
        since: String? = null,
        cursor: String? = null,
        limit: Int? = null,
    ): HttpSyncKvList

    /** `204` and `404` both succeed silently — the post-condition is "this key is gone." */
    suspend fun delete(key: String)
}

// ----- HttpURLConnection client ----------------------------------------------------------

/**
 * Concrete transport for [HttpSyncKvTransport] over the v2 KV REST API. Uses
 * `HttpURLConnection` to match the rest of the app's networking style (no extra dependency,
 * cooperates with the existing IO dispatcher). The `baseUrl` is the server's mount point —
 * e.g. `https://dragos.games/api/book_sync` — and the `/v1/kv/...` paths are appended.
 */
class HttpSyncKvClient(
    private val baseUrl: String,
    private val bearerToken: String,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val multipartPartSizeBytes: Long = DEFAULT_MULTIPART_PART_SIZE_BYTES,
    private val multipartThresholdBytes: Long = DEFAULT_MULTIPART_UPLOAD_THRESHOLD_BYTES,
) : HttpSyncKvTransport {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    override suspend fun put(
        key: String,
        contentType: String,
        body: ByteArray,
    ): HttpSyncKvWriteResponse = withContext(ioDispatcher) {
        val connection = openConnection("PUT", "/v1/kv/${encodeKey(key)}", contentType)
        connection.doOutput = true
        try {
            val context = currentCoroutineContext()
            connection.runCancellable {
                connection.outputStream.use { it.write(body) }
                context.ensureActive()
                val (code, raw) = readBody(connection)
                context.ensureActive()
                if (code !in 200..299) throw HttpSyncException(parseError(code, raw))
                json.decodeFromString(HttpSyncKvWriteResponse.serializer(), raw)
            }
        } catch (e: HttpSyncException) {
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw HttpSyncException(friendlyMessage(e))
        } finally {
            connection.disconnect()
        }
    }

    override suspend fun putFile(
        key: String,
        contentType: String,
        file: File,
        onByteProgress: ((bytesTransferred: Long, totalBytes: Long) -> Unit)?,
    ): HttpSyncKvWriteResponse = withContext(ioDispatcher) {
        if (file.length() > multipartThresholdBytes) {
            return@withContext putFileMultipart(key, contentType, file, onByteProgress)
        }
        putFileSingleRequest(key, contentType, file, onByteProgress)
    }

    private suspend fun putFileSingleRequest(
        key: String,
        contentType: String,
        file: File,
        onByteProgress: ((bytesTransferred: Long, totalBytes: Long) -> Unit)?,
    ): HttpSyncKvWriteResponse {
        val connection = openConnection("PUT", "/v1/kv/${encodeKey(key)}", contentType)
        connection.doOutput = true
        val totalBytes = file.length()
        connection.setFixedLengthStreamingMode(totalBytes)
        return try {
            val context = currentCoroutineContext()
            val buffer = ByteArray(DEFAULT_STREAM_BUFFER_SIZE)
            connection.runCancellable {
                var transferred = 0L
                file.inputStream().buffered(DEFAULT_STREAM_BUFFER_SIZE).use { input ->
                    connection.outputStream.buffered(DEFAULT_STREAM_BUFFER_SIZE).use { output ->
                        while (true) {
                            context.ensureActive()
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            transferred += read.toLong()
                            onByteProgress?.invoke(transferred, totalBytes)
                        }
                    }
                }
                context.ensureActive()
                val (code, raw) = readBody(connection)
                context.ensureActive()
                if (code !in 200..299) throw HttpSyncException(parseError(code, raw))
                json.decodeFromString(HttpSyncKvWriteResponse.serializer(), raw)
            }
        } catch (e: HttpSyncException) {
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw HttpSyncException(friendlyMessage(e))
        } finally {
            connection.disconnect()
        }
    }

    private suspend fun putFileMultipart(
        key: String,
        contentType: String,
        file: File,
        onByteProgress: ((bytesTransferred: Long, totalBytes: Long) -> Unit)?,
    ): HttpSyncKvWriteResponse {
        require(multipartPartSizeBytes in 1..MAX_MULTIPART_PART_SIZE_BYTES) {
            "multipartPartSizeBytes must be between 1 and $MAX_MULTIPART_PART_SIZE_BYTES."
        }
        var uploadId: String? = null
        try {
            uploadId = startMultipartUpload(key, contentType)
            val parts = uploadMultipartParts(uploadId, file, onByteProgress)
            currentCoroutineContext().ensureActive()
            return completeMultipartUpload(uploadId, parts, fallbackContentType = contentType)
        } catch (e: CancellationException) {
            uploadId?.let(::cancelMultipartUploadQuietly)
            throw e
        } catch (e: HttpSyncException) {
            uploadId?.let(::cancelMultipartUploadQuietly)
            throw e
        } catch (e: Exception) {
            uploadId?.let(::cancelMultipartUploadQuietly)
            throw HttpSyncException(friendlyMessage(e))
        }
    }

    private suspend fun startMultipartUpload(key: String, contentType: String): String {
        val request = HttpSyncMultipartStartRequest(key = key, contentType = contentType)
        val body = json.encodeToString(HttpSyncMultipartStartRequest.serializer(), request)
            .toByteArray(Charsets.UTF_8)
        val connection = openConnection(
            "POST",
            "/v1/kv-multipart/start",
            "application/json; charset=utf-8",
        )
        connection.doOutput = true
        connection.setFixedLengthStreamingMode(body.size)
        try {
            val context = currentCoroutineContext()
            return connection.runCancellable {
                connection.outputStream.use { it.write(body) }
                context.ensureActive()
                val (code, raw) = readBody(connection)
                context.ensureActive()
                if (code !in 200..299) throw HttpSyncException(parseError(code, raw))
                json.decodeFromString(HttpSyncMultipartStartResponse.serializer(), raw).uploadId
            }
        } finally {
            connection.disconnect()
        }
    }

    private suspend fun uploadMultipartParts(
        uploadId: String,
        file: File,
        onByteProgress: ((bytesTransferred: Long, totalBytes: Long) -> Unit)?,
    ): List<Int> {
        val parts = mutableListOf<Int>()
        val buffer = ByteArray(DEFAULT_STREAM_BUFFER_SIZE)
        var partNumber = 1
        val totalFileBytes = file.length()
        var remainingFileBytes = totalFileBytes
        // Bytes already accounted for by completed parts — the per-part counter is
        // offset by this so the callback reports whole-file progress, not per-part.
        var transferredFileBytes = 0L
        file.inputStream().buffered(DEFAULT_STREAM_BUFFER_SIZE).use { input ->
            while (remainingFileBytes > 0L) {
                currentCoroutineContext().ensureActive()
                val partLength = minOf(multipartPartSizeBytes, remainingFileBytes)
                uploadMultipartPart(
                    uploadId,
                    partNumber,
                    input,
                    partLength,
                    buffer,
                    transferredFileBytes,
                    totalFileBytes,
                    onByteProgress,
                )
                parts += partNumber
                partNumber += 1
                remainingFileBytes -= partLength
                transferredFileBytes += partLength
            }
        }
        return parts
    }

    private suspend fun uploadMultipartPart(
        uploadId: String,
        partNumber: Int,
        input: InputStream,
        partLength: Long,
        buffer: ByteArray,
        transferredFileBytesBefore: Long,
        totalFileBytes: Long,
        onByteProgress: ((bytesTransferred: Long, totalBytes: Long) -> Unit)?,
    ) {
        val connection = openConnection(
            "PUT",
            "/v1/kv-multipart/${urlEncode(uploadId)}/$partNumber",
            "application/octet-stream",
        )
        connection.doOutput = true
        connection.setFixedLengthStreamingMode(partLength)
        try {
            val context = currentCoroutineContext()
            connection.runCancellable {
                connection.outputStream.buffered(DEFAULT_STREAM_BUFFER_SIZE).use { output ->
                    var remaining = partLength
                    while (remaining > 0L) {
                        context.ensureActive()
                        val requested = minOf(buffer.size.toLong(), remaining).toInt()
                        val read = input.read(buffer, 0, requested)
                        if (read < 0) throw HttpSyncException("Multipart upload ended before part $partNumber was complete.")
                        output.write(buffer, 0, read)
                        remaining -= read.toLong()
                        onByteProgress?.invoke(
                            transferredFileBytesBefore + (partLength - remaining),
                            totalFileBytes,
                        )
                    }
                }
                context.ensureActive()
                val (code, raw) = readBody(connection)
                context.ensureActive()
                if (code !in 200..299) throw HttpSyncException(parseError(code, raw))
            }
        } finally {
            connection.disconnect()
        }
    }

    private suspend fun completeMultipartUpload(
        uploadId: String,
        parts: List<Int>,
        fallbackContentType: String,
    ): HttpSyncKvWriteResponse {
        val request = HttpSyncMultipartCompleteRequest(parts = parts)
        val body = json.encodeToString(HttpSyncMultipartCompleteRequest.serializer(), request)
            .toByteArray(Charsets.UTF_8)
        val connection = openConnection(
            "POST",
            "/v1/kv-multipart/${urlEncode(uploadId)}/complete",
            "application/json; charset=utf-8",
        )
        connection.doOutput = true
        connection.setFixedLengthStreamingMode(body.size)
        try {
            val context = currentCoroutineContext()
            return connection.runCancellable {
                connection.outputStream.use { it.write(body) }
                context.ensureActive()
                val (code, raw) = readBody(connection)
                context.ensureActive()
                if (code !in 200..299) throw HttpSyncException(parseError(code, raw))
                val response = json.decodeFromString(HttpSyncMultipartCompleteResponse.serializer(), raw)
                HttpSyncKvWriteResponse(
                    key = response.key,
                    lastModified = response.lastModified,
                    etag = response.etag,
                    size = response.size.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                    contentType = response.contentType ?: fallbackContentType,
                )
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun cancelMultipartUploadQuietly(uploadId: String) {
        val connection = openConnection("DELETE", "/v1/kv-multipart/${urlEncode(uploadId)}", contentType = null)
        try {
            connection.responseCode
        } catch (_: Exception) {
            // Best effort only; the user-facing error should be the original upload failure.
        } finally {
            connection.disconnect()
        }
    }

    override suspend fun get(key: String): HttpSyncKvFetched? = getWithLimit(key, maxBytes = null)

    override suspend fun getBounded(key: String, maxBytes: Int): HttpSyncKvFetched? {
        require(maxBytes >= 0)
        return getWithLimit(key, maxBytes)
    }

    private suspend fun getWithLimit(key: String, maxBytes: Int?): HttpSyncKvFetched? = withContext(ioDispatcher) {
        val connection = openConnection("GET", "/v1/kv/${encodeKey(key)}", contentType = null)
        try {
            val code = connection.responseCode
            if (code == 404) return@withContext null
            if (code !in 200..299) {
                val raw = (connection.errorStream ?: connection.inputStream)
                    ?.bufferedReader()?.use { it.readText() }
                    .orEmpty()
                throw HttpSyncException(parseError(code, raw))
            }
            // Defensive null check: per HTTP spec a 2xx with a body always has a non-null
            // inputStream, but a misbehaving proxy / Cloudflare worker could return 200 with
            // an empty payload, and dereferencing would NPE under that pathology.
            val declaredLength = connection.contentLengthLong
            if (maxBytes != null && declaredLength > maxBytes) {
                throw HttpSyncException("Blob at $key exceeds the $maxBytes-byte download limit.")
            }
            val body = connection.inputStream?.use { input ->
                if (maxBytes == null) input.readBytes() else input.readBytesBounded(key, maxBytes)
            } ?: byteArrayOf()
            HttpSyncKvFetched(
                body = body,
                contentType = connection.getHeaderField("Content-Type")
                    ?: "application/octet-stream",
                lastModified = connection.getHeaderField("Last-Modified") ?: "",
                etag = connection.getHeaderField("ETag") ?: "",
            )
        } catch (e: HttpSyncException) {
            throw e
        } catch (e: Exception) {
            throw HttpSyncException(friendlyMessage(e))
        } finally {
            connection.disconnect()
        }
    }

    override suspend fun downloadToFile(
        key: String,
        targetFile: File,
        onByteProgress: ((bytesTransferred: Long, totalBytes: Long) -> Unit)?,
    ): HttpSyncKvFileFetched? =
        withContext(ioDispatcher) {
            val connection = openConnection("GET", "/v1/kv/${encodeKey(key)}", contentType = null)
            try {
                val code = connection.responseCode
                if (code == 404) return@withContext null
                if (code !in 200..299) {
                    val raw = (connection.errorStream ?: connection.inputStream)
                        ?.bufferedReader()?.use { it.readText() }
                        .orEmpty()
                    throw HttpSyncException(parseError(code, raw))
                }
                targetFile.parentFile?.mkdirs()
                // `contentLengthLong` is -1 if the server didn't send Content-Length
                // (chunked transfer). The progress callback tolerates a non-positive
                // total — the consumer skips fraction math when total <= 0.
                val totalBytes = connection.contentLengthLong
                val buffer = ByteArray(DEFAULT_STREAM_BUFFER_SIZE)
                connection.inputStream.buffered(DEFAULT_STREAM_BUFFER_SIZE).use { input ->
                    targetFile.outputStream().buffered(DEFAULT_STREAM_BUFFER_SIZE).use { output ->
                        var transferred = 0L
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            transferred += read.toLong()
                            onByteProgress?.invoke(transferred, totalBytes)
                        }
                    }
                }
                HttpSyncKvFileFetched(
                    contentType = connection.getHeaderField("Content-Type")
                        ?: "application/octet-stream",
                    lastModified = connection.getHeaderField("Last-Modified") ?: "",
                    etag = connection.getHeaderField("ETag") ?: "",
                )
            } catch (e: HttpSyncException) {
                targetFile.delete()
                throw e
            } catch (e: Exception) {
                targetFile.delete()
                throw HttpSyncException(friendlyMessage(e))
            } finally {
                connection.disconnect()
            }
        }

    override suspend fun list(
        prefix: String?,
        since: String?,
        cursor: String?,
        limit: Int?,
    ): HttpSyncKvList = withContext(ioDispatcher) {
        val params = buildList {
            prefix?.let { add("prefix=" + urlEncode(it)) }
            since?.let { add("since=" + urlEncode(it)) }
            cursor?.let { add("cursor=" + urlEncode(it)) }
            limit?.let { add("limit=$it") }
        }
        val query = if (params.isEmpty()) "" else "?" + params.joinToString("&")
        val connection = openConnection("GET", "/v1/kv$query", contentType = null)
        try {
            val (code, raw) = readBody(connection)
            if (code !in 200..299) throw HttpSyncException(parseError(code, raw))
            json.decodeFromString(HttpSyncKvList.serializer(), raw)
        } catch (e: HttpSyncException) {
            throw e
        } catch (e: Exception) {
            throw HttpSyncException(friendlyMessage(e))
        } finally {
            connection.disconnect()
        }
    }

    override suspend fun delete(key: String) {
        withContext(ioDispatcher) {
            val connection = openConnection("DELETE", "/v1/kv/${encodeKey(key)}", contentType = null)
            try {
                val code = connection.responseCode
                // 204 (deleted) and 404 (already gone) both satisfy the post-condition.
                if (code == 204 || code == 404) return@withContext
                val raw = (connection.errorStream ?: connection.inputStream)
                    ?.bufferedReader()?.use { it.readText() }
                    .orEmpty()
                throw HttpSyncException(parseError(code, raw))
            } catch (e: HttpSyncException) {
                throw e
            } catch (e: Exception) {
                throw HttpSyncException(e.message ?: "HTTP sync request failed.")
            } finally {
                connection.disconnect()
            }
        }
    }

    private fun openConnection(method: String, path: String, contentType: String?): HttpURLConnection {
        val connection = URL(baseUrl.trimEnd('/') + path).openConnection() as HttpURLConnection
        connection.requestMethod = method
        connection.connectTimeout = 15_000
        connection.readTimeout = 30_000
        connection.setRequestProperty("Authorization", "Bearer $bearerToken")
        connection.setRequestProperty("Accept", "application/json")
        if (contentType != null) {
            connection.setRequestProperty("Content-Type", contentType)
        }
        return connection
    }

    private fun readBody(connection: HttpURLConnection): Pair<Int, String> {
        val code = connection.responseCode
        val stream = if (code in 200..299) connection.inputStream else connection.errorStream
        val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        return code to body
    }

    private suspend fun <T> HttpURLConnection.runCancellable(block: () -> T): T =
        suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { disconnect() }
            try {
                val result = block()
                if (continuation.isActive) {
                    continuation.resume(result)
                }
            } catch (e: Throwable) {
                if (continuation.isActive) {
                    continuation.resumeWithException(e)
                }
            }
        }

    private fun parseError(code: Int, rawBody: String): String {
        val message = runCatching {
            json.decodeFromString(HttpSyncKvError.serializer(), rawBody).error
        }.getOrNull()?.takeIf { it.isNotBlank() }
        return when {
            code == 401 -> "Server rejected the bearer token (HTTP 401). Check the token in Settings → Advanced → HTTP Sync."
            code == 403 -> "Server forbids this request (HTTP 403)."
            code == 404 -> "Not found on server (HTTP 404)."
            code in 500..599 && message != null -> "Server is having trouble (HTTP $code): $message"
            code in 500..599 -> "Server is having trouble (HTTP $code). Try again in a moment."
            message != null -> "HTTP $code: $message"
            else -> "HTTP sync request failed (HTTP $code)."
        }
    }

    /**
     * Turns raw JVM I/O exceptions into messages a user can act on. The transport sees
     * these as `e.message` from `HttpURLConnection`, which is fine for Logcat but useless
     * for an in-app error toast ("Failed to connect to ...example.com/...: connect failed:
     * ENETUNREACH (Network is unreachable)" is not friendly).
     */
    private fun friendlyMessage(e: Throwable): String {
        val raw = e.message.orEmpty()
        return when (e) {
            is java.net.UnknownHostException ->
                "Can't reach the sync server — check your network or the base URL."
            is java.net.ConnectException ->
                "Sync server is not reachable (connection refused). Is it up?"
            is java.net.SocketTimeoutException ->
                "Sync request timed out. Network is too slow or the server is hung."
            is javax.net.ssl.SSLException ->
                "TLS handshake with the sync server failed (${raw.ifBlank { "unknown SSL error" }})."
            is java.io.IOException -> {
                val lower = raw.lowercase()
                when {
                    "network is unreachable" in lower || "enetunreach" in lower ->
                        "No network — try again when you're back online."
                    "permission denied" in lower ->
                        "Network permission denied. Restart the app or check system settings."
                    else -> "Network error: ${raw.ifBlank { e.javaClass.simpleName }}."
                }
            }
            is kotlinx.serialization.SerializationException ->
                "Sync server returned malformed JSON. Either the URL is wrong or the server crashed."
            else -> raw.ifBlank { "HTTP sync request failed (${e.javaClass.simpleName})." }
        }
    }

    /**
     * Encodes a key for use in the URL path. The server's grammar
     * (`[A-Za-z0-9_.-]` per segment, `/`-separated) needs no real percent-encoding, but
     * routing through `URLEncoder` and restoring `/` keeps the client safe if a future
     * client-side bug ever produces a borderline character.
     */
    private fun encodeKey(key: String): String =
        key.split('/').joinToString("/") { segment ->
            urlEncode(segment)
        }

    private fun urlEncode(value: String): String =
        URLEncoder.encode(value, Charsets.UTF_8.name()).replace("+", "%20")
}

private const val DEFAULT_STREAM_BUFFER_SIZE = 64 * 1024
private const val MAX_MULTIPART_PART_SIZE_BYTES = 64L * 1024L * 1024L
private const val DEFAULT_MULTIPART_PART_SIZE_BYTES = MAX_MULTIPART_PART_SIZE_BYTES
private const val DEFAULT_MULTIPART_UPLOAD_THRESHOLD_BYTES = MAX_MULTIPART_PART_SIZE_BYTES
