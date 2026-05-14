package moe.antimony.hoshi.features.mangareader

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.antimony.hoshi.epub.BookRepository
import moe.antimony.hoshi.epub.Bookmark
import moe.antimony.hoshi.mokuro.MokuroBook
import moe.antimony.hoshi.mokuro.MokuroBookParser
import java.io.File

/**
 * Loads a mokuro manga book for the reader route: resolves the book directory, parses
 * `mokuro.json` and reads the saved bookmark — all off the main thread.
 *
 * The manga counterpart of [moe.antimony.hoshi.navigation.ReaderRouteStateHolder] for the
 * EPUB reader, kept deliberately small: the manga reader has no statistics / Sasayaki /
 * auto-sync wiring in v1.
 */
internal class MangaReaderLoader(
    private val repository: BookRepository,
    private val parser: MokuroBookParser = MokuroBookParser(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    suspend fun load(bookId: String): MangaReaderLoadState = withContext(ioDispatcher) {
        runCatching {
            val entry = repository.loadBookEntry(bookId) ?: error("Book not found.")
            val book = parser.parse(entry.root)
            val bookmark = repository.loadBookmark(entry.root)
            MangaReaderLoadState.Ready(
                bookRoot = entry.root,
                book = book,
                initialPageIndex = (bookmark?.chapterIndex ?: 0)
                    .coerceIn(0, book.pages.lastIndex),
            )
        }.getOrElse { error ->
            MangaReaderLoadState.Error(error.localizedMessage ?: "Failed to open manga.")
        }
    }
}

internal sealed interface MangaReaderLoadState {
    data object Loading : MangaReaderLoadState

    data class Ready(
        val bookRoot: File,
        val book: MokuroBook,
        val initialPageIndex: Int,
    ) : MangaReaderLoadState

    data class Error(
        val message: String,
    ) : MangaReaderLoadState
}

/**
 * Builds the page-index [Bookmark] for a manga book. Manga reuses the EPUB [Bookmark]
 * schema unchanged (no iOS-incompatible fields): `chapterIndex` and `characterCount` both
 * carry the 0-based page index, `progress` is always 0.0.
 */
internal fun mangaBookmark(pageIndex: Int, lastModifiedSeconds: Double): Bookmark =
    Bookmark(
        chapterIndex = pageIndex,
        progress = 0.0,
        characterCount = pageIndex,
        lastModified = lastModifiedSeconds,
    )
