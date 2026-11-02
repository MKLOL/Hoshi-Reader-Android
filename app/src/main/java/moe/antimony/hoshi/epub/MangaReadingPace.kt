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
