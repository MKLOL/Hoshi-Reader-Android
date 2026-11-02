package moe.antimony.hoshi.features.mangareader

import kotlinx.coroutines.runBlocking
import moe.antimony.hoshi.epub.BookRepository
import moe.antimony.hoshi.epub.DeviceIdentity
import moe.antimony.hoshi.epub.ReadingStatistics
import moe.antimony.hoshi.epub.collapsedByDay
import moe.antimony.hoshi.epub.deduplicateReadingStatistics
import moe.antimony.hoshi.epub.medianMangaPageSeconds
import moe.antimony.hoshi.features.reader.ReaderStatisticsClock
import moe.antimony.hoshi.features.reader.ReaderStatisticsTracker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

class MangaReadingPaceTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val phone = DeviceIdentity("phone", "Phone")
    private val tablet = DeviceIdentity("tablet", "Tablet")

    @Test
    fun anIdlePageCannotDistortTheMedianOfCompletedPages() {
        val statistics = day(samples = mapOf(30 to 1, 40 to 1, 50 to 1, 3600 to 1))

        assertEquals(45.0, statistics.medianMangaPageSeconds()!!, 0.0)
        assertEquals(450.0, mangaSecondsRemaining(10, statistics.medianMangaPageSeconds())!!, 0.0)
    }

    @Test
    fun theMedianWeightsEveryPageRatherThanEveryDistinctDuration() {
        assertEquals(30.0, day(samples = mapOf(30 to 3, 40 to 1, 3600 to 1)).medianMangaPageSeconds()!!, 0.0)
        assertEquals(35.0, day(samples = mapOf(30 to 2, 40 to 2, 3600 to 0)).medianMangaPageSeconds()!!, 0.0)
    }

    @Test
    fun mergingOlderSnapshotsOrNewerStrippedCountersCannotErasePageEvidence() {
        val old = day(samples = mapOf(30 to 4, 3600 to 1))
        val newer = old.copy(lastStatisticModified = 2, readingTime = 500.0, mangaPageSeconds = mapOf(30 to 2, 40 to 2))
        val expected = newer.copy(mangaPageSeconds = mapOf(30 to 4, 40 to 2, 3600 to 1))
        assertEquals(listOf(expected), listOf(old, newer).deduplicateReadingStatistics())
        assertEquals(listOf(expected), listOf(newer, old).deduplicateReadingStatistics())
        assertEquals(listOf(expected), listOf(old, newer, old, newer).deduplicateReadingStatistics())

        val stripped = newer.copy(mangaPageSeconds = emptyMap())
        assertEquals(old.mangaPageSeconds, listOf(old, stripped).deduplicateReadingStatistics().single().mangaPageSeconds)
        assertEquals(old.mangaPageSeconds, listOf(stripped, old).deduplicateReadingStatistics().single().mangaPageSeconds)
    }

    @Test
    fun ttuDayExportsDoNotDuplicateDevicePageSamplesIntoTheImportedBaseline() {
        assertTrue(listOf(day(samples = mapOf(30 to 2))).collapsedByDay().single().mangaPageSeconds.isEmpty())
    }

    @Test
    fun remainingTimeStaysUnknownUntilThereIsACompletedPage() {
        assertNull(day().medianMangaPageSeconds())
        assertNull(mangaSecondsRemaining(10, medianPageSeconds = null))
        assertEquals(0.0, mangaSecondsRemaining(0, medianPageSeconds = null)!!, 0.0)
        assertEquals(0.0, mangaSecondsRemaining(-1, medianPageSeconds = 30.0)!!, 0.0)
    }

    @Test
    fun completedPageDurationsSurviveSavingAndReopeningOnTheSameAndANewDay() = runBlocking {
        val files = temporaryFolder.newFolder("persistent-manga")
        val repository = BookRepository(files, deviceIdentity = phone)
        val bookRoot = repository.createBookDirectory("volume")
        val clock = Clock()
        val first = tracker(clock)
        first.start(currentCharacter = 0)
        clock.advance(30)
        first.onMangaPageChanged(currentCounter = 1, completedPage = true)
        first.pause(currentCharacter = 1)
        repository.saveStatistics(bookRoot, first.statisticsForPersistence())

        // A new repository and tracker exercise the saved sidecar, not the old in-memory state.
        val reopenedRepository = BookRepository(files, deviceIdentity = phone)
        val saved = reopenedRepository.loadStatistics(bookRoot)
        assertEquals(mapOf(30 to 1), saved.single().mangaPageSeconds)
        assertEquals(phone.id, saved.single().deviceId)
        assertEquals("2026-11-01", saved.single().dateKey)
        val reopened = tracker(clock, saved)
        assertTrue(reopened.state.session.mangaPageSeconds.isEmpty())
        assertEquals(mapOf(30 to 1), reopened.state.today.mangaPageSeconds)
        assertEquals(30.0, reopened.state.allTime.medianMangaPageSeconds()!!, 0.0)

        clock.at = clock.at.plusDays(1)
        val nextDay = tracker(clock, reopenedRepository.loadStatistics(bookRoot))
        assertTrue(nextDay.state.today.mangaPageSeconds.isEmpty())
        assertEquals(30.0, nextDay.state.allTime.medianMangaPageSeconds()!!, 0.0)
        nextDay.start(currentCharacter = 1)
        clock.advance(50)
        nextDay.onMangaPageChanged(currentCounter = 2, completedPage = true)
        reopenedRepository.saveStatistics(bookRoot, nextDay.statisticsForPersistence())

        val days = reopenedRepository.loadStatistics(bookRoot).associateBy { it.dateKey }
        assertEquals(mapOf(30 to 1), days.getValue("2026-11-01").mangaPageSeconds)
        assertEquals(mapOf(50 to 1), days.getValue("2026-11-02").mangaPageSeconds)
        assertEquals(phone.id, days.getValue("2026-11-02").deviceId)
        assertEquals(40.0, tracker(clock, days.values.toList()).state.allTime.medianMangaPageSeconds()!!, 0.0)
    }

    @Test
    fun periodicTicksAccumulateOnePageWithoutCreatingSamples() {
        val clock = Clock()
        val tracker = tracker(clock)
        tracker.start(currentCharacter = 0)

        repeat(3) {
            clock.advance(10)
            tracker.update(currentCharacter = 0)
        }
        assertTrue(tracker.state.session.mangaPageSeconds.isEmpty())
        assertTrue(tracker.statisticsForPersistence().single().mangaPageSeconds.isEmpty())

        tracker.onMangaPageChanged(currentCounter = 1, completedPage = true)
        assertEquals(mapOf(30 to 1), tracker.state.session.mangaPageSeconds)
        assertEquals(mapOf(30 to 1), tracker.state.today.mangaPageSeconds)
        assertEquals(mapOf(30 to 1), tracker.state.allTime.mangaPageSeconds)
    }

    @Test
    fun jumpingAndGoingBackDiscardThePreviousPageDuration() {
        val clock = Clock()
        val tracker = tracker(clock)
        tracker.start(currentCharacter = 0)
        clock.advance(600)
        tracker.onMangaPageChanged(currentCounter = 10, completedPage = false)
        clock.advance(40)
        tracker.onMangaPageChanged(currentCounter = 10, completedPage = false)
        assertTrue(tracker.state.allTime.mangaPageSeconds.isEmpty())

        clock.advance(30)
        tracker.onMangaPageChanged(currentCounter = 11, completedPage = true)

        assertEquals(mapOf(30 to 1), tracker.state.session.mangaPageSeconds)
        assertEquals(670.0, tracker.state.allTime.readingTime, 0.0)
    }

    @Test
    fun resumingTheSamePageExcludesTimeSpentInTheBackground() {
        val clock = Clock()
        val tracker = tracker(clock)
        tracker.start(currentCharacter = 0)
        clock.advance(10)
        tracker.update(currentCharacter = 0)
        clock.advance(5)
        tracker.pause(currentCharacter = 0)
        clock.advance(3600)
        tracker.start(currentCharacter = 0)
        clock.advance(15)
        tracker.onMangaPageChanged(currentCounter = 1, completedPage = true)

        assertEquals(mapOf(30 to 1), tracker.state.session.mangaPageSeconds)
        assertEquals(30.0, tracker.state.allTime.readingTime, 0.0)
    }

    @Test
    fun navigationWhilePausedInvalidatesTheOldPageDuration() {
        val clock = Clock()
        val tracker = tracker(clock)
        tracker.start(currentCharacter = 0)
        clock.advance(20)
        tracker.pause(currentCharacter = 0)
        clock.advance(3600)
        tracker.onMangaPageChanged(currentCounter = 2, completedPage = false)
        tracker.start(currentCharacter = 2)
        clock.advance(10)
        tracker.onMangaPageChanged(currentCounter = 3, completedPage = true)

        assertEquals(mapOf(10 to 1), tracker.state.allTime.mangaPageSeconds)
        assertEquals(30.0, tracker.state.allTime.readingTime, 0.0)
    }

    @Test
    fun sameDayDeviceHistoriesAreAddedWithoutRewritingAnotherDevicesSamples() {
        val clock = Clock()
        val phoneHistory = day(device = phone, samples = mapOf(30 to 2))
        val tabletHistory = day(device = tablet, samples = mapOf(50 to 1))
        val tracker = tracker(clock, listOf(phoneHistory, tabletHistory))
        assertEquals(mapOf(30 to 2, 50 to 1), tracker.state.today.mangaPageSeconds)
        assertEquals(30.0, tracker.state.allTime.medianMangaPageSeconds()!!, 0.0)
        tracker.start(currentCharacter = 0)
        clock.advance(40)
        tracker.onMangaPageChanged(currentCounter = 1, completedPage = true)

        val persisted = tracker.statisticsForPersistence()
        assertEquals(tabletHistory, persisted.single { it.deviceId == tablet.id })
        assertEquals(mapOf(30 to 2, 40 to 1), persisted.single { it.deviceId == phone.id }.mangaPageSeconds)
        assertEquals(mapOf(40 to 1), tracker.state.session.mangaPageSeconds)
        assertEquals(mapOf(30 to 2, 40 to 1, 50 to 1), tracker.state.allTime.mangaPageSeconds)
        assertEquals(35.0, tracker.state.allTime.medianMangaPageSeconds()!!, 0.0)
    }

    @Test
    fun zeroElapsedTimeDoesNotInventACompletedPageSample() {
        val clock = Clock()
        val tracker = tracker(clock)
        tracker.start(currentCharacter = 0)
        tracker.onMangaPageChanged(currentCounter = 1, completedPage = true)

        assertTrue(tracker.state.allTime.mangaPageSeconds.isEmpty())
        assertNull(tracker.state.allTime.medianMangaPageSeconds())
    }

    @Test
    fun pageDurationTrackingRequiresTheMangaOptIn() {
        val clock = Clock()
        val tracker = ReaderStatisticsTracker("Manga", emptyList(), enabled = true, clock = clock)
        tracker.start(currentCharacter = 0)
        clock.advance(30)
        tracker.onMangaPageChanged(currentCounter = 1, completedPage = true)

        assertTrue(tracker.state.allTime.mangaPageSeconds.isEmpty())
        assertEquals(30.0, tracker.state.allTime.readingTime, 0.0)
    }

    private fun tracker(clock: Clock, history: List<ReadingStatistics> = emptyList()) = ReaderStatisticsTracker(
        title = "Manga",
        initialStatistics = history,
        enabled = true,
        clock = clock,
        device = phone,
        trackMangaPageDurations = true,
    )

    private fun day(device: DeviceIdentity = phone, samples: Map<Int, Int> = emptyMap()) = ReadingStatistics(
        title = "Manga",
        dateKey = "2026-11-01",
        deviceId = device.id,
        deviceName = device.name,
        lastStatisticModified = 1,
        mangaPageSeconds = samples,
    )

    private class Clock(var at: LocalDateTime = LocalDateTime.parse("2026-11-01T12:00")) : ReaderStatisticsClock {
        override fun currentTimeMillis(): Long = at.toInstant(ZoneOffset.UTC).toEpochMilli()
        override fun currentDate(): LocalDate = at.toLocalDate()
        override fun zoneId(): ZoneId = ZoneOffset.UTC
        fun advance(seconds: Long) {
            at = at.plusSeconds(seconds)
        }
    }
}
