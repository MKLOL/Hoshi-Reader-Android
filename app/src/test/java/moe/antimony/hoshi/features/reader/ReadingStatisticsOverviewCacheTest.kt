package moe.antimony.hoshi.features.reader

import kotlinx.coroutines.runBlocking
import moe.antimony.hoshi.epub.BookMetadata
import moe.antimony.hoshi.epub.BookRepository
import moe.antimony.hoshi.epub.ReadingStatistics
import moe.antimony.hoshi.features.statistics.charactersByDate
import moe.antimony.hoshi.features.statistics.readingDays
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.FileTime

class ReadingStatisticsOverviewCacheTest {
    @get:Rule val folder = TemporaryFolder()
    private val projected = mutableListOf<Pair<String, String>>()
    private val cache = ReadingStatisticsOverviewCache { input, resetHour ->
        projected += input.title to input.statistics.single().dateKey
        readingDays(input.statistics, input.charactersByDate(), resetHour)
    }

    @Test
    fun recentReadingAndRolloverReuseOlderTotalsUntilOlderSyncArrives() = runBlocking {
        val repository = BookRepository(folder.root)
        val active = repository.book("active")
        val other = repository.book("other")
        repository.saveStatistics(active, listOf(row("2027-01-01"), row("2027-01-12")))
        repository.saveStatistics(other, listOf(row("2027-01-02")))
        val first = cache.load(repository, "2027-01-12", 3)
        assertEquals(3, projected.size)
        projected.clear()

        repository.saveStatistics(active, listOf(row("2027-01-12", seconds = 720.0, stamp = 2)))
        val refreshed = cache.load(repository, "2027-01-12", 3)
        assertEquals(listOf("active" to "2027-01-12"), projected)
        assertEquals(720.0, refreshed.todaySeconds, 0.0)
        assertSame(first.olderStreakDays, refreshed.olderStreakDays)
        assertSame(first.streakHistoryRevision, refreshed.streakHistoryRevision)
        projected.clear()

        repository.saveStatistics(other, listOf(row("2027-01-02", stamp = 2).copy(charactersRead = 42)))
        val charactersChanged = cache.load(repository, "2027-01-12", 3)
        assertEquals(listOf("other" to "2027-01-02"), projected)
        assertEquals(42, charactersChanged.olderStreakDays!!.single { it.dateKey == "2027-01-02" }.characters)
        assertSame(refreshed.streakHistoryRevision, charactersChanged.streakHistoryRevision)
        projected.clear()

        val nextDay = cache.load(repository, "2027-01-15", 3)
        assertEquals(emptyList<Pair<String, String>>(), projected)
        assertEquals(listOf("2027-01-12", "2027-01-02", "2027-01-01"), nextDay.olderStreakDays!!.map { it.dateKey })
        assertSame(refreshed.streakHistoryRevision, nextDay.streakHistoryRevision)

        repository.saveStatistics(other, listOf(row("2027-01-02", seconds = 840.0, stamp = 3)))
        val synced = cache.load(repository, "2027-01-15", 3)
        assertEquals(listOf("other" to "2027-01-02"), projected)
        assertEquals(840.0, synced.olderStreakDays!!.single { it.dateKey == "2027-01-02" }.seconds, 0.0)
        assertNotSame(nextDay.streakHistoryRevision, synced.streakHistoryRevision)
    }

    @Test
    fun sameStampRestoreAndResetChangesStillUpdateTheCorrectReadingDay() = runBlocking {
        val repository = BookRepository(folder.root)
        val book = repository.book("book")
        val original = row("2027-01-10").copy(readingTimeByHour = mapOf("2027-01-10T01:00" to 600.0))
        repository.saveStatistics(book, listOf(original))
        val initial = cache.load(repository, "2027-01-18", 3)
        assertEquals("2027-01-09", initial.daily.single().dateKey)
        projected.clear()

        // Even an in-place restore with identical file size, identity and mtime can change hours.
        val file = book.resolve("statistics.json")
        val modified: FileTime = Files.getLastModifiedTime(file.toPath())
        file.writeText(file.readText().replace("2027-01-10T01:00", "2027-01-10T04:00"))
        Files.setLastModifiedTime(file.toPath(), modified)
        repository.notifyStatisticsChanged()
        val restored = cache.load(repository, "2027-01-18", 3)
        assertEquals(listOf("book" to "2027-01-10"), projected)
        assertEquals("2027-01-10", restored.daily.single().dateKey)
        assertNotSame(initial.streakHistoryRevision, restored.streakHistoryRevision)
        projected.clear()

        val newReset = cache.load(repository, "2027-01-18", 5)
        assertEquals(listOf("book" to "2027-01-10"), projected)
        assertEquals("2027-01-09", newReset.daily.single().dateKey)
        assertNotSame(restored.streakHistoryRevision, newReset.streakHistoryRevision)
    }

    private suspend fun BookRepository.book(id: String): File = createBookDirectory(id).also { root ->
        saveMetadata(root, BookMetadata(id = id, title = id, cover = null, folder = id, lastAccess = 1.0))
    }

    private fun row(date: String, seconds: Double = 600.0, stamp: Long = 1): ReadingStatistics =
        ReadingStatistics(title = "Book", dateKey = date, readingTime = seconds, lastStatisticModified = stamp)
}
