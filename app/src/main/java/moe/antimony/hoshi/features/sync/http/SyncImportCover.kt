package moe.antimony.hoshi.features.sync.http

import moe.antimony.hoshi.epub.BookRepository
import moe.antimony.hoshi.epub.ContentType
import moe.antimony.hoshi.epub.GENERATED_COVER_FILENAME
import moe.antimony.hoshi.epub.EpubBook
import moe.antimony.hoshi.epub.EpubBookParser
import moe.antimony.hoshi.epub.bookContentType
import moe.antimony.hoshi.mokuro.MokuroBookParser
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

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
    return bookRepository.syncedCoverPath(bookRoot, coverHref)
}

/**
 * Repairs a book whose pre-fix build materialized a receiver-side cover into the root (poisoning
 * its content hash). Returns true when the payload actually matches [expectedSha] once that one
 * file is set aside — the cover is renamed to the hash-excluded name, metadata is repointed, and
 * the caller must skip the payload replacement it was about to run.
 */
internal suspend fun migrateLegacyGeneratedCoverAndRepoint(
    payloadCodec: HttpSyncPayloadCodec,
    bookRepository: BookRepository,
    bookRoot: File,
    expectedSha: String,
): Boolean {
    val metadata = bookRepository.loadMetadata(bookRoot)
    val candidates = buildList {
        add("cover.jpg")
        val name = metadata?.cover?.substringAfterLast('/')
        if (!name.isNullOrEmpty() && name !in this) add(name)
    }
    if (!payloadCodec.migrateLegacyGeneratedCover(bookRoot, expectedSha, candidates)) return false
    metadata?.let {
        bookRepository.saveMetadata(
            bookRoot,
            it.copy(cover = "Books/${bookRoot.name}/$GENERATED_COVER_FILENAME"),
        )
    }
    return true
}

/**
 * Requires a freshly downloaded EPUB to parse before sync registers it on the shelf and
 * returns its processed model so import can persist the same `bookinfo.json` as iOS.
 */
internal fun validateSyncImportedBook(
    bookRoot: File,
    epubParser: EpubBookParser = EpubBookParser(),
): EpubBook? = if (bookContentType(bookRoot) == ContentType.Epub) {
    epubParser.parse(bookRoot)
} else {
    null
}

internal fun createSyncImportStagingDirectory(booksDirectory: File): File {
    Files.createDirectories(booksDirectory.toPath())
    val stagingParent = booksDirectory.parentFile ?: booksDirectory
    Files.createDirectories(stagingParent.toPath())
    return Files.createTempDirectory(stagingParent.toPath(), ".http-sync-import-").toFile()
}

/** Atomically publishes a validated staging directory to a new, collision-free book folder. */
internal fun publishSyncImportDirectory(stagingRoot: File, booksDirectory: File): File {
    val target = booksDirectory.resolve("http-sync-${UUID.randomUUID()}")
    check(!target.exists()) { "Sync import destination unexpectedly exists: $target" }
    try {
        Files.move(stagingRoot.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
    } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
        Files.move(stagingRoot.toPath(), target.toPath())
    }
    return target
}
