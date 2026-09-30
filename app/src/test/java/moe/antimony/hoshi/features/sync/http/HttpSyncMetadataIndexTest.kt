package moe.antimony.hoshi.features.sync.http

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException
import java.time.Instant

class HttpSyncMetadataIndexTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test
    fun unchangedLargeNamespaceUsesOneDeltaRequestRegardlessOfKeyCount() = runBlocking {
        for (count in listOf(10, 10_001)) {
            val transport = MetadataTransport().apply {
                repeat(count) { number -> record("books/%05d/statistics".format(number), number.toLong()) }
            }
            val index = HttpSyncMetadataIndex(temporaryFolder.newFolder()) { NOW }
            assertEquals(count, index.list(transport).applicationKeys().size)
            transport.requests.clear()

            assertEquals(count, index.list(transport).applicationKeys().size)

            assertEquals("An unchanged namespace of $count keys needs one delta request", 1, transport.requests.size)
            assertNotNull(transport.requests.single().since)
        }
    }

    @Test
    fun restartReusesPersistedMetadataAndThePollingBoundary() = runBlocking {
        val root = temporaryFolder.newFolder()
        val first = MetadataTransport().apply {
            record("a", 10)
            record("b", 20)
        }
        val expected = HttpSyncMetadataIndex(root) { NOW }.list(first)
        val expectedFloor = stamp(first.serverSeconds - 1)
        val afterRestart = MetadataTransport().apply { metadata.putAll(first.metadata) }

        assertEquals(expected, HttpSyncMetadataIndex(root) { NOW + 1_000 }.list(afterRestart))

        assertEquals(1, afterRestart.requests.size)
        assertEquals(expectedFloor, afterRestart.requests.single().since)
    }

    @Test
    fun writeAtThePreviousTimestampBoundaryIsNotSkipped() = runBlocking {
        val transport = MetadataTransport().apply { record("book", 10, "old") }
        val index = HttpSyncMetadataIndex(temporaryFolder.newFolder()) { NOW }
        index.list(transport)
        val boundary = transport.serverSeconds
        transport.record("book", boundary, "new")
        transport.requests.clear()

        assertEquals("new", index.list(transport).single { it.key == "book" }.etag)

        assertEquals(stamp(boundary - 1), transport.requests.single().since)
    }

    @Test
    fun largeDeltaWithEqualTimestampsDoesNotRepeatItsPagesOnEveryPoll() = runBlocking {
        val transport = MetadataTransport().apply { record("initial", 0) }
        val index = HttpSyncMetadataIndex(temporaryFolder.newFolder()) { NOW }
        index.list(transport)
        repeat(5_000) { number -> transport.record("books/%05d/statistics".format(number), 20) }
        assertEquals(5_001, index.list(transport).applicationKeys().size)
        transport.requests.clear()

        assertEquals(5_001, index.list(transport).applicationKeys().size)

        assertEquals(1, transport.requests.size)
        assertNotNull(transport.requests.single().since)
    }

    @Test
    fun laterPaginationWritesCannotHideAnUpdateToAnAlreadyScannedKey() = runBlocking {
        val transport = MetadataTransport(pageSize = 2).apply {
            record("a", 10, "old-a")
            record("b", 20)
            record("z", 30, "old-z")
            beforeList = { request ->
                if (request.cursor != null) {
                    beforeList = null
                    // a was already scanned; z changes still later and appears in this page.
                    record("a", 40, "new-a")
                    record("z", 50, "new-z")
                }
            }
        }
        val index = HttpSyncMetadataIndex(temporaryFolder.newFolder()) { NOW }
        val duringWrites = index.list(transport).associateBy { it.key }
        assertEquals("new-z", duringWrites.getValue("z").etag)

        val afterWrites = index.list(transport).associateBy { it.key }

        assertEquals("new-a", afterWrites.getValue("a").etag)
        assertEquals("new-z", afterWrites.getValue("z").etag)
    }

    @Test
    fun failedOrInvalidPaginationNeverCommitsAPartialListing() = runBlocking {
        for (failure in listOf("network", "missing-cursor", "repeated-cursor")) {
            val root = temporaryFolder.newFolder()
            val transport = MetadataTransport(pageSize = 2).apply {
                record("a", 10)
                record("z", 20)
            }
            val index = HttpSyncMetadataIndex(root) { NOW }
            val baseline = index.list(transport)
            val expectedFloor = stamp(transport.serverSeconds - 1)
            transport.record("b", 30)
            transport.record("c", 40)
            transport.record("d", 50)
            transport.overridePage = { request ->
                if (request.cursor == null) null else when (failure) {
                    "network" -> throw IOException("interrupted listing")
                    "missing-cursor" -> HttpSyncKvList(truncated = true)
                    else -> HttpSyncKvList(truncated = true, nextCursor = request.cursor)
                }
            }
            try {
                index.list(transport)
                fail("$failure must fail the listing")
            } catch (expected: Exception) {
                assertTrue(expected is IOException || expected is HttpSyncException)
            }

            // A restart must retain the last completed snapshot, not the failed page's b/c.
            transport.overridePage = null
            listOf("b", "c", "d").forEach { transport.metadata.remove(it) }
            transport.requests.clear()
            val restarted = HttpSyncMetadataIndex(root) { NOW + 1_000 }.list(transport)
            assertEquals(baseline.applicationKeys(), restarted.applicationKeys())
            assertEquals(expectedFloor, transport.requests.first().since)
        }
    }

    @Test
    fun switchingNamespaceCannotReuseAnotherAccountsKeysOrWatermark() = runBlocking {
        val root = temporaryFolder.newFolder()
        val index = HttpSyncMetadataIndex(root) { NOW }
        val first = MetadataTransport(cacheIdentity = "account-a").apply { record("only-a", 100) }
        val second = MetadataTransport(cacheIdentity = "account-b").apply { record("only-b", 10) }
        index.list(first)

        assertEquals(listOf("only-b"), index.list(second).applicationKeys())
        assertNull(second.requests.single().since)
        first.requests.clear()
        assertEquals(listOf("only-a"), index.list(first).applicationKeys())
        assertNull(first.requests.single().since)

        second.requests.clear()
        assertEquals(listOf("only-b"), HttpSyncMetadataIndex(root) { NOW }.list(second).applicationKeys())
        assertNull(second.requests.single().since)
    }

    @Test
    fun dailyFullAuditFindsHardDeletesThatAnIncrementalListingCannotSee() = runBlocking {
        var now = NOW
        val index = HttpSyncMetadataIndex(temporaryFolder.newFolder()) { now }
        val transport = MetadataTransport().apply {
            record("keep", 10)
            record("deleted", 20)
        }
        index.list(transport)
        transport.metadata.remove("deleted")
        now += 23 * 60 * 60 * 1_000L
        assertTrue(index.list(transport).any { it.key == "deleted" })

        now += 60 * 60 * 1_000L
        transport.requests.clear()
        val audited = index.list(transport)

        assertEquals(listOf("keep"), audited.applicationKeys())
        assertNull(transport.requests.single().since)
        assertFalse(audited.any { it.key == "deleted" })
    }

    @Test
    fun transportsWithoutAnIdentityAlwaysListFresh() = runBlocking {
        val transport = MetadataTransport(cacheIdentity = null).apply { record("old", 10) }
        val index = HttpSyncMetadataIndex(temporaryFolder.newFolder()) { NOW }
        index.list(transport)
        transport.metadata.clear()
        transport.record("new", 20)
        transport.requests.clear()

        assertEquals(listOf("new"), index.list(transport).applicationKeys())
        assertNull(transport.requests.single().since)
    }

    private data class Request(val since: String?, val cursor: String?, val limit: Int?)

    /** Models key-ordered, exclusive-since pagination and server-stamped checkpoint writes. */
    private class MetadataTransport(
        override val cacheIdentity: String? = "account",
        private val pageSize: Int = 2_000,
    ) : HttpSyncKvTransport {
        val metadata = sortedMapOf<String, HttpSyncKvKeyMeta>()
        val requests = mutableListOf<Request>()
        var serverSeconds = 0L
            private set
        var beforeList: ((Request) -> Unit)? = null
        var overridePage: ((Request) -> HttpSyncKvList?)? = null

        fun record(key: String, seconds: Long, etag: String = "etag-$seconds") {
            serverSeconds = maxOf(serverSeconds, seconds)
            metadata[key] = HttpSyncKvKeyMeta(key, stamp(seconds), etag, 10, "application/json")
        }

        override suspend fun list(prefix: String?, since: String?, cursor: String?, limit: Int?): HttpSyncKvList {
            val request = Request(since, cursor, limit)
            requests += request
            beforeList?.invoke(request)
            overridePage?.invoke(request)?.let { return it }
            val matching = metadata.values.filter { entry ->
                (prefix == null || entry.key.startsWith(prefix)) &&
                    (since == null || Instant.parse(entry.lastModified) > Instant.parse(since)) &&
                    (cursor == null || entry.key > cursor)
            }
            val page = matching.take(minOf(pageSize, limit ?: pageSize))
            val truncated = matching.size > page.size
            return HttpSyncKvList(page, truncated, page.lastOrNull()?.key.takeIf { truncated })
        }

        override suspend fun get(key: String): HttpSyncKvFetched? = error("metadata-only test")
        override suspend fun put(key: String, contentType: String, body: ByteArray): HttpSyncKvWriteResponse {
            check(key.startsWith("sync/maps/")) { "Only an index checkpoint may be written" }
            val next = maxOf(serverSeconds, metadata.values.maxOfOrNull {
                Instant.parse(it.lastModified).epochSecond - Instant.parse(stamp(0)).epochSecond
            } ?: 0) + 1
            record(key, next)
            return HttpSyncKvWriteResponse(key, stamp(next), "etag-$next", body.size, contentType)
        }
        override suspend fun delete(key: String): Unit = error("metadata-only test")
    }

    private companion object {
        val NOW: Long = Instant.parse("2026-09-30T12:00:00Z").toEpochMilli()
        fun stamp(seconds: Long): String = Instant.parse("2026-09-01T00:00:00Z").plusSeconds(seconds).toString()
        fun List<HttpSyncKvKeyMeta>.applicationKeys(): List<String> =
            map { it.key }.filterNot { it.startsWith("sync/maps/") }.sorted()
    }
}
