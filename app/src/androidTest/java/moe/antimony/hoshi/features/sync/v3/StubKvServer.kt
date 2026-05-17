package moe.antimony.hoshi.features.sync.v3

import fi.iki.elonen.NanoHTTPD
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.ByteArrayInputStream
import java.io.Closeable
import java.io.InputStream
import java.net.URLDecoder
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Collections
import java.util.LinkedHashMap
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * In-process HTTP server implementing the v2 KV protocol (see `docs/HTTP_SYNC_KV.md`)
 * so instrumented v3 sync tests can exercise the REAL [HttpSyncKvClient] over real
 * sockets, not a fake transport.
 *
 * Implementation guidance — see `docs/SYNC_V3_SPEC.md` § Test strategy → Integration:
 *
 *  - Uses [fi.iki.elonen.NanoHTTPD] (declared as `androidTestImplementation` in
 *    `app/build.gradle.kts`).
 *  - Binds to `127.0.0.1` on the requested port (`0` = ephemeral); [baseUrl]
 *    exposes the actual URL once [start] has run.
 *  - State lives in a thread-safe map keyed by stored key string. Multipart
 *    upload state lives in a parallel map keyed by opaque upload id.
 *  - `lastModified` is stamped as RFC 3339 UTC with millisecond precision. We
 *    keep a monotonic counter so two writes that hit the same wallclock
 *    millisecond still produce strictly increasing timestamps — tests rely on
 *    this for LWW ordering.
 *  - `etag` is `sha256:<hex>` of the body.
 *  - Bearer-token auth: every route checks `Authorization: Bearer <token>` and
 *    returns 401 with `{"error": "..."}` on mismatch or missing.
 *  - [setBehavior] is a per-request intercept hook used by failure-mode tests
 *    to inject 5xx / drops / delays. See [StubKvBehavior] / [BehaviorAction].
 *
 * Usage:
 * ```
 * StubKvServer().use { server ->
 *     val settings = HttpSyncSettings(baseUrl = server.baseUrl, bearerToken = server.token, enabled = true)
 *     val engine = V3SyncEngine(...)
 *     engine.syncOnce(settings)
 * }
 * ```
 *
 * Public surface MUST stay stable; multiple integration test files depend on it.
 */
class StubKvServer(
    /** Port to bind. `0` = ephemeral (recommended). */
    val port: Int = 0,
    /** Bearer token tests will send. Hardcoded by default for convenience. */
    val token: String = "stub-token",
) : Closeable {

    // ─── State ────────────────────────────────────────────────────────────────

    private data class StoredEntry(
        val body: ByteArray,
        val contentType: String,
        val lastModified: String,
        val etag: String,
    ) {
        // ByteArray identity equality is fine; nothing relies on structural equality.
        override fun equals(other: Any?): Boolean = this === other
        override fun hashCode(): Int = System.identityHashCode(this)
    }

    private data class MultipartUpload(
        val key: String,
        val contentType: String,
        // partNumber (1-based) -> bytes. Concurrent writes from a single client are
        // possible in theory; we serialize via the map's intrinsic lock at write time.
        val parts: MutableMap<Int, ByteArray> = Collections.synchronizedMap(LinkedHashMap()),
    )

    private val store: MutableMap<String, StoredEntry> =
        Collections.synchronizedMap(LinkedHashMap())
    private val uploads: MutableMap<String, MultipartUpload> =
        Collections.synchronizedMap(LinkedHashMap())

    // Monotonic clock for lastModified stamping. We start at the current wallclock
    // millis so timestamps look reasonable in logs, and bump by at least 1 ms per
    // write so two writes in the same instant still get strictly increasing
    // RFC 3339 strings (tests do LWW compares on these strings).
    private val monotonicMillis = AtomicLong(System.currentTimeMillis())

    private val behaviorRef = AtomicReference<StubKvBehavior>(StubKvBehavior { _, _ -> BehaviorAction.Passthrough })

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Volatile
    private var server: NanoStub? = null

    // ─── Public surface ───────────────────────────────────────────────────────

    /** `http://127.0.0.1:<actualPort>` once started. */
    val baseUrl: String
        get() {
            val srv = server ?: error("StubKvServer.start() has not been called.")
            return "http://127.0.0.1:${srv.listeningPort}"
        }

    /** Start listening. Idempotent. */
    fun start() {
        if (server != null) return
        val srv = NanoStub(port)
        // NanoHTTPD.start(int, boolean): timeout=0 means no socket read timeout, daemon=false
        // is the safe default for tests so threads don't get killed mid-request.
        srv.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
        server = srv
    }

    /** Stop and release the port. Idempotent. */
    fun stop() {
        server?.let {
            it.stop()
        }
        server = null
    }

    override fun close() = stop()

    /** All keys currently in the store. */
    fun keys(): List<String> = synchronized(store) { store.keys.toList() }

    /** Bytes stored at [key], or null if absent. */
    fun bytesAt(key: String): ByteArray? = synchronized(store) { store[key]?.body?.copyOf() }

    /** Clear ALL state. Useful at the start of each test method. */
    fun reset() {
        synchronized(store) { store.clear() }
        synchronized(uploads) { uploads.clear() }
        behaviorRef.set(StubKvBehavior { _, _ -> BehaviorAction.Passthrough })
    }

    /**
     * Install a behavior override that mutates responses for the next N requests
     * matching a predicate. See class kdoc for the recommended shape.
     */
    fun setBehavior(behavior: StubKvBehavior) {
        behaviorRef.set(behavior)
    }

    // ─── Server impl ──────────────────────────────────────────────────────────

    private inner class NanoStub(port: Int) : NanoHTTPD("127.0.0.1", port) {

        override fun serve(session: IHTTPSession): Response {
            val method = session.method?.name ?: "GET"
            val uri = session.uri ?: "/"

            // Behavior intercept FIRST — failure-mode tests script per-call overrides.
            when (val action = runCatching { behaviorRef.get().intercept(method, uri) }.getOrDefault(BehaviorAction.Passthrough)) {
                is BehaviorAction.FailWith ->
                    return jsonResponse(statusFor(action.code), action.body.ifBlank { errorJson("injected failure") })
                BehaviorAction.DropConnection -> {
                    // NanoHTTPD doesn't natively support yanking the socket. The cleanest
                    // we can do without forking the library is return a response whose body
                    // stream throws once NanoHTTPD tries to copy it to the wire — that
                    // aborts mid-response and Java's HttpURLConnection on the client side
                    // surfaces it as an IOException, which is what "dropped connection"
                    // smells like to the rest of the code. See class kdoc for the rationale.
                    val body = object : InputStream() {
                        override fun read(): Int = throw java.io.IOException("stub: dropped connection")
                        override fun read(b: ByteArray, off: Int, len: Int): Int =
                            throw java.io.IOException("stub: dropped connection")
                    }
                    val response = newChunkedResponse(Response.Status.OK, "application/octet-stream", body)
                    response.closeConnection(true)
                    return response
                }
                is BehaviorAction.Delay -> {
                    try {
                        Thread.sleep(action.millis)
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                    }
                    // fall through to default handler
                }
                BehaviorAction.Passthrough -> {
                    // fall through
                }
            }

            // Auth on every route.
            val auth = session.headers?.get("authorization")
            if (auth == null || auth != "Bearer $token") {
                return jsonResponse(Response.Status.UNAUTHORIZED, errorJson("invalid bearer token"))
            }

            return try {
                route(method, uri, session)
            } catch (e: Exception) {
                jsonResponse(Response.Status.INTERNAL_ERROR, errorJson("stub server error: ${e.message ?: e.javaClass.simpleName}"))
            }
        }

        private fun route(method: String, uri: String, session: IHTTPSession): Response {
            // Strip query string for path matching.
            val path = uri.substringBefore('?')

            // Multipart routes are matched first because /v1/kv would otherwise match
            // /v1/kv-multipart paths via substring.
            when {
                path == "/v1/kv-multipart/start" && method == "POST" ->
                    return handleMultipartStart(session)
                path == "/v1/kv" && method == "GET" ->
                    return handleList(session)
                path.startsWith("/v1/kv-multipart/") -> {
                    val rest = path.removePrefix("/v1/kv-multipart/")
                    val segments = rest.split('/')
                    return when {
                        segments.size == 2 && segments[1] == "complete" && method == "POST" ->
                            handleMultipartComplete(uploadId = segments[0], session = session)
                        segments.size == 1 && method == "DELETE" ->
                            handleMultipartCancel(uploadId = segments[0])
                        segments.size == 2 && method == "PUT" ->
                            handleMultipartPart(
                                uploadId = segments[0],
                                partNumber = segments[1].toIntOrNull()
                                    ?: return jsonResponse(Response.Status.BAD_REQUEST, errorJson("invalid part number")),
                                session = session,
                            )
                        else -> jsonResponse(Response.Status.NOT_FOUND, errorJson("not found: $method $path"))
                    }
                }
                path.startsWith("/v1/kv/") -> {
                    val key = decodeKey(path.removePrefix("/v1/kv/"))
                    return when (method) {
                        "PUT" -> handlePut(key, session)
                        "GET" -> handleGet(key)
                        "DELETE" -> handleDelete(key)
                        else -> jsonResponse(Response.Status.METHOD_NOT_ALLOWED, errorJson("method not allowed"))
                    }
                }
                else -> return jsonResponse(Response.Status.NOT_FOUND, errorJson("not found: $method $path"))
            }
        }

        private fun handlePut(key: String, session: IHTTPSession): Response {
            val body = readBody(session)
            val contentType = session.headers?.get("content-type") ?: "application/octet-stream"
            val entry = storeEntry(key, body, contentType)
            return jsonResponse(
                Response.Status.OK,
                json.encodeToString(
                    StubWriteResponse.serializer(),
                    StubWriteResponse(
                        key = key,
                        lastModified = entry.lastModified,
                        etag = entry.etag,
                        size = body.size,
                        contentType = entry.contentType,
                    ),
                ),
            )
        }

        private fun handleGet(key: String): Response {
            val entry = synchronized(store) { store[key] }
                ?: return jsonResponse(Response.Status.NOT_FOUND, errorJson("no such key: $key"))
            val response = newFixedLengthResponse(
                Response.Status.OK,
                entry.contentType,
                ByteArrayInputStream(entry.body),
                entry.body.size.toLong(),
            )
            response.addHeader("Last-Modified", entry.lastModified)
            response.addHeader("ETag", entry.etag)
            // NanoHTTPD doesn't always emit a Content-Type header from the mime parameter
            // alone in older versions, but newFixedLengthResponse(Status, mime, ...) does.
            // The explicit overwrite below makes the behavior bulletproof regardless.
            response.mimeType = entry.contentType
            return response
        }

        private fun handleDelete(key: String): Response {
            synchronized(store) { store.remove(key) }
            // 204 either way per spec — "404 is a no-op success from the client's view."
            return newFixedLengthResponse(Response.Status.NO_CONTENT, "application/json", "")
        }

        private fun handleList(session: IHTTPSession): Response {
            val params = session.parameters ?: emptyMap()
            val prefix = params["prefix"]?.firstOrNull().orEmpty()
            val since = params["since"]?.firstOrNull()
            val cursor = params["cursor"]?.firstOrNull()
            val requestedLimit = params["limit"]?.firstOrNull()?.toIntOrNull() ?: 500
            val limit = requestedLimit.coerceIn(1, 2000)

            val snapshot: List<Pair<String, StoredEntry>> =
                synchronized(store) {
                    store.entries
                        .map { it.key to it.value }
                        .sortedBy { it.first }
                }

            val filtered = snapshot.asSequence()
                .filter { (k, _) -> k.startsWith(prefix) }
                .filter { (_, v) -> since == null || v.lastModified > since }
                .filter { (k, _) -> cursor == null || k > cursor }
                .toList()

            val pageEntries = filtered.take(limit)
            val truncated = filtered.size > limit
            val keys = pageEntries.map { (k, v) ->
                StubKeyMeta(
                    key = k,
                    lastModified = v.lastModified,
                    etag = v.etag,
                    size = v.body.size,
                    contentType = v.contentType,
                )
            }
            val nextCursor = if (truncated && keys.isNotEmpty()) keys.last().key else null
            return jsonResponse(
                Response.Status.OK,
                json.encodeToString(
                    StubListResponse.serializer(),
                    StubListResponse(keys = keys, truncated = truncated, nextCursor = nextCursor),
                ),
            )
        }

        private fun handleMultipartStart(session: IHTTPSession): Response {
            val body = readBody(session)
            val request = try {
                json.decodeFromString(StubMultipartStartRequest.serializer(), body.toString(Charsets.UTF_8))
            } catch (e: Exception) {
                return jsonResponse(Response.Status.BAD_REQUEST, errorJson("malformed start body: ${e.message}"))
            }
            val uploadId = UUID.randomUUID().toString()
            synchronized(uploads) {
                uploads[uploadId] = MultipartUpload(key = request.key, contentType = request.contentType)
            }
            return jsonResponse(
                Response.Status.OK,
                json.encodeToString(
                    StubMultipartStartResponse.serializer(),
                    StubMultipartStartResponse(uploadId = uploadId),
                ),
            )
        }

        private fun handleMultipartPart(uploadId: String, partNumber: Int, session: IHTTPSession): Response {
            val upload = synchronized(uploads) { uploads[uploadId] }
                ?: return jsonResponse(Response.Status.NOT_FOUND, errorJson("no such upload: $uploadId"))
            if (partNumber < 1) {
                return jsonResponse(Response.Status.BAD_REQUEST, errorJson("partNumber must be >= 1"))
            }
            val body = readBody(session)
            upload.parts[partNumber] = body
            return jsonResponse(Response.Status.OK, "{}")
        }

        private fun handleMultipartComplete(uploadId: String, session: IHTTPSession): Response {
            val upload = synchronized(uploads) { uploads[uploadId] }
                ?: return jsonResponse(Response.Status.NOT_FOUND, errorJson("no such upload: $uploadId"))
            val body = readBody(session)
            val request = try {
                json.decodeFromString(StubMultipartCompleteRequest.serializer(), body.toString(Charsets.UTF_8))
            } catch (e: Exception) {
                return jsonResponse(Response.Status.BAD_REQUEST, errorJson("malformed complete body: ${e.message}"))
            }
            // Concatenate parts in the order the client specified. Missing parts are
            // a hard error — the alternative is a silently-truncated upload, which would
            // be a nightmare to debug in a test failure.
            val totalSize = request.parts.sumOf { partNumber ->
                upload.parts[partNumber]?.size?.toLong()
                    ?: return jsonResponse(
                        Response.Status.BAD_REQUEST,
                        errorJson("missing part $partNumber for upload $uploadId"),
                    )
            }
            if (totalSize > Int.MAX_VALUE) {
                return jsonResponse(Response.Status.PAYLOAD_TOO_LARGE, errorJson("upload exceeds 2 GiB"))
            }
            val merged = ByteArray(totalSize.toInt())
            var offset = 0
            for (partNumber in request.parts) {
                val partBytes = upload.parts.getValue(partNumber)
                System.arraycopy(partBytes, 0, merged, offset, partBytes.size)
                offset += partBytes.size
            }
            val entry = storeEntry(upload.key, merged, upload.contentType)
            synchronized(uploads) { uploads.remove(uploadId) }
            return jsonResponse(
                Response.Status.OK,
                json.encodeToString(
                    StubWriteResponse.serializer(),
                    StubWriteResponse(
                        key = upload.key,
                        lastModified = entry.lastModified,
                        etag = entry.etag,
                        size = merged.size,
                        contentType = entry.contentType,
                    ),
                ),
            )
        }

        private fun handleMultipartCancel(uploadId: String): Response {
            synchronized(uploads) { uploads.remove(uploadId) }
            return newFixedLengthResponse(Response.Status.NO_CONTENT, "application/json", "")
        }

        // ─── helpers ──────────────────────────────────────────────────────────

        private fun readBody(session: IHTTPSession): ByteArray {
            val contentLength = session.headers?.get("content-length")?.toIntOrNull() ?: 0
            if (contentLength <= 0) return ByteArray(0)
            val input = session.inputStream ?: return ByteArray(0)
            val buf = ByteArray(contentLength)
            var read = 0
            while (read < contentLength) {
                val n = input.read(buf, read, contentLength - read)
                if (n <= 0) break
                read += n
            }
            return if (read == contentLength) buf else buf.copyOf(read)
        }

        private fun storeEntry(key: String, body: ByteArray, contentType: String): StoredEntry {
            val lastModified = nextLastModified()
            val etag = "sha256:" + sha256Hex(body)
            val entry = StoredEntry(
                body = body,
                contentType = contentType,
                lastModified = lastModified,
                etag = etag,
            )
            synchronized(store) { store[key] = entry }
            return entry
        }

        private fun nextLastModified(): String {
            // Strictly monotonic millis so two writes that arrive in the same wallclock
            // millisecond still produce different lastModified strings. Tests rely on
            // string comparison for ordering.
            while (true) {
                val now = System.currentTimeMillis()
                val prev = monotonicMillis.get()
                val next = if (now > prev) now else prev + 1
                if (monotonicMillis.compareAndSet(prev, next)) {
                    return RFC_3339_MILLIS.format(Instant.ofEpochMilli(next).atOffset(ZoneOffset.UTC))
                }
            }
        }

        private fun jsonResponse(status: Response.IStatus, jsonBody: String): Response {
            val response = newFixedLengthResponse(status, "application/json; charset=utf-8", jsonBody)
            response.mimeType = "application/json; charset=utf-8"
            return response
        }

        private fun statusFor(code: Int): Response.IStatus = when (code) {
            200 -> Response.Status.OK
            201 -> Response.Status.CREATED
            204 -> Response.Status.NO_CONTENT
            400 -> Response.Status.BAD_REQUEST
            401 -> Response.Status.UNAUTHORIZED
            403 -> Response.Status.FORBIDDEN
            404 -> Response.Status.NOT_FOUND
            409 -> Response.Status.CONFLICT
            412 -> Response.Status.PRECONDITION_FAILED
            413 -> Response.Status.PAYLOAD_TOO_LARGE
            500 -> Response.Status.INTERNAL_ERROR
            503 -> Response.Status.SERVICE_UNAVAILABLE
            else -> object : Response.IStatus {
                override fun getDescription(): String = "$code Custom"
                override fun getRequestStatus(): Int = code
            }
        }
    }

    private fun errorJson(message: String): String =
        json.encodeToString(StubErrorBody.serializer(), StubErrorBody(error = message))

    private fun decodeKey(raw: String): String =
        raw.split('/').joinToString("/") { URLDecoder.decode(it, Charsets.UTF_8.name()) }

    private fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        val sb = StringBuilder(digest.size * 2)
        for (b in digest) sb.append("%02x".format(b))
        return sb.toString()
    }

    companion object {
        private val RFC_3339_MILLIS: DateTimeFormatter =
            DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)
    }
}

// ─── Local wire-protocol shapes ───────────────────────────────────────────────
//
// The production-code wire shapes in HttpSyncKv.kt are file-private, so we
// redeclare equivalents here. These MUST match the wire JSON expected by
// HttpSyncKvClient — same field names, same nullability, same defaults.

@Serializable
private data class StubWriteResponse(
    val key: String,
    val lastModified: String,
    val etag: String,
    val size: Int,
    val contentType: String,
)

@Serializable
private data class StubKeyMeta(
    val key: String,
    val lastModified: String,
    val etag: String,
    val size: Int,
    val contentType: String,
)

@Serializable
private data class StubListResponse(
    val keys: List<StubKeyMeta> = emptyList(),
    val truncated: Boolean = false,
    val nextCursor: String? = null,
)

@Serializable
private data class StubMultipartStartRequest(
    val key: String,
    val contentType: String,
)

@Serializable
private data class StubMultipartStartResponse(
    val uploadId: String,
)

@Serializable
private data class StubMultipartCompleteRequest(
    val parts: List<Int>,
)

@Serializable
private data class StubErrorBody(
    val error: String,
)

// ─── Behavior override hook ───────────────────────────────────────────────────

/**
 * Hook for tests to inject failure modes (drop connection, return 5xx, delay
 * response, ...). Default implementation is pass-through.
 */
fun interface StubKvBehavior {
    /**
     * Called for every incoming request before the default handler runs. Return
     * [BehaviorAction.Passthrough] to use the default, or any of the failure
     * actions to override.
     */
    fun intercept(method: String, path: String): BehaviorAction
}

sealed interface BehaviorAction {
    data object Passthrough : BehaviorAction
    data class FailWith(val code: Int, val body: String = "") : BehaviorAction
    data object DropConnection : BehaviorAction
    data class Delay(val millis: Long) : BehaviorAction
}
