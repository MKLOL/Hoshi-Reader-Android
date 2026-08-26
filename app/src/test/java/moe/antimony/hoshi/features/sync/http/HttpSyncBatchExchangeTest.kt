package moe.antimony.hoshi.features.sync.http

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import moe.antimony.hoshi.epub.BookMetadata
import moe.antimony.hoshi.epub.BookRepository
import moe.antimony.hoshi.epub.Bookmark
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.Base64
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

class HttpSyncBatchExchangeTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test
    fun preparedSnapshotServesUnchangedListsAndGetsWithOneHttpCall() = runBlocking {
        val repository = BookRepository(temporaryFolder.newFolder())
        val state = HttpSyncBatchState(repository)
        val calls = AtomicInteger()
        val server = HttpServer.create(
            InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0),
            0,
        )
        val body = "{\"title\":\"Cached\",\"contentType\":\"epub\"}"
        val encoded = Base64.getEncoder().encodeToString(body.toByteArray())
        server.createContext("/") { exchange ->
            calls.incrementAndGet()
            exchange.requestBody.use { it.readBytes() }
            val response = """{
                "keys":[{
                    "key":"books/cached/metadata",
                    "lastModified":"2026-08-26T12:00:00.000Z",
                    "etag":"sha256:cached",
                    "size":${body.length},
                    "contentType":"application/json",
                    "bodyBase64":"$encoded"
                }],
                "removedKeys":[],
                "writeAcks":[]
            }""".trimIndent().toByteArray()
            exchange.responseHeaders.set("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, response.size.toLong())
            exchange.responseBody.use { it.write(response) }
        }
        server.start()
        try {
            val transport = HttpSyncBatchKvTransport(
                HttpSyncSettings(
                    baseUrl = "http://127.0.0.1:${server.address.port}",
                    bearerToken = "token",
                ),
                state,
            )

            transport.prepare()
            assertEquals(1, transport.list(prefix = "books/").keys.size)
            assertEquals(body, transport.get("books/cached/metadata")!!.body.toString(Charsets.UTF_8))
            assertEquals(body, transport.get("books/cached/metadata")!!.body.toString(Charsets.UTF_8))
            assertEquals("exchange + cached list/GETs must be one request", 1, calls.get())
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun pageTurnsAcrossBooksCoalesceAndClearOnlyAfterAcknowledgement() = runBlocking {
        val repository = BookRepository(temporaryFolder.newFolder())
        val state = HttpSyncBatchState(repository)
        val first = createBook(repository, "First", "first")
        val second = createBook(repository, "Second", "second")

        repository.saveBookmark(first, Bookmark(0, 0.2, 20, 100.0))
        state.queueBookmark(first, "First", "first")
        val firstMutation = state.exchangeRequest().writes.single().mutationId

        // Another turn for the same book replaces its queued body, rather than adding
        // another HTTP write. A different book joins the same request.
        repository.saveBookmark(first, Bookmark(0, 0.4, 40, 101.0))
        state.queueBookmark(first, "First", "first")
        repository.saveBookmark(second, Bookmark(2, 0.8, 80, 102.0))
        state.queueBookmark(second, "Second", "second")

        val request = state.exchangeRequest()
        assertEquals(2, request.writes.size)
        assertNotEquals(firstMutation, request.writes.first { it.key == bookmarkKey("first") }.mutationId)
        val firstBlob = Json.decodeFromString(
            HttpSyncBookmarkBlob.serializer(),
            String(Base64.getDecoder().decode(request.writes.first { it.key == bookmarkKey("first") }.bodyBase64)),
        )
        assertEquals(0.4, firstBlob.progress, 0.0)

        // An acknowledgement for only one exact mutation must leave the other durable.
        val accepted = request.writes.first()
        state.applyExchange(
            HttpSyncExchangeResponse(
                keys = listOf(
                    HttpSyncExchangeKey(
                        key = accepted.key,
                        lastModified = "2026-08-26T12:00:00.000Z",
                        etag = "sha256:accepted",
                        size = 64,
                        contentType = HttpSyncPusher.JSON_CONTENT_TYPE,
                        bodyBase64 = accepted.bodyBase64,
                    ),
                ),
                writeAcks = listOf(
                    HttpSyncExchangeWriteAck(accepted.key, accepted.mutationId, true, "sha256:accepted"),
                ),
            ),
        )
        assertTrue(state.hasPending())
        assertEquals(1, state.exchangeRequest().writes.size)
        assertEquals("sha256:accepted", state.exchangeRequest().knownEtags[accepted.key])

        val remaining = state.exchangeRequest().writes.single()
        state.applyExchange(
            HttpSyncExchangeResponse(
                writeAcks = listOf(
                    HttpSyncExchangeWriteAck(remaining.key, remaining.mutationId, true, "sha256:remaining"),
                ),
            ),
        )
        assertFalse(state.hasPending())
    }

    private suspend fun createBook(repository: BookRepository, title: String, syncId: String) =
        repository.createBookDirectoryForImportedTitle(title).also { root ->
            repository.saveMetadata(
                root,
                BookMetadata(
                    id = UUID.randomUUID().toString(),
                    title = title,
                    cover = null,
                    folder = root.name,
                    lastAccess = 0.0,
                    syncId = syncId,
                ),
            )
        }
}
