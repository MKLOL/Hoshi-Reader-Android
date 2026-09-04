package moe.antimony.hoshi.features.reader.sentence

import kotlinx.serialization.Serializable
import moe.antimony.hoshi.epub.Bookmark

/** One chapter of a book as sentence mode sees it; an unreadable chapter has no sentences. */
data class SentenceChapter(
    /** Index into `EpubBook.chapters`, the same index the reader's bookmark uses. */
    val chapterIndex: Int,
    val title: String?,
    val sentences: List<ReaderSentence>,
)

data class SentenceBook(val chapters: List<SentenceChapter>) {
    val totalSentences: Int = chapters.sumOf { it.sentences.size }
    val readableChapterCount: Int = chapters.count { it.sentences.isNotEmpty() }

    fun sentenceAt(position: SentencePosition): ReaderSentence? =
        chapters.getOrNull(position.chapter)?.sentences?.getOrNull(position.sentence)

    /** 1-based index of [position] across the whole book, for the "12 / 240" counter. */
    fun ordinal(position: SentencePosition): Int {
        var before = 0
        for (index in 0 until position.chapter.coerceIn(0, chapters.size)) before += chapters[index].sentences.size
        return before + position.sentence + 1
    }

    /** 1-based number of the readable chapter containing [position], for "Chapter 3 of 12". */
    fun readableChapterOrdinal(position: SentencePosition): Int =
        chapters.take(position.chapter + 1).count { it.sentences.isNotEmpty() }
}

@Serializable
data class SentencePosition(val chapter: Int, val sentence: Int)

/** Moves one sentence at a time across chapter boundaries, skipping chapters with no text. */
object SentenceNavigation {
    fun next(book: SentenceBook, position: SentencePosition): SentencePosition? {
        val current = book.chapters.getOrNull(position.chapter)
            ?: return if (position.chapter < 0) firstPosition(book) else null
        if (position.sentence + 1 < current.sentences.size) {
            return SentencePosition(position.chapter, position.sentence + 1)
        }
        for (index in position.chapter + 1 until book.chapters.size) {
            if (book.chapters[index].sentences.isNotEmpty()) return SentencePosition(index, 0)
        }
        return null
    }

    fun previous(book: SentenceBook, position: SentencePosition): SentencePosition? {
        if (position.sentence > 0 && book.chapters.getOrNull(position.chapter) != null) {
            val last = book.chapters[position.chapter].sentences.lastIndex
            return SentencePosition(position.chapter, (position.sentence - 1).coerceAtMost(last))
        }
        for (index in (position.chapter - 1).coerceAtMost(book.chapters.lastIndex) downTo 0) {
            val sentences = book.chapters[index].sentences
            if (sentences.isNotEmpty()) return SentencePosition(index, sentences.lastIndex)
        }
        return null
    }

    fun firstPosition(book: SentenceBook): SentencePosition? {
        val index = book.chapters.indexOfFirst { it.sentences.isNotEmpty() }
        return if (index < 0) null else SentencePosition(index, 0)
    }

    /** A position that exists in [book], or null when the book has no text at all. */
    fun clamp(book: SentenceBook, position: SentencePosition): SentencePosition? {
        val chapter = book.chapters.getOrNull(position.chapter)
        if (chapter != null && chapter.sentences.isNotEmpty()) {
            return SentencePosition(position.chapter, position.sentence.coerceIn(0, chapter.sentences.lastIndex))
        }
        return next(book, SentencePosition(position.chapter, Int.MAX_VALUE - 1)) ?: previous(book, position)
    }

    /**
     * Where to start: the position saved by sentence mode, else the reader's bookmark mapped
     * onto its chapter (progress becomes a sentence index), else the first sentence.
     */
    fun initialPosition(book: SentenceBook, saved: SentencePosition?, bookmark: Bookmark?): SentencePosition? {
        saved?.let { clamp(book, it) }?.let { return it }
        if (bookmark != null) {
            val chapter = book.chapters.getOrNull(bookmark.chapterIndex)
            if (chapter != null && chapter.sentences.isNotEmpty()) {
                val index = (bookmark.progress.coerceIn(0.0, 1.0) * chapter.sentences.size).toInt()
                return SentencePosition(bookmark.chapterIndex, index.coerceIn(0, chapter.sentences.lastIndex))
            }
            clamp(book, SentencePosition(bookmark.chapterIndex, 0))?.let { return it }
        }
        return firstPosition(book)
    }
}
