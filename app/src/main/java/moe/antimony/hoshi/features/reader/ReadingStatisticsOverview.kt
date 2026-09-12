package moe.antimony.hoshi.features.reader

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.antimony.hoshi.epub.BookRepository
import moe.antimony.hoshi.epub.ContentType
import moe.antimony.hoshi.epub.ReadingStatistics
import moe.antimony.hoshi.epub.bookContentType
import moe.antimony.hoshi.epub.deduplicateReadingStatistics
import moe.antimony.hoshi.epub.readingTotals
import moe.antimony.hoshi.features.bookshelf.BookCoverSource
import moe.antimony.hoshi.features.bookshelf.isBookCompleted
import moe.antimony.hoshi.features.bookshelf.toBookCoverSource
import moe.antimony.hoshi.mokuro.MangaTextStatistic
import moe.antimony.hoshi.mokuro.deduplicateMangaTextStatistics

/** One day of reading: seconds spent and the amount read (characters, or OCR characters for manga). */
data class DailyReading(
    val dateKey: String,
    val seconds: Double,
    val characters: Int,
)

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
)

/**
 * Folds every book's per-day statistics into per-book and per-day totals. A book without any
 * reading time is left out; [todayKey] is the ISO date whose records count as "today".
 */
fun summarizeReadingStatistics(
    inputs: List<BookStatisticsInput>,
    todayKey: String,
): ReadingStatisticsOverview {
    val dailySeconds = mutableMapOf<String, Double>()
    val dailyCharacters = mutableMapOf<String, Int>()
    val books = inputs.mapNotNull { input ->
        val statistics = input.statistics.deduplicateReadingStatistics()
        val mangaText = input.mangaTextStatistics.deduplicateMangaTextStatistics()
        // A day may hold one entry per device; the day is the sum of them.
        val secondsByDay: Map<String, Double> = statistics.groupBy { it.dateKey }.mapValues { (_, entries) -> entries.sumOf { it.readingTime } }
        val charactersByDay: Map<String, Int> = when (input.contentType) {
            ContentType.Epub -> statistics.groupBy { it.dateKey }.mapValues { (_, entries) -> entries.sumOf { it.charactersRead } }
            ContentType.Mokuro -> mangaText.groupBy { it.dateKey }.mapValues { (_, entries) -> entries.sumOf { it.charactersRead } }
        }
        val days = (secondsByDay.keys + charactersByDay.keys).distinct()
            .map { dateKey ->
                DailyReading(
                    dateKey = dateKey,
                    seconds = secondsByDay[dateKey] ?: 0.0,
                    characters = charactersByDay[dateKey] ?: 0,
                )
            }
            .filter { it.seconds > 0.0 || it.characters > 0 }
            .sortedByDescending { it.dateKey }
        // The same totals the reader's Statistics sheet shows as "All Time".
        val totals = statistics.readingTotals()
        val charactersRead = when (input.contentType) {
            ContentType.Epub -> totals.charactersRead
            ContentType.Mokuro -> mangaText.sumOf { it.charactersRead }
        }
        // Anything the reader's sheet would show as read is listed here too: time, characters,
        // or manga pages recorded without either.
        if (totals.readingTime <= 0.0 && charactersRead <= 0 && totals.charactersRead <= 0) return@mapNotNull null
        // Only listed books feed the per-day totals, so "today" can never exceed "all time".
        days.forEach { day ->
            dailySeconds[day.dateKey] = (dailySeconds[day.dateKey] ?: 0.0) + day.seconds
            dailyCharacters[day.dateKey] = (dailyCharacters[day.dateKey] ?: 0) + day.characters
        }
        val readDays = days.filter { it.seconds > 0.0 }
        BookReadingSummary(
            bookId = input.bookId,
            title = input.title,
            contentType = input.contentType,
            totalSeconds = totals.readingTime,
            charactersRead = charactersRead,
            pagesRead = when (input.contentType) {
                ContentType.Epub -> null
                ContentType.Mokuro -> totals.charactersRead
            },
            startedDateKey = readDays.minOfOrNull { it.dateKey },
            lastReadDateKey = readDays.maxOfOrNull { it.dateKey },
            progress = input.progress.coerceIn(0.0, 1.0),
            finished = isBookCompleted(input.progress),
            coverSource = input.coverSource,
            days = days,
            devices = summarizeDevices(input.contentType, statistics, mangaText),
        )
    }.sortedWith(compareByDescending<BookReadingSummary> { it.totalSeconds }.thenBy { it.title })
    val daily = (dailySeconds.keys + dailyCharacters.keys).distinct()
        .map { DailyReading(it, dailySeconds[it] ?: 0.0, dailyCharacters[it] ?: 0) }
        .sortedByDescending { it.dateKey }
    return ReadingStatisticsOverview(
        totalSeconds = books.sumOf { it.totalSeconds },
        todaySeconds = dailySeconds[todayKey] ?: 0.0,
        totalCharacters = books.sumOf { it.charactersRead },
        todayCharacters = dailyCharacters[todayKey] ?: 0,
        books = books,
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
            lastReadDateKey = own.filter { it.readingTime > 0.0 }.maxOfOrNull { it.dateKey },
            bookCount = 1,
            newestStamp = newest?.first ?: stamped.maxOfOrNull { it.first } ?: 0L,
        )
    }.sortedWith(compareByDescending<DeviceReadingSummary> { it.totalSeconds }.thenBy { it.deviceName ?: "" })
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

/**
 * Reads every book's statistics sidecars, bookmark progress and cover. Call it again whenever
 * [BookRepository.statisticsChanges] changes: the result is a snapshot of the files, never
 * cached across screens.
 */
suspend fun loadReadingStatisticsOverview(
    bookRepository: BookRepository,
    todayKey: String,
): ReadingStatisticsOverview = withContext(Dispatchers.IO) {
    val inputs = bookRepository.loadBookEntries().map { entry ->
        val contentType = bookContentType(entry.root)
        BookStatisticsInput(
            bookId = entry.metadata.id,
            title = entry.displayTitle,
            contentType = contentType,
            statistics = bookRepository.loadStatistics(entry.root),
            mangaTextStatistics = when (contentType) {
                ContentType.Epub -> emptyList()
                ContentType.Mokuro -> bookRepository.loadMangaTextStatistics(entry.root)
            },
            progress = bookRepository.loadReadingProgress(entry.root),
            coverSource = bookRepository.coverFile(entry)?.toBookCoverSource(),
        )
    }
    summarizeReadingStatistics(inputs, todayKey)
}
