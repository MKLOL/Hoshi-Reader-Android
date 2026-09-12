package moe.antimony.hoshi.features.reader

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.antimony.hoshi.epub.BookRepository
import moe.antimony.hoshi.epub.ContentType
import moe.antimony.hoshi.epub.ReadingStatistics
import moe.antimony.hoshi.epub.bookContentType
import moe.antimony.hoshi.epub.deduplicateReadingStatistics
import moe.antimony.hoshi.mokuro.MangaTextStatistic
import moe.antimony.hoshi.mokuro.deduplicateMangaTextStatistics

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
    /** ISO date (`yyyy-MM-dd`) of the most recent day with recorded reading time. */
    val lastReadDateKey: String?,
)

/** Everything the Settings -> Statistics page shows. */
data class ReadingStatisticsOverview(
    val totalSeconds: Double,
    val todaySeconds: Double,
    val totalCharacters: Int,
    val todayCharacters: Int,
    /** Books with recorded reading time, longest first. */
    val books: List<BookReadingSummary>,
)

data class BookStatisticsInput(
    val bookId: String,
    val title: String,
    val contentType: ContentType,
    /** The shared `statistics.json`: characters for an EPUB, pages for a manga. */
    val statistics: List<ReadingStatistics>,
    /** The manga-only `manga_statistics.json` with OCR characters per day. */
    val mangaTextStatistics: List<MangaTextStatistic> = emptyList(),
)

/**
 * Folds every book's per-day statistics into per-book totals. A book without any reading
 * time is left out; [todayKey] is the ISO date whose records count as "today".
 */
fun summarizeReadingStatistics(
    inputs: List<BookStatisticsInput>,
    todayKey: String,
): ReadingStatisticsOverview {
    var todaySeconds = 0.0
    var todayCharacters = 0
    val books = inputs.mapNotNull { input ->
        val statistics = input.statistics.deduplicateReadingStatistics()
        val mangaText = input.mangaTextStatistics.deduplicateMangaTextStatistics()
        todaySeconds += statistics.filter { it.dateKey == todayKey }.sumOf { it.readingTime }
        todayCharacters += when (input.contentType) {
            ContentType.Epub -> statistics.filter { it.dateKey == todayKey }.sumOf { it.charactersRead }
            ContentType.Mokuro -> mangaText.filter { it.dateKey == todayKey }.sumOf { it.charactersRead }
        }
        val totalSeconds = statistics.sumOf { it.readingTime }
        if (totalSeconds <= 0.0) return@mapNotNull null
        BookReadingSummary(
            bookId = input.bookId,
            title = input.title,
            contentType = input.contentType,
            totalSeconds = totalSeconds,
            charactersRead = when (input.contentType) {
                ContentType.Epub -> statistics.sumOf { it.charactersRead }
                ContentType.Mokuro -> mangaText.sumOf { it.charactersRead }
            },
            pagesRead = when (input.contentType) {
                ContentType.Epub -> null
                ContentType.Mokuro -> statistics.sumOf { it.charactersRead }
            },
            lastReadDateKey = statistics.filter { it.readingTime > 0.0 }.maxOfOrNull { it.dateKey },
        )
    }.sortedWith(compareByDescending<BookReadingSummary> { it.totalSeconds }.thenBy { it.title })
    return ReadingStatisticsOverview(
        totalSeconds = books.sumOf { it.totalSeconds },
        todaySeconds = todaySeconds,
        totalCharacters = books.sumOf { it.charactersRead },
        todayCharacters = todayCharacters,
        books = books,
    )
}

/** Loads every book's statistics sidecars off the main thread; `overview` is null while loading. */
class ReadingStatisticsOverviewViewModel(
    private val bookRepository: BookRepository,
    private val clock: ReaderStatisticsClock = SystemReaderStatisticsClock,
) : ViewModel() {
    private val overviewState = MutableStateFlow<ReadingStatisticsOverview?>(null)
    val overview: StateFlow<ReadingStatisticsOverview?> = overviewState

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            overviewState.value = withContext(Dispatchers.IO) {
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
                    )
                }
                summarizeReadingStatistics(inputs, clock.currentDate().toString())
            }
        }
    }
}
