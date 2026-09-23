package moe.antimony.hoshi.features.usage

import java.util.UUID

/** Where a word lookup started. */
enum class UsageLookupSource(val serialName: String) {
    /** A word pressed in the book or on the manga page. */
    Page("page"),

    /** A word pressed inside a dictionary popup's definition. */
    Popup("popup"),
}

/**
 * The usage log for one opening of a reader. Every event it writes carries the same session
 * id and book, so a day's log can be split back into reader sessions; reading spans are
 * opened and closed exactly when the statistics tracker starts and stops counting time, so
 * the log's reading time and the statistics' reading time describe the same seconds.
 *
 * Page numbers passed in are 1-based, as the reader displays them.
 */
class ReaderUsageSession(
    private val log: UsageLog,
    private val bookId: String?,
    private val bookTitle: String,
    private val contentType: UsageContentType,
    val id: String = UUID.randomUUID().toString(),
) {
    private var openedAt: Long? = null
    private var readingSince: Long? = null
    private var closed = false

    val isReading: Boolean get() = readingSince != null

    fun opened(page: Int?) {
        if (openedAt != null || closed) return
        val event = event(UsageEventType.ReaderOpened).copy(page = page)
        openedAt = event.at
        log.append(event)
    }

    /** Follows the statistics tracker: counted reading time started or stopped. */
    fun readingChanged(reading: Boolean, page: Int? = null) {
        if (closed) return
        if (reading) {
            if (readingSince != null) return
            val event = event(UsageEventType.ReadingStarted).copy(page = page)
            readingSince = event.at
            log.append(event)
        } else {
            val since = readingSince ?: return
            readingSince = null
            log.append(event(UsageEventType.ReadingStopped).copy(page = page, startedAt = since))
        }
    }

    /** An EPUB page turn, landing on [chapter] (1-based) at [character] characters in. */
    fun epubPageTurned(chapter: Int, character: Int) {
        if (closed) return
        log.append(event(UsageEventType.PageTurned).copy(chapter = chapter, character = character))
    }

    fun pageTurned(fromPage: Int, toPage: Int) {
        if (closed || fromPage == toPage) return
        log.append(event(UsageEventType.PageTurned).copy(page = toPage, fromPage = fromPage, toPage = toPage))
    }

    fun wordLookedUp(
        text: String,
        source: UsageLookupSource,
        found: Boolean,
        page: Int? = null,
        term: String? = null,
    ) {
        if (closed || text.isBlank()) return
        log.append(
            event(UsageEventType.WordLookedUp).copy(
                page = page,
                text = text.trim().take(MAX_TEXT_LENGTH),
                term = term?.trim()?.ifEmpty { null },
                source = source.serialName,
                outcome = if (found) "found" else "not-found",
            ),
        )
    }

    fun bubbleRevealed(text: String?, page: Int?) = bubbleEvent(UsageEventType.BubbleRevealed, text, page)

    fun bubbleTranslated(text: String?, page: Int?) = bubbleEvent(UsageEventType.BubbleTranslated, text, page)

    fun bubbleCopied(text: String?, page: Int?) = bubbleEvent(UsageEventType.BubbleCopied, text, page)

    fun screenshotTranslated(page: Int?) {
        if (closed) return
        log.append(event(UsageEventType.ScreenshotTranslated).copy(page = page))
    }

    /** Ends the session, closing a reading span still open. Later calls do nothing. */
    fun closed(page: Int?) {
        if (closed) return
        readingChanged(reading = false, page = page)
        log.append(event(UsageEventType.ReaderClosed).copy(page = page, startedAt = openedAt))
        closed = true
    }

    private fun bubbleEvent(type: UsageEventType, text: String?, page: Int?) {
        if (closed) return
        log.append(event(type).copy(page = page, text = text?.trim()?.take(MAX_TEXT_LENGTH)?.ifEmpty { null }))
    }

    private fun event(type: UsageEventType): UsageEvent =
        log.newEvent(type).copy(
            session = id,
            bookId = bookId,
            bookTitle = bookTitle,
            contentType = contentType.serialName,
        )

    private companion object {
        /** Long enough for any bubble; stops a pathological selection from bloating the log. */
        const val MAX_TEXT_LENGTH = 500
    }
}
