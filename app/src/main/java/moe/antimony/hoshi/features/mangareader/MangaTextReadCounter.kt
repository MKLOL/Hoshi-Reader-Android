package moe.antimony.hoshi.features.mangareader

import moe.antimony.hoshi.features.reader.ReaderStatisticsClock
import moe.antimony.hoshi.features.reader.SystemReaderStatisticsClock
import moe.antimony.hoshi.mokuro.MangaTextStatistic
import moe.antimony.hoshi.mokuro.deduplicateMangaTextStatistics

data class MangaTextReadState(
    val sessionCharacters: Int,
    val todayCharacters: Int,
    val allTimeCharacters: Int,
)

/**
 * Counts OCR characters read, per day, next to the page-based
 * [moe.antimony.hoshi.features.reader.ReaderStatisticsTracker]. Only forward page turns add
 * characters (see `MokuroBook.ocrCharactersTurnedPast`); turning back adds nothing, and turning
 * forward again over the same pages counts them again, exactly like the page counter.
 */
class MangaTextReadCounter(
    initialStatistics: List<MangaTextStatistic>,
    private val clock: ReaderStatisticsClock = SystemReaderStatisticsClock,
) {
    private var statistics = initialStatistics.deduplicateMangaTextStatistics()
    private var sessionCharacters = 0
    private var hasChanges = false

    val state: MangaTextReadState
        get() {
            val today = clock.currentDate().toString()
            return MangaTextReadState(
                sessionCharacters = sessionCharacters,
                todayCharacters = statistics.firstOrNull { it.dateKey == today }?.charactersRead ?: 0,
                allTimeCharacters = statistics.sumOf { it.charactersRead },
            )
        }

    fun add(characters: Int) {
        if (characters <= 0) return
        val today = clock.currentDate().toString()
        val existing = statistics.firstOrNull { it.dateKey == today }
        val updated = MangaTextStatistic(
            dateKey = today,
            charactersRead = (existing?.charactersRead ?: 0) + characters,
            lastModified = clock.currentTimeMillis(),
        )
        statistics = statistics.filterNot { it.dateKey == today } + updated
        sessionCharacters += characters
        hasChanges = true
    }

    /** Every day's count once something was read in this session; null while nothing changed. */
    fun statisticsForPersistenceOrNull(): List<MangaTextStatistic>? =
        if (hasChanges) statistics else null
}
