package moe.antimony.hoshi.features.sync.v3

import kotlinx.coroutines.runBlocking
import moe.antimony.hoshi.epub.BookMetadata
import moe.antimony.hoshi.epub.BookRepository
import moe.antimony.hoshi.epub.ReadingStatistics
import moe.antimony.hoshi.features.ai.AiChatHistoryStore
import moe.antimony.hoshi.features.sync.http.FakeKvTransport
import moe.antimony.hoshi.features.sync.http.HttpSyncKvFetched
import moe.antimony.hoshi.features.sync.http.HttpSyncKvTransport
import moe.antimony.hoshi.features.sync.http.HttpSyncKvWriteResponse
import moe.antimony.hoshi.features.sync.http.HttpSyncPayloadCodec
import moe.antimony.hoshi.features.sync.http.HttpSyncSettings
import moe.antimony.hoshi.features.sync.http.STATISTICS_SYNC_STATE_FILENAME
import moe.antimony.hoshi.features.sync.http.mangaStatisticsKey
import moe.antimony.hoshi.features.sync.http.payloadZipKey
import moe.antimony.hoshi.features.sync.http.statisticsKey
import moe.antimony.hoshi.mokuro.MangaTextStatistic
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * `V3Action.SyncStatistics` through the real engine: sentinel roots for books imported in the
 * same pass, the skip when that import failed, and the request budget once converged.
 */
class V3StatisticsExecutorTest {
    @get:Rule val temp = TemporaryFolder()

    private val configured = HttpSyncSettings("https://x", "t")
    private val syncId = "shirokuma"

    private class CountingTransport(private val delegate: FakeKvTransport) : HttpSyncKvTransport by delegate {
        val requests = mutableListOf<Pair<String, String>>()
        fun statisticsRequests() = requests.filter { it.second.endsWith("/statistics") || it.second.endsWith("/manga_statistics") }
        fun clear() = requests.clear()

        override suspend fun get(key: String): HttpSyncKvFetched? {
            requests += "GET" to key
            return delegate.get(key)
        }

        override suspend fun getBounded(key: String, maxBytes: Int): HttpSyncKvFetched? {
            requests += "GET" to key
            return delegate.getBounded(key, maxBytes)
        }

        override suspend fun put(key: String, contentType: String, body: ByteArray): HttpSyncKvWriteResponse {
            requests += "PUT" to key
            return delegate.put(key, contentType, body)
        }
    }

    private fun engine(repository: BookRepository, transport: HttpSyncKvTransport) = V3SyncEngine(
        bookRepository = repository,
        aiHistoryStore = AiChatHistoryStore(),
        aiSettingsRepository = null,
        payloadCodec = HttpSyncPayloadCodec(kotlinx.coroutines.Dispatchers.Unconfined),
        transportFactory = { transport },
        ioDispatcher = kotlinx.coroutines.Dispatchers.Unconfined,
    )

    private fun day(dateKey: String, seconds: Double, characters: Int, modified: Long) =
        ReadingStatistics(title = "Shirokuma", dateKey = dateKey, charactersRead = characters, readingTime = seconds, lastStatisticModified = modified)

    private suspend fun manga(repository: BookRepository): File {
        val root = repository.createBookDirectoryForImportedTitle("Shirokuma")
        repository.saveMetadata(root, BookMetadata(id = "id-shirokuma", title = "Shirokuma", cover = null, folder = root.name, lastAccess = 1.0, syncId = syncId))
        root.resolve("mokuro.json").writeText("{}")
        root.resolve("pages").mkdirs()
        root.resolve("pages/p1.png").writeBytes(byteArrayOf(0x42))
        return root
    }

    private suspend fun publishFromDeviceA(fake: FakeKvTransport) {
        val repoA = BookRepository(temp.newFolder("A"))
        val rootA = manga(repoA)
        repoA.saveStatistics(rootA, listOf(day("2026-09-10", 600.0, 12, 10)))
        repoA.saveMangaTextStatistics(rootA, listOf(MangaTextStatistic("2026-09-10", 3000, 10)))
        val published = engine(repoA, fake).syncOnce(configured)
        assertEquals(emptyList<V3Error>(), published.errors)
        assertEquals(2, published.pushed.statistics)
        assertTrue(statisticsKey(syncId) in fake.kv)
        assertTrue(mangaStatisticsKey(syncId) in fake.kv)
    }

    @Test
    fun statisticsForARemoteOnlyBookLandInTheRootImportedInTheSamePass() = runBlocking {
        val fake = FakeKvTransport()
        publishFromDeviceA(fake)
        val transport = CountingTransport(fake)
        val repoB = BookRepository(temp.newFolder("B"))

        val result = engine(repoB, transport).syncOnce(configured)

        assertEquals(emptyList<V3Error>(), result.errors)
        assertEquals(1, result.applied.payloads)
        assertEquals("both kinds applied into the new root", 2, result.applied.statistics)
        assertEquals(0, result.pushed.statistics)
        val rootB = repoB.loadBookEntries().single().root
        assertEquals(listOf(day("2026-09-10", 600.0, 12, 10)), repoB.loadStatistics(rootB))
        assertEquals(listOf(MangaTextStatistic("2026-09-10", 3000, 10)), repoB.loadMangaTextStatistics(rootB))
        assertTrue("state cache written into the imported root", rootB.resolve(STATISTICS_SYNC_STATE_FILENAME).isFile)
        assertEquals(listOf("GET" to statisticsKey(syncId), "GET" to mangaStatisticsKey(syncId)), transport.statisticsRequests())

        // Converged: the planner still emits SyncStatistics for the book, the executor makes no request.
        transport.clear()
        val again = engine(repoB, transport).syncOnce(configured)
        assertEquals(emptyList<V3Error>(), again.errors)
        assertEquals(0, again.applied.statistics + again.pushed.statistics)
        assertEquals(emptyList<Pair<String, String>>(), transport.statisticsRequests())
    }

    @Test
    fun aFailedImportSkipsItsStatisticsWithoutASecondErrorOrAStrayDirectory() = runBlocking {
        val fake = FakeKvTransport()
        publishFromDeviceA(fake)
        fake.kv.remove(payloadZipKey(syncId)) // manifest present, zip missing
        val transport = CountingTransport(fake)
        val repoB = BookRepository(temp.newFolder("B"))

        val result = engine(repoB, transport).syncOnce(configured)

        assertTrue("import surfaces its own error: ${result.errors}", result.errors.any { it.action == "ImportRemoteBook" && it.syncId == syncId })
        assertTrue("no statistics error piled on top: ${result.errors}", result.errors.none { it.action == "SyncStatistics" })
        assertEquals(0, result.applied.statistics)
        assertEquals(emptyList<Pair<String, String>>(), transport.statisticsRequests())
        assertEquals(emptyList<File>(), repoB.loadBookEntries().map { it.root })
        assertEquals("nothing created under Books", emptyList<String>(), repoB.booksDirectory.listFiles().orEmpty().map { it.name }.filter { !it.startsWith(".") })
    }

    @Test
    fun aLocalBookWhoseDirectoryVanishedMidSyncIsSkippedNotRecreated() = runBlocking {
        val fake = FakeKvTransport()
        publishFromDeviceA(fake)
        val repoB = BookRepository(temp.newFolder("B"))
        val rootB = manga(repoB)
        val plan = V3Planner().compute(
            V3LocalState(repoB, AiChatHistoryStore(), null).read(),
            V3RemoteState().read(fake) {}.snapshot,
        )
        assertTrue(plan.actions.any { it is V3Action.SyncStatistics && it.root == rootB })
        // The user deletes the book between planning and execution.
        assertTrue(rootB.deleteRecursively())

        val history = AiChatHistoryStore()
        val codec = HttpSyncPayloadCodec(kotlinx.coroutines.Dispatchers.Unconfined)
        val locks = moe.antimony.hoshi.features.sync.http.HttpSyncBookLocks()
        val result = V3Executor(
            bookRepository = repoB,
            aiHistoryStore = history,
            payloadCodec = codec,
            pushOps = V3PushOps(repoB, history, null, codec, locks),
            bookLocks = locks,
        ).run(plan, fake) {}

        assertTrue("no statistics error: ${result.errors}", result.errors.none { it.action == "SyncStatistics" })
        assertEquals(0, result.applied.statistics)
        assertTrue("directory not recreated by the state file", !rootB.exists())
    }
}
