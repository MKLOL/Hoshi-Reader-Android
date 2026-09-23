package moe.antimony.hoshi.features.usage

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** What happened. Serialized as a stable kebab-case name so the log reads well outside the app. */
@Serializable
enum class UsageEventType {
    /** A book was opened in a reader; starts a reader session. */
    @SerialName("reader-opened")
    ReaderOpened,

    /** The reader was closed; [UsageEvent.startedAt] is when the session opened. */
    @SerialName("reader-closed")
    ReaderClosed,

    /** Counted reading time started (opening, returning to the app, or resuming tracking). */
    @SerialName("reading-started")
    ReadingStarted,

    /** Counted reading time stopped; [UsageEvent.startedAt] is when this interval began. */
    @SerialName("reading-stopped")
    ReadingStopped,

    /**
     * The page changed: [UsageEvent.fromPage] to [UsageEvent.toPage] for manga; for an EPUB,
     * [UsageEvent.chapter] and [UsageEvent.character] give where the turn landed.
     */
    @SerialName("page-turned")
    PageTurned,

    /** A word was pressed and looked up in the dictionaries. */
    @SerialName("word-looked-up")
    WordLookedUp,

    /** A hidden manga OCR bubble was tapped to show its text. */
    @SerialName("bubble-revealed")
    BubbleRevealed,

    /** A manga bubble's text was sent for translation. */
    @SerialName("bubble-translated")
    BubbleTranslated,

    /** A manga bubble's text was copied to the clipboard. */
    @SerialName("bubble-copied")
    BubbleCopied,

    /** A cropped screenshot of the page was sent for translation. */
    @SerialName("screenshot-translated")
    ScreenshotTranslated,
}

/** The kind of book a reader session is reading, as written into the log. */
enum class UsageContentType(val serialName: String) {
    Epub("epub"),
    Manga("manga"),
}

/**
 * One line of the usage log. Only the fields that mean something for [type] are written, so a
 * line stays short and a reader of the file sees exactly what was recorded.
 *
 * Times are epoch milliseconds; [utcOffset] keeps the local wall-clock time recoverable even if
 * the device later changes time zone. Page numbers are 1-based, as the reader shows them.
 */
@Serializable
data class UsageEvent(
    /** When it happened, in epoch milliseconds. */
    val at: Long,
    /** The device's UTC offset at that moment, such as `+09:00`. */
    val utcOffset: String,
    val type: UsageEventType,
    /** Ties together every event from one opening of a reader. */
    val session: String? = null,
    val bookId: String? = null,
    val bookTitle: String? = null,
    /** `epub` or `manga`. */
    val contentType: String? = null,
    /** The page the reader was on (manga), 1-based. */
    val page: Int? = null,
    val fromPage: Int? = null,
    val toPage: Int? = null,
    /** EPUB: the 1-based spine chapter the reader is on. */
    val chapter: Int? = null,
    /** EPUB: characters from the start of the book to the reading position. */
    val character: Int? = null,
    /** For [UsageEventType.ReadingStopped] and [UsageEventType.ReaderClosed]: when the span began. */
    val startedAt: Long? = null,
    /** The looked-up text as it appears on the page, or the bubble's text. */
    val text: String? = null,
    /** For a found lookup: the dictionary form of [text], such as 食べる for 食べ. */
    val term: String? = null,
    /** Where a lookup started: `page` (the book itself) or `popup` (inside a definition). */
    val source: String? = null,
    /** For lookups: `found` or `not-found`. */
    val outcome: String? = null,
) {
    /** The span this event closes, for the events that close one. */
    val span: LongRange?
        get() = startedAt?.takeIf { it <= at }?.let { it..at }
}
