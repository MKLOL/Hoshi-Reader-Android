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
    fun aReinstalledBookContinuesTodayInsteadOfStartingItAgain() = runBlocking {
        val repository = repository()
        val first = book(repository, "book", "Book")
        repository.saveStatistics(first, listOf(day("2026-10-03", 1_800.0, phone, modified = 1)))
        repository.deleteBook(first)
        val again = book(repository, "book", "Book")

        // The reader loads the reinstalled book's days before it starts counting.
        val loaded = repository.loadStatistics(again)
        assertEquals("the kept history is already back", listOf(1_800.0), loaded.map { it.readingTime })
        // Ten more minutes, counted on top of what was loaded.
        repository.saveStatistics(again, listOf(day("2026-10-03", 2_400.0, phone, modified = 2)))

        assertEquals(listOf(2_400.0), repository.loadStatistics(again).map { it.readingTime })
        assertTrue(repository.loadStatisticsHistory().isEmpty())
        val overview = loadReadingStatisticsOverview(repository, "2026-10-03", streakResetHour = 0)
        assertEquals(2_400.0, overview.todaySeconds, 0.0)
    }

    @Test
    fun aSaveLandingAfterADeleteNeverBringsTheBookBack() = runBlocking {
        val repository = repository()
        val root = book(repository, "book", "Book")
        repository.saveStatistics(root, listOf(day("2026-10-01", 600.0, phone)))
        repository.deleteBook(root)

        // A reader's or a sync's write that was already under way when the book was deleted.
        repository.saveStatistics(root, listOf(day("2026-10-02", 300.0, phone)))
        repository.saveMangaTextStatistics(root, listOf(MangaTextStatistic("2026-10-02", 10, 1, phone.id, phone.name)))

        assertFalse("no untitled ghost folder", root.exists())
        assertTrue(repository.loadBookEntries().isEmpty())
        val kept = repository.loadStatisticsHistory().single()
        assertEquals(setOf("2026-10-01", "2026-10-02"), repository.loadStatistics(kept.root).map { it.dateKey }.toSet())
    }

    @Test
    fun aSaveForAFolderThatNeverExistedIsDropped() = runBlocking {
        val repository = repository()
        val missing = repository.booksDirectory.resolve("never")
        repository.saveStatistics(missing, listOf(day("2026-10-01", 600.0, phone)))
        assertFalse(missing.exists())
        assertTrue(repository.loadStatisticsHistory().isEmpty())
    }

    @Test
    fun aDeleteInterruptedByTheAppStoppingKeepsItsHistoryOnTheNextStart() = runBlocking {
        val files = temporaryFolder.newFolder()
        val first = BookRepository(files, deviceIdentity = phone)
        val root = book(first, "book", "Book")
        first.saveStatistics(root, listOf(day("2026-10-01", 600.0, phone)))
        // The app stopped right after the folder was moved aside.
        val leftover = first.booksDirectory.resolve(".deleting-crashed")
        assertTrue(root.renameTo(leftover))

        val restarted = BookRepository(files, deviceIdentity = phone)
        val kept = restarted.loadStatisticsHistory().single()

        assertEquals("book", kept.syncId)
        assertEquals(listOf(600.0), restarted.loadStatistics(kept.root).map { it.readingTime })
        assertFalse(leftover.exists())
    }

    @Test
    fun anUnreadableStatisticsFileIsSetAsideNotOverwritten() = runBlocking {
        val repository = repository()
        val root = book(repository, "book", "Book")
        root.resolve("statistics.json").writeText("{ this is not json")

        repository.saveStatistics(root, listOf(day("2026-10-01", 600.0, phone)))

        assertEquals(listOf(600.0), repository.loadStatistics(root).map { it.readingTime })
        val aside = repository.booksDirectory.resolve(".unreadable").listFiles().orEmpty()
        assertEquals(1, aside.size)
        assertEquals("{ this is not json", aside.single().readText())
    }

    @Test
    fun aHistoryWithOcrCountsIsAMangaUntilItsTypeIsKnown() = runBlocking {
        val repository = repository()
        val history = repository.statisticsHistoryRoot("unknown")!!
        assertEquals(ContentType.Epub, repository.loadStatisticsHistory().single().contentType)
        repository.saveMangaTextStatistics(history, listOf(MangaTextStatistic("2026-10-01", 10, 1, tablet.id, tablet.name)))
        assertEquals(ContentType.Mokuro, repository.loadStatisticsHistory().single().contentType)
        repository.statisticsHistoryRoot("unknown", contentType = ContentType.Mokuro)
        assertEquals(ContentType.Mokuro, readStatisticsHistoryInfo(history)?.contentType)
    }

    @Test
    fun aDeletedBookRemembersHowMuchOfItWasRead() = runBlocking {
        val repository = repository()
        // Read to the very end.
        val finished = book(repository, "finished", "Finished")
        repository.saveStatistics(finished, listOf(day("2026-10-01", 600.0, phone)))
        repository.saveBookInfo(finished, BookInfo(characterCount = 1_000, chapterInfo = emptyMap()))
        repository.saveBookmark(finished, Bookmark(chapterIndex = 9, progress = 1.0, characterCount = 1_000, lastModified = 1.0))
        // The end of chapter 3 of 10: the bookmark's progress is within the chapter.
        val partway = book(repository, "partway", "Partway")
        repository.saveStatistics(partway, listOf(day("2026-10-01", 300.0, phone)))
        repository.saveBookInfo(partway, BookInfo(characterCount = 1_000, chapterInfo = emptyMap()))
        repository.saveBookmark(partway, Bookmark(chapterIndex = 2, progress = 1.0, characterCount = 300, lastModified = 1.0))
        repository.deleteBook(finished)
        repository.deleteBook(partway)

        val books = loadReadingStatisticsOverview(repository, "2026-10-01", streakResetHour = 0).books.associateBy { it.title }
        assertFalse(books.getValue("Finished").onDevice)
        assertTrue(books.getValue("Finished").finished)
        assertTrue(books.getValue("Partway").progressKnown)
        assertEquals(0.3, books.getValue("Partway").progress, 1e-9)
        assertFalse(books.getValue("Partway").finished)
    }

    @Test
    fun aBookThisInstallNeverHadShowsNoPosition() = runBlocking {
        val repository = repository()
        val history = repository.statisticsHistoryRoot("elsewhere", "Elsewhere", ContentType.Epub)!!
        repository.saveStatistics(history, listOf(day("2026-10-01", 600.0, tablet)))
        val book = loadReadingStatisticsOverview(repository, "2026-10-01", streakResetHour = 0).books.single()
        assertFalse(book.progressKnown)
        assertFalse(book.finished)
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
