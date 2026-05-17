package moe.antimony.hoshi.features.sync.v3

import java.io.Closeable

/**
 * In-process HTTP server implementing the v2 KV protocol (see `docs/HTTP_SYNC_KV.md`)
 * so instrumented v3 sync tests can exercise the REAL [HttpSyncKvClient] over real
 * sockets, not a fake transport.
 *
 * Implementation guidance — see `docs/SYNC_V3_SPEC.md` § Test strategy → Integration:
 *
 *  - Use [fi.iki.elonen.NanoHTTPD] (already a candidate androidTest dependency;
 *    add it under `androidTestImplementation` in `app/build.gradle.kts`).
 *  - Bind to `127.0.0.1` on an ephemeral port (`new NanoHTTPD(0)`); expose
 *    [baseUrl] so tests build the [HttpSyncSettings] without hardcoding a port.
 *  - Store state in a `LinkedHashMap<String, Entry>` where `Entry` has body bytes,
 *    contentType, lastModified, etag, size. Same shape as the server-side SQLite
 *    schema in HTTP_SYNC_KV.md.
 *  - Stamp `lastModified` as RFC 3339 UTC with millisecond precision. To keep tests
 *    deterministic, accept an injected clock; default to monotonically increasing
 *    so ordering is stable.
 *  - Stamp `etag` as `sha256:<hex>` of the body.
 *  - Implement EVERY route in HTTP_SYNC_KV.md, including the multipart upload
 *    family. Multipart parts live in a separate map keyed by uploadId until
 *    `complete` concatenates them into the main store.
 *  - Pagination: `cursor` is the last key from the previous response. SQL-style
 *    `key > cursor` filter. Default `limit=500`, max `2000`.
 *  - Bearer-token auth: check `Authorization: Bearer <token>` against a configured
 *    token. 401 on mismatch, 401 on missing.
 *  - Support an injectable failure mode so [V3SyncFailureModeTest] can request
 *    "return 503 on the next GET for this key" or "drop connection mid-stream
 *    on this PUT". Recommend a `Behavior` interface that the test installs via
 *    [setBehavior], with a `default` no-op that just delegates to the in-memory
 *    store.
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

    /** `http://127.0.0.1:<actualPort>` once started. */
    val baseUrl: String
        get() = TODO("Implementation agent: return the bound URL once start() has run")

    /** Start listening. Idempotent. */
    fun start() {
        TODO("Implementation agent: fill")
    }

    /** Stop and release the port. Idempotent. */
    fun stop() {
        TODO("Implementation agent: fill")
    }

    override fun close() = stop()

    // ─── State inspection helpers for tests ──────────────────────────────────

    /** All keys currently in the store. */
    fun keys(): List<String> {
        TODO("Implementation agent: fill")
    }

    /** Bytes stored at [key], or null if absent. */
    fun bytesAt(key: String): ByteArray? {
        TODO("Implementation agent: fill")
    }

    /** Clear ALL state. Useful at the start of each test method. */
    fun reset() {
        TODO("Implementation agent: fill")
    }

    /**
     * Install a behavior override that mutates responses for the next N requests
     * matching a predicate. See class kdoc for the recommended shape.
     */
    fun setBehavior(behavior: StubKvBehavior) {
        TODO("Implementation agent: fill")
    }
}

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
