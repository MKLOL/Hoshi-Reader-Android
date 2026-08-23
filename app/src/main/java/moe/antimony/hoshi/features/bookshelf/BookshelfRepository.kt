package moe.antimony.hoshi.features.bookshelf

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import moe.antimony.hoshi.dictionary.DictionaryRepository
import moe.antimony.hoshi.epub.BookEntry
import moe.antimony.hoshi.epub.BookInfo
import moe.antimony.hoshi.epub.BookMetadata
import moe.antimony.hoshi.epub.BookRepository
import moe.antimony.hoshi.epub.BookShelf
import moe.antimony.hoshi.epub.BookSortOption
import moe.antimony.hoshi.epub.Bookmark
import moe.antimony.hoshi.epub.ContentType
import moe.antimony.hoshi.epub.EpubBook
import moe.antimony.hoshi.epub.EpubBookParser
import moe.antimony.hoshi.epub.bookContentType
import moe.antimony.hoshi.epub.isUuidString
import moe.antimony.hoshi.features.sync.StatisticsSyncMode
import moe.antimony.hoshi.features.sync.SyncDirection
import moe.antimony.hoshi.features.sync.SyncManager
import moe.antimony.hoshi.features.sync.SyncResult
import moe.antimony.hoshi.features.sync.http.HttpSyncAutoPush
import moe.antimony.hoshi.features.sync.http.HttpSyncContentType
import moe.antimony.hoshi.features.sync.http.HttpSyncDeletedBookRecord
import moe.antimony.hoshi.features.sync.http.HttpSyncDeletedBookStateStore
import moe.antimony.hoshi.features.sync.http.deriveSyncId
import moe.antimony.hoshi.mokuro.MokuroBook
import moe.antimony.hoshi.mokuro.MokuroBookParser
import moe.antimony.hoshi.mokuro.MokuroImportException
import java.io.File
import java.time.Instant
import java.util.UUID

internal interface BookshelfRepository {
    suspend fun loadBooks(sortOption: BookSortOption): BookshelfLoadResult
    suspend fun openBook(entry: BookEntry): String
    suspend fun importBook(uri: Uri): String
    suspend fun importMokuroFolder(treeUri: Uri): String
    suspend fun deleteBook(entry: BookEntry)
    suspend fun deleteBooks(entries: Collection<BookEntry>)
    suspend fun moveBooks(bookIds: Set<String>, shelfName: String?)
    suspend fun createShelf(name: String)
    suspend fun deleteShelf(name: String)
    suspend fun moveShelf(fromIndex: Int, toIndex: Int)
    suspend fun markRead(entry: BookEntry)
    suspend fun renameBook(entry: BookEntry, title: String?)
    suspend fun changeSort(sortOption: BookSortOption)
    suspend fun changeShowReading(showReading: Boolean)
    suspend fun rebuildLookupQuery()
    suspend fun syncBook(
        entry: BookEntry,
        direction: SyncDirection?,
        syncStats: Boolean,
        statsSyncMode: StatisticsSyncMode,
        syncAudioBook: Boolean,
    ): SyncResult
}

internal class AndroidBookshelfRepository(
    private val context: Context,
    private val bookRepository: BookRepository,
    private val dictionaryRepository: DictionaryRepository,
    private val settingsRepository: BookshelfSettingsRepository,
    private val syncManager: SyncManager,
    private val bookParser: EpubBookParser = EpubBookParser(),
    private val mokuroParser: MokuroBookParser = MokuroBookParser(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    /**
     * Fire-and-forget HTTP-sync hooks for metadata-class edits (shelf moves, deletes,
     * imports). Optional so tests that don't exercise sync can omit it.
     */
    private val httpSyncAutoPush: HttpSyncAutoPush? = null,
) : BookshelfRepository {
    private val contentResolver = context.contentResolver
    private val httpSyncDeletedBookStateStore = HttpSyncDeletedBookStateStore(
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        },
    )

    override suspend fun loadBooks(sortOption: BookSortOption): BookshelfLoadResult = withContext(ioDispatcher) {
        loadBookshelfResult(
            bookRepository = bookRepository,
            sortOption = sortOption,
            settings = settingsRepository.settings.first(),
        )
    }

    override suspend fun openBook(entry: BookEntry): String = withContext(ioDispatcher) {
        // Manga book directories have no EpubBookParser-readable content; dispatch on the
        // content type derived from disk structure so the EPUB parser is never handed one.
        when (bookContentType(entry.root)) {
            ContentType.Epub -> {
                val parsedBook = bookParser.parse(entry.root)
                saveMetadata(entry.root, parsedBook, bookRepository.loadMetadata(entry.root))
                saveBookInfo(entry.root, parsedBook)
            }
            ContentType.Mokuro -> writeMokuroSidecars(entry.root)
        }
        // Touch lastAccess on open (upstream) so recents ordering stays correct.
        val metadata = bookRepository.loadMetadata(entry.root) ?: entry.metadata
        bookRepository.saveMetadata(
            entry.root,
            metadata.copy(lastAccess = bookRepository.currentAppleReferenceDateSeconds()),
        )
        readerBookId(entry.root)
    }

    override suspend fun importBook(uri: Uri): String = withContext(ioDispatcher) {
        val root = bookRepository.importBook(contentResolver, uri)
        when (bookContentType(root)) {
            ContentType.Epub -> {
                val parsedBook = bookParser.parse(root)
                saveMetadata(root, parsedBook, bookRepository.loadMetadata(root))
                saveBookInfo(root, parsedBook)
            }
            ContentType.Mokuro -> writeMokuroSidecars(root)
        }
        // Publish the new book's metadata immediately (payload still uploads on manual sync).
        notifyBookImported(root)
        readerBookId(root)
    }

    override suspend fun importMokuroFolder(treeUri: Uri): String = withContext(ioDispatcher) {
        val tree = DocumentFile.fromTreeUri(context, treeUri)
            ?: throw MokuroImportException("Unable to open the selected folder.")
        val root = bookRepository.importMokuroFolder(tree, contentResolver::openInputStream)
        writeMokuroSidecars(root)
        // Publish the new book's metadata immediately (payload still uploads on manual sync).
        notifyBookImported(root)
        readerBookId(root)
    }

    override suspend fun deleteBook(entry: BookEntry) = withContext(ioDispatcher) {
        val tombstone = recordHttpSyncTombstone(entry)
        bookRepository.deleteBook(entry.root, ::releasePersistedSasayakiAudioUri)
        // Push the tombstone immediately; the staged record above stays as the retry
        // path for the next manual sync.
        notifyBookDeleted(tombstone)
    }

    override suspend fun deleteBooks(entries: Collection<BookEntry>) = withContext(ioDispatcher) {
        entries.forEach { entry ->
            val tombstone = recordHttpSyncTombstone(entry)
            bookRepository.deleteBook(entry.root, ::releasePersistedSasayakiAudioUri)
            notifyBookDeleted(tombstone)
        }
    }

    override suspend fun moveBooks(bookIds: Set<String>, shelfName: String?) = withContext(ioDispatcher) {
        val shelves = bookRepository.loadShelves()
            .map { shelf -> shelf.copy(bookIds = shelf.bookIds.filterNot { it in bookIds }) }
            .map { shelf ->
                if (shelf.name == shelfName) {
                    shelf.copy(bookIds = (shelf.bookIds + bookIds).distinct())
                } else {
                    shelf
                }
            }
        bookRepository.saveShelves(shelves)
        // Metadata edits sync immediately (fire-and-forget; no-op when sync is off).
        notifyShelfPlacementChanged(bookIds, shelfName)
    }

    override suspend fun createShelf(name: String) = withContext(ioDispatcher) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return@withContext
        val shelves = bookRepository.loadShelves()
        if (shelves.none { it.name == trimmed }) {
            bookRepository.saveShelves(shelves + BookShelf(trimmed, emptyList()))
        }
    }

    override suspend fun deleteShelf(name: String) = withContext(ioDispatcher) {
        val shelves = bookRepository.loadShelves()
        // Books on the deleted shelf become unshelved — a placement change for each of them.
        val orphanedIds = shelves.firstOrNull { it.name == name }?.bookIds.orEmpty().toSet()
        bookRepository.saveShelves(shelves.filterNot { it.name == name })
        notifyShelfPlacementChanged(orphanedIds, shelfName = null)
    }

    override suspend fun moveShelf(fromIndex: Int, toIndex: Int) = withContext(ioDispatcher) {
        val shelves = bookRepository.loadShelves().toMutableList()
        if (fromIndex !in shelves.indices || toIndex !in shelves.indices || fromIndex == toIndex) {
            return@withContext
        }
        val shelf = shelves.removeAt(fromIndex)
        shelves.add(toIndex, shelf)
        bookRepository.saveShelves(shelves)
    }

    override suspend fun markRead(entry: BookEntry) = withContext(ioDispatcher) {
        val bookInfo = bookRepository.loadBookInfo(entry.root) ?: return@withContext
        when (bookContentType(entry.root)) {
            ContentType.Epub -> {
                val lastChapter = bookInfo.chapterInfo.values
                    .mapNotNull { it.spineIndex }
                    .maxOrNull()
                    ?: 0
                bookRepository.saveBookmark(
                    entry.root,
                    Bookmark(
                        chapterIndex = lastChapter,
                        progress = 1.0,
                        characterCount = bookInfo.characterCount,
                        lastModified = bookRepository.currentAppleReferenceDateSeconds(),
                    ),
                )
            }
            ContentType.Mokuro -> {
                // For manga, bookinfo.characterCount is the page count: the last page index
                // is one below it, and that index doubles as the bookmark position.
                val totalPages = bookInfo.characterCount
                val lastPageIndex = (totalPages - 1).coerceAtLeast(0)
                bookRepository.saveBookmark(
                    entry.root,
                    Bookmark(
                        chapterIndex = lastPageIndex,
                        progress = 1.0,
                        characterCount = totalPages,
                        lastModified = bookRepository.currentAppleReferenceDateSeconds(),
                    ),
                )
            }
        }
        // "Mark read" is a deliberate bookmark edit just like a page turn: route it through
        // the same rev bump + fire-and-forget push path the reader hooks use, so the fresh
        // end-of-book bookmark out-revisions (instead of losing to) an older remote position.
        notifyBookmarkEdited(entry)
    }

    override suspend fun renameBook(entry: BookEntry, title: String?) = withContext(ioDispatcher) {
        val metadata = bookRepository.loadMetadata(entry.root) ?: entry.metadata
        bookRepository.saveMetadata(entry.root, metadata.copy(renamedTitle = title))
    }

    override suspend fun changeSort(sortOption: BookSortOption) {
        settingsRepository.update { it.copy(sortOption = sortOption) }
    }

    override suspend fun changeShowReading(showReading: Boolean) {
        settingsRepository.update { it.copy(showReading = showReading) }
    }

    override suspend fun rebuildLookupQuery() {
        dictionaryRepository.rebuildLookupQuery()
    }

    override suspend fun syncBook(
        entry: BookEntry,
        direction: SyncDirection?,
        syncStats: Boolean,
        statsSyncMode: StatisticsSyncMode,
        syncAudioBook: Boolean,
    ): SyncResult = withContext(ioDispatcher) {
        syncManager.syncBook(
            entry = entry,
            direction = direction,
            syncStats = syncStats,
            statsSyncMode = statsSyncMode,
            syncAudioBook = syncAudioBook,
        )
    }

    private suspend fun saveMetadata(root: File, parsedBook: EpubBook, previous: BookMetadata? = null) {
        val metadata = BookMetadata(
            id = previous?.id?.takeIf { it.isUuidString() } ?: UUID.randomUUID().toString(),
            title = parsedBook.title,
            cover = bookRepository.metadataCoverPath(root, parsedBook.coverHref),
            folder = root.name,
            lastAccess = bookRepository.currentAppleReferenceDateSeconds(),
            // Preserve an existing import stamp on re-save (cover-parse fill-in, migrations, etc.).
            // First-time imports get a fresh RFC 3339 stamp so a server-side tombstone with an
            // older `deletedAt` can no longer wipe the fresh local copy on the next sync.
            importedAt = previous?.importedAt ?: Instant.now().toString(),
            renamedTitle = previous?.renamedTitle,
        )
        bookRepository.saveMetadata(root, metadata)
    }

    private suspend fun saveBookInfo(root: File, parsedBook: EpubBook) {
        bookRepository.saveBookInfo(root, parsedBook.bookInfo)
    }

    /**
     * Writes the shared `metadata.json` / `bookinfo.json` sidecars for a mokuro book
     * directory, parsing the on-disk `mokuro.json`. The content type itself is never
     * persisted — it stays derivable from disk structure.
     */
    private suspend fun writeMokuroSidecars(root: File) {
        val book = mokuroParser.parse(root)
        saveMokuroMetadata(root, book, bookRepository.loadMetadata(root))
        // For manga there are no chapters; characterCount carries the total page count so
        // the bookshelf's progress fraction (bookmark.characterCount / total) still works.
        bookRepository.saveBookInfo(
            root,
            BookInfo(characterCount = book.pages.size, chapterInfo = emptyMap()),
        )
    }

    private suspend fun saveMokuroMetadata(root: File, book: MokuroBook, previous: BookMetadata?) {
        val metadata = BookMetadata(
            id = previous?.id?.takeIf { it.isUuidString() } ?: UUID.randomUUID().toString(),
            title = book.title,
            cover = bookRepository.metadataCoverPath(root, book.coverImagePath),
            folder = root.name,
            lastAccess = bookRepository.currentAppleReferenceDateSeconds(),
            // Same rule as the EPUB import path: preserve a prior stamp on re-save, otherwise
            // record a fresh RFC 3339 import stamp so a stale remote tombstone cannot wipe
            // this freshly-imported book on the next sync.
            importedAt = previous?.importedAt ?: Instant.now().toString(),
        )
        bookRepository.saveMetadata(root, metadata)
    }

    private suspend fun readerBookId(root: File): String =
        bookRepository.loadMetadata(root)?.id ?: root.name

    private fun recordHttpSyncTombstone(entry: BookEntry): StagedTombstone? {
        val title = entry.metadata.title?.takeIf { it.isNotBlank() } ?: return null
        val syncId = deriveSyncId(title) ?: return null
        val record = HttpSyncDeletedBookRecord(
            title = title,
            contentType = HttpSyncContentType.fromLocal(bookContentType(entry.root)),
            deletedAt = Instant.now().toString(),
        )
        httpSyncDeletedBookStateStore.recordDeletedBook(
            booksRoot = bookRepository.booksDirectory,
            syncId = syncId,
            record = record,
        )
        return StagedTombstone(syncId = syncId, record = record)
    }

    private data class StagedTombstone(
        val syncId: String,
        val record: HttpSyncDeletedBookRecord,
    )

    // ----- Fire-and-forget HTTP-sync notifications (mirror iOS BookshelfViewModel hooks) ---

    private suspend fun notifyBookImported(root: File) {
        val autoPush = httpSyncAutoPush ?: return
        val metadata = bookRepository.loadMetadata(root) ?: return
        autoPush.onBookImported(
            title = metadata.title,
            contentType = bookContentType(root),
            importedAt = metadata.importedAt,
        )
    }

    private suspend fun notifyBookDeleted(tombstone: StagedTombstone?) {
        val autoPush = httpSyncAutoPush ?: return
        val staged = tombstone ?: return
        autoPush.onBookDeleted(
            syncId = staged.syncId,
            title = staged.record.title,
            contentType = staged.record.contentType,
            deletedAt = staged.record.deletedAt,
        )
    }

    private suspend fun notifyBookmarkEdited(entry: BookEntry) {
        httpSyncAutoPush?.onBookmarkEdited(entry.root, entry.metadata.title)
    }

    private suspend fun notifyShelfPlacementChanged(bookIds: Set<String>, shelfName: String?) {
        val autoPush = httpSyncAutoPush ?: return
        if (bookIds.isEmpty()) return
        val entriesById = bookRepository.loadBookEntries().associateBy { it.metadata.id }
        for (bookId in bookIds) {
            val entry = entriesById[bookId] ?: continue
            autoPush.onShelfPlacementChanged(
                title = entry.metadata.title,
                contentType = bookContentType(entry.root),
                importedAt = entry.metadata.importedAt,
                shelfName = shelfName,
            )
        }
    }

    private fun releasePersistedSasayakiAudioUri(uriString: String) {
        contentResolver.releasePersistableUriPermission(
            Uri.parse(uriString),
            Intent.FLAG_GRANT_READ_URI_PERMISSION,
        )
    }
}

internal suspend fun loadBookshelfResult(
    bookRepository: BookRepository,
    sortOption: BookSortOption,
    settings: BookshelfSettings,
): BookshelfLoadResult {
    val entries = bookRepository.loadBookEntries(sortOption)
    return BookshelfLoadResult(
        entries = entries,
        progressById = loadBookProgressById(entries, bookRepository),
        coverSourcesById = loadBookCoverSourcesById(entries, bookRepository),
        shelves = bookRepository.loadShelves(),
        settings = settings,
    )
}

internal suspend fun loadBookCoverSourcesById(
    entries: List<BookEntry>,
    bookRepository: BookRepository,
): Map<String, BookCoverSource> =
    entries.mapNotNull { entry ->
        bookRepository.coverFile(entry)?.toBookCoverSource()?.let { source ->
            entry.metadata.id to source
        }
    }.toMap()

internal fun File.toBookCoverSource(): BookCoverSource =
    BookCoverSource(
        path = absolutePath,
        cacheKey = "$absolutePath:${lastModified()}:${length()}",
    )
