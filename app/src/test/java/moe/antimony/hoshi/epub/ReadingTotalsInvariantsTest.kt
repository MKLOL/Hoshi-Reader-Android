package moe.antimony.hoshi.epub

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.random.Random

/**
 * [readingTotals] is the one function both the reader sheet ("All Time") and the Statistics
 * page derive a book's totals from. These tests pin down what it promises for the lists that
 * can actually reach it: files with duplicate days, days with zero or negative time, and
 * any ordering of entries.
 */
class ReadingTotalsInvariantsTest {
    private fun day(dateKey: String, seconds: Double, characters: Int, modified: Long) =
        ReadingStatistics(
            title = "Book",
            dateKey = dateKey,
            charactersRead = characters,
            readingTime = seconds,
            lastStatisticModified = modified,
        )

    @Test
    fun totalsAreTheSumOfTheNewestEntryPerDayInAnyOrder() {
        val days = listOf(
            day("2026-09-01", 600.0, 1_200, modified = 10),
            day("2026-09-02", 1_845.5, 3_010, modified = 20),
            day("2026-09-02", 12.0, 5, modified = 19), // older duplicate: ignored
            day("2026-09-02", 1_900.0, 3_100, modified = 21), // newest duplicate: the one that counts
            day("2026-09-03", 0.0, 0, modified = 30),
            day("2026-09-04", 30.0, 40, modified = 40),
        )
        val expectedSeconds = 600.0 + 1_900.0 + 0.0 + 30.0
        val expectedCharacters = 1_200 + 3_100 + 0 + 40

        val random = Random(7)
        val orderings = listOf(days, days.reversed()) + List(40) { days.shuffled(random) }
        orderings.forEach { ordering ->
            val totals = ordering.readingTotals()
            assertEquals(ordering.toString(), expectedSeconds, totals.readingTime, 1e-9)
            assertEquals(ordering.toString(), expectedCharacters, totals.charactersRead)
            // Deduplicating first changes nothing: the totals already deduplicate.
            assertEquals(totals, ordering.deduplicateReadingStatistics().readingTotals())
            assertEquals(
                ordering.deduplicateReadingStatistics().sumOf { it.readingTime },
                totals.readingTime,
                1e-9,
            )
        }
    }

    @Test
    fun zeroAndNegativeTimesAreSummedAsRecorded() {
        val totals = listOf(
            day("2026-09-01", 0.0, 12, modified = 1),
            day("2026-09-02", -45.0, 3, modified = 1),
            day("2026-09-03", 60.0, 0, modified = 1),
        ).readingTotals()

        assertEquals(15.0, totals.readingTime, 0.0)
        assertEquals(15, totals.charactersRead)
        assertEquals(3_600, totals.readingSpeed)
    }

    @Test
    fun speedIsZeroWheneverThereIsNoPositiveTime() {
        assertEquals(0, emptyList<ReadingStatistics>().readingTotals().readingSpeed)
        assertEquals(0, listOf(day("2026-09-01", 0.0, 500, modified = 1)).readingTotals().readingSpeed)
        assertEquals(0, listOf(day("2026-09-01", -5.0, 500, modified = 1)).readingTotals().readingSpeed)
        assertEquals(ReadingTotals(readingTime = 0.0, charactersRead = 0), emptyList<ReadingStatistics>().readingTotals())
    }

    @Test
    fun duplicateDaysWithEqualModificationTimesKeepTheFirstListedEntry() {
        // Documented limitation: a tie on lastStatisticModified is resolved by position, so
        // the totals of such a list depend on its order. Both screens read the same file in
        // the same order (and the repository deduplicates on save and load), so they still
        // agree with each other; only a list that never went through the file can differ.
        val first = day("2026-09-02", 100.0, 10, modified = 5)
        val second = day("2026-09-02", 250.0, 25, modified = 5)

        assertEquals(100.0, listOf(first, second).readingTotals().readingTime, 0.0)
        assertEquals(250.0, listOf(second, first).readingTotals().readingTime, 0.0)
    }
}
