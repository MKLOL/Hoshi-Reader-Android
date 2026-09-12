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

/** One book's share of the reading statistics, aggregated from its `statistics.json`. */
data class BookReadingSummary(
    val bookId: String,
    val title: String,
    val contentType: ContentType,
    /** Seconds spent reading across every recorded day. */
    val totalSeconds: Double,
    /** Characters read for an EPUB; pages read for a manga (see `MangaStatisticsSheet`). */
    val unitsRead: Int,
    /** ISO date (`yyyy-MM-dd`) of the most recent day with recorded reading time. */
    val lastReadDateKey: String?,
)

/** Everything the Settings -> Statistics page shows. */
data class ReadingStatisticsOverview(
    val totalSeconds: Double,
    val todaySeconds: Double,
    /** Books with recorded reading time, longest first. */
    val books: List<BookReadingSummary>,
)

data class BookStatisticsInput(
    val bookId: String,
    val title: String,
    val contentType: ContentType,
    val statistics: List<ReadingStatistics>,
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
    val books = inputs.mapNotNull { input ->
        val statistics = input.statistics.deduplicateReadingStatistics()
        todaySeconds += statistics.filter { it.dateKey == todayKey }.sumOf { it.readingTime }
        val totalSeconds = statistics.sumOf { it.readingTime }
        if (totalSeconds <= 0.0) return@mapNotNull null
        BookReadingSummary(
            bookId = input.bookId,
            title = input.title,
            contentType = input.contentType,
            totalSeconds = totalSeconds,
            unitsRead = statistics.sumOf { it.charactersRead },
            lastReadDateKey = statistics.filter { it.readingTime > 0.0 }.maxOfOrNull { it.dateKey },
        )
    }.sortedWith(compareByDescending<BookReadingSummary> { it.totalSeconds }.thenBy { it.title })
    return ReadingStatisticsOverview(
        totalSeconds = books.sumOf { it.totalSeconds },
        todaySeconds = todaySeconds,
        books = books,
    )
}

/** Loads every book's `statistics.json` off the main thread; `overview` is null while loading. */
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
                    BookStatisticsInput(
                        bookId = entry.metadata.id,
                        title = entry.displayTitle,
                        contentType = bookContentType(entry.root),
                        statistics = bookRepository.loadStatistics(entry.root),
                    )
                }
                summarizeReadingStatistics(inputs, clock.currentDate().toString())
            }
        }
    }
}
