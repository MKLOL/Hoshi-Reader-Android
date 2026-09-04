package moe.antimony.hoshi.features.sync.integration

import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import moe.antimony.hoshi.features.sync.http.HttpSyncKvClient
import org.junit.rules.ExternalResource
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.concurrent.thread

/**
 * Boots `tools/sync-test-server/sync_test_server.py` — a real HTTP implementation of the KV
 * API documented in `docs/HTTP_SYNC_KV.md` — for the lifetime of one test class, so the
 * production [HttpSyncKvClient] and both sync engines run over actual HTTP instead of the
 * in-memory `FakeKvTransport`.
 *
 * Requires a Python 3 interpreter (`python3` on the PATH, or the `HOSHI_PYTHON` environment
 * variable). A missing interpreter FAILS these tests; it never silently skips them, because
 * skipped integration coverage is exactly how the 0.11.3 content-hash regression shipped.
 */
class SyncTestServerRule : ExternalResource() {
    lateinit var server: SyncTestServer
        private set

    override fun before() {
        server = SyncTestServer.start()
    }

    override fun after() {
        server.close()
    }
}

class SyncTestServer private constructor(
    private val process: Process,
    val port: Int,
    val token: String,
) : AutoCloseable {
    val baseUrl: String = "http://127.0.0.1:$port"

    private val json = Json { ignoreUnknownKeys = true }

    /** The production client, pointed at this server. Small multipart sizes exercise that path. */
    fun client(
        multipartThresholdBytes: Long = DEFAULT_MULTIPART_BYTES,
        multipartPartSizeBytes: Long = DEFAULT_MULTIPART_BYTES,
    ): HttpSyncKvClient = HttpSyncKvClient(
        baseUrl = baseUrl,
        bearerToken = token,
        ioDispatcher = Dispatchers.IO,
        multipartPartSizeBytes = multipartPartSizeBytes,
        multipartThresholdBytes = multipartThresholdBytes,
    )

    /** Drops every key, unfinished upload, and the request log. */
    fun reset() {
        post("/_test/reset")
    }

    fun clearRequests() {
        post("/_test/requests/clear")
    }

    /** Every request the server handled since the last [clearRequests] / [reset]. */
    fun requests(): List<RecordedRequest> =
        json.decodeFromString(RequestLog.serializer(), get("/_test/requests")).requests

    /** Raw JSON snapshot of the whole store (bodies base64) — loadable via [load]. */
    fun dumpJson(): String = get("/_test/dump")

    fun dump(): JsonObject = json.parseToJsonElement(dumpJson()).jsonObject

    fun load(snapshotJson: String) {
        post("/_test/load", snapshotJson.toByteArray(Charsets.UTF_8), "application/json; charset=utf-8")
    }

    /** `key -> etag` for every stored key, straight from the server's own bookkeeping. */
    fun etags(): Map<String, String> = dump().getValue("entries").jsonArray.associate { entry ->
        val obj = entry.jsonObject
        obj.getValue("key").jsonPrimitive.content to obj.getValue("etag").jsonPrimitive.content
    }

    private fun get(path: String): String = request("GET", path, null, null)

    private fun post(path: String, body: ByteArray = ByteArray(0), contentType: String? = null): String =
        request("POST", path, body, contentType)

    private fun request(method: String, path: String, body: ByteArray?, contentType: String?): String {
        val connection = URL(baseUrl + path).openConnection() as HttpURLConnection
        connection.requestMethod = method
        connection.setRequestProperty("Authorization", "Bearer $token")
        contentType?.let { connection.setRequestProperty("Content-Type", it) }
        if (body != null) {
            connection.doOutput = true
            connection.setFixedLengthStreamingMode(body.size)
            connection.outputStream.use { it.write(body) }
        }
        try {
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            check(code in 200..299) { "$method $path -> HTTP $code $text" }
            return text
        } finally {
            connection.disconnect()
        }
    }

    override fun close() {
        process.destroy()
        if (!process.waitFor(5, TimeUnit.SECONDS)) process.destroyForcibly()
    }

    companion object {
        private const val DEFAULT_MULTIPART_BYTES: Long = 64L * 1024L * 1024L
        private const val SCRIPT_RELATIVE_PATH = "tools/sync-test-server/sync_test_server.py"

        fun start(token: String = "it-" + UUID.randomUUID()): SyncTestServer {
            val script = locateScript()
            val python = System.getenv("HOSHI_PYTHON")?.takeIf { it.isNotBlank() } ?: "python3"
            val process = try {
                ProcessBuilder(python, script.path, "--port", "0", "--token", token).start()
            } catch (e: IOException) {
                throw IllegalStateException(
                    "Cannot start the sync test server: '$python' is not runnable. " +
                        "Install Python 3 or point HOSHI_PYTHON at an interpreter.",
                    e,
                )
            }
            val stderr = StringBuffer()
            thread(isDaemon = true, name = "sync-test-server-stderr") {
                process.errorStream.bufferedReader().forEachLine { stderr.append(it).append('\n') }
            }
            val ready = CompletableFuture<Int>()
            thread(isDaemon = true, name = "sync-test-server-stdout") {
                val reader = process.inputStream.bufferedReader()
                try {
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line.startsWith("SYNC_TEST_SERVER_READY port=")) {
                            ready.complete(line.substringAfter("port=").trim().toInt())
                        }
                    }
                } catch (e: Exception) {
                    ready.completeExceptionally(e)
                }
                ready.completeExceptionally(IllegalStateException("server exited before it was ready"))
            }
            val port = try {
                ready.get(30, TimeUnit.SECONDS)
            } catch (e: Exception) {
                process.destroyForcibly()
                val reason = if (e is TimeoutException) "did not report readiness within 30 s" else e.message
                throw IllegalStateException("Sync test server failed to start ($reason).\n$stderr", e)
            }
            return SyncTestServer(process, port, token)
        }

        private fun locateScript(): File {
            var dir: File? = File(System.getProperty("user.dir") ?: ".").canonicalFile
            repeat(6) {
                val candidate = dir?.resolve(SCRIPT_RELATIVE_PATH)
                if (candidate != null && candidate.isFile) return candidate
                dir = dir?.parentFile
            }
            error("$SCRIPT_RELATIVE_PATH not found above ${System.getProperty("user.dir")}")
        }
    }
}

@Serializable
data class RecordedRequest(val method: String, val path: String, val status: Int, val size: Int) {
    val isPayloadUpload: Boolean
        get() = (method == "PUT" && (path.endsWith("/payload.zip") || path.endsWith("/epub.zip"))) ||
            path == "/v1/kv-multipart/start"
    val isPayloadDownload: Boolean
        get() = method == "GET" && (path.endsWith("/payload.zip") || path.endsWith("/epub.zip"))
    val isManifestWrite: Boolean
        get() = method == "PUT" && path.endsWith(".manifest")
}

@Serializable
private data class RequestLog(val requests: List<RecordedRequest>)
