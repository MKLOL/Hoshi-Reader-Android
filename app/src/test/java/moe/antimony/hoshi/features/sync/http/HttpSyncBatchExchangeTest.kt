package moe.antimony.hoshi.features.sync.http

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.awaitAll
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import moe.antimony.hoshi.epub.BookMetadata
import moe.antimony.hoshi.epub.BookRepository
import moe.antimony.hoshi.epub.Bookmark
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest
import java.util.UUID

class HttpSyncBatchExchangeTest {
    @get:Rule val temporaryFolder = TemporaryFolder()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test
    fun noChangeUsesOneMetadataCall() = runBlocking {
        val repository = BookRepository(temporaryFolder.newFolder())
        val state = HttpSyncBatchState(repository)
        val transport = CountingMapTransport()
        state.publishMaps(transport)
        transport.resetCounts()

        val result = state.syncMaps(transport)

        assertFalse(result.needsBootstrap)
        assertFalse(result.booksChanged)
        assertEquals(1, transport.listCalls)
        assertEquals(0, transport.getCalls)
        assertEquals(0, transport.putCalls)
    }

    @Test
    fun pageTurnsAcrossBooksUseOneBatchedPut() = runBlocking {
        val repository = BookRepository(temporaryFolder.newFolder())
        val state = HttpSyncBatchState(repository)
        val transport = CountingMapTransport()
        val first = createBook(repository, "First", "first")
        val second = createBook(repository, "Second", "second")
        state.publishMaps(transport)

        repository.saveBookmark(first, Bookmark(0, 0.2, 20, 100.0))
        state.queueBookmark(first, "First", "first")
        repository.saveBookmark(first, Bookmark(0, 0.4, 40, 101.0))
        state.queueBookmark(first, "First", "first")
        repository.saveBookmark(second, Bookmark(2, 0.8, 80, 102.0))
        state.queueBookmark(second, "Second", "second")
        transport.resetCounts()

        state.syncMaps(transport)

        assertEquals(1, transport.listCalls)
        assertEquals(0, transport.getCalls)
        assertEquals(1, transport.putCalls)
        assertFalse(state.hasPending())
        val map = transport.bookmarks()
        assertEquals(2, map.size)
        assertEquals(0.4, map.getValue("first").value!!.progress, 0.0)
        assertEquals(0.8, map.getValue("second").value!!.progress, 0.0)
    }

    @Test
    fun simultaneousDevicesWriteDifferentShardsWithoutLosingEitherBookmark() = runBlocking {
        val firstRepository = BookRepository(temporaryFolder.newFolder("device-a"))
        val secondRepository = BookRepository(temporaryFolder.newFolder("device-b"))
        val firstState = HttpSyncBatchState(firstRepository)
        val secondState = HttpSyncBatchState(secondRepository)
        val transport = CountingMapTransport()
        val firstA = createBook(firstRepository, "First", "first")
        createBook(firstRepository, "Second", "second")
        createBook(secondRepository, "First", "first")
        val secondB = createBook(secondRepository, "Second", "second")
        firstState.publishMaps(transport)
        secondState.publishMaps(transport)

        firstRepository.saveBookmark(firstA, Bookmark(0, 0.7, 70, 800_000_000.0))
        firstState.queueBookmark(firstA, "First", "first")
        secondRepository.saveBookmark(secondB, Bookmark(0, 0.8, 80, 800_000_001.0))
        secondState.queueBookmark(secondB, "Second", "second")

        listOf(
            async { firstState.syncMaps(transport) },
            async { secondState.syncMaps(transport) },
        ).awaitAll()

        assertEquals(0.7, transport.bookmarks().getValue("first").value!!.progress, 0.0)
        assertEquals(0.8, transport.bookmarks().getValue("second").value!!.progress, 0.0)
        assertEquals(2, transport.bookmarkShardCount())
    }

    @Test
    fun unchangedLegacyKeyIsAcknowledgedButOldClientEditTriggersReconcile() = runBlocking {
        val repository = BookRepository(temporaryFolder.newFolder())
        val state = HttpSyncBatchState(repository)
        val transport = CountingMapTransport()
        val root = createBook(repository, "Example", "example")
        repository.saveBookmark(root, Bookmark(0, 0.4, 40, 800_000_000.0))
        transport.put(
            bookmarkKey("example"),
            "application/json; charset=utf-8",
            json.encodeToString(
                HttpSyncBookmarkBlob.serializer(),
                HttpSyncBookmarkBlob(0, 0.4, 40, "2026-05-09T06:13:20Z", 0),
            ).toByteArray(),
        )
        val bootstrap = state.syncMaps(transport)
        state.publishMaps(transport, bootstrap.observedLegacyEtags)
        assertFalse(state.syncMaps(transport).otherChanged)

        val oldClientEdit = json.encodeToString(
            HttpSyncBookmarkBlob.serializer(),
            HttpSyncBookmarkBlob(0, 0.9, 90, "2026-08-26T12:30:00Z", 50),
        ).toByteArray()
        transport.put(bookmarkKey("example"), "application/json; charset=utf-8", oldClientEdit)
        assertTrue(state.syncMaps(transport).otherChanged)
    }

    @Test
    fun publishAcknowledgesOnlyThePreflightSnapshot() = runBlocking {
        val repository = BookRepository(temporaryFolder.newFolder())
        val state = HttpSyncBatchState(repository)
        val transport = CountingMapTransport()
        val legacyKey = "books/example/bookmark.json"
        transport.put(legacyKey, "application/json", byteArrayOf(1))

        val preflight = state.syncMaps(transport)
        transport.put(legacyKey, "application/json", byteArrayOf(2))
        state.publishMaps(transport, preflight.observedLegacyEtags)

        assertTrue(state.syncMaps(transport).otherChanged)
    }

    @Test
    fun fullCycleRunnerCoalescesConcurrentManualAndBackgroundScans() = runBlocking {
        val runner = HttpSyncFullCycleRunner(this)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var invocations = 0
        suspend fun result() = HttpSyncResult(
            uploadedBookmarks = 0,
            uploadedChatEntries = 0,
            uploadedMetadata = 0,
            downloadedBookmarks = 0,
            downloadedChatEntries = 0,
            remoteOnlyBooks = 0,
            errors = emptyList(),
        )

        val manual = async {
            runner.run {
                invocations += 1
                entered.complete(Unit)
                release.await()
                result()
            }
        }
        entered.await()
        val background = async {
            runner.run {
                invocations += 1
                result()
            }
        }
        yield()

        assertEquals(1, invocations)
        release.complete(Unit)
        listOf(manual, background).awaitAll()
        assertEquals(1, invocations)
    }

    @Test
    fun coldCacheNeverErasesItsExistingServerShard() = runBlocking {
        val files = temporaryFolder.newFolder()
        val repository = BookRepository(files)
        val installationId = "11111111-1111-1111-1111-111111111111"
        val firstState = HttpSyncBatchState(repository, installationId = installationId)
        val transport = CountingMapTransport()
        val root = createBook(repository, "Example", "example")
        repository.saveBookmark(root, Bookmark(0, 0.6, 60, 800_000_000.0))
        firstState.publishMaps(transport)
        repository.booksDirectory.resolve(".http_sync_exchange_cache.json").delete()
        transport.resetCounts()

        val coldState = HttpSyncBatchState(repository, installationId = installationId)
        coldState.syncMaps(transport)

        assertEquals(0, transport.putCalls)
        assertEquals(0.6, transport.bookmarks().getValue("example").value!!.progress, 0.0)
    }

    @Test
    fun metadataPaginationStillFindsMaps() = runBlocking {
        val repository = BookRepository(temporaryFolder.newFolder())
        val state = HttpSyncBatchState(repository)
        val transport = CountingMapTransport()
        state.publishMaps(transport)
        repeat(2_001) { index ->
            transport.put("books/filler-${index.toString().padStart(4, '0')}/chat/x", "text/plain", byteArrayOf(1))
        }
        repository.booksDirectory.resolve(".http_sync_exchange_cache.json").delete()
        transport.resetCounts()

        HttpSyncBatchState(repository).syncMaps(transport)

        assertEquals(2, transport.listCalls)
        assertEquals(2, transport.getCalls)
    }

    @Test
    fun newerTimestampWinsEvenAgainstHigherStaleRevision() = runBlocking {
        val repository = BookRepository(temporaryFolder.newFolder())
        val state = HttpSyncBatchState(repository)
        val transport = CountingMapTransport()
        val root = createBook(repository, "Example", "example")
        repository.saveBookmark(root, Bookmark(0, 0.7, 70, 800_000_000.0))
        state.queueBookmark(root, "Example", "example")
        state.publishMaps(transport)

        val localMap = transport.bookmarks()
        val local = localMap.getValue("example")
        val staleRemote = local.copy(
            etag = "sha256:" + "1".repeat(64),
            lastModified = "2026-01-01T00:00:00Z",
            value = local.value!!.copy(
                progress = 0.1,
                lastModified = "2026-01-01T00:00:00Z",
                rev = 999,
            ),
        )
        transport.storeBookmarkMap(mapOf("example" to staleRemote))

        state.syncMaps(transport)

        assertEquals(0.7, repository.loadBookmark(root)!!.progress, 0.0)
        assertEquals(0.7, transport.bookmarks()
            .getValue("example").value!!.progress, 0.0)
    }

    @Test
    fun newerRemoteIsAppliedAndEmitted() = runBlocking {
        val repository = BookRepository(temporaryFolder.newFolder())
        val state = HttpSyncBatchState(repository)
        val transport = CountingMapTransport()
        val root = createBook(repository, "Example", "example")
        repository.saveBookmark(root, Bookmark(0, 0.2, 20, 100.0))
        state.publishMaps(transport)
        val update = async { state.remoteBookmarkUpdates.first() }
        transport.storeBookmarkMap(
            mapOf(
                "example" to entry(
                    progress = 0.9,
                    timestamp = "2026-08-26T12:05:00Z",
                    rev = 0,
                ),
            ),
        )

        val result = state.syncMaps(transport)

        assertEquals(1, result.downloadedBookmarks)
        assertEquals("example", update.await())
        assertEquals(0.9, repository.loadBookmark(root)!!.progress, 0.0)
    }

    @Test
    fun missingMapsRequireBootstrapWithoutWriting() = runBlocking {
        val repository = BookRepository(temporaryFolder.newFolder())
        val state = HttpSyncBatchState(repository)
        val transport = CountingMapTransport()

        val result = state.syncMaps(transport)

        assertTrue(result.needsBootstrap)
        assertEquals(1, transport.listCalls)
        assertEquals(0, transport.putCalls)
    }

    @Test
    fun pageTurnQueuedDuringPutRemainsDurable() = runBlocking {
        val repository = BookRepository(temporaryFolder.newFolder())
        val state = HttpSyncBatchState(repository)
        val base = CountingMapTransport()
        val root = createBook(repository, "Example", "example")
        state.publishMaps(base)
        repository.saveBookmark(root, Bookmark(0, 0.2, 20, 100.0))
        state.queueBookmark(root, "Example", "example")

        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val blocking = object : HttpSyncKvTransport by base {
            override suspend fun put(
                key: String,
                contentType: String,
                body: ByteArray,
            ): HttpSyncKvWriteResponse {
                if (key.startsWith(BOOKMARKS_MAP_PREFIX)) {
                    entered.complete(Unit)
                    release.await()
                }
                return base.put(key, contentType, body)
            }
        }
        val inFlight = async { state.syncMaps(blocking) }
        entered.await()
        repository.saveBookmark(root, Bookmark(0, 0.8, 80, 101.0))
        state.queueBookmark(root, "Example", "example")
        release.complete(Unit)
        inFlight.await()

        assertTrue(state.hasPending())
        state.syncMaps(base)
        assertFalse(state.hasPending())
        assertEquals(0.8, base.bookmarks()
            .getValue("example").value!!.progress, 0.0)
    }

    @Test
    fun pageTurnQueuedDuringBootstrapPublishRemainsDurable() = runBlocking {
        val repository = BookRepository(temporaryFolder.newFolder())
        val state = HttpSyncBatchState(repository)
        val base = CountingMapTransport()
        val root = createBook(repository, "Example", "example")
        repository.saveBookmark(root, Bookmark(0, 0.2, 20, 100.0))
        state.queueBookmark(root, "Example", "example")

        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val blocking = object : HttpSyncKvTransport by base {
            override suspend fun put(
                key: String,
                contentType: String,
                body: ByteArray,
            ): HttpSyncKvWriteResponse {
                if (key.startsWith(BOOKMARKS_MAP_PREFIX)) {
                    entered.complete(Unit)
                    release.await()
                }
                return base.put(key, contentType, body)
            }
        }
        val inFlight = async { state.publishMaps(blocking) }
        entered.await()
        repository.saveBookmark(root, Bookmark(0, 0.9, 90, 101.0))
        state.queueBookmark(root, "Example", "example")
        release.complete(Unit)
        inFlight.await()

        assertTrue(state.hasPending())
        state.syncMaps(base)
        assertEquals(0.9, base.bookmarks()
            .getValue("example").value!!.progress, 0.0)
    }

    private suspend fun createBook(repository: BookRepository, title: String, syncId: String): File =
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

    private fun entry(progress: Double, timestamp: String, rev: Int) = HttpSyncBookmarkMapEntry(
        etag = "sha256:" + "2".repeat(64),
        lastModified = timestamp,
        value = HttpSyncBookmarkBlob(0, progress, 20, timestamp, rev),
    )

    private fun decodeBookmarkMap(body: ByteArray): Map<String, HttpSyncBookmarkMapEntry> =
        json.decodeFromString(
            MapSerializer(String.serializer(), HttpSyncBookmarkMapEntry.serializer()),
            body.toString(Charsets.UTF_8),
        )
}

private class CountingMapTransport : HttpSyncKvTransport {
    private data class Stored(
        val body: ByteArray,
        val contentType: String,
        val lastModified: String,
        val etag: String,
    )

    private val values = linkedMapOf<String, Stored>()
    var listCalls = 0
    var getCalls = 0
    var putCalls = 0
    private var clock = 0

    override suspend fun put(key: String, contentType: String, body: ByteArray): HttpSyncKvWriteResponse {
        putCalls += 1
        clock += 1
        val etag = body.etag()
        val timestamp = "2026-08-26T12:00:${clock.toString().padStart(2, '0')}Z"
        values[key] = Stored(body.copyOf(), contentType, timestamp, etag)
        return HttpSyncKvWriteResponse(key, timestamp, etag, body.size, contentType)
    }

    override suspend fun get(key: String): HttpSyncKvFetched? {
        getCalls += 1
        val stored = values[key] ?: return null
        return HttpSyncKvFetched(
            stored.body.copyOf(),
            stored.contentType,
            stored.lastModified,
            stored.etag,
        )
    }

    override suspend fun list(
        prefix: String?,
        since: String?,
        cursor: String?,
        limit: Int?,
    ): HttpSyncKvList {
        listCalls += 1
        val matching = values.filterKeys { prefix == null || it.startsWith(prefix) }.map { (key, value) ->
                HttpSyncKvKeyMeta(key, value.lastModified, value.etag, value.body.size, value.contentType)
            }.sortedBy { it.key }.filter { cursor == null || it.key > cursor }
        val pageSize = limit ?: matching.size
        val page = matching.take(pageSize)
        return HttpSyncKvList(
            keys = page,
            truncated = matching.size > page.size,
            nextCursor = page.lastOrNull()?.key?.takeIf { matching.size > page.size },
        )
    }

    override suspend fun delete(key: String) {
        values.remove(key)
    }

    fun body(key: String): ByteArray = values.getValue(key).body.copyOf()

    fun storeBookmarkMap(map: Map<String, HttpSyncBookmarkMapEntry>) {
        val json = Json { encodeDefaults = true }
        val body = json.encodeToString(
            MapSerializer(String.serializer(), HttpSyncBookmarkMapEntry.serializer()),
            map.toSortedMap(),
        ).toByteArray()
        clock += 1
        values["${BOOKMARKS_MAP_PREFIX}remote-test-device.json"] = Stored(
            body,
            "application/json; charset=utf-8",
            "2026-08-26T12:10:${clock.toString().padStart(2, '0')}Z",
            body.etag(),
        )
    }

    fun bookmarks(): Map<String, HttpSyncBookmarkMapEntry> {
        val json = Json { ignoreUnknownKeys = true }
        val serializer = MapSerializer(String.serializer(), HttpSyncBookmarkMapEntry.serializer())
        val merged = linkedMapOf<String, HttpSyncBookmarkMapEntry>()
        for ((key, stored) in values) {
            if (key != BOOKMARKS_MAP_KEY && !key.startsWith(BOOKMARKS_MAP_PREFIX)) continue
            val shard = json.decodeFromString(serializer, stored.body.toString(Charsets.UTF_8))
            for ((id, entry) in shard) {
                val old = merged[id]
                if (old == null || entry.lastModified > old.lastModified) merged[id] = entry
            }
        }
        return merged
    }

    fun bookmarkShardCount(): Int = values.keys.count { it.startsWith(BOOKMARKS_MAP_PREFIX) }

    fun bookmarkBody(syncId: String): ByteArray {
        val json = Json { encodeDefaults = true }
        return json.encodeToString(
            HttpSyncBookmarkBlob.serializer(),
            bookmarks().getValue(syncId).value!!,
        ).toByteArray()
    }

    fun resetCounts() {
        listCalls = 0
        getCalls = 0
        putCalls = 0
    }

    private fun ByteArray.etag(): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(this)
        return "sha256:" + digest.joinToString("") { "%02x".format(it) }
    }
}
