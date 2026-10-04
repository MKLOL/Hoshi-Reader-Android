package moe.antimony.hoshi.features.reader

import moe.antimony.hoshi.epub.DeviceIdentity
import moe.antimony.hoshi.epub.ReadingStatistics
import moe.antimony.hoshi.epub.deduplicateReadingStatistics
import moe.antimony.hoshi.epub.readingTotals
import moe.antimony.hoshi.epub.readingHoursBetween
import moe.antimony.hoshi.epub.sumReadingHours
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.abs
import java.time.Instant
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import moe.antimony.hoshi.epub.convertWallClock
import moe.antimony.hoshi.epub.dayDeviceKey
import moe.antimony.hoshi.epub.recordedZone
import moe.antimony.hoshi.epub.zoneOrNull
import moe.antimony.hoshi.features.statistics.readingDayOf
import moe.antimony.hoshi.features.statistics.readingDays
import moe.antimony.hoshi.features.statistics.streakDate

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
    fun zoneId(): ZoneId = ZoneId.systemDefault()
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
    /**
     * Told each time counting starts (`true`) or stops (`false`), only on a real change. The
     * usage log opens and closes its reading spans here so they cover exactly the counted time.
     */
    private val onTrackingChanged: (Boolean) -> Unit = {},
    /**
     * The hour a reading day starts (the Statistics reset setting). "Today" runs from it until
     * the same hour the next morning, like the Statistics screen and the streak. Read on every
     * use, so changing the setting applies without reopening the book.
     */
    private val resetHour: () -> Int = { 0 },
    /**
     * The zone reading days are counted in on every device (see `statisticsZone`), read on
     * every use; null counts them on [clock]'s own wall clock. Entries are still recorded on
     * [clock]'s wall clock, named by [ReadingStatistics.timeZone].
     */
    private val statisticsZone: () -> ZoneId? = { null },
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
        today = todayAcrossDevices(),
        allTime = allTimeStatistic(statistics),
    )

    /**
     * The sheet's numbers. "Today" follows the clock even while tracking is stopped, so a sheet
     * read after the reset hour labels the new reading day as today exactly like the Statistics page.
     */
    val state: ReaderStatisticsState
        get() {
            rollTodayIfNeeded()
            if (currentState.today.dateKey != readingDay().toString()) {
                currentState = currentState.copy(today = todayAcrossDevices())
            }
            return currentState
        }

    /**
     * [charactersByDate] (calendar date to characters, e.g. a manga's OCR text) as read on the
     * current reading day, each date's count split the way its reading time fell.
     */
    fun readingDayCharacters(charactersByDate: Map<String, Int>): Int {
        rollTodayIfNeeded()
        val day = readingDay().toString()
        return readingDays(recordsWithRunningToday(), charactersByDate, resetHour(), statisticsZone())
            .firstOrNull { it.dateKey == day }?.characters ?: 0
    }

    fun start(currentCharacter: Int) {
        if (!enabled) return
        val wasTracking = currentState.isTracking
        currentState = currentState.copy(isTracking = true)
        resetBaseline(currentCharacter)
        if (!wasTracking) onTrackingChanged(true)
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
        onTrackingChanged(false)
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
        val zone = clock.zoneId()
        val hours = readingHoursBetween(lastTimestampMillis, now, zone)
        todayOnThisDevice = todayOnThisDevice.recordedIn(zone).updated(timeDiff, finalCharDiff, modified, hours)
        currentState = currentState.copy(
            session = currentState.session.recordedIn(zone).updated(timeDiff, finalCharDiff, modified, hours),
            today = todayAcrossDevices(),
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
        currentState = currentState.copy(today = todayAcrossDevices())
    }

    /** This device's stored entry for [date], carrying the device's current name, or a fresh one. */
    private fun statisticForDate(date: LocalDate): ReadingStatistics =
        statistics.firstOrNull { it.dateKey == date.toString() && it.deviceId == device?.id }
            ?.let { stored -> if (device != null) stored.copy(deviceName = device.name) else stored }
            ?: defaultStatistic(date)

    /** The reading day the clock is in: before the reset hour it is still the previous date. */
    private fun readingDay(): LocalDate {
        statisticsZone()?.let { zone ->
            return streakDate(Instant.ofEpochMilli(clock.currentTimeMillis()).atZone(zone), resetHour())
        }
        val date = clock.currentDate()
        val reset = resetHour().coerceIn(0, 23)
        if (reset == 0) return date
        val hour = Instant.ofEpochMilli(clock.currentTimeMillis()).atZone(clock.zoneId()).hour
        return if (hour < reset) date.minusDays(1) else date
    }

    /** Every device's records, with this device's running entry standing in for its stored one. */
    private fun recordsWithRunningToday(): List<ReadingStatistics> =
        statistics.filterNot { it.dateKey == todayOnThisDevice.dateKey && it.deviceId == todayOnThisDevice.deviceId } +
            todayOnThisDevice

    /**
     * Today for this book across every device, as a reading day (see [readingDays]): after
     * midnight and before the reset hour it is still last night. Time, characters and the
     * average speed are the whole day's; the min/max speed fields stay this device's session
     * values, which is all the sheets show them for. Records stay keyed by calendar date.
     */
    private fun todayAcrossDevices(): ReadingStatistics {
        val records = recordsWithRunningToday()
        val reset = resetHour()
        val zone = statisticsZone()
        val day = readingDay().toString()
        val characters = records.groupBy { dayDeviceKey(it.dateKey, it.deviceId) }
            .mapValues { (_, entries) -> entries.sumOf { it.charactersRead } }
        val total = readingDays(records, characters, reset, zone).firstOrNull { it.dateKey == day }
        val readingTime = total?.seconds ?: 0.0
        val charactersRead = total?.characters ?: 0
        return todayOnThisDevice.copy(
            dateKey = day,
            readingTime = readingTime,
            charactersRead = charactersRead,
            // The hours that make up the day: its own records' with a midnight reset, as before.
            readingTimeByHour = if (reset <= 0 && zone == null) {
                records.filter { it.dateKey == day }.map { it.readingTimeByHour }.sumReadingHours()
            } else {
                records.map { it.hoursOn(zone) }.sumReadingHours().filterKeys { key ->
                    runCatching { readingDayOf(LocalDateTime.parse(key), reset).toString() == day }.getOrDefault(false)
                }
            },
            timeZone = zone?.id ?: todayOnThisDevice.timeZone,
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

/**
 * This entry recorded on [zone]'s wall clock. Hours recorded in another zone move to [zone]'s:
 * the zone the entry names, or for an entry from before zones were recorded the one its stamp
 * tells (a device whose clock was on another zone and has since been set right).
 */
private fun ReadingStatistics.recordedIn(zone: ZoneId): ReadingStatistics {
    if (timeZone == zone.id) return this
    val from = zoneOrNull(timeZone) ?: recordedZone(zone)
    if (from == null || from == zone) return copy(timeZone = zone.id)
    return copy(timeZone = zone.id, readingTimeByHour = readingTimeByHour.rekeyed(from, zone))
}

/** [ReadingStatistics.readingTimeByHour] on [zone]'s wall clock (as recorded when null). */
private fun ReadingStatistics.hoursOn(zone: ZoneId?): Map<String, Double> {
    zone ?: return readingTimeByHour
    val from = recordedZone(zone)?.takeIf { it != zone } ?: return readingTimeByHour
    return readingTimeByHour.rekeyed(from, zone)
}

private fun Map<String, Double>.rekeyed(from: ZoneId, to: ZoneId): Map<String, Double> {
    val result = mutableMapOf<String, Double>()
    forEach { (key, seconds) ->
        val hour = runCatching { LocalDateTime.parse(key) }.getOrNull()
        val moved = hour?.let { convertWallClock(it, from, to).truncatedTo(ChronoUnit.HOURS).toString() } ?: key
        result[moved] = (result[moved] ?: 0.0) + seconds
    }
    return result
}

private fun ReadingStatistics.updated(
    timeDiff: Double,
    characterDiff: Int,
    lastStatisticModified: Long,
    hours: Map<String, Double> = emptyMap(),
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
        readingTimeByHour = listOf(readingTimeByHour, hours).sumReadingHours(),
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
