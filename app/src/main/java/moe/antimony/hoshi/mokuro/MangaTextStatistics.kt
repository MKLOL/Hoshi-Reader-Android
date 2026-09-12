package moe.antimony.hoshi.mokuro

import kotlinx.serialization.Serializable

/**
 * One day's manga text read, in OCR characters, for one book. Stored in the book's
 * `manga_statistics.json` (Android-only and excluded from the sync payload). The shared
 * `statistics.json` keeps counting pages for manga so its numbers mean the same thing on
 * every platform; this sidecar adds the "how much text" dimension next to it.
 */
@Serializable
data class MangaTextStatistic(
    val dateKey: String,
    val charactersRead: Int = 0,
    val lastModified: Long = 0,
)

/** Collapses duplicate days, keeping the most recently modified entry, like `statistics.json`. */
fun List<MangaTextStatistic>.deduplicateMangaTextStatistics(): List<MangaTextStatistic> =
    fold(linkedMapOf<String, MangaTextStatistic>()) { grouped, statistic ->
        val existing = grouped[statistic.dateKey]
        if (existing == null || statistic.lastModified > existing.lastModified) {
            grouped[statistic.dateKey] = statistic
        }
        grouped
    }.values.toList()

/** Characters of OCR text on the page; whitespace between columns is not text that was read. */
fun MokuroPage.ocrCharacterCount(): Int =
    textBoxes.sumOf { box -> box.lines.sumOf { line -> line.count { !it.isWhitespace() } } }

/**
 * OCR characters on the pages a forward turn from [fromPageIndex] to [toPageIndex] leaves
 * behind, i.e. pages `[from, to)`. A backward turn or a jump to the same page reads nothing
 * new, matching how pages are counted.
 */
fun MokuroBook.ocrCharactersTurnedPast(fromPageIndex: Int, toPageIndex: Int): Int {
    val from = fromPageIndex.coerceAtLeast(0)
    val to = toPageIndex.coerceAtMost(pages.size)
    if (to <= from) return 0
    var total = 0
    for (index in from until to) {
        total += pages[index].ocrCharacterCount()
    }
    return total
}
