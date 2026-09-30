package moe.antimony.hoshi.features.sync.v3

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import moe.antimony.hoshi.features.sync.http.HttpSyncKvFetched
import moe.antimony.hoshi.features.sync.http.HttpSyncKvKeyMeta
import moe.antimony.hoshi.features.sync.http.HttpSyncKvList
import moe.antimony.hoshi.features.sync.http.HttpSyncKvTransport
import moe.antimony.hoshi.features.sync.http.HttpSyncKvWriteResponse
import moe.antimony.hoshi.features.sync.http.HttpSyncException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.security.MessageDigest

class V3RemoteStateCacheTest {
    @get:Rule val temp = TemporaryFolder()

    @Test
    fun unchangedBooksReuseBodiesAcrossEngineRestartsAndFetchOnlyChangedKeys() = runBlocking {
        val directory = temp.newFolder()
        val server = CountingTransport()
        repeat(50) { index ->
            server.seed("books/book-$index/metadata", """{"title":"Book $index","contentType":"epub"}""")
            server.seed("books/book-$index/bookmark", """{"chapterIndex":1,"progress":0.5,"characterCount":20,"lastModified":"2026-09-30T12:00:00Z"}""")
            server.seed("books/book-$index/epub.manifest", """{"sha256":"sha256:archive","sizeBytes":20,"originalName":"Book $index","format":"epub"}""")
        }
        val first = V3RemoteState(directory).read(server) {}
        assertTrue(first.errors.toString(), first.errors.isEmpty())
        assertEquals(150, server.gets.count { it.startsWith("books/") })

        server.gets.clear()
        val second = V3RemoteState(directory).read(server) {}
        assertEquals(first.snapshot, second.snapshot)
        assertTrue(server.gets.toString(), server.gets.none { it.startsWith("books/") })

        server.seed("books/book-12/metadata", """{"title":"Changed title","contentType":"epub"}""")
        server.gets.clear()
        val third = V3RemoteState(directory).read(server) {}
        assertEquals(listOf("books/book-12/metadata"), server.gets.filter { it.startsWith("books/") })
        assertEquals("Changed title", third.snapshot.books.getValue("book-12").metadata?.title)
    }

    @Test
    fun corruptedDiskCacheRefetchesCurrentBodiesWithoutFailingSync() = runBlocking {
        val directory = temp.newFolder()
        val server = CountingTransport()
        val key = "books/book/metadata"
        server.seed(key, """{"title":"Recovered","contentType":"epub"}""")
        val expected = V3RemoteState(directory).read(server) {}
        directory.resolve(".http_sync_v3_remote_cache.json").writeText("{ interrupted cache")
        server.gets.clear()

        val restored = V3RemoteState(directory).read(server) {}

        assertEquals(expected.snapshot, restored.snapshot)
        assertTrue(restored.errors.toString(), restored.errors.isEmpty())
        assertEquals(listOf(key), server.gets.filter { it.startsWith("books/") })
        server.gets.clear()
        assertEquals(expected.snapshot, V3RemoteState(directory).read(server) {}.snapshot)
        assertTrue(server.gets.none { it.startsWith("books/") })
    }

    @Test
    fun cacheWriteFailureDoesNotFailSyncOrRetainStaleBodies() = runBlocking {
        val directory = temp.newFolder()
        // A directory at the sidecar path reliably rejects replacement on all test hosts.
        assertTrue(directory.resolve(".http_sync_v3_remote_cache.json").mkdir())
        val server = CountingTransport()
        val key = "books/book/metadata"
        server.seed(key, """{"title":"Before","contentType":"epub"}""")
        val reader = V3RemoteState(directory)
        assertTrue(reader.read(server) {}.errors.isEmpty())
        server.seed(key, """{"title":"After","contentType":"epub"}""")

        val changed = reader.read(server) {}

        assertTrue(changed.errors.toString(), changed.errors.isEmpty())
        assertEquals("After", changed.snapshot.books.getValue("book").metadata?.title)
        assertFalse("Failed writes must not leave temporary sidecars", directory.listFiles().orEmpty().any { it.name.endsWith(".tmp") })
    }

    @Test
    fun missingKeysAreNotResurrectedFromCachedBodies() = runBlocking {
        val server = CountingTransport()
        val reader = V3RemoteState(temp.newFolder())
        server.seed("books/removed/metadata", """{"title":"Removed","contentType":"epub"}""")
        reader.read(server) {}
        server.delete("books/removed/metadata")
        assertFalse(reader.read(server) {}.snapshot.books.containsKey("removed"))
    }

    @Test
    fun switchingAccountsDoesNotReuseAnotherAccountsBodiesEvenWithMatchingValidators() = runBlocking {
        val directory = temp.newFolder()
        val first = CountingTransport("account-one")
        first.seed("books/book/metadata", """{"title":"First","contentType":"epub"}""")
        V3RemoteState(directory).read(first) {}
        val second = CountingTransport("account-two")
        second.seed("books/book/metadata", """{"title":"Other","contentType":"epub"}""")
        val old = first.values.getValue("books/book/metadata")
        val other = second.values.getValue("books/book/metadata")
        second.values["books/book/metadata"] = other.copy(etag = old.etag, stamp = old.stamp)
        assertEquals("Other", V3RemoteState(directory).read(second) {}.snapshot.books.getValue("book").metadata?.title)
        assertTrue("books/book/metadata" in second.gets)
    }

    @Test
    fun getRacingAWriteIsNotCachedUnderTheOldListingVersion() = runBlocking {
        val directory = temp.newFolder()
        val server = CountingTransport()
        val key = "books/book/metadata"
        server.seed(key, """{"title":"First","contentType":"epub"}""")
        server.beforeGet = {
            server.beforeGet = null
            server.seed(key, """{"title":"Later","contentType":"epub"}""")
        }
        assertEquals("Later", V3RemoteState(directory).read(server) {}.snapshot.books.getValue("book").metadata?.title)
        server.gets.clear()
        assertEquals("Later", V3RemoteState(directory).read(server) {}.snapshot.books.getValue("book").metadata?.title)
        assertTrue(key in server.gets)
    }

    @Test
    fun cancelledRemoteReadPropagatesCancellation() = runBlocking {
        val server = CountingTransport()
        server.seed("books/book/metadata", """{"title":"First","contentType":"epub"}""")
        server.beforeGet = { throw CancellationException("cancelled") }
        var cancelled = false
        try {
            V3RemoteState(temp.newFolder()).read(server) {}
        } catch (_: CancellationException) {
            cancelled = true
        }
        assertTrue(cancelled)
    }

    @Test
    fun malformedPaginationStopsInsteadOfRepeatingRequestsForever() = runBlocking {
        for (mode in listOf("missing", "repeated", "cycle")) {
            var calls = 0
            val transport = object : HttpSyncKvTransport by CountingTransport() {
                override suspend fun list(prefix: String?, since: String?, cursor: String?, limit: Int?): HttpSyncKvList {
                    calls++
                    check(calls <= 3) { "Invalid pagination kept requesting pages" }
                    val next = when (mode) {
                        "missing" -> null
                        "repeated" -> "same"
                        else -> if (cursor == "a") "b" else "a"
                    }
                    return HttpSyncKvList(truncated = true, nextCursor = next)
                }
            }
            try {
                V3RemoteState(temp.newFolder()).read(transport) {}
                fail("Invalid pagination must fail the sync")
            } catch (expected: HttpSyncException) {
                assertEquals(moe.antimony.hoshi.R.string.http_sync_invalid_pagination, expected.messageResource)
            }
            assertEquals(when (mode) { "missing" -> 1; "repeated" -> 2; else -> 3 }, calls)
        }
    }

    private class CountingTransport(override val cacheIdentity: String = "test-account") : HttpSyncKvTransport {
        data class Value(val body: ByteArray, val etag: String, val stamp: String)
        val values = linkedMapOf<String, Value>()
        val gets = mutableListOf<String>()
        var beforeGet: (() -> Unit)? = null
        private var generation = 0

        fun seed(key: String, body: String) {
            val bytes = body.toByteArray()
            val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            values[key] = Value(bytes, "sha256:$hash", "revision-${++generation}")
        }

        override suspend fun get(key: String): HttpSyncKvFetched? {
            gets += key
            beforeGet?.invoke()
            return values[key]?.let { HttpSyncKvFetched(it.body, "application/json", it.stamp, it.etag) }
        }

        override suspend fun list(prefix: String?, since: String?, cursor: String?, limit: Int?) = HttpSyncKvList(
            values.filterKeys { prefix == null || it.startsWith(prefix) }.map { (key, value) ->
                HttpSyncKvKeyMeta(key, value.stamp, value.etag, value.body.size, "application/json")
            },
        )

        override suspend fun delete(key: String) { values.remove(key) }
        override suspend fun put(key: String, contentType: String, body: ByteArray): HttpSyncKvWriteResponse = error("unexpected PUT")
    }
}
