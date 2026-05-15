package moe.antimony.hoshi.features.sync.http

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

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

@Serializable
private data class HttpSyncKvError(val error: String? = null)

class HttpSyncException(message: String) : Exception(message)

// ----- Transport interface ---------------------------------------------------------------

/**
 * The four blob-store operations the sync code needs. An interface so unit tests fake the
 * network with an in-memory map and never touch [HttpSyncKvClient].
 */
interface HttpSyncKvTransport {
    suspend fun put(key: String, contentType: String, body: ByteArray): HttpSyncKvWriteResponse

    /** Returns `null` on `404` (key not present). All other non-2xx responses throw. */
    suspend fun get(key: String): HttpSyncKvFetched?

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
            connection.outputStream.use { it.write(body) }
            val (code, raw) = readBody(connection)
            if (code !in 200..299) throw HttpSyncException(parseError(code, raw))
            json.decodeFromString(HttpSyncKvWriteResponse.serializer(), raw)
        } catch (e: HttpSyncException) {
            throw e
        } catch (e: Exception) {
            throw HttpSyncException(friendlyMessage(e))
        } finally {
            connection.disconnect()
        }
    }

    override suspend fun get(key: String): HttpSyncKvFetched? = withContext(ioDispatcher) {
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
            val body = connection.inputStream?.use { it.readBytes() } ?: byteArrayOf()
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

    override suspend fun list(
        prefix: String?,
        since: String?,
        cursor: String?,
        limit: Int?,
    ): HttpSyncKvList = withContext(ioDispatcher) {
        val params = buildList {
            prefix?.let { add("prefix=" + URLEncoder.encode(it, Charsets.UTF_8)) }
            since?.let { add("since=" + URLEncoder.encode(it, Charsets.UTF_8)) }
            cursor?.let { add("cursor=" + URLEncoder.encode(it, Charsets.UTF_8)) }
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
        val connection = URL(baseUrl + path).openConnection() as HttpURLConnection
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

    private fun parseError(code: Int, rawBody: String): String {
        val message = runCatching {
            json.decodeFromString(HttpSyncKvError.serializer(), rawBody).error
        }.getOrNull()?.takeIf { it.isNotBlank() }
        return when {
            code == 401 -> "Server rejected the bearer token (HTTP 401). Check the token in Settings → Advanced → HTTP Sync."
            code == 403 -> "Server forbids this request (HTTP 403)."
            code == 404 -> "Not found on server (HTTP 404)."
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
            URLEncoder.encode(segment, Charsets.UTF_8).replace("+", "%20")
        }
}
