package moe.antimony.hoshi.features.reader

import moe.antimony.hoshi.epub.BookRepository
import moe.antimony.hoshi.epub.ContentType
import moe.antimony.hoshi.epub.LEGACY_READING_ZONE
import moe.antimony.hoshi.epub.ReadingStatistics
import moe.antimony.hoshi.epub.StatisticsHistoryEntry
import moe.antimony.hoshi.epub.deduplicateReadingStatistics
import moe.antimony.hoshi.epub.readingTotals
import moe.antimony.hoshi.features.bookshelf.BookCoverSource
import moe.antimony.hoshi.features.bookshelf.isBookCompleted
import moe.antimony.hoshi.mokuro.MangaTextStatistic
import moe.antimony.hoshi.mokuro.deduplicateMangaTextStatistics
import moe.antimony.hoshi.features.statistics.charactersByDate
import moe.antimony.hoshi.features.statistics.readingDays
import java.time.LocalDate
import java.time.ZoneId

/** One day of reading: seconds spent and the amount read (characters, or OCR characters for manga). */
data class DailyReading(
    val dateKey: String,
    val seconds: Double,
    val characters: Int,
    internal val estimatedStreakSeconds: Double = 0.0,
) {
    internal val hasReading: Boolean get() = seconds > 0.0 || characters > 0
    internal val streakSeconds: Double get() = seconds + estimatedStreakSeconds
}

/**
 * What one device read: of one book on the book page, of the whole library on the overview.
 * [deviceId] is null for entries recorded before devices were tracked that no install has
 * claimed yet (only possible in files that arrived from elsewhere).
 */
data class DeviceReadingSummary(
    val deviceId: String?,
    /** The name the device had when it last recorded reading. */
    val deviceName: String?,
    val totalSeconds: Double,
    /** Characters read on this device: the book's text for an EPUB, OCR text for a manga. */
    val charactersRead: Int,
    /** Manga pages turned past on this device. */
    val pagesRead: Int,
    /** ISO date (`yyyy-MM-dd`) of the most recent day with reading time on this device. */
    val lastReadDateKey: String?,
    /** Books with reading time or characters recorded on this device. */
    val bookCount: Int,
    /** Newest modification stamp among this device's entries; the name on that entry is the one shown. */
    val newestStamp: Long = 0L,
)

/** One book's share of the reading statistics, aggregated from its sidecars. */
data class BookReadingSummary(
    val bookId: String,
    val title: String,
    val contentType: ContentType,
    /** Seconds spent reading across every recorded day. */
    val totalSeconds: Double,
    /** Characters read: the book's text for an EPUB, OCR text for a manga. */
    val charactersRead: Int,
    /** Pages turned past; manga only. */
    val pagesRead: Int?,
    /** ISO date (`yyyy-MM-dd`) of the first day with recorded reading time. */
    val startedDateKey: String?,
    /** ISO date (`yyyy-MM-dd`) of the most recent day with recorded reading time. */
    val lastReadDateKey: String?,
    /** Position in the book, 0..1, from the bookmark. */
    val progress: Double,
    val finished: Boolean,
    val coverSource: BookCoverSource?,
    /** Every day with reading time or amount read, newest first. */
    val days: List<DailyReading>,
    /** This book's reading per device, longest first. */
    val devices: List<DeviceReadingSummary> = emptyList(),
    /** False for a book only its reading history is kept for (not downloaded here, or deleted). */
    val onDevice: Boolean = true,
    /** False when this install never knew the position, so [progress] and [finished] mean nothing. */
    val progressKnown: Boolean = true,
) {
    val daysRead: Int get() = days.count { it.seconds > 0.0 }
}

/** Everything the Statistics screens show. */
data class ReadingStatisticsOverview(
    val totalSeconds: Double,
    val todaySeconds: Double,
    val totalCharacters: Int,
    val todayCharacters: Int,
    /** Books with recorded reading time or characters, longest first. */
    val books: List<BookReadingSummary>,
    /** Reading per day across every book, newest first. */
    val daily: List<DailyReading>,
    /** Reading per device across every book, longest first. */
    val devices: List<DeviceReadingSummary> = emptyList(),
    /** Actual reading with the same estimated credit the older streak calculation used. */
    val streakDaily: List<DailyReading> = daily,
    /** The cached portion before the current three reading days, newest first. */
    val olderStreakDays: List<DailyReading>? = null,
    /** At most three reading days ending today, newest first. */
    val recentStreakDays: List<DailyReading>? = null,
    /** Stable when an older day is reused or when the window moves forward. */
    val streakHistoryRevision: Any? = null,
    val streakWindowDay: LocalDate? = null,
)

data class BookStatisticsInput(
    val bookId: String,
    val title: String,
    val contentType: ContentType,
    /** The shared `statistics.json`: characters for an EPUB, pages for a manga. */
    val statistics: List<ReadingStatistics>,
    /** The manga-only `manga_statistics.json` with OCR characters per day. */
    val mangaTextStatistics: List<MangaTextStatistic> = emptyList(),
    val progress: Double = 0.0,
    val coverSource: BookCoverSource? = null,
    /** False for a book only its reading history is kept for. */
    val onDevice: Boolean = true,
    /** False when [progress] is not known (a book this install never had). */
    val progressKnown: Boolean = true,
)

/**
 * Folds every book's per-day statistics into per-book and per-day totals. A book without any
 * reading time is left out. Every day here is a reading day running from [streakResetHour]
 * until the same hour the next morning, the day the streak counts (see [readingDays]), so no
 * total changes day at midnight; [todayKey] is the reading day shown as "today".
 */
fun summarizeReadingStatistics(
    inputs: List<BookStatisticsInput>,
    todayKey: String,
    streakResetHour: Int = 0,
    legacyZone: ZoneId = LEGACY_READING_ZONE,
): ReadingStatisticsOverview {
    val projections = inputs.mapNotNull { input ->
        val days = readingDays(input.statistics, input.charactersByDate(), streakResetHour, legacyZone, includeEstimatedStreakCredit = true)
        summarizeBookReading(input, days.filter(DailyReading::hasReading), streakResetHour, legacyZone)?.let { it to days }
    }
    val byDate = projections.flatMap { it.second }.groupBy { it.dateKey }
    val daily = byDate.entries.map { (date, days) ->
        combineDailyReading(date, days)
    }.sortedByDescending { it.dateKey }
    return summarizeBooks(projections.map { it.first }, daily.filter(DailyReading::hasReading), todayKey)
        .copy(streakDaily = daily)
}

internal fun combineDailyReading(date: String, days: Collection<DailyReading>): DailyReading =
    DailyReading(date, days.sumOf { it.seconds }, days.sumOf { it.characters }, days.sumOf { it.estimatedStreakSeconds })

internal fun summarizeBookReading(
    input: BookStatisticsInput,
    days: List<DailyReading>,
    resetHour: Int,
    legacyZone: ZoneId,
): BookReadingSummary? {
    val statistics = input.statistics.deduplicateReadingStatistics()
    val mangaText = input.mangaTextStatistics.deduplicateMangaTextStatistics()
    // The same totals the reader's Statistics sheet shows as "All Time".
    val totals = statistics.readingTotals()
    val charactersRead = when (input.contentType) {
        ContentType.Epub -> totals.charactersRead
        ContentType.Mokuro -> mangaText.sumOf { it.charactersRead }
    }
    if (totals.readingTime <= 0.0 && charactersRead <= 0 && totals.charactersRead <= 0) return null
    return BookReadingSummary(
        bookId = input.bookId,
        title = input.title,
        contentType = input.contentType,
        totalSeconds = totals.readingTime,
        charactersRead = charactersRead,
        pagesRead = when (input.contentType) {
            ContentType.Epub -> null
            ContentType.Mokuro -> totals.charactersRead
        },
        startedDateKey = days.lastOrNull { it.seconds > 0.0 }?.dateKey,
        lastReadDateKey = days.firstOrNull { it.seconds > 0.0 }?.dateKey,
        progress = input.progress.coerceIn(0.0, 1.0),
        finished = isBookCompleted(input.progress),
        coverSource = input.coverSource,
        days = days,
        devices = summarizeDevices(input.contentType, statistics, mangaText, resetHour, legacyZone),
        onDevice = input.onDevice,
        progressKnown = input.progressKnown,
    )
}

internal fun summarizeBooks(
    books: List<BookReadingSummary>,
    daily: List<DailyReading>,
    todayKey: String,
): ReadingStatisticsOverview {
    val today = daily.firstOrNull { it.dateKey == todayKey }
    return ReadingStatisticsOverview(
        totalSeconds = books.sumOf { it.totalSeconds },
        todaySeconds = today?.seconds ?: 0.0,
        totalCharacters = books.sumOf { it.charactersRead },
        todayCharacters = today?.characters ?: 0,
        books = books.sortedWith(compareByDescending<BookReadingSummary> { it.totalSeconds }.thenBy { it.title }),
        daily = daily,
        devices = books.flatMap { it.devices }.mergeDevices(),
    )
}

/**
 * One book's reading split by device. Every device's entry counts for its own device; the
 * device's name is the one on its newest entry, so a renamed device shows its new name.
 */
private fun summarizeDevices(
    contentType: ContentType,
    statistics: List<ReadingStatistics>,
    mangaText: List<MangaTextStatistic>,
    resetHour: Int,
    legacyZone: ZoneId,
): List<DeviceReadingSummary> {
    val deviceIds = (statistics.map { it.deviceId } + mangaText.map { it.deviceId }).distinct()
    return deviceIds.mapNotNull { deviceId ->
        val own = statistics.filter { it.deviceId == deviceId }
        val ownText = mangaText.filter { it.deviceId == deviceId }
        val totalSeconds = own.sumOf { it.readingTime }
        val charactersRead = when (contentType) {
            ContentType.Epub -> own.sumOf { it.charactersRead }
            ContentType.Mokuro -> ownText.sumOf { it.charactersRead }
        }
        val pagesRead = when (contentType) {
            ContentType.Epub -> 0
            ContentType.Mokuro -> own.sumOf { it.charactersRead }
        }
        if (totalSeconds <= 0.0 && charactersRead <= 0 && pagesRead <= 0) return@mapNotNull null
        val stamped = own.map { it.lastStatisticModified to it.deviceName } +
            ownText.map { it.lastModified to it.deviceName }
        val newest = stamped.filter { !it.second.isNullOrBlank() }.maxByOrNull { it.first }
        DeviceReadingSummary(
            deviceId = deviceId,
            deviceName = newest?.second,
            totalSeconds = totalSeconds,
            charactersRead = charactersRead,
            pagesRead = pagesRead,
            lastReadDateKey = latestReadingDay(own, resetHour, legacyZone),
            bookCount = 1,
            newestStamp = newest?.first ?: stamped.maxOfOrNull { it.first } ?: 0L,
        )
    }.sortedWith(compareByDescending<DeviceReadingSummary> { it.totalSeconds }.thenBy { it.deviceName ?: "" })
}

/** A legacy hour can be moved up to two calendar days forward by zone conversion. */
private fun latestReadingDay(statistics: List<ReadingStatistics>, resetHour: Int, legacyZone: ZoneId): String? {
    var latest: LocalDate? = null
    for (row in statistics.sortedByDescending { it.dateKey }) {
        if (latest != null && row.dateKey <= latest.minusDays(2).toString()) break
        val key = readingDays(listOf(row), emptyMap(), resetHour, legacyZone).firstOrNull()?.dateKey ?: continue
        val day = runCatching { LocalDate.parse(key) }.getOrNull() ?: continue
        if (latest == null || day > latest) latest = day
    }
    return latest?.toString()
}

/** Adds up per-book device summaries into per-device totals for the whole library. */
fun List<DeviceReadingSummary>.mergeDevices(): List<DeviceReadingSummary> =
    groupBy { it.deviceId }
        .map { (deviceId, entries) ->
            DeviceReadingSummary(
                deviceId = deviceId,
                // A renamed device shows its newest name on every screen: the name on the
                // most recently modified entry, whichever book it belongs to.
                deviceName = entries.filter { !it.deviceName.isNullOrBlank() }
                    .maxByOrNull { it.newestStamp }?.deviceName,
                totalSeconds = entries.sumOf { it.totalSeconds },
                charactersRead = entries.sumOf { it.charactersRead },
                pagesRead = entries.sumOf { it.pagesRead },
                lastReadDateKey = entries.mapNotNull { it.lastReadDateKey }.maxOrNull(),
                bookCount = entries.sumOf { it.bookCount },
                newestStamp = entries.maxOf { it.newestStamp },
            )
        }
        .sortedWith(compareByDescending<DeviceReadingSummary> { it.totalSeconds }.thenBy { it.deviceName ?: "" })

/** Book id of a book listed only from its reading history (see [StatisticsHistoryEntry]). */
const val STATISTICS_HISTORY_BOOK_ID_PREFIX: String = "history:"

/** Loads the library, including books whose reading history is kept without their files. */
suspend fun loadReadingStatisticsOverview(
    bookRepository: BookRepository,
    todayKey: String,
    streakResetHour: Int = 0,
    legacyZone: ZoneId = LEGACY_READING_ZONE,
): ReadingStatisticsOverview =
    ReadingStatisticsOverviewCache().load(bookRepository, todayKey, streakResetHour, legacyZone).copy(
        olderStreakDays = null, recentStreakDays = null, streakHistoryRevision = null, streakWindowDay = null,
    )
