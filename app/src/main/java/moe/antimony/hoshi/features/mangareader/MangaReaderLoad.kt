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
 * EPUB reader, kept deliberately small: the manga reader still has no Sasayaki wiring, and
 * statistics load directly in [MangaReaderScreen] so the route can respect current reader
 * settings.
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
                    .coerceIn(0, book.pages.lastIndex.coerceAtLeast(0)),
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
 * schema unchanged (no iOS-incompatible fields):
 *  - `chapterIndex` carries the 0-based page index — the reader's resume position.
 *  - `characterCount` carries the 1-based "pages read" count (`pageIndex + 1`), so the
 *    bookshelf's `loadReadingProgress` (`characterCount / bookInfo.characterCount`, where
 *    `bookInfo.characterCount` is the page count) reaches 100% on the last page. This also
 *    matches what "Mark Read" writes (`characterCount = totalPages`).
 *  - `progress` is always 0.0 (the EPUB per-chapter fraction is meaningless for manga).
 */
internal fun mangaBookmark(pageIndex: Int, lastModifiedSeconds: Double): Bookmark =
    Bookmark(
        chapterIndex = pageIndex,
        progress = 0.0,
        characterCount = pageIndex + 1,
        lastModified = lastModifiedSeconds,
    )
