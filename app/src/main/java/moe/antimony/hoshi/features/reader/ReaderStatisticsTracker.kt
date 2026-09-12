package moe.antimony.hoshi.features.reader

import moe.antimony.hoshi.epub.ReadingStatistics
import moe.antimony.hoshi.epub.deduplicateReadingStatistics
import moe.antimony.hoshi.epub.readingTotals
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.abs

data class ReaderStatisticsState(
    val isTracking: Boolean,
    val session: ReadingStatistics,
    val today: ReadingStatistics,
    val allTime: ReadingStatistics,
)

interface ReaderStatisticsClock {
    fun currentTimeMillis(): Long
    fun currentDate(): LocalDate
}

object SystemReaderStatisticsClock : ReaderStatisticsClock {
    override fun currentTimeMillis(): Long = System.currentTimeMillis()

    override fun currentDate(): LocalDate = LocalDate.now(ZoneId.systemDefault())
}

class ReaderStatisticsTracker(
    private val title: String,
    initialStatistics: List<ReadingStatistics>,
    private val enabled: Boolean,
    private val clock: ReaderStatisticsClock = SystemReaderStatisticsClock,
) {
    private var statistics = initialStatistics.deduplicateReadingStatistics()
    private var lastTimestampMillis: Long = clock.currentTimeMillis()
    private var lastCharacterCount: Int = 0
    private var hasUpdated = false

    private var currentState: ReaderStatisticsState = ReaderStatisticsState(
        isTracking = false,
        session = defaultStatistic(clock.currentDate()),
        today = statisticForDate(clock.currentDate()),
        allTime = allTimeStatistic(statistics),
    )

    /**
     * The sheet's numbers. "Today" follows the clock even while tracking is stopped, so a
     * sheet read after midnight labels the new day as today exactly like the Statistics page.
     */
    val state: ReaderStatisticsState
        get() {
            rollTodayIfNeeded()
            return currentState
        }

    fun start(currentCharacter: Int) {
        if (!enabled) return
        currentState = currentState.copy(isTracking = true)
        resetBaseline(currentCharacter)
    }

    fun startForPageTurnIfNeeded(currentCharacter: Int) {
        if (!currentState.isTracking) {
            start(currentCharacter)
        }
    }

    fun stop(currentCharacter: Int) {
        pause(currentCharacter)
    }

    fun pause(currentCharacter: Int): Boolean {
        if (!currentState.isTracking) return false
        update(currentCharacter)
        currentState = currentState.copy(isTracking = false)
        return true
    }

    fun update(currentCharacter: Int) {
        if (!enabled || !currentState.isTracking) return
        rollTodayIfNeeded()
        val now = clock.currentTimeMillis()
        val elapsedMillis = now - lastTimestampMillis
        if (elapsedMillis < 0L) {
            // The wall clock was set back: lose this one tick, not every tick until the clock
            // passes the value it had before the correction.
            lastTimestampMillis = now
        }
        if (elapsedMillis <= 0L) return
        val timeDiff = elapsedMillis.toDouble() / 1000.0

        val charDiff = currentCharacter - lastCharacterCount
        val finalCharDiff = if (charDiff < 0 && abs(charDiff) > currentState.session.charactersRead) {
            -currentState.session.charactersRead
        } else {
            charDiff
        }
        val modified = clock.currentTimeMillis()
        currentState = currentState.copy(
            session = currentState.session.updated(timeDiff, finalCharDiff, modified),
            today = currentState.today.updated(timeDiff, finalCharDiff, modified),
            allTime = currentState.allTime.updated(timeDiff, finalCharDiff, modified),
        )
        hasUpdated = true
        lastTimestampMillis = now
        lastCharacterCount = currentCharacter
    }

    fun resetBaseline(currentCharacter: Int) {
        lastCharacterCount = currentCharacter
        lastTimestampMillis = clock.currentTimeMillis()
    }

    /** The list to write, or null when this session has not added anything (nothing to save, nothing to sync). */
    fun statisticsForPersistenceOrNull(): List<ReadingStatistics>? =
        if (enabled && hasUpdated) statisticsForPersistence() else null

    fun statisticsForPersistence(): List<ReadingStatistics> {
        rollTodayIfNeeded()
        return storeToday()
    }

    private fun storeToday(): List<ReadingStatistics> {
        val today = currentState.today
        val next = statistics.toMutableList()
        val index = next.indexOfFirst { it.dateKey == today.dateKey }
        if (index >= 0) {
            next[index] = today
        } else {
            next += today
        }
        statistics = next.deduplicateReadingStatistics()
        return statistics
    }

    private fun rollTodayIfNeeded() {
        val currentDate = clock.currentDate()
        val currentDateKey = currentDate.toString()
        if (currentState.today.dateKey == currentDateKey) return
        storeToday()
        currentState = currentState.copy(today = statisticForDate(currentDate))
    }

    private fun statisticForDate(date: LocalDate): ReadingStatistics =
        statistics.firstOrNull { it.dateKey == date.toString() } ?: defaultStatistic(date)

    private fun defaultStatistic(date: LocalDate): ReadingStatistics =
        ReadingStatistics(title = title, dateKey = date.toString())

    private fun allTimeStatistic(statistics: List<ReadingStatistics>): ReadingStatistics {
        // Same totals the Statistics screens compute from the persisted file (see readingTotals).
        val totals = statistics.readingTotals()
        return defaultStatistic(clock.currentDate()).copy(
            readingTime = totals.readingTime,
            charactersRead = totals.charactersRead,
            lastReadingSpeed = totals.readingSpeed,
        )
    }
}

private fun ReadingStatistics.updated(
    timeDiff: Double,
    characterDiff: Int,
    lastStatisticModified: Long,
): ReadingStatistics {
    val nextReadingTime = readingTime + timeDiff
    val nextCharactersRead = (charactersRead + characterDiff).coerceAtLeast(0)
    val nextReadingSpeed = if (nextReadingTime > 0.0) {
        (nextCharactersRead.toDouble() / nextReadingTime * 3600.0).toInt()
    } else {
        0
    }
    return copy(
        readingTime = nextReadingTime,
        charactersRead = nextCharactersRead,
        lastReadingSpeed = nextReadingSpeed,
        maxReadingSpeed = maxOf(maxReadingSpeed, nextReadingSpeed),
        minReadingSpeed = if (minReadingSpeed != 0) minOf(minReadingSpeed, nextReadingSpeed) else nextReadingSpeed,
        altMinReadingSpeed = if (characterDiff != 0) {
            if (altMinReadingSpeed != 0) minOf(altMinReadingSpeed, nextReadingSpeed) else nextReadingSpeed
        } else {
            altMinReadingSpeed
        },
        // Strictly newer than the entry this one was derived from, so the per-day merge on
        // save (newest stamp wins) can never prefer the on-disk base over the session built on
        // it, whatever the wall clock says (a fast clock on another device, or this one set back).
        lastStatisticModified = maxOf(lastStatisticModified, this.lastStatisticModified + 1),
    )
}
