package moe.antimony.hoshi.epub

import kotlinx.serialization.Serializable

@Serializable
data class ReadingStatistics(
    val title: String,
    val dateKey: String,
    val charactersRead: Int = 0,
    val readingTime: Double = 0.0,
    val minReadingSpeed: Int = 0,
    val altMinReadingSpeed: Int = 0,
    val lastReadingSpeed: Int = 0,
    val maxReadingSpeed: Int = 0,
    val lastStatisticModified: Long = 0,
)

/**
 * All-time totals of a book's per-day statistics. Every screen that shows a book's reading
 * time or amount read derives it from this one function, so the reader's Statistics sheet
 * and the Statistics screens can never disagree about the same `statistics.json`.
 */
data class ReadingTotals(
    val readingTime: Double,
    val charactersRead: Int,
) {
    /** Characters (or manga pages) per hour over the whole recorded time; 0 when nothing was read. */
    val readingSpeed: Int
        get() = if (readingTime > 0.0) (charactersRead.toDouble() / readingTime * 3600.0).toInt() else 0
}

fun List<ReadingStatistics>.readingTotals(): ReadingTotals {
    val deduplicated = deduplicateReadingStatistics()
    return ReadingTotals(
        readingTime = deduplicated.sumOf { it.readingTime },
        charactersRead = deduplicated.sumOf { it.charactersRead },
    )
}

fun List<ReadingStatistics>.deduplicateReadingStatistics(): List<ReadingStatistics> =
    fold(linkedMapOf<String, ReadingStatistics>()) { grouped, statistic ->
        val existing = grouped[statistic.dateKey]
        if (existing == null || statistic.lastStatisticModified > existing.lastStatisticModified) {
            grouped[statistic.dateKey] = statistic
        }
        grouped
    }.values.toList()
