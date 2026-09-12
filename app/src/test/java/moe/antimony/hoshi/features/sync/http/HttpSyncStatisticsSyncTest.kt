package moe.antimony.hoshi.features.sync.http

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import moe.antimony.hoshi.epub.BookMetadata
import moe.antimony.hoshi.epub.BookRepository
import moe.antimony.hoshi.epub.ReadingStatistics
import moe.antimony.hoshi.mokuro.MangaTextStatistic
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class HttpSyncStatisticsSyncTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val syncId = "shirokuma"
    private val key = statisticsKey(syncId)

    private fun day(dateKey: String, seconds: Double, characters: Int, modified: Long) =
        ReadingStatistics(title = "Shirokuma", dateKey = dateKey, charactersRead = characters, readingTime = seconds, lastStatisticModified = modified)

    private suspend fun book(repository: BookRepository, manga: Boolean = true): File {
        val root = repository.createBookDirectory("book")
        repository.saveMetadata(root, BookMetadata(id = "book", title = "Shirokuma", cover = null, folder = "book", lastAccess = 1.0, syncId = syncId))
        if (manga) root.resolve("mokuro.json").writeText("{}")
        return root
    }

    private fun FakeKvTransport.putStatistics(entries: List<ReadingStatistics>) = runBlocking {
        put(key, "application/json", json.encodeToString(HttpSyncStatisticsBlob.serializer(), HttpSyncStatisticsBlob(syncId = syncId, entries = entries)).toByteArray())
    }

    private fun FakeKvTransport.remoteEntries(): List<ReadingStatistics> =
        json.decodeFromString(HttpSyncStatisticsBlob.serializer(), kv.getValue(key).body.decodeToString()).entries

    @Test
    fun localOnlyStatisticsAreUploadedOnceAndCostNothingAfterwards() = runBlocking {
        val repository = BookRepository(temp.newFolder())
        val root = book(repository)
        val transport = FakeKvTransport()
        val sync = HttpSyncStatisticsSync(repository)
        repository.saveStatistics(root, listOf(day("2026-09-10", 600.0, 12, 1), day("2026-09-12", 90.0, 3, 2)))

        val first = sync.sync(transport, root, syncId, StatisticsSyncKind.Reading, StatisticsRemoteListing.Absent)

        assertEquals(StatisticsSyncOutcome(downloaded = false, uploaded = true), first)
        assertEquals(listOf("2026-09-10", "2026-09-12"), transport.remoteEntries().map { it.dateKey })
        val stamp = transport.kv.getValue(key).lastModified
        val size = transport.kv.getValue(key).body.size

        // Converged: neither the listing nor the reader path makes a request.
        assertEquals(StatisticsSyncOutcome.NONE, sync.sync(transport, root, syncId, StatisticsSyncKind.Reading, StatisticsRemoteListing.Listed(size)))
        assertEquals(StatisticsSyncOutcome.NONE, sync.sync(transport, root, syncId, StatisticsSyncKind.Reading, StatisticsRemoteListing.Unknown))
        assertEquals(stamp, transport.kv.getValue(key).lastModified)
        // Proof that no GET happened either: with the key gone, a fetch would have re-uploaded it.
        transport.kv.remove(key)
        assertEquals(StatisticsSyncOutcome.NONE, sync.sync(transport, root, syncId, StatisticsSyncKind.Reading, StatisticsRemoteListing.Listed(size)))
        assertNull(transport.kv[key])
    }

    @Test
    fun remoteOnlyStatisticsAreInstalledLocallyAndSignalAChange() = runBlocking {
        val repository = BookRepository(temp.newFolder())
        val root = book(repository)
        val transport = FakeKvTransport()
        transport.putStatistics(listOf(day("2026-09-01", 1_200.0, 40, 7)))
        val before = repository.statisticsChanges.value

        val outcome = HttpSyncStatisticsSync(repository).sync(transport, root, syncId, StatisticsSyncKind.Reading, StatisticsRemoteListing.Listed(transport.kv.getValue(key).body.size))

        assertEquals(StatisticsSyncOutcome(downloaded = true, uploaded = false), outcome)
        assertEquals(listOf(day("2026-09-01", 1_200.0, 40, 7)), repository.loadStatistics(root))
        assertTrue(repository.statisticsChanges.value > before)
    }

    @Test
    fun bothSidesMergePerDayWithTheNewestEntryWinningInBothDirections() = runBlocking {
        val repository = BookRepository(temp.newFolder())
        val root = book(repository)
        val transport = FakeKvTransport()
        val sync = HttpSyncStatisticsSync(repository)
        repository.saveStatistics(root, listOf(day("2026-09-10", 600.0, 12, 5), day("2026-09-12", 30.0, 1, 1)))
        transport.putStatistics(listOf(day("2026-09-05", 900.0, 20, 3), day("2026-09-12", 240.0, 9, 2)))

        val outcome = sync.sync(transport, root, syncId, StatisticsSyncKind.Reading, StatisticsRemoteListing.Listed(transport.kv.getValue(key).body.size))

        assertEquals(StatisticsSyncOutcome(downloaded = true, uploaded = true), outcome)
        val expected = listOf(day("2026-09-05", 900.0, 20, 3), day("2026-09-10", 600.0, 12, 5), day("2026-09-12", 240.0, 9, 2))
        assertEquals(expected, repository.loadStatistics(root).sortedBy { it.dateKey })
        assertEquals(expected, transport.remoteEntries().sortedBy { it.dateKey })

        // Converged now, in both listing modes.
        val size = transport.kv.getValue(key).body.size
        assertEquals(StatisticsSyncOutcome.NONE, sync.sync(transport, root, syncId, StatisticsSyncKind.Reading, StatisticsRemoteListing.Listed(size)))
        assertEquals(StatisticsSyncOutcome.NONE, sync.sync(transport, root, syncId, StatisticsSyncKind.Reading, StatisticsRemoteListing.Unknown))

        // A later local session is pushed again, and an unrelated remote change is pulled.
        repository.saveStatistics(root, listOf(day("2026-09-13", 45.0, 2, 9)))
        assertEquals(StatisticsSyncOutcome(downloaded = false, uploaded = true), sync.sync(transport, root, syncId, StatisticsSyncKind.Reading, StatisticsRemoteListing.Listed(size)))
        assertEquals(4, transport.remoteEntries().size)
        transport.putStatistics(transport.remoteEntries() + day("2026-08-01", 10.0, 1, 1))
        val outcome2 = sync.sync(transport, root, syncId, StatisticsSyncKind.Reading, StatisticsRemoteListing.Listed(transport.kv.getValue(key).body.size))
        assertEquals(StatisticsSyncOutcome(downloaded = true, uploaded = false), outcome2)
        assertEquals(5, repository.loadStatistics(root).size)
    }

    @Test
    fun malformedOrUnsupportedRemoteBlobsAreRejectedWithoutTouchingLocalStatistics() = runBlocking {
        val repository = BookRepository(temp.newFolder())
        val root = book(repository)
        val local = listOf(day("2026-09-10", 600.0, 12, 5))
        repository.saveStatistics(root, local)
        val transport = FakeKvTransport()
        val sync = HttpSyncStatisticsSync(repository)

        transport.put(key, "application/json", "<html>proxy error</html>".toByteArray())
        try {
            sync.sync(transport, root, syncId, StatisticsSyncKind.Reading, StatisticsRemoteListing.Listed(1))
            fail("malformed blob must be rejected")
        } catch (expected: HttpSyncException) {
            assertTrue(expected.message.orEmpty().contains("malformed"))
        }
        transport.put(key, "application/json", """{"version":99,"syncId":"shirokuma","entries":[]}""".toByteArray())
        try {
            sync.sync(transport, root, syncId, StatisticsSyncKind.Reading, StatisticsRemoteListing.Listed(2))
            fail("unsupported version must be rejected")
        } catch (expected: HttpSyncException) {
            assertTrue(expected.message.orEmpty().contains("unsupported version"))
        }
        assertEquals(local, repository.loadStatistics(root))
        assertFalse(root.resolve(STATISTICS_SYNC_STATE_FILENAME).exists())
    }

    @Test
    fun mangaTextStatisticsOnlySyncForMangaAndUseTheirOwnKey() = runBlocking {
        val repository = BookRepository(temp.newFolder())
        val transport = FakeKvTransport()
        val sync = HttpSyncStatisticsSync(repository)

        val epub = repository.createBookDirectory("epub").also {
            repository.saveMetadata(it, BookMetadata(id = "epub", title = "Novel", cover = null, folder = "epub", lastAccess = 1.0, syncId = "novel"))
        }
        repository.saveMangaTextStatistics(epub, listOf(MangaTextStatistic("2026-09-12", 500, 1)))
        assertEquals(StatisticsSyncOutcome.NONE, sync.sync(transport, epub, "novel", StatisticsSyncKind.MangaText, StatisticsRemoteListing.Absent))
        assertNull(transport.kv[mangaStatisticsKey("novel")])

        val manga = book(repository)
        repository.saveMangaTextStatistics(manga, listOf(MangaTextStatistic("2026-09-12", 282, 1)))
        assertEquals(StatisticsSyncOutcome(downloaded = false, uploaded = true), sync.sync(transport, manga, syncId, StatisticsSyncKind.MangaText, StatisticsRemoteListing.Absent))
        val blob = json.decodeFromString(HttpSyncMangaStatisticsBlob.serializer(), transport.kv.getValue(mangaStatisticsKey(syncId)).body.decodeToString())
        assertEquals(listOf(MangaTextStatistic("2026-09-12", 282, 1)), blob.entries)
        assertEquals(syncId, blob.syncId)
    }

    @Test
    fun aMissingBookDirectoryIsLeftAloneAndNeverRecreated() = runBlocking {
        val repository = BookRepository(temp.newFolder())
        val transport = FakeKvTransport()
        transport.putStatistics(listOf(day("2026-09-01", 1_200.0, 40, 7)))
        val gone = File(temp.root, "Books/deleted")

        val outcome = HttpSyncStatisticsSync(repository).sync(transport, gone, syncId, StatisticsSyncKind.Reading, StatisticsRemoteListing.Listed(10))

        assertEquals(StatisticsSyncOutcome.NONE, outcome)
        assertFalse(gone.exists())
    }
}
