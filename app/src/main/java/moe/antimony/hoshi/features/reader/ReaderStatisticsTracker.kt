package moe.antimony.hoshi.features.reader

import moe.antimony.hoshi.epub.DeviceIdentity
import moe.antimony.hoshi.epub.ReadingStatistics
import moe.antimony.hoshi.epub.deduplicateReadingStatistics
import moe.antimony.hoshi.epub.readingTotals
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.abs

data class ReaderStatisticsState(
    val isTracking: Boolean,
    val session: ReadingStatistics,
    /**
     * Today for this book across every device: this device's running entry plus whatever
     * other devices already recorded for the day, so the sheet agrees with the Statistics
     * page's day-by-day history.
     */
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
    /** The device whose per-day entry this tracker adds to; other devices' entries are left as they are. */
    private val device: DeviceIdentity? = null,
) {
    private var statistics = initialStatistics.deduplicateReadingStatistics()
    private var lastTimestampMillis: Long = clock.currentTimeMillis()
    private var lastCharacterCount: Int = 0
    private var hasUpdated = false

    /** This device's entry for today: what [update] adds to and [storeToday] writes back. */
    private var todayOnThisDevice: ReadingStatistics = statisticForDate(clock.currentDate())

    private var currentState: ReaderStatisticsState = ReaderStatisticsState(
        isTracking = false,
        session = defaultStatistic(clock.currentDate()),
        today = todayAcrossDevices(clock.currentDate()),
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
        todayOnThisDevice = todayOnThisDevice.updated(timeDiff, finalCharDiff, modified)
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
        val today = todayOnThisDevice
        val next = statistics.toMutableList()
        val index = next.indexOfFirst { it.dateKey == today.dateKey && it.deviceId == today.deviceId }
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
        if (todayOnThisDevice.dateKey == currentDateKey) return
        storeToday()
        todayOnThisDevice = statisticForDate(currentDate)
        currentState = currentState.copy(today = todayAcrossDevices(currentDate))
    }

    /** This device's stored entry for [date], carrying the device's current name, or a fresh one. */
    private fun statisticForDate(date: LocalDate): ReadingStatistics =
        statistics.firstOrNull { it.dateKey == date.toString() && it.deviceId == device?.id }
            ?.let { stored -> if (device != null) stored.copy(deviceName = device.name) else stored }
            ?: defaultStatistic(date)

    /**
     * Every device's entry for [date] added up, with this device's running entry standing in for
     * its stored one. Time, characters and the average speed are the whole day's; the min/max
     * speed fields stay this device's session values, which is all the sheets show them for.
     */
    private fun todayAcrossDevices(date: LocalDate): ReadingStatistics {
        val dateKey = date.toString()
        val others = statistics.filter { it.dateKey == dateKey && it.deviceId != todayOnThisDevice.deviceId }
        val readingTime = todayOnThisDevice.readingTime + others.sumOf { it.readingTime }
        val charactersRead = todayOnThisDevice.charactersRead + others.sumOf { it.charactersRead }
        return todayOnThisDevice.copy(
            readingTime = readingTime,
            charactersRead = charactersRead,
            lastReadingSpeed = if (readingTime > 0.0) (charactersRead / readingTime * 3600.0).toInt() else 0,
        )
    }

    private fun defaultStatistic(date: LocalDate): ReadingStatistics =
        ReadingStatistics(title = title, dateKey = date.toString(), deviceId = device?.id, deviceName = device?.name)

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
