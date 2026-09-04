package moe.antimony.hoshi.features.reader.sentence

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.antimony.hoshi.epub.EpubBook
import moe.antimony.hoshi.epub.EpubTocItem
import moe.antimony.hoshi.features.ai.EpubSentenceTranslation
import moe.antimony.hoshi.features.ai.EpubTranslationStore
import moe.antimony.hoshi.features.sync.http.syncIdForMetadata
import moe.antimony.hoshi.navigation.ReaderRouteLoadState
import moe.antimony.hoshi.navigation.ReaderRouteStateHolder
import java.io.File

/** Resolves a sentence's stored pre-translation, when the book has a translation blob. */
class SentenceTranslationSource(
    private val bookRoot: File,
    private val syncId: String,
    private val spineCount: Int,
) {
    fun translationFor(sentence: ReaderSentence): EpubSentenceTranslation? {
        val hash = EpubTranslationStore.textHash(EpubTranslationStore.normalize(sentence.text))
        return EpubTranslationStore.lookup("${sentence.id}#$hash", bookRoot, syncId, spineCount)
    }
}

sealed interface SentenceReaderLoadState {
    data object Loading : SentenceReaderLoadState

    data class Ready(
        val title: String,
        val book: SentenceBook,
        val initialPosition: SentencePosition?,
        val translations: SentenceTranslationSource?,
    ) : SentenceReaderLoadState

    data class Error(val message: String) : SentenceReaderLoadState
}

/** Opens a book the way the reader does, then segments every readable chapter. */
internal class SentenceReaderLoader(
    private val stateHolder: ReaderRouteStateHolder,
    private val positionStore: SentenceReaderPositionStore,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    suspend fun load(bookId: String): SentenceReaderLoadState = withContext(ioDispatcher) {
        when (val state = stateHolder.load(bookId)) {
            is ReaderRouteLoadState.Error -> SentenceReaderLoadState.Error(state.message)
            ReaderRouteLoadState.Loading -> SentenceReaderLoadState.Loading
            is ReaderRouteLoadState.Ready -> {
                val book = segmentBook(state.book)
                val syncId = syncIdForMetadata(state.entry.metadata)
                val translations = syncId
                    ?.takeIf { EpubTranslationStore.preload(state.bookRoot, it, state.book.spineCount) }
                    ?.let { SentenceTranslationSource(state.bookRoot, it, state.book.spineCount) }
                SentenceReaderLoadState.Ready(
                    title = state.book.title,
                    book = book,
                    initialPosition = SentenceNavigation.initialPosition(book, positionStore.load(bookId), state.bookmark),
                    translations = translations,
                )
            }
        }
    }

    companion object {
        /** Every chapter keeps its index so bookmarks map onto it; unreadable ones are just empty. */
        fun segmentBook(book: EpubBook): SentenceBook {
            val titles = tocTitles(book.toc)
            return SentenceBook(
                book.chapters.mapIndexed { index, chapter ->
                    val readable = chapter.linear && !chapter.isNavigation() && !chapter.isGuideToc
                    val html = if (!readable) "" else chapter.html.ifEmpty {
                        book.readResource(chapter.href)?.toString(Charsets.UTF_8).orEmpty()
                    }
                    SentenceChapter(
                        chapterIndex = index,
                        title = titles[chapter.href.substringBefore('#')],
                        sentences = if (html.isEmpty()) emptyList() else EpubSentenceSegmenter.segment(chapter.spineIndex ?: index, html),
                    )
                },
            )
        }

        private fun moe.antimony.hoshi.epub.EpubChapter.isNavigation(): Boolean =
            properties.orEmpty().split(' ').any { it == "nav" }

        private fun tocTitles(items: List<EpubTocItem>): Map<String, String> {
            val out = linkedMapOf<String, String>()
            fun walk(item: EpubTocItem) {
                item.href?.substringBefore('#')?.let { href -> out.putIfAbsent(href, item.label) }
                item.children.forEach(::walk)
            }
            items.forEach(::walk)
            return out
        }
    }
}
