package moe.antimony.hoshi.features.sync.http

import moe.antimony.hoshi.epub.BookRepository
import moe.antimony.hoshi.epub.ContentType
import moe.antimony.hoshi.epub.EpubBookParser
import moe.antimony.hoshi.epub.bookContentType
import moe.antimony.hoshi.mokuro.MokuroBookParser
import java.io.File

/**
 * Resolves the [moe.antimony.hoshi.epub.BookMetadata.cover] path for a book directory
 * that has just been materialized by sync (v2 [HttpSyncReconciler.importRemoteOnlyBook]
 * or v3 [moe.antimony.hoshi.features.sync.v3.V3Executor.importRemoteBook]).
 *
 * Mirrors the cover-extraction step that the user-side import flow runs via
 * `AndroidBookshelfRepository.saveMetadata` / `saveMokuroMetadata`: parse the on-disk
 * book, hand the resulting `coverHref` / `coverImagePath` to
 * [BookRepository.metadataCoverPath], and return whatever that returns.
 *
 * Returns `null` if:
 *  - the book directory isn't a recognized format,
 *  - the parser fails (corrupt sidecar / unsupported variant), or
 *  - the parser produces no cover reference.
 *
 * This is the fix for "book cover doesn't render in the bookshelf after HTTPS sync until
 * the book is opened once." Before this helper existed, both sync engines wrote
 * `BookMetadata(cover = null)` post-unzip and relied on the user opening the book to
 * trigger the parse-and-rewrite path in [BookshelfRepository.openBook]. The bookshelf
 * cover loader reads `metadata.cover` directly, so until that first open the slot was
 * empty.
 */
internal suspend fun resolveSyncImportedCoverPath(
    bookRepository: BookRepository,
    bookRoot: File,
    epubParser: EpubBookParser = EpubBookParser(),
    mokuroParser: MokuroBookParser = MokuroBookParser(),
): String? {
    val coverHref = runCatching {
        when (bookContentType(bookRoot)) {
            ContentType.Mokuro -> mokuroParser.parse(bookRoot).coverImagePath
            ContentType.Epub -> epubParser.parse(bookRoot).coverHref
        }
    }.getOrNull() ?: return null
    return bookRepository.metadataCoverPath(bookRoot, coverHref)
}
