package moe.antimony.hoshi.features.reader.sentence

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import moe.antimony.hoshi.epub.DeviceIdentity
import moe.antimony.hoshi.epub.ReadingStatistics
import moe.antimony.hoshi.features.reader.ReaderSelectionData
import moe.antimony.hoshi.features.reader.ReaderSelectionRect
import moe.antimony.hoshi.features.reader.ReaderStatisticsClock
import moe.antimony.hoshi.features.usage.ReaderUsageSession
import moe.antimony.hoshi.features.usage.UsageContentType
import moe.antimony.hoshi.features.usage.UsageEventType
import moe.antimony.hoshi.features.usage.UsageLog
import moe.antimony.hoshi.features.usage.UsageLookupSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

class SentenceReaderStatisticsSessionTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun savedPositionIsOnlyABaselineAndSentenceTurnsUseNormalNetCharacterCounts() {
        val clock = Clock()
        val session = session(clock, initialCharacter = 100)
        assertNull(session.statisticsForPersistenceOrNull())
        session.resume()
        clock.advance(5)
        session.tick()
        assertEquals(0, session.state.session.charactersRead)

        clock.advance(5)
        session.navigate(chapter = 1, character = 130)
        clock.advance(5)
        session.navigate(chapter = 1, character = 110)
        clock.advance(5)
        session.navigate(chapter = 1, character = 130)
        clock.advance(5)
        session.tick()

        assertEquals(30, session.state.session.charactersRead)
        assertEquals(25.0, session.state.session.readingTime, 0.0)
        assertEquals(30, session.statisticsForPersistenceOrNull()!!.single().charactersRead)
    }

    @Test
    fun pauseAndResumeExcludeBackgroundTimeAndRepeatedResumeDoesNotResetTheBaseline() {
        val clock = Clock()
        val session = session(clock, initialCharacter = 10)
        session.resume()
        clock.advance(2)
        session.resume()
        clock.advance(3)
        session.navigate(chapter = 1, character = 15)
        session.pause()
        session.pause()
        assertFalse(session.state.isTracking)

        clock.advance(120)
        session.tick()
        session.resume()
        clock.advance(5)
        session.navigate(chapter = 2, character = 25)
        session.close()
        session.close()
        clock.advance(10)
        session.resume()
        session.navigate(chapter = 3, character = 100)
        session.tick()

        assertEquals(10.0, session.state.session.readingTime, 0.0)
        assertEquals(15, session.state.session.charactersRead)
        assertFalse(session.state.isTracking)
    }

    @Test
    fun updatesThisDevicesExistingDailyEntryAndPreservesOtherDevices() {
        val clock = Clock()
        val day = clock.currentDate().toString()
        val previous = listOf(
            ReadingStatistics("Book", day, charactersRead = 40, readingTime = 30.0, deviceId = "this-device"),
            ReadingStatistics("Book", day, charactersRead = 200, readingTime = 100.0, deviceId = "other-device"),
        )
        val session = SentenceReaderStatisticsSession(
            title = "Book",
            initialStatistics = previous,
            initialCharacter = 70,
            device = DeviceIdentity("this-device", "Phone"),
            clock = clock,
        )
        session.resume()
        clock.advance(10)
        session.navigate(chapter = 1, character = 80)
        session.pause()

        val saved = requireNotNull(session.statisticsForPersistenceOrNull())
        val own = saved.single { it.deviceId == "this-device" }
        assertEquals(50, own.charactersRead)
        assertEquals(40.0, own.readingTime, 0.0)
        assertEquals("Phone", own.deviceName)
        assertEquals(previous[1], saved.single { it.deviceId == "other-device" })
        assertEquals(250, session.state.allTime.charactersRead)
    }

    @Test
    fun usageSpansSentenceTurnsAndLookupsShareOneNormalEpubSession() = runBlocking {
        val clock = Clock()
        val log = UsageLog(temporary.root, clock::currentTimeMillis, { ZoneOffset.UTC }, Dispatchers.Unconfined)
        val usage = ReaderUsageSession(log, "book-id", "Book", UsageContentType.Epub, id = "sentence-session")
        val session = session(clock, usageSession = usage)
        session.resume()
        session.resume()
        clock.advance(5)
        session.navigate(chapter = 2, character = 30)
        session.logLookup(
            ReaderSelectionData("大丈夫", "大丈夫。", ReaderSelectionRect(0.0, 0.0, 10.0, 10.0), 0, 0),
            popup = null,
            source = UsageLookupSource.Page,
        )
        session.pause()
        clock.advance(60)
        session.resume()
        clock.advance(5)
        session.close()
        session.close()

        val events = log.eventsOn(clock.currentDate())
        assertEquals(
            listOf(
                UsageEventType.ReaderOpened, UsageEventType.ReadingStarted, UsageEventType.PageTurned,
                UsageEventType.WordLookedUp, UsageEventType.ReadingStopped, UsageEventType.ReadingStarted,
                UsageEventType.ReadingStopped, UsageEventType.ReaderClosed,
            ),
            events.map { it.type },
        )
        assertTrue(events.all { it.session == "sentence-session" && it.contentType == "epub" && it.bookId == "book-id" })
        assertEquals(2, events.single { it.type == UsageEventType.PageTurned }.chapter)
        assertEquals(30, events.single { it.type == UsageEventType.PageTurned }.character)
        assertEquals("not-found", events.single { it.type == UsageEventType.WordLookedUp }.outcome)
        assertEquals(10.0, session.state.session.readingTime, 0.0)
    }

    private fun session(
        clock: Clock,
        initialCharacter: Int = 0,
        usageSession: ReaderUsageSession? = null,
    ) = SentenceReaderStatisticsSession(
        title = "Book",
        initialStatistics = emptyList(),
        initialCharacter = initialCharacter,
        usageSession = usageSession,
        clock = clock,
    )

    private class Clock : ReaderStatisticsClock {
        private var millis = Instant.parse("2026-10-24T12:00:00Z").toEpochMilli()
        override fun currentTimeMillis(): Long = millis
        override fun currentDate(): LocalDate = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()
        override fun zoneId() = ZoneOffset.UTC
        fun advance(seconds: Long) { millis += seconds * 1_000 }
    }
}
