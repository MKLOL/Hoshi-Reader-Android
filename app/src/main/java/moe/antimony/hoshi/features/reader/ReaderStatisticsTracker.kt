package moe.antimony.hoshi.features.reader

import moe.antimony.hoshi.epub.DAY_DEVICE_SEPARATOR
import moe.antimony.hoshi.epub.DeviceIdentity
import moe.antimony.hoshi.epub.LEGACY_READING_ZONE
import moe.antimony.hoshi.epub.ReadingStatistics
import moe.antimony.hoshi.epub.addReadingHours
import moe.antimony.hoshi.epub.deduplicateReadingStatistics
import moe.antimony.hoshi.epub.readingTotals
import moe.antimony.hoshi.epub.readingHoursBetween
import moe.antimony.hoshi.epub.sumMangaPageSeconds
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.math.abs
import kotlin.math.roundToInt
import java.time.Instant
import moe.antimony.hoshi.epub.dayDeviceKey
import moe.antimony.hoshi.features.statistics.TRAVEL_READING_START
import moe.antimony.hoshi.features.statistics.legacyStatisticsCutoff
import moe.antimony.hoshi.features.statistics.readingDayOf
import moe.antimony.hoshi.features.statistics.readingDays
import moe.antimony.hoshi.features.statistics.statisticsClock
import moe.antimony.hoshi.features.statistics.statisticsReadingHours

// Even a fixed offset of ±18 hours and a reset at 23:00 fall inside this UTC window.
private val EARLIEST_CUTOFF_MILLIS = TRAVEL_READING_START.minusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
private val LATEST_CUTOFF_MILLIS = TRAVEL_READING_START.plusDays(2).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

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
    /** Manga records one duration per completed page, independently of periodic time ticks. */
    private val trackMangaPageDurations: Boolean = false,
    /** The previously selected statistics clock, used to preserve earlier reading days. */
    private val legacyZone: () -> ZoneId = { LEGACY_READING_ZONE },
) {
    private val statisticsByDate = initialStatistics.deduplicateReadingStatistics().groupBy { it.dateKey }.toMutableMap()
    private var todayResetHour = resetHour().coerceIn(0, 23)
    private var todayLegacyZone = legacyZone()
    private var lastTimestampMillis: Long = clock.currentTimeMillis()
    private var lastZone: ZoneId = clock.zoneId()
    private var lastCharacterCount: Int = 0
    private var hasUpdated = false
    private var activePageSeconds = 0.0

    /** This device's entry for today: what [update] adds to and [storeToday] writes back. */
    private var todayOnThisDevice: ReadingStatistics = statisticForDate(clock.currentDate())

    private var currentState: ReaderStatisticsState = ReaderStatisticsState(
        isTracking = false,
        session = defaultStatistic(clock.currentDate()),
        today = todayAcrossDevices(),
        allTime = allTimeStatistic(statisticsByDate.values.flatten()),
    )

    /**
     * The sheet's numbers. "Today" follows the clock even while tracking is stopped, so a sheet
     * read after the reset hour labels the new reading day as today exactly like the Statistics page.
     */
    val state: ReaderStatisticsState
        get() {
            rollTodayIfNeeded()
            if (todayResetHour != resetHour().coerceIn(0, 23) || todayLegacyZone != legacyZone() ||
                currentState.today.dateKey != readingDay().toString()
            ) {
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
        val reset = resetHour().coerceIn(0, 23)
        val zone = legacyZone()
        val day = readingDay(reset, zone)
        val keys = readingDaySourceDates(day)
        val characters = charactersByDate.filterKeys { it.substringBefore(DAY_DEVICE_SEPARATOR) in keys }
        return readingDays(keys.flatMap(::recordsForDate), characters, reset, zone)
            .firstOrNull { it.dateKey == day.toString() }?.characters ?: 0
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
        val now = clock.currentTimeMillis()
        val currentZone = clock.zoneId()
        val elapsedMillis = now - lastTimestampMillis
        if (elapsedMillis <= 0L) {
            // A backwards clock correction starts a new baseline immediately.
            lastTimestampMillis = now
            lastZone = currentZone
            return
        }
        // When a clock changes zones between ticks, finish the pending interval on
        // the clock it started on. New time begins in the new zone at this tick.
        val endDate = if (lastZone == currentZone) clock.currentDate()
            else Instant.ofEpochMilli(now).atZone(lastZone).toLocalDate()
        rollTodayIfNeeded(endDate)
        val timeDiff = elapsedMillis.toDouble() / 1000.0
        if (trackMangaPageDurations) activePageSeconds += timeDiff

        val charDiff = currentCharacter - lastCharacterCount
        val finalCharDiff = if (charDiff < 0 && abs(charDiff) > currentState.session.charactersRead) {
            -currentState.session.charactersRead
        } else {
            charDiff
        }
        val cutoff = if (now >= EARLIEST_CUTOFF_MILLIS && lastTimestampMillis < LATEST_CUTOFF_MILLIS) {
            legacyStatisticsCutoff(resetHour(), legacyZone()).toEpochMilli()
        } else null
        val hours = readingHoursBetween(lastTimestampMillis, now, lastZone, cutoff)
        todayOnThisDevice = todayOnThisDevice.addReadingHours(lastZone, hours).updated(timeDiff, finalCharDiff, now)
        currentState = currentState.copy(
            session = currentState.session.addReadingHours(lastZone, hours).updated(timeDiff, finalCharDiff, now),
            allTime = currentState.allTime.updated(timeDiff, finalCharDiff, now),
        )
        rollTodayIfNeeded()
        currentState = currentState.copy(today = todayAcrossDevices())
        hasUpdated = true
        lastTimestampMillis = now
        lastZone = currentZone
        lastCharacterCount = currentCharacter
    }

    /**
     * Called for every navigation. Only a real single-page forward turn completes a page;
     * jumps/backtracking discard its unfinished sample rather than inventing page durations.
     * Pausing preserves active time on the same page, but never includes time spent paused.
     */
    fun onMangaPageChanged(currentCounter: Int, completedPage: Boolean) {
        update(currentCounter)
        if (trackMangaPageDurations && currentState.isTracking && completedPage && activePageSeconds > 0.0) {
            val sample = mapOf(activePageSeconds.roundToInt().coerceAtLeast(1) to 1)
            fun ReadingStatistics.withSample() = copy(
                mangaPageSeconds = listOf(mangaPageSeconds, sample).sumMangaPageSeconds(),
                lastStatisticModified = maxOf(clock.currentTimeMillis(), lastStatisticModified + 1),
            )
            todayOnThisDevice = todayOnThisDevice.withSample()
            currentState = currentState.copy(
                session = currentState.session.withSample(),
                today = todayAcrossDevices(),
                allTime = currentState.allTime.withSample(),
            )
            hasUpdated = true
        }
        activePageSeconds = 0.0
    }

    fun resetBaseline(currentCharacter: Int) {
        lastCharacterCount = currentCharacter
        lastTimestampMillis = clock.currentTimeMillis()
        lastZone = clock.zoneId()
    }

    /** The list to write, or null when this session has not added anything (nothing to save, nothing to sync). */
    fun statisticsForPersistenceOrNull(): List<ReadingStatistics>? =
        if (enabled && hasUpdated) statisticsForPersistence() else null

    fun statisticsForPersistence(): List<ReadingStatistics> {
        rollTodayIfNeeded()
        storeToday()
        return statisticsByDate.values.flatten()
    }

    private fun storeToday() {
        val date = todayOnThisDevice.dateKey
        statisticsByDate[date] = recordsForDate(date)
    }

    private fun rollTodayIfNeeded(currentDate: LocalDate = clock.currentDate()) {
        val currentDateKey = currentDate.toString()
        if (todayOnThisDevice.dateKey == currentDateKey) return
        storeToday()
        todayOnThisDevice = statisticForDate(currentDate)
        currentState = currentState.copy(today = todayAcrossDevices())
    }

    /** This device's stored entry for [date], carrying the device's current name, or a fresh one. */
    private fun statisticForDate(date: LocalDate): ReadingStatistics =
        statisticsByDate[date.toString()]?.firstOrNull { it.deviceId == device?.id }
            ?.let { stored -> if (device != null) stored.copy(deviceName = device.name) else stored }
            ?: defaultStatistic(date)

    /** The reading day the clock is in: before the reset hour it is still the previous date. */
    private fun readingDay(reset: Int = resetHour().coerceIn(0, 23), zone: ZoneId = legacyZone()): LocalDate {
        val device = Instant.ofEpochMilli(clock.currentTimeMillis()).atZone(clock.zoneId())
        val statistics = statisticsClock(device, reset, zone)
        val date = if (statistics.zone == device.zone) clock.currentDate() else statistics.toLocalDate()
        return if (statistics.hour < reset) date.minusDays(1) else date
    }

    private fun recordsForDate(date: String): List<ReadingStatistics> {
        val stored = statisticsByDate[date].orEmpty()
        return if (date == todayOnThisDevice.dateKey) {
            stored.filterNot { it.deviceId == todayOnThisDevice.deviceId } + todayOnThisDevice
        } else stored
    }

    /** Legacy zone conversion can also bring in hours recorded on either neighbouring clock. */
    private fun readingDaySourceDates(day: LocalDate): List<String> {
        val offsets = if (day < TRAVEL_READING_START) -2L..4L else 0L..2L
        return offsets.map { day.plusDays(it).toString() }
    }

    /**
     * Today for this book across every device, as a reading day (see [readingDays]): after
     * midnight and before the reset hour it is still last night. Time, characters and the
     * average speed are the whole day's; the min/max speed fields stay this device's session
     * values, which is all the sheets show them for. Records stay keyed by calendar date.
     */
    private fun todayAcrossDevices(): ReadingStatistics {
        val reset = resetHour().coerceIn(0, 23)
        todayResetHour = reset
        val zone = legacyZone()
        todayLegacyZone = zone
        val readingDay = readingDay(reset, zone)
        val keys = readingDaySourceDates(readingDay)
        val day = readingDay.toString()
        val records = keys.flatMap(::recordsForDate)
        val characters = records.groupBy { dayDeviceKey(it.dateKey, it.deviceId) }
            .mapValues { (_, entries) -> entries.sumOf { it.charactersRead } }
        val total = readingDays(records, characters, reset, zone).firstOrNull { it.dateKey == day }
        val readingTime = total?.seconds ?: 0.0
        val charactersRead = total?.characters ?: 0
        return todayOnThisDevice.copy(
            dateKey = day,
            readingTime = readingTime,
            charactersRead = charactersRead,
            readingTimeByHour = records.flatMap { it.statisticsReadingHours(reset, zone) }
                .filter { (hour, _) -> readingDayOf(hour, reset) == readingDay }
                .groupingBy { (hour, _) -> hour.toString() }
                .fold(0.0) { total, (_, seconds) -> total + seconds },
            timeZone = null,
            readingTimeByZone = emptyMap(),
            lastReadingSpeed = if (readingTime > 0.0) (charactersRead / readingTime * 3600.0).toInt() else 0,
            mangaPageSeconds = recordsForDate(todayOnThisDevice.dateKey).map { it.mangaPageSeconds }.sumMangaPageSeconds(),
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
            mangaPageSeconds = statistics.deduplicateReadingStatistics().map { it.mangaPageSeconds }.sumMangaPageSeconds(),
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
