package moe.antimony.hoshi.features.usage

import java.time.LocalDate
import java.time.ZoneId

/** One stretch of counted reading, clipped to the day it is shown on. */
data class UsageReadingSpan(
    val startMillis: Long,
    val endMillis: Long,
    val bookId: String?,
    val bookTitle: String?,
    val contentType: String?,
) {
    val durationMillis: Long get() = (endMillis - startMillis).coerceAtLeast(0L)
}

/** A word in dictionary form and how many times it was looked up. */
data class UsageWordCount(val word: String, val count: Int)

/** What the usage log says about one local calendar day. */
data class UsageDaySummary(
    val date: LocalDate,
    /** Reading spans, earliest first. */
    val spans: List<UsageReadingSpan>,
    /** Reader openings that started this day. */
    val sessions: Int,
    val pageTurns: Int,
    /** Every word press, including ones the dictionaries found nothing for. */
    val wordLookups: Int,
    /** Word presses on a manga page (not inside a popup): the ones a revealed bubble leads to. */
    val mangaPageLookups: Int,
    /** Different words found, counted by dictionary form. */
    val distinctWords: Int,
    /**
     * Found words looked up at least twice, in dictionary form: the ones that did not stick.
     * Most looked-up first, ties broken by the latest lookup.
     */
    val repeatedWords: List<UsageWordCount>,
    val bubblesRevealed: Int,
    val bubbleTranslations: Int,
    val bubblesCopied: Int,
    val screenshotTranslations: Int,
    val firstActivityMillis: Long?,
    val lastActivityMillis: Long?,
) {
    val readingMillis: Long get() = spans.sumOf { it.durationMillis }

    val isEmpty: Boolean get() = firstActivityMillis == null
}

/**
 * Rebuilds [date]'s timeline from its events.
 *
 * A reading span is a `reading-stopped` event's [UsageEvent.startedAt]..[UsageEvent.at], cut to
 * the day, so a span that began before midnight still counts from midnight. A span that never
 * saw its stop (the app was killed mid-read) ends at the last thing its session logged: that
 * is the last moment the reader is known to have been in use.
 */
fun summarizeUsageDay(
    events: List<UsageEvent>,
    date: LocalDate,
    zone: ZoneId,
    maxRepeatedWords: Int = 5,
): UsageDaySummary {
    val dayStart = date.atStartOfDay(zone).toInstant().toEpochMilli()
    val dayEnd = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
    val ordered = events.filter { it.at in dayStart until dayEnd }.sortedBy { it.at }

    fun span(start: Long, end: Long, event: UsageEvent): UsageReadingSpan? {
        val clippedStart = start.coerceAtLeast(dayStart)
        val clippedEnd = end.coerceAtMost(dayEnd)
        if (clippedEnd <= clippedStart) return null
        return UsageReadingSpan(clippedStart, clippedEnd, event.bookId, event.bookTitle, event.contentType)
    }

    val spans = mutableListOf<UsageReadingSpan>()
    val openStarts = mutableMapOf<String?, UsageEvent>()
    val lastEventOfSession = mutableMapOf<String?, Long>()
    for (event in ordered) {
        lastEventOfSession[event.session] = event.at
        when (event.type) {
            UsageEventType.ReadingStarted -> openStarts[event.session] = event
            UsageEventType.ReadingStopped -> {
                openStarts.remove(event.session)
                span(event.startedAt ?: event.at, event.at, event)?.let(spans::add)
            }
            else -> Unit
        }
    }
    for ((session, start) in openStarts) {
        span(start.at, lastEventOfSession[session] ?: start.at, start)?.let(spans::add)
    }

    val lookups = ordered.filter { it.type == UsageEventType.WordLookedUp }
    // A press that matched nothing logged the text it scanned, which is not a word.
    val words = lookups.filter { it.outcome != "not-found" }
        .mapNotNull { (it.term ?: it.text)?.takeIf(String::isNotBlank) }
    return UsageDaySummary(
        date = date,
        spans = spans.sortedBy { it.startMillis },
        sessions = ordered.count { it.type == UsageEventType.ReaderOpened },
        pageTurns = ordered.count { it.type == UsageEventType.PageTurned && it.source != ReaderUsageSession.PAGE_JUMP },
        wordLookups = lookups.size,
        mangaPageLookups = lookups.count {
            it.contentType == UsageContentType.Manga.serialName && it.source == UsageLookupSource.Page.serialName
        },
        distinctWords = words.toSet().size,
        repeatedWords = repeatedWords(words, maxRepeatedWords),
        bubblesRevealed = ordered.count { it.type == UsageEventType.BubbleRevealed },
        bubbleTranslations = ordered.count { it.type == UsageEventType.BubbleTranslated },
        bubblesCopied = ordered.count { it.type == UsageEventType.BubbleCopied },
        screenshotTranslations = ordered.count { it.type == UsageEventType.ScreenshotTranslated },
        firstActivityMillis = listOfNotNull(ordered.firstOrNull()?.at, spans.minOfOrNull { it.startMillis }).minOrNull(),
        lastActivityMillis = ordered.lastOrNull()?.at,
    )
}

/** Words seen at least twice, most frequent first, then the most recently looked up. */
private fun repeatedWords(wordsInOrder: List<String>, limit: Int): List<UsageWordCount> {
    val counts = wordsInOrder.groupingBy { it }.eachCount()
    val lastIndex = wordsInOrder.withIndex().associate { (index, word) -> word to index }
    return counts.filterValues { it >= 2 }
        .keys
        .sortedWith(compareByDescending<String> { counts.getValue(it) }.thenByDescending { lastIndex.getValue(it) })
        .take(limit)
        .map { UsageWordCount(it, counts.getValue(it)) }
}
