package moe.antimony.hoshi.features.sync.http

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import moe.antimony.hoshi.epub.DeviceIdentity
import moe.antimony.hoshi.epub.BookMetadata
import moe.antimony.hoshi.epub.BookRepository
import moe.antimony.hoshi.epub.ReadingStatistics
import moe.antimony.hoshi.mokuro.MangaTextStatistic
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

class HttpSyncStatisticsLocalChangesTest {
    @get:Rule val temp = TemporaryFolder()

    private val syncId = "book"
    private val day = ReadingStatistics(
        "Book", "2026-09-29", charactersRead = 120, readingTime = 600.0,
        lastStatisticModified = 1, deviceId = "phone",
    )

    private suspend fun book(repository: BookRepository, manga: Boolean = false): File {
        val root = repository.createBookDirectory("book")
        repository.saveMetadata(root, BookMetadata(
            id = "book", title = "Book", cover = null, folder = root.name, lastAccess = 1.0, syncId = syncId,
        ))
        if (manga) root.resolve("mokuro.json").writeText("{}")
        return root
    }

    @Test fun aMissedOrFailedReaderPushStaysDirtyUntilTheStatisticsAreExchanged() = runBlocking {
        val repository = BookRepository(temp.newFolder())
        val root = book(repository)
        val sync = HttpSyncStatisticsSync(repository)
        val server = FakeKvTransport()
        assertFalse(sync.hasLocalChanges(root, syncId))

        repository.saveStatistics(root, listOf(day))
        assertTrue("a local save alone requires reconciliation", sync.hasLocalChanges(root, syncId))
        val offline = object : HttpSyncKvTransport by server {
            override suspend fun put(key: String, contentType: String, body: ByteArray): HttpSyncKvWriteResponse =
                throw IOException("offline")
        }
        val failed = runCatching {
            sync.sync(offline, root, syncId, StatisticsSyncKind.Reading, StatisticsRemoteListing.Unknown)
        }.exceptionOrNull()
        assertTrue(failed is IOException)
        assertTrue("failed writes cannot mark local history synced", sync.hasLocalChanges(root, syncId))
        assertTrue(server.kv.isEmpty())

        sync.sync(server, root, syncId, StatisticsSyncKind.Reading, StatisticsRemoteListing.Absent)
        assertFalse(sync.hasLocalChanges(root, syncId))
        repository.saveStatistics(root, listOf(day.copy(readingTime = 900.0, lastStatisticModified = 2)))
        assertTrue("a later local session must retry even with an unchanged remote map", sync.hasLocalChanges(root, syncId))
    }

    @Test fun aMissingLocalSidecarIsRecoveredEvenWhenItsOldExchangeStateSurvives() = runBlocking {
        val repository = BookRepository(temp.newFolder())
        val root = book(repository)
        val sync = HttpSyncStatisticsSync(repository)
        val server = FakeKvTransport()
        repository.saveStatistics(root, listOf(day))
        sync.sync(server, root, syncId, StatisticsSyncKind.Reading, StatisticsRemoteListing.Absent)
        assertFalse(sync.hasLocalChanges(root, syncId))
        assertTrue(root.resolve("statistics.json").delete())
        assertTrue(root.resolve(STATISTICS_SYNC_STATE_FILENAME).isFile)
        assertTrue("empty local data is not converged with a previous nonempty exchange", sync.hasLocalChanges(root, syncId))

        val remote = server.kv.getValue(statisticsKey(syncId))
        val before = repository.statisticsChanges.value
        val result = sync.sync(server, root, syncId, StatisticsSyncKind.Reading,
            StatisticsRemoteListing.Listed(remote.body.size, remote.lastModified))
        assertEquals(StatisticsSyncOutcome(downloaded = true, uploaded = false), result)
        assertEquals(listOf(day), repository.loadStatistics(root))
        assertTrue(repository.statisticsChanges.value > before)
        assertFalse(sync.hasLocalChanges(root, syncId))
    }

    @Test fun anEmptyTabletWithoutExchangeStateStillPullsAnAlreadyListedRemoteKey() = runBlocking {
        val phone = BookRepository(temp.newFolder())
        val phoneRoot = book(phone)
        val server = FakeKvTransport()
        phone.saveStatistics(phoneRoot, listOf(day))
        HttpSyncStatisticsSync(phone).sync(server, phoneRoot, syncId, StatisticsSyncKind.Reading, StatisticsRemoteListing.Absent)

        val tablet = BookRepository(temp.newFolder())
        val tabletRoot = book(tablet)
        val sync = HttpSyncStatisticsSync(tablet)
        assertTrue(tablet.loadStatistics(tabletRoot).isEmpty())
        assertFalse(tabletRoot.resolve(STATISTICS_SYNC_STATE_FILENAME).exists())
        assertTrue("a cached remote ETag does not prove the empty tablet applied the data",
            sync.hasLocalChanges(tabletRoot, syncId, remoteReadingPresent = true))
        val remote = server.kv.getValue(statisticsKey(syncId))
        sync.sync(server, tabletRoot, syncId, StatisticsSyncKind.Reading,
            StatisticsRemoteListing.Listed(remote.body.size, remote.lastModified))
        assertEquals(listOf(day), tablet.loadStatistics(tabletRoot))
        assertFalse(sync.hasLocalChanges(tabletRoot, syncId, remoteReadingPresent = true))
    }

    @Test fun reorderingUnchangedRowsDoesNotForceAnotherExchangeButLostStateDoes() = runBlocking {
        val repository = BookRepository(temp.newFolder())
        val root = book(repository)
        val sync = HttpSyncStatisticsSync(repository)
        val server = FakeKvTransport()
        val entries = listOf(day, day.copy(dateKey = "2026-09-28", lastStatisticModified = 2))
        repository.saveStatistics(root, entries)
        sync.sync(server, root, syncId, StatisticsSyncKind.Reading, StatisticsRemoteListing.Absent)
        repository.replaceStatistics(root, entries.reversed())
        assertFalse(sync.hasLocalChanges(root, syncId))
        assertTrue(root.resolve(STATISTICS_SYNC_STATE_FILENAME).delete())
        assertTrue(sync.hasLocalChanges(root, syncId))
    }

    @Test fun mangaTextChangesAreDetectedWithoutReadingTimeChanges() = runBlocking {
        val repository = BookRepository(temp.newFolder())
        val root = book(repository, manga = true)
        val sync = HttpSyncStatisticsSync(repository)
        val server = FakeKvTransport()
        assertFalse(sync.hasLocalChanges(root, syncId))
        repository.saveMangaTextStatistics(root, listOf(MangaTextStatistic("2026-09-29", 500, 1)))
        assertTrue(sync.hasLocalChanges(root, syncId))
        sync.sync(server, root, syncId, StatisticsSyncKind.MangaText, StatisticsRemoteListing.Absent)
        assertFalse(sync.hasLocalChanges(root, syncId))
        repository.saveMangaTextStatistics(root, listOf(MangaTextStatistic("2026-09-29", 600, 2)))
        assertTrue(sync.hasLocalChanges(root, syncId))
    }

    @Test fun sameSizeSameTimestampAtomicReplacementStillDetectsNewReadingHistory() = runBlocking {
        val repository = BookRepository(temp.newFolder())
        val root = book(repository)
        val sync = HttpSyncStatisticsSync(repository)
        repository.saveStatistics(root, listOf(day))
        sync.sync(FakeKvTransport(), root, syncId, StatisticsSyncKind.Reading, StatisticsRemoteListing.Absent)
        assertFalse(sync.hasLocalChanges(root, syncId))
        val file = root.resolve("statistics.json")
        val before = file.readText()
        val after = before.replace("600.0", "900.0")
        assertTrue(before != after)
        val replacement = root.resolve("replacement.tmp").apply { writeText(after) }
        Files.setLastModifiedTime(replacement.toPath(), Files.getLastModifiedTime(file.toPath()))
        Files.move(replacement.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)

        assertTrue(sync.hasLocalChanges(root, syncId))
        assertEquals(900.0, repository.loadStatistics(root).single().readingTime, 0.0)
    }

    @Test fun corruptExchangeStateAndStatisticsBypassPreviousUnchangedValidationAndRecover() = runBlocking {
        val repository = BookRepository(temp.newFolder())
        val root = book(repository)
        val sync = HttpSyncStatisticsSync(repository)
        val server = FakeKvTransport()
        repository.saveStatistics(root, listOf(day))
        sync.sync(server, root, syncId, StatisticsSyncKind.Reading, StatisticsRemoteListing.Absent)
        for (filename in listOf(STATISTICS_SYNC_STATE_FILENAME, "statistics.json")) {
            assertFalse(sync.hasLocalChanges(root, syncId, remoteReadingPresent = true))
            root.resolve(filename).writeText("{ truncated JSON")
            assertTrue(filename, sync.hasLocalChanges(root, syncId, remoteReadingPresent = true))
            val remote = server.kv.getValue(statisticsKey(syncId))
            sync.sync(server, root, syncId, StatisticsSyncKind.Reading,
                StatisticsRemoteListing.Listed(remote.body.size, remote.lastModified))
            assertEquals(listOf(day), repository.loadStatistics(root))
            assertFalse(filename, sync.hasLocalChanges(root, syncId, remoteReadingPresent = true))
        }
    }

    @Test fun changedRemotePresenceAndBookTypeCannotReuseAnEmptyBookValidation() = runBlocking {
        val repository = BookRepository(temp.newFolder())
        val root = book(repository)
        val sync = HttpSyncStatisticsSync(repository)
        assertFalse(sync.hasLocalChanges(root, syncId))
        assertTrue(sync.hasLocalChanges(root, syncId, remoteReadingPresent = true))
        assertFalse(sync.hasLocalChanges(root, syncId))
        repository.saveMangaTextStatistics(root, listOf(MangaTextStatistic("2026-09-29", 500, 1)))
        assertFalse(sync.hasLocalChanges(root, syncId))
        root.resolve("mokuro.json").writeText("{}")
        assertTrue(sync.hasLocalChanges(root, syncId))
    }

    @Test fun legacyHistoryIsRevalidatedWhenItsExchangeMarkerDisappears() = runBlocking {
        val repository = BookRepository(temp.newFolder(), deviceIdentity = DeviceIdentity("tablet", "Tablet"))
        val root = book(repository)
        val server = FakeKvTransport()
        val legacyDay = day.copy(deviceId = null)
        server.put(statisticsKey(syncId), "application/json", Json.encodeToString(
            HttpSyncStatisticsBlob.serializer(), HttpSyncStatisticsBlob(syncId = syncId, entries = listOf(legacyDay)),
        ).toByteArray())
        val sync = HttpSyncStatisticsSync(repository)
        val remote = server.kv.getValue(statisticsKey(syncId))
        sync.sync(server, root, syncId, StatisticsSyncKind.Reading,
            StatisticsRemoteListing.Listed(remote.body.size, remote.lastModified))
        assertFalse(sync.hasLocalChanges(root, syncId))
        assertEquals(null, repository.loadStatistics(root).single().deviceId)
        assertTrue(root.resolve(STATISTICS_SYNC_STATE_FILENAME).delete())

        assertTrue(sync.hasLocalChanges(root, syncId))
        assertEquals("tablet", repository.loadStatistics(root).single().deviceId)
        assertEquals(day.readingTime, repository.loadStatistics(root).single().readingTime, 0.0)
    }

    @Test fun unusedMangaSidecarsAndDeletedBooksDoNotTriggerAnExchange() = runBlocking {
        val repository = BookRepository(temp.newFolder())
        val root = book(repository)
        val sync = HttpSyncStatisticsSync(repository)
        repository.saveMangaTextStatistics(root, listOf(MangaTextStatistic("2026-09-29", 500, 1)))
        assertFalse("EPUBs do not sync manga-only statistics", sync.hasLocalChanges(root, syncId))
        assertFalse(sync.hasLocalChanges(File(repository.booksDirectory, "deleted"), syncId))
    }
}
