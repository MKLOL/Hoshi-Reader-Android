package moe.antimony.hoshi.features.reader.sentence

import moe.antimony.hoshi.epub.Bookmark
import moe.antimony.hoshi.epub.EpubBook
import moe.antimony.hoshi.epub.filteredReaderText
import kotlin.math.abs

/** A sentence boundary expressed in the ordinary EPUB reader's bookmark coordinates. */
internal data class SentenceProgressPoint(
    val chapterIndex: Int,
    val progress: Double,
    val characterCount: Int,
)

/**
 * Sentence IDs use the translator's normalization, whose character ranges differ slightly from
 * the reader's. Accumulate reader-filtered displayed text instead, then project its boundaries
 * onto the existing chapter counts. A short sentence never counts as much as a long sentence.
 */
internal class SentenceReadingProgress(
    private val book: SentenceBook,
    private val epubBook: EpubBook,
) {
    private val boundaries: List<IntArray> = book.chapters.map { chapter ->
        val offsets = IntArray(chapter.sentences.size + 1)
        chapter.sentences.forEachIndexed { index, sentence ->
            // This API accepts HTML. Escape literal markup before using its exact normal-reader
            // character filter so text such as "a < b > c" cannot disappear as an HTML tag.
            val escaped = sentence.text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            val normalized = escaped.filteredReaderText()
            val count = normalized.codePointCount(0, normalized.length)
            offsets[index + 1] = (offsets[index].toLong() + count).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        }
        offsets
    }

    /** Full normal-reader end, including any trailing chapters sentence mode intentionally skips. */
    val completionPoint: SentenceProgressPoint? = if (book.totalSentences > 0 && epubBook.chapters.isNotEmpty()) {
        point(epubBook.chapters.lastIndex, 1.0)
    } else null

    fun at(position: SentencePosition): SentenceProgressPoint? = boundary(position, after = false)

    fun endOf(position: SentencePosition): SentenceProgressPoint? = boundary(position, after = true)

    fun isLast(position: SentencePosition): Boolean = book.sentenceAt(position) != null && SentenceNavigation.next(book, position) == null

    fun isFinished(position: SentencePosition?, bookmark: Bookmark?): Boolean =
        position != null && isLast(position) && bookmark != null && completionPoint?.let { end ->
            bookmark.chapterIndex == end.chapterIndex && bookmark.progress >= 1.0 && bookmark.characterCount >= end.characterCount
        } == true

    /**
     * The normal bookmark is now authoritative across reader modes and sync. A private cursor
     * supplies precision only when it still projects onto that bookmark, or no bookmark exists.
     */
    fun initialPosition(saved: SentencePosition?, bookmark: Bookmark?): SentencePosition? {
        val privatePosition = saved?.let { SentenceNavigation.clamp(book, it) }
        if (bookmark == null) return privatePosition ?: SentenceNavigation.firstPosition(book)
        val normalProgress = bookmark.progress.takeIf { it.isFinite() }?.coerceIn(0.0, 1.0) ?: 0.0
        privatePosition?.let { position ->
            at(position)?.let { point ->
                if (point.chapterIndex == bookmark.chapterIndex && abs(point.progress - normalProgress) < 0.00000001) return position
            }
        }
        val chapterIndex = book.chapters.indexOfFirst { it.chapterIndex == bookmark.chapterIndex }
        val chapter = book.chapters.getOrNull(chapterIndex)
        if (chapter != null && chapter.sentences.isNotEmpty()) {
            val index = chapter.sentences.indices.lastOrNull { sentenceIndex ->
                (at(SentencePosition(chapterIndex, sentenceIndex))?.progress ?: 0.0) <= normalProgress + 0.00000001
            } ?: 0
            return SentencePosition(chapterIndex, index)
        }
        // A final navigation/credits chapter maps back to the last readable sentence. Empty
        // chapters in the middle keep the same next-readable-chapter behavior as normal mode.
        return SentenceNavigation.clamp(book, SentencePosition(bookmark.chapterIndex, 0))
    }

    /**
     * A cursor from before sentence mode shared the bookmark is adopted once when it is further
     * along than the bookmark, since that is where the reader last was in sentence mode.
     */
    fun separateCursorAhead(saved: SentencePosition?, bookmark: Bookmark?): SentencePosition? {
        val position = saved?.let { SentenceNavigation.clamp(book, it) } ?: return null
        val characterCount = at(position)?.characterCount ?: return null
        return position.takeIf { bookmark == null || characterCount > bookmark.characterCount }
    }

    private fun boundary(position: SentencePosition, after: Boolean): SentenceProgressPoint? {
        val chapter = book.chapters.getOrNull(position.chapter) ?: return null
        if (chapter.sentences.getOrNull(position.sentence) == null) return null
        val offsets = boundaries[position.chapter]
        val count = offsets[position.sentence + if (after) 1 else 0]
        val total = offsets.last()
        val chapterCount = epubBook.chapters.getOrNull(chapter.chapterIndex)?.let { epubBook.bookInfo.chapterInfo[it.href]?.chapterCount } ?: return null
        val progress = if (chapterCount > 0 && total > 0) {
            val normalOffset = (count.toLong() * chapterCount / total).toInt()
            when (normalOffset) {
                0 -> 0.0
                chapterCount -> 1.0
                // characterCountAt floors the product; one ulp keeps exact integer boundaries
                // from falling a character behind due to floating-point division.
                else -> Math.nextUp(normalOffset.toDouble() / chapterCount)
            }
        } else {
            val boundary = position.sentence + if (after) 1 else 0
            boundary.toDouble() / chapter.sentences.size
        }
        return point(chapter.chapterIndex, progress)
    }

    private fun point(chapterIndex: Int, progress: Double) = SentenceProgressPoint(
        chapterIndex = chapterIndex,
        progress = progress,
        characterCount = epubBook.characterCountAt(chapterIndex, progress),
    )
}
