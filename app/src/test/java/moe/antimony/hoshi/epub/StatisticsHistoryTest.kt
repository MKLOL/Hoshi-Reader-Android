package moe.antimony.hoshi.epub

import kotlinx.coroutines.runBlocking
import moe.antimony.hoshi.features.reader.STATISTICS_HISTORY_BOOK_ID_PREFIX
import moe.antimony.hoshi.features.reader.loadReadingStatisticsOverview
import moe.antimony.hoshi.features.statistics.computeReadingStreak
import moe.antimony.hoshi.mokuro.MangaTextStatistic
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.LocalDate

class StatisticsHistoryTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    private val phone = DeviceIdentity("phone", "Phone")
    private val tablet = DeviceIdentity("tablet", "Tablet")

    private fun repository() = BookRepository(temporaryFolder.newFolder(), deviceIdentity = phone)

    private suspend fun book(repository: BookRepository, syncId: String, title: String, manga: Boolean = false): File {
        val root = repository.createBookDirectory(syncId)
        repository.saveMetadata(root, BookMetadata(id = syncId, title = title, cover = null, folder = syncId, lastAccess = 0.0, syncId = syncId))
        if (manga) root.resolve(MOKURO_SIDECAR_FILE).writeText("{}")
        return root
    }

    private fun day(date: String, seconds: Double, device: DeviceIdentity?, modified: Long = 1) = ReadingStatistics(
        title = "Book", dateKey = date, readingTime = seconds, charactersRead = 10,
        lastStatisticModified = modified, deviceId = device?.id, deviceName = device?.name,
    )

    @Test
    fun deletingABookKeepsItsReadingInHistory() = runBlocking {
        val repository = repository()
        val root = book(repository, "manga_1", "Manga", manga = true)
        repository.saveStatistics(root, listOf(day("2026-10-01", 600.0, phone), day("2026-10-02", 300.0, tablet)))
        repository.saveMangaTextStatistics(root, listOf(MangaTextStatistic("2026-10-01", 1_234, 1, phone.id, phone.name)))

        repository.deleteBook(root)

        assertFalse(root.exists())
        val kept = repository.loadStatisticsHistory().single()
        assertEquals("manga_1", kept.syncId)
        assertEquals("Manga", kept.title)
        assertEquals(ContentType.Mokuro, kept.contentType)
        assertEquals(setOf(600.0, 300.0), repository.loadStatistics(kept.root).map { it.readingTime }.toSet())
        assertEquals(listOf(1_234), repository.loadMangaTextStatistics(kept.root).map { it.charactersRead })
        assertTrue("the bookshelf never lists history folders", repository.loadBookEntries().isEmpty())
    }

    @Test
    fun aDeletedBooksOwnPreDeviceDaysStayItsOwnAndOthersStayUnclaimed() = runBlocking {
        val repository = repository()
        // Never synced: device-less days are this install's own and are claimed on load.
        val own = book(repository, "own", "Own")
        own.resolve("statistics.json").writeText("""[{"title":"Own","dateKey":"2026-09-01","readingTime":60.0}]""")
        repository.deleteBook(own)
        val ownKept = repository.loadStatisticsHistory().single { it.syncId == "own" }
        assertEquals(listOf("phone"), repository.loadStatistics(ownKept.root).map { it.deviceId })

        // Synced before: device-less days came from older clients elsewhere and stay unclaimed.
        val synced = book(repository, "synced", "Synced")
        synced.resolve("statistics.json").writeText("""[{"title":"Synced","dateKey":"2026-09-02","readingTime":60.0}]""")
        synced.resolve(".http_sync_statistics.json").writeText("{}")
        repository.deleteBook(synced)
        val syncedKept = repository.loadStatisticsHistory().single { it.syncId == "synced" }
        assertEquals(listOf<String?>(null), repository.loadStatistics(syncedKept.root).map { it.deviceId })
    }

    @Test
    fun historyNamesThatCouldLeaveTheHistoryFolderAreRefused() = runBlocking {
        val repository = repository()
        for (name in listOf(".", "..", "...", "a/b", "", "x".repeat(65), "a b")) {
            assertNull(name, repository.statisticsHistoryRoot(name))
        }
        assertEquals(repository.statisticsHistoryDirectory.resolve("ok_1.x-y"), repository.statisticsHistoryRoot("ok_1.x-y"))
    }

    @Test
    fun aBookInstalledAgainTakesBackItsHistory() = runBlocking {
        val repository = repository()
        val history = repository.statisticsHistoryRoot("book", "Book", ContentType.Epub)!!
        repository.saveStatistics(history, listOf(day("2026-09-01", 600.0, tablet)))
        val root = book(repository, "book", "Book")
        repository.saveStatistics(root, listOf(day("2026-10-01", 60.0, phone)))

        repository.absorbStatisticsHistory(repository.loadStatisticsHistory().single(), root)

        assertTrue(repository.loadStatisticsHistory().isEmpty())
        assertEquals(setOf("tablet", "phone"), repository.loadStatistics(root).mapNotNull { it.deviceId }.toSet())
    }

    @Test
    fun todayAndTheStreakCountBooksThatAreNotInstalledWithoutDoubleCounting() = runBlocking {
        val repository = repository()
        val today = LocalDate.of(2026, 10, 3)
        // Read on the tablet in a book this phone never downloaded.
        val elsewhere = repository.statisticsHistoryRoot("elsewhere", "Elsewhere", ContentType.Epub)!!
        repository.saveStatistics(elsewhere, (0L until 5L).map { day(today.minusDays(it).toString(), 900.0, tablet) })
        // An installed book whose history folder was not folded in yet: same days, joined once.
        val root = book(repository, "book", "Book")
        val tabletDay = day("2026-09-20", 300.0, tablet)
        repository.saveStatistics(root, listOf(tabletDay))
        val stale = repository.statisticsHistoryRoot("book", "Book", ContentType.Epub)!!
        repository.saveStatistics(stale, listOf(tabletDay))

        val overview = loadReadingStatisticsOverview(repository, today.toString(), streakResetHour = 0)

        assertEquals(900.0, overview.todaySeconds, 0.0)
        assertEquals(5 * 900.0 + 300.0, overview.totalSeconds, 0.0)
        assertEquals(5, computeReadingStreak(overview.streakDaily, 600.0, today).currentDays)
        assertEquals(listOf("Elsewhere", "Book"), overview.books.map { it.title })
        assertEquals(STATISTICS_HISTORY_BOOK_ID_PREFIX + "elsewhere", overview.books.first().bookId)
    }
}
