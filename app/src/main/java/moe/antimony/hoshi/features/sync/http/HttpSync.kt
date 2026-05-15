package moe.antimony.hoshi.features.sync.http

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import moe.antimony.hoshi.epub.BookRepository
import moe.antimony.hoshi.epub.Bookmark
import moe.antimony.hoshi.epub.ContentType
import moe.antimony.hoshi.epub.bookContentType
import moe.antimony.hoshi.features.ai.AiChatEntry
import moe.antimony.hoshi.features.ai.AiChatHistoryStore
import moe.antimony.hoshi.features.ai.AiChatLog
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.Instant
import kotlin.math.max

/**
 * Android-only HTTP sync against a user-supplied server. See `docs/HTTP_SYNC.md` for the
 * wire protocol. This file contains the four things that go together:
 *
 *  1. The on-the-wire record shapes ([HttpSyncBookRecord] and friends), `@Serializable` so
 *     `kotlinx.serialization` round-trips them with the same JSON the Python reference
 *     server emits.
 *  2. [HttpSyncClient], a thin `HttpURLConnection` wrapper that implements the four routes.
 *  3. [HttpSyncManager], which enumerates local books, builds records, and reconciles them
 *     against the server with last-write-wins on the `clientModified` field.
 *  4. The Apple-reference-seconds <-> RFC 3339 conversion (the on-disk `lastModified`
 *     uses Apple's epoch; the wire format uses RFC 3339 so it reads sensibly in a server
 *     log).
 */

/** Apple's reference date in Unix epoch seconds (2001-01-01T00:00:00Z). */
private const val APPLE_REFERENCE_EPOCH: Long = 978_307_200L

// ----- Wire records ----------------------------------------------------------------------

@Serializable
data class HttpSyncBookSummary(
    val syncId: String,
    val clientModified: String? = null,
    val serverModified: String? = null,
)

@Serializable
data class HttpSyncBooksList(
    val books: List<HttpSyncBookSummary> = emptyList(),
)

/**
 * A book's full sync record. Mirrors the JSON shape documented in `docs/HTTP_SYNC.md`.
 * `bookmark` and `aiChatLog` are nullable so an EPUB record skips `aiChatLog` and a
 * never-opened book skips `bookmark`.
 */
@Serializable
data class HttpSyncBookRecord(
    val syncId: String,
    val title: String,
    val contentType: HttpSyncContentType,
    val bookmark: HttpSyncBookmark? = null,
    val aiChatLog: HttpSyncAiChatLog? = null,
    val clientModified: String,
    val serverModified: String? = null,
)

@Serializable
enum class HttpSyncContentType {
    @SerialName("epub") Epub,
    @SerialName("mokuro") Mokuro;

    fun toLocal(): ContentType = when (this) {
        Epub -> ContentType.Epub
        Mokuro -> ContentType.Mokuro
    }

    companion object {
        fun fromLocal(content: ContentType): HttpSyncContentType = when (content) {
            ContentType.Epub -> Epub
            ContentType.Mokuro -> Mokuro
        }
    }
}

@Serializable
data class HttpSyncBookmark(
    val chapterIndex: Int,
    val progress: Double,
    val characterCount: Int,
    val lastModified: String? = null,
)

@Serializable
data class HttpSyncAiChatLog(
    val entries: List<HttpSyncAiChatEntry> = emptyList(),
)

@Serializable
data class HttpSyncAiChatEntry(
    val bubbleText: String,
    val prompt: String,
    val model: String,
    val response: String,
    val timestampSeconds: Double,
)

@Serializable
data class HttpSyncPutResponse(
    val serverModified: String? = null,
)

@Serializable
private data class HttpSyncError(
    val error: String? = null,
)

class HttpSyncException(message: String) : Exception(message)

// ----- syncId helper ----------------------------------------------------------------------

/**
 * Computes a stable, server-safe sync id from a book's title. The rule mirrors what the
 * iOS-shared Drive sync already does for its folder names — lowercase, replace non-ASCII-
 * alphanumerics with `_`, collapse runs, trim. Two devices that imported the same titled
 * book compute the same id and therefore converge on the server.
 */
internal fun deriveSyncId(title: String?): String? {
    val raw = title?.trim().orEmpty()
    if (raw.isEmpty()) return null
    val sanitized = buildString {
        for (ch in raw.lowercase()) {
            if (ch in 'a'..'z' || ch in '0'..'9') append(ch) else append('_')
        }
    }
        .replace(Regex("_+"), "_")
        .trim('_')
    return sanitized.ifEmpty { null }
}

// ----- HTTP client ------------------------------------------------------------------------

/**
 * Subset of the protocol that the manager actually needs. An interface so tests can fake
 * the network without spinning up an in-process HTTP server.
 */
interface HttpSyncTransport {
    suspend fun listBooks(): HttpSyncBooksList
    suspend fun getBook(syncId: String): HttpSyncBookRecord?
    suspend fun putBook(record: HttpSyncBookRecord): HttpSyncPutResponse
    @Suppress("unused") // exposed for future "delete on local delete" wiring
    suspend fun deleteBook(syncId: String)
}

class HttpSyncClient(
    private val baseUrl: String,
    private val bearerToken: String,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : HttpSyncTransport {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    override suspend fun listBooks(): HttpSyncBooksList =
        request("GET", "/v1/books") { body ->
            json.decodeFromString(HttpSyncBooksList.serializer(), body)
        }

    override suspend fun getBook(syncId: String): HttpSyncBookRecord? =
        requestOptional("GET", "/v1/books/${syncId.urlEncode()}") { body ->
            json.decodeFromString(HttpSyncBookRecord.serializer(), body)
        }

    override suspend fun putBook(record: HttpSyncBookRecord): HttpSyncPutResponse =
        request(
            method = "PUT",
            path = "/v1/books/${record.syncId.urlEncode()}",
            requestBody = json.encodeToString(HttpSyncBookRecord.serializer(), record),
        ) { body ->
            if (body.isBlank()) HttpSyncPutResponse() else
                json.decodeFromString(HttpSyncPutResponse.serializer(), body)
        }

    override suspend fun deleteBook(syncId: String) {
        request<Unit>("DELETE", "/v1/books/${syncId.urlEncode()}") { }
    }

    private suspend fun <T> request(
        method: String,
        path: String,
        requestBody: String? = null,
        parse: (String) -> T,
    ): T = withContext(ioDispatcher) {
        val connection = (URL(baseUrl + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15_000
            readTimeout = 30_000
            setRequestProperty("Authorization", "Bearer $bearerToken")
            setRequestProperty("Accept", "application/json")
            if (requestBody != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
        }
        try {
            if (requestBody != null) {
                connection.outputStream.use { it.write(requestBody.toByteArray(Charsets.UTF_8)) }
            }
            val code = connection.responseCode
            val rawBody = (if (code in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()
                ?.use { it.readText() }
                .orEmpty()
            if (code !in 200..299) {
                throw HttpSyncException(parseError(code, rawBody))
            }
            parse(rawBody)
        } catch (e: HttpSyncException) {
            throw e
        } catch (e: Exception) {
            throw HttpSyncException(e.message ?: "HTTP sync request failed.")
        } finally {
            connection.disconnect()
        }
    }

    /** Like [request] but returns `null` on `404`. */
    private suspend fun <T> requestOptional(
        method: String,
        path: String,
        parse: (String) -> T,
    ): T? = withContext(ioDispatcher) {
        val connection = (URL(baseUrl + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15_000
            readTimeout = 30_000
            setRequestProperty("Authorization", "Bearer $bearerToken")
            setRequestProperty("Accept", "application/json")
        }
        try {
            val code = connection.responseCode
            if (code == 404) return@withContext null
            val rawBody = (if (code in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()
                ?.use { it.readText() }
                .orEmpty()
            if (code !in 200..299) {
                throw HttpSyncException(parseError(code, rawBody))
            }
            parse(rawBody)
        } catch (e: HttpSyncException) {
            throw e
        } catch (e: Exception) {
            throw HttpSyncException(e.message ?: "HTTP sync request failed.")
        } finally {
            connection.disconnect()
        }
    }

    private fun parseError(code: Int, rawBody: String): String {
        val message = runCatching {
            json.decodeFromString(HttpSyncError.serializer(), rawBody).error
        }.getOrNull()?.takeIf { it.isNotBlank() }
        return when {
            code == 401 -> "Server rejected the bearer token (HTTP 401). Check the token in Settings → Advanced → HTTP Sync."
            message != null -> "HTTP $code: $message"
            else -> "HTTP sync request failed (HTTP $code)."
        }
    }

    private fun String.urlEncode(): String =
        URLEncoder.encode(this, Charsets.UTF_8).replace("+", "%20")
}

// ----- Manager ---------------------------------------------------------------------------

/**
 * Outcome of one [HttpSyncManager.syncOnce] pass. Used by the UI to show a one-line
 * "synced N up, M down" summary plus any per-book errors.
 */
data class HttpSyncResult(
    val uploaded: Int,
    val downloaded: Int,
    val upToDate: Int,
    val remoteOnly: Int,
    val errors: List<String>,
) {
    val totalLocal: Int get() = uploaded + downloaded + upToDate
    fun summary(): String {
        val parts = mutableListOf<String>()
        if (uploaded > 0) parts += "$uploaded uploaded"
        if (downloaded > 0) parts += "$downloaded downloaded"
        if (upToDate > 0) parts += "$upToDate up to date"
        if (remoteOnly > 0) parts += "$remoteOnly remote-only"
        if (parts.isEmpty()) parts += "nothing to sync"
        return parts.joinToString(", ")
    }
}

/**
 * Reconciles local book state with the configured HTTP sync server.
 *
 * Algorithm per pass:
 *  1. List local books and build their per-book sync records.
 *  2. `GET /v1/books` → the server's list with `clientModified` timestamps.
 *  3. For each local book, compare local `clientModified` to the server's:
 *     - local strictly newer  → `PUT` the local record.
 *     - server strictly newer → `GET` the full record, write `bookmark.json` and
 *       `ai_chat_log.json` accordingly.
 *     - equal                 → skip.
 *  4. Server books with no local counterpart are reported as `remoteOnly` (the user has to
 *     import the book on this device — v1 does not download payloads).
 *
 * The whole flow is one IO-dispatched coroutine; callers wrap it in their own scope.
 */
class HttpSyncManager(
    private val bookRepository: BookRepository,
    private val aiHistoryStore: AiChatHistoryStore = AiChatHistoryStore(),
    private val transportFactory: (HttpSyncSettings) -> HttpSyncTransport = { settings ->
        HttpSyncClient(settings.baseUrl, settings.bearerToken)
    },
) {
    /**
     * One full reconciliation pass. Throws [HttpSyncException] for protocol-level failures
     * (network, 401, malformed JSON); per-book errors are collected into [HttpSyncResult.errors]
     * and the pass continues so a single corrupt book does not block the rest.
     */
    suspend fun syncOnce(settings: HttpSyncSettings): HttpSyncResult {
        require(settings.isConfigured) { "HTTP sync is not configured." }
        val transport = transportFactory(settings)

        val localEntries = bookRepository.loadBookEntries()
        val localById: Map<String, LocalBookState> = buildMap {
            for (entry in localEntries) {
                val syncId = deriveSyncId(entry.metadata.title) ?: continue
                val bookmark = runCatching { bookRepository.loadBookmark(entry.root) }.getOrNull()
                val aiChatLog = if (bookContentType(entry.root) == ContentType.Mokuro) {
                    runCatching { aiHistoryStore.load(entry.root) }.getOrNull()
                } else {
                    null
                }
                put(
                    syncId,
                    LocalBookState(
                        root = entry.root,
                        title = entry.metadata.title.orEmpty(),
                        contentType = bookContentType(entry.root),
                        bookmark = bookmark,
                        aiChatLog = aiChatLog,
                    ),
                )
            }
        }

        val remote = transport.listBooks().books.associateBy { it.syncId }

        var uploaded = 0
        var downloaded = 0
        var upToDate = 0
        val errors = mutableListOf<String>()

        for ((syncId, local) in localById) {
            try {
                val localRecord = local.toRecord(syncId)
                val remoteSummary = remote[syncId]
                val cmp = compareModified(localRecord.clientModified, remoteSummary?.clientModified)
                when {
                    remoteSummary == null || cmp > 0 -> {
                        transport.putBook(localRecord)
                        uploaded += 1
                    }
                    cmp < 0 -> {
                        val full = transport.getBook(syncId)
                        if (full == null) {
                            // Listed but missing on follow-up GET — treat as upload candidate.
                            transport.putBook(localRecord)
                            uploaded += 1
                        } else {
                            applyToLocal(local.root, full)
                            downloaded += 1
                        }
                    }
                    else -> upToDate += 1
                }
            } catch (e: HttpSyncException) {
                errors += "${local.title.ifBlank { syncId }}: ${e.message}"
            }
        }

        val remoteOnly = remote.keys.count { it !in localById }

        return HttpSyncResult(
            uploaded = uploaded,
            downloaded = downloaded,
            upToDate = upToDate,
            remoteOnly = remoteOnly,
            errors = errors,
        )
    }

    private suspend fun applyToLocal(bookRoot: File, record: HttpSyncBookRecord) {
        record.bookmark?.let { remote ->
            val localBookmark = Bookmark(
                chapterIndex = remote.chapterIndex,
                progress = remote.progress,
                characterCount = remote.characterCount,
                lastModified = remote.lastModified
                    ?.let(::rfc3339ToAppleSeconds)
                    ?: bookRepository.currentAppleReferenceDateSeconds(),
            )
            bookRepository.saveBookmark(bookRoot, localBookmark)
        }
        record.aiChatLog?.let { remote ->
            val entries = remote.entries.map {
                AiChatEntry(
                    bubbleText = it.bubbleText,
                    prompt = it.prompt,
                    model = it.model,
                    response = it.response,
                    timestampSeconds = it.timestampSeconds,
                )
            }
            aiHistoryStore.save(bookRoot, AiChatLog(entries))
        }
    }

    private data class LocalBookState(
        val root: File,
        val title: String,
        val contentType: ContentType,
        val bookmark: Bookmark?,
        val aiChatLog: AiChatLog?,
    ) {
        fun toRecord(syncId: String): HttpSyncBookRecord = HttpSyncBookRecord(
            syncId = syncId,
            title = title,
            contentType = HttpSyncContentType.fromLocal(contentType),
            bookmark = bookmark?.let {
                HttpSyncBookmark(
                    chapterIndex = it.chapterIndex,
                    progress = it.progress,
                    characterCount = it.characterCount,
                    lastModified = it.lastModified?.let(::appleSecondsToRfc3339),
                )
            },
            aiChatLog = aiChatLog?.let { log ->
                HttpSyncAiChatLog(
                    entries = log.entries.map {
                        HttpSyncAiChatEntry(
                            bubbleText = it.bubbleText,
                            prompt = it.prompt,
                            model = it.model,
                            response = it.response,
                            timestampSeconds = it.timestampSeconds,
                        )
                    },
                )
            },
            clientModified = clientModifiedFor(bookmark, aiChatLog),
        )

        private fun clientModifiedFor(bookmark: Bookmark?, aiChatLog: AiChatLog?): String {
            val maxBookmark = bookmark?.lastModified ?: Double.NEGATIVE_INFINITY
            val maxChat = aiChatLog?.entries
                ?.maxOfOrNull { it.timestampSeconds }
                ?: Double.NEGATIVE_INFINITY
            val best = max(maxBookmark, maxChat)
            return if (best.isFinite()) appleSecondsToRfc3339(best) else INSTANT_ZERO
        }
    }

    private companion object {
        const val INSTANT_ZERO: String = "1970-01-01T00:00:00Z"
    }
}

// ----- timestamp helpers -----------------------------------------------------------------

/** Compare two RFC-3339 timestamps as strings; lexicographic order is chronological for `Z`-suffixed UTC. */
internal fun compareModified(a: String?, b: String?): Int {
    val left = a ?: ""
    val right = b ?: ""
    return left.compareTo(right)
}

internal fun appleSecondsToRfc3339(appleSeconds: Double): String {
    val unixSeconds = appleSeconds + APPLE_REFERENCE_EPOCH
    val instant = Instant.ofEpochMilli((unixSeconds * 1000.0).toLong())
    return instant.toString()
}

internal fun rfc3339ToAppleSeconds(rfc3339: String): Double {
    val instant = runCatching { Instant.parse(rfc3339) }.getOrNull() ?: return 0.0
    val unixMillis = instant.toEpochMilli()
    return unixMillis / 1000.0 - APPLE_REFERENCE_EPOCH
}
