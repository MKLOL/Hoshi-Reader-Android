package moe.antimony.hoshi.epub

/** Add disjoint page samples (different days/devices, or one newly completed page). */
internal fun Iterable<Map<Int, Int>>.sumMangaPageSeconds(): Map<Int, Int> {
    val result = sortedMapOf<Int, Int>()
    forEach { histogram ->
        histogram.forEach { (seconds, count) ->
            if (seconds > 0 && count > 0) {
                result[seconds] = ((result[seconds] ?: 0).toLong() + count).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            }
        }
    }
    return result
}

/** Weighted median of completed page durations, including both middle pages for even counts. */
fun ReadingStatistics.medianMangaPageSeconds(): Double? {
    val samples = mangaPageSeconds.filter { (seconds, count) -> seconds > 0 && count > 0 }.toSortedMap()
    val count = samples.values.sumOf { it.toLong() }
    if (count == 0L) return null
    val lower = (count - 1) / 2
    val upper = count / 2
    var seen = 0L
    var lowerSeconds = 0
    for ((seconds, occurrences) in samples) {
        val next = seen + occurrences
        if (seen <= lower && next > lower) lowerSeconds = seconds
        if (next > upper) return (lowerSeconds.toDouble() + seconds) / 2.0
        seen = next
    }
    return null
}

/** Drop the fastest and slowest tenth of the timed pages when they cover the book's reading. */
internal fun ReadingStatistics.trimmedMangaPageSeconds(): Double? {
    val samples = mangaPageSeconds.filter { (seconds, count) -> seconds > 0 && count > 0 }
    val timedPages = samples.values.sumOf { it.toLong() }
    val loggedPages = charactersRead.toLong()
    if (timedPages < 10 || timedPages > loggedPages || timedPages < loggedPages - loggedPages / 10) return null

    // Older rows and unfinished/revisited pages can have active time without timed turns.
    // The upper bound allows duration rounding and rejects inconsistent or duplicate samples.
    val timedSeconds = samples.entries.sumOf { (seconds, count) -> seconds.toDouble() * count }
    if (!readingTime.isFinite() || readingTime <= 0 || timedSeconds / readingTime !in 0.9..1.1) {
        return null
    }

    val trim = timedPages / 10
    val end = timedPages - trim
    var seen = 0L
    var keptSeconds = 0.0
    for ((seconds, count) in samples.toSortedMap()) {
        val next = seen + count
        val kept = (minOf(next, end) - maxOf(seen, trim)).coerceAtLeast(0)
        keptSeconds += seconds.toDouble() * kept
        if (next >= end) break
        seen = next
    }
    return keptSeconds / (timedPages - 2 * trim)
}
