package moe.antimony.hoshi.features.reader.sentence

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import moe.antimony.hoshi.epub.BookEntry
import moe.antimony.hoshi.epub.BookInfo
import moe.antimony.hoshi.epub.BookMetadata
import moe.antimony.hoshi.epub.Bookmark
import moe.antimony.hoshi.epub.DeviceIdentity
import moe.antimony.hoshi.epub.EpubBook
import moe.antimony.hoshi.epub.EpubChapter
import moe.antimony.hoshi.epub.ReaderRouteBookRepository
import moe.antimony.hoshi.epub.ReadingPositionEvent
import moe.antimony.hoshi.epub.ReadingPositionSession
import moe.antimony.hoshi.epub.ReadingPositionState
import moe.antimony.hoshi.epub.ReadingStatistics
import moe.antimony.hoshi.navigation.ReaderRouteLoadState
import moe.antimony.hoshi.navigation.ReaderRouteStateHolder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicLong

class SentenceReadingProgressTest {
    @Test
    fun progressUsesReaderCharactersRatherThanSentenceCountOrTranslatorOffsets() {
        // The translator counts the compatibility ideograph 﨑; the normal reader does not.
        val epub = epub("<p>A\uFA11。</p><p>BBBB。</p>")
        val book = SentenceReaderLoader.segmentBook(epub)
        val progress = SentenceReadingProgress(book, epub)

        assertEquals(2, book.chapters.single().sentences[1].start)
        assertEquals(5, epub.bookInfo.characterCount)
        assertEquals(1, progress.at(SentencePosition(0, 1))?.characterCount)
        assertEquals(0.2, progress.at(SentencePosition(0, 1))!!.progress, 0.00000001)
        assertEquals(5, progress.endOf(SentencePosition(0, 1))?.characterCount)
    }

    @Test
    fun literalMarkupInSentenceTextDoesNotLoseCharacters() {
        val epub = epub("<p>a &lt; b &gt; c。</p><p>DD。</p>")
        val book = SentenceReaderLoader.segmentBook(epub)
        val progress = SentenceReadingProgress(book, epub)

        assertEquals("a < b > c。", book.chapters.single().sentences.first().text)
        assertEquals(3, progress.at(SentencePosition(0, 1))?.characterCount)
        assertEquals(5, progress.endOf(SentencePosition(0, 1))?.characterCount)
    }

    @Test
    fun cachedChapterCountsRemainTheNormalBookmarkProjection() {
        val epub = epub("<p>A。</p><p>BBBBBBBBB。</p>").let { original ->
            original.copy(bookInfo = BookInfo(100, mapOf("c0.xhtml" to BookInfo.ChapterInfo(0, 0, 100))))
        }
        val book = SentenceReaderLoader.segmentBook(epub)
        val progress = SentenceReadingProgress(book, epub)

        val point = progress.at(SentencePosition(0, 1))!!
        assertEquals(10, point.characterCount)
        assertEquals(point.characterCount, epub.characterCountAt(point.chapterIndex, point.progress))
        assertEquals(100, progress.endOf(SentencePosition(0, 1))?.characterCount)
    }

    @Test
    fun finishingTheLastReadableSentenceCompletesTheWholeBookWithTrailingNavigation() {
        val epub = EpubBook("Book", listOf(
            EpubChapter("nav", "nav.xhtml", "application/xhtml+xml", "<body>目次</body>", properties = "nav"),
            EpubChapter("story", "story.xhtml", "application/xhtml+xml", "<body>大丈夫。</body>"),
            EpubChapter("appendix", "appendix.xhtml", "application/xhtml+xml", "<body>付録</body>", linear = false),
        ))
        val book = SentenceReaderLoader.segmentBook(epub)
        val progress = SentenceReadingProgress(book, epub)
        val final = SentencePosition(1, 0)

        assertEquals(1, book.totalSentences)
        assertEquals(2, progress.at(final)?.characterCount)
        assertEquals(5, progress.endOf(final)?.characterCount)
        assertEquals(SentenceProgressPoint(2, 1.0, 7), progress.completionPoint)
        assertFalse(progress.isFinished(final, Bookmark(1, 0.0, 2)))
        val completed = Bookmark(2, 1.0, 7)
        assertEquals(final, progress.initialPosition(SentencePosition(1, 0), completed))
        assertTrue(progress.isFinished(final, completed))
    }

    @Test
    fun normalBookmarkWinsOverStaleSentenceCursorAndUsesCharacterWeightedResume() {
        val epub = epub("<p>A。</p><p>BBBBBBBBB。</p>")
        val progress = SentenceReadingProgress(SentenceReaderLoader.segmentBook(epub), epub)

        assertEquals(SentencePosition(0, 1), progress.initialPosition(SentencePosition(0, 0), Bookmark(0, 0.1, 1)))
        assertEquals(SentencePosition(0, 1), progress.initialPosition(null, Bookmark(0, 0.5, 5)))
        assertEquals(SentencePosition(0, 0), progress.initialPosition(SentencePosition(0, 1), Bookmark(0, 0.0, 0)))
        assertEquals(SentencePosition(0, 1), progress.initialPosition(SentencePosition(0, 1), null))
    }

    @Test
    fun navigationAndExplicitFinishUseNormalHistoryAndSupportReadingBackward() = runBlocking {
        val fixture = fixture()
        var refreshCount = 0
        val session = SentenceReaderProgressSession(fixture.state, fixture.progress, fixture.holder, this, { fixture.repository.now }, { refreshCount += 1 })
        assertTrue("Opening sentence mode does not write or finish anything", fixture.repository.events.isEmpty())

        session.navigate(SentencePosition(0, 1))?.join()
        fixture.repository.now = 2.0
        session.navigate(SentencePosition(0, 0))?.join()
        assertNull("A sentence before the end cannot finish the book", session.finish(SentencePosition(0, 0)))
        fixture.repository.now = 3.0
        session.finish(SentencePosition(0, 1))?.join()

        assertEquals(listOf(1, 0, 10), fixture.repository.bookmarks.map { it.characterCount })
        assertEquals(listOf("sentenceTurn", "sentenceTurn", "finishBook"), fixture.repository.events.map { it.action })
        assertEquals(listOf(1.0, 2.0, 3.0), fixture.repository.events.map { it.occurredAt })
        assertEquals(3, refreshCount)
        assertEquals(1.0, fixture.repository.bookmarks.last().progress, 0.0)
        assertNull("Repeated finish is idempotent", session.finish(SentencePosition(0, 1)))
    }

    @Test
    fun queuedSavesKeepNavigationOrderActionTimeAndStatisticsSeparate() = runBlocking {
        val fixture = fixture()
        val gate = CompletableDeferred<Unit>()
        fixture.repository.firstWriteGate = gate
        val flushes = mutableListOf<Boolean>()
        val holder = ReaderRouteStateHolder(
            repository = fixture.repository,
            ioDispatcher = Dispatchers.Unconfined,
            onStatisticsPersisted = { _, _, _, flush -> flushes += flush },
        )
        val session = SentenceReaderProgressSession(fixture.state, fixture.progress, holder, this, { fixture.repository.now }, {})

        session.navigate(SentencePosition(0, 1))
        fixture.repository.now = 2.0
        session.navigate(SentencePosition(0, 0))
        fixture.repository.now = 3.0
        session.finish(SentencePosition(0, 1))
        val statistics = listOf(ReadingStatistics(title = "Book", dateKey = "2026-10-24", charactersRead = 10))
        session.saveStatistics(statistics, flush = false)
        gate.complete(Unit)
        session.awaitSaves()
        session.saveStatistics(statistics, flush = true).join()

        assertEquals(listOf(1, 0, 10), fixture.repository.bookmarks.map { it.characterCount })
        assertEquals(listOf(1.0, 2.0, 3.0), fixture.repository.events.map { it.occurredAt })
        assertEquals(listOf(false, true), flushes)
        assertEquals(statistics, fixture.repository.statistics)
        assertEquals("Statistics flushing does not manufacture bookmark events", 3, fixture.repository.events.size)
    }

    @Test
    fun emptyBooksHaveNoProgressOrCompletionAction() {
        val epub = epub("<p>。</p>")
        val progress = SentenceReadingProgress(SentenceReaderLoader.segmentBook(epub), epub)
        assertNull(progress.at(SentencePosition(0, 0)))
        assertNull(progress.completionPoint)
        assertNull(progress.initialPosition(null, null))
    }

    private fun epub(html: String) = EpubBook("Book", listOf(
        EpubChapter("c0", "c0.xhtml", "application/xhtml+xml", "<body>$html</body>"),
    ))

    private data class Fixture(
        val state: ReaderRouteLoadState.Ready,
        val progress: SentenceReadingProgress,
        val holder: ReaderRouteStateHolder,
        val repository: ProgressRepository,
    )

    private fun fixture(): Fixture {
        val epub = epub("<p>A。</p><p>BBBBBBBBB。</p>")
        val progress = SentenceReadingProgress(SentenceReaderLoader.segmentBook(epub), epub)
        val root = File("book-a")
        val session = ReadingPositionSession(null, DeviceIdentity("device", "Android"), "origin", AtomicLong(), ReadingPositionState("book-a"))
        val state = ReaderRouteLoadState.Ready(
            BookEntry(root, BookMetadata("book-a", "Book", null, "book-a", 0.0)), root, epub, null, session,
        )
        val repository = ProgressRepository()
        return Fixture(state, progress, ReaderRouteStateHolder(repository, ioDispatcher = Dispatchers.Unconfined), repository)
    }

    private class ProgressRepository : ReaderRouteBookRepository {
        var now = 1.0
        var firstWriteGate: CompletableDeferred<Unit>? = null
        val bookmarks = mutableListOf<Bookmark>()
        val events = mutableListOf<ReadingPositionEvent>()
        var statistics: List<ReadingStatistics> = emptyList()
        override suspend fun loadBookEntry(bookId: String): BookEntry? = null
        override suspend fun syncedCoverPath(bookRoot: File, coverHref: String?): String? = null
        override suspend fun saveMetadata(bookRoot: File, metadata: BookMetadata) = Unit
        override suspend fun loadBookmark(bookRoot: File): Bookmark? = bookmarks.lastOrNull()
        override suspend fun saveBookmark(bookRoot: File, bookmark: Bookmark) {
            if (bookmarks.isEmpty()) firstWriteGate?.await()
            bookmarks += bookmark
        }
        override suspend fun recordReadingPosition(bookRoot: File, event: ReadingPositionEvent) {
            saveBookmark(bookRoot, event.bookmark())
            events += event
        }
        override suspend fun loadStatistics(bookRoot: File): List<ReadingStatistics> = statistics
        override suspend fun saveStatistics(bookRoot: File, statistics: List<ReadingStatistics>) {
            this.statistics = statistics
        }
        override suspend fun loadReaderBookInfo(bookRoot: File): BookInfo? = null
        override suspend fun saveBookInfo(bookRoot: File, bookInfo: BookInfo) = Unit
        override fun currentAppleReferenceDateSeconds(): Double = now
    }
}
