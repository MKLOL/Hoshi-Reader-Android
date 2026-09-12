package moe.antimony.hoshi.epub

import android.content.ContentResolver
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import moe.antimony.hoshi.mokuro.MangaTextStatistic
import moe.antimony.hoshi.mokuro.deduplicateMangaTextStatistics
import kotlinx.serialization.json.Json
import moe.antimony.hoshi.features.sync.http.HttpSyncActiveBooks
import moe.antimony.hoshi.features.sync.http.HttpSyncBookLocks
import moe.antimony.hoshi.features.sync.http.HttpSyncPayloadCodec
import moe.antimony.hoshi.features.sync.http.deriveSyncId
import moe.antimony.hoshi.features.sync.http.syncIdForMetadata
import moe.antimony.hoshi.importing.ImportFileType
import moe.antimony.hoshi.importing.importDisplayName
import moe.antimony.hoshi.importing.validateImportFile
import moe.antimony.hoshi.mokuro.MokuroImporter
import java.io.File
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipInputStream

/**
 * Cover file materialized by a sync receiver for a book whose payload shipped none. The name is
 * excluded from the cross-platform payload content hash (see `PAYLOAD_EXCLUDED_FILES`) so a
 * receiver-generated file never makes local content look different from the origin's. Must stay
 * identical to iOS `FileNames.generatedCover`.
 */
internal const val GENERATED_COVER_FILENAME: String = ".generated_cover.jpg"

class BookRepository(
    filesDir: File,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val fileDataSource: BookFileDataSource = BookFileDataSource(filesDir, ioDispatcher),
    private val sidecarDataSource: BookSidecarDataSource = BookSidecarDataSource(ioDispatcher),
    private val clock: BookClock = SystemBookClock,
    private val bookLocks: HttpSyncBookLocks = HttpSyncBookLocks(),
) : ReaderRouteBookRepository, SasayakiSidecarRepository {
    private val importDataSource = BookImportDataSource(
        filesDir = filesDir,
        fileDataSource = fileDataSource,
        ioDispatcher = ioDispatcher,
        sidecarDataSource = sidecarDataSource,
        bookLocks = bookLocks,
    )

    val currentBookFile: File get() = fileDataSource.currentBookFile
    val booksDirectory: File get() = fileDataSource.booksDirectory

    suspend fun loadAllBooks(): List<File> = fileDataSource.loadAllBooks()

    suspend fun loadBookEntries(sortOption: BookSortOption = BookSortOption.Recent): List<BookEntry> {
        val idReplacements = linkedMapOf<String, String>()
        val entries = loadAllBooks()
            .map { root ->
                val migration = migrateLegacyBookForIosBackupCompatibility(root, loadMetadata(root))
                if (migration.oldId != migration.metadata.id) {
                    idReplacements[migration.oldId] = migration.metadata.id
                }
                BookEntry(root = root, metadata = migration.metadata)
            }
        if (idReplacements.isNotEmpty()) {
            replaceShelfBookIds(idReplacements)
        }
        return when (sortOption) {
            BookSortOption.Recent -> entries.sortedByDescending { it.metadata.lastAccess }
            BookSortOption.Title -> entries.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.displayTitle })
        }
    }

    override suspend fun loadBookEntry(bookId: String): BookEntry? {
        for (root in loadAllBooks()) {
            val migration = migrateLegacyBookForIosBackupCompatibility(root, loadMetadata(root))
            if (migration.oldId != migration.metadata.id) {
                replaceShelfBookIds(mapOf(migration.oldId to migration.metadata.id))
            }
            if (migration.metadata.id == bookId || migration.oldId == bookId) {
                return BookEntry(root = root, metadata = migration.metadata)
            }
        }
        return null
    }

    suspend fun createBookDirectory(folder: String = UUID.randomUUID().toString()): File =
        fileDataSource.createBookDirectory(folder)

    suspend fun createBookDirectoryForImportedTitle(title: String): File =
        fileDataSource.createBookDirectoryForImportedTitle(title)

    suspend fun loadMetadata(bookRoot: File): BookMetadata? =
        sidecarDataSource.loadMetadata(bookRoot)

    override suspend fun saveMetadata(bookRoot: File, metadata: BookMetadata) {
        sidecarDataSource.saveMetadata(bookRoot, metadata)
    }

    suspend fun coverFile(entry: BookEntry): File? = fileDataSource.coverFile(entry)

    override suspend fun metadataCoverPath(bookRoot: File, coverHref: String?): String? =
        fileDataSource.metadataCoverPath(bookRoot, coverHref)

    suspend fun syncedCoverPath(bookRoot: File, coverHref: String?): String? =
        fileDataSource.syncedCoverPath(bookRoot, coverHref)

    suspend fun deleteBook(
        bookRoot: File,
        releasePersistedSasayakiAudioUri: (String) -> Unit = {},
    ) {
        val removedId = loadMetadata(bookRoot)?.id ?: bookRoot.name
        loadSasayakiPlayback(bookRoot)?.audioUri?.let { uri ->
            runCatching { releasePersistedSasayakiAudioUri(uri) }
        }
        fileDataSource.deleteBook(bookRoot)
        statisticsChangeCounter.update { it + 1 }
        val cleanedShelves = loadShelves().map { shelf ->
            shelf.copy(bookIds = shelf.bookIds.filterNot { it == removedId })
        }
        saveShelves(cleanedShelves)
    }

    suspend fun loadShelves(): List<BookShelf> =
        sidecarDataSource.loadShelves(fileDataSource.booksDirectory).orEmpty()

    suspend fun saveShelves(shelves: List<BookShelf>) {
        sidecarDataSource.saveShelves(fileDataSource.booksDirectory, shelves)
    }

    suspend fun shelvesLastModifiedMillis(): Long? =
        sidecarDataSource.shelvesLastModifiedMillis(fileDataSource.booksDirectory)

    private suspend fun replaceShelfBookIds(idReplacements: Map<String, String>) {
        saveShelves(
            loadShelves().map { shelf ->
                shelf.copy(bookIds = shelf.bookIds.map { idReplacements[it] ?: it })
            },
        )
    }

    override suspend fun loadBookmark(bookRoot: File): Bookmark? =
        sidecarDataSource.loadBookmark(bookRoot)

    override suspend fun saveBookmark(bookRoot: File, bookmark: Bookmark) {
        sidecarDataSource.saveBookmark(bookRoot, bookmark)
    }

    override suspend fun loadStatistics(bookRoot: File): List<ReadingStatistics> =
        bookLocks.withBookLock(bookRoot) { sidecarDataSource.loadStatistics(bookRoot).orEmpty() }

    /**
     * Merges [statistics] into the sidecar day by day (newest `lastStatisticModified` wins per
     * day) instead of overwriting it. A reader only knows the days it loaded plus today, so a
     * plain overwrite would drop days that a sync import added while the book was open.
     */
    override suspend fun saveStatistics(bookRoot: File, statistics: List<ReadingStatistics>) {
        // Not cancellable: the manga reader cancels its debounced save when the next page turn
        // arrives, and a write that has already reached the file must still signal the change.
        withContext(NonCancellable) {
            bookLocks.withBookLock(bookRoot) {
                val onDisk = sidecarDataSource.loadStatistics(bookRoot).orEmpty()
                sidecarDataSource.saveStatistics(bookRoot, (statistics + onDisk).deduplicateReadingStatistics())
            }
            statisticsChangeCounter.update { it + 1 }
        }
    }

    /** Overwrites the sidecar wholesale, for a sync in Replace mode; readers never call this. */
    suspend fun replaceStatistics(bookRoot: File, statistics: List<ReadingStatistics>) {
        withContext(NonCancellable) {
            bookLocks.withBookLock(bookRoot) { sidecarDataSource.saveStatistics(bookRoot, statistics) }
            statisticsChangeCounter.update { it + 1 }
        }
    }

    suspend fun loadMangaTextStatistics(bookRoot: File): List<MangaTextStatistic> =
        bookLocks.withBookLock(bookRoot) { sidecarDataSource.loadMangaTextStatistics(bookRoot).orEmpty() }

    /** Same day-by-day merge as [saveStatistics]. */
    suspend fun saveMangaTextStatistics(bookRoot: File, statistics: List<MangaTextStatistic>) {
        withContext(NonCancellable) {
            bookLocks.withBookLock(bookRoot) {
                val onDisk = sidecarDataSource.loadMangaTextStatistics(bookRoot).orEmpty()
                sidecarDataSource.saveMangaTextStatistics(bookRoot, (statistics + onDisk).deduplicateMangaTextStatistics())
            }
            statisticsChangeCounter.update { it + 1 }
        }
    }

    /** For paths that replace book directories wholesale (backup restore) and cannot go through a save. */
    fun notifyStatisticsChanged() {
        statisticsChangeCounter.update { it + 1 }
    }

    private val pendingStatisticsSaves = ConcurrentHashMap<String, MutableSet<Job>>()

    /**
     * Registers a statistics write a reader launched fire-and-forget (its dispose, ON_STOP and
     * toggle saves) so [awaitPendingStatisticsSaves] can wait for it. Call it right where the
     * coroutine is launched: the write takes the book lock only after its first IO hop, and a
     * reader opened on the same book in that gap (back, then the same cover again; the
     * sentence reader pushed over the EPUB reader; the activity recreated) would otherwise
     * read the sidecar before the previous session's final entry is in it, then overwrite that
     * entry with one built on the stale day.
     */
    fun trackStatisticsSave(bookRoot: File, save: Job) {
        val saves = pendingStatisticsSaves.computeIfAbsent(bookRoot.absolutePath) { ConcurrentHashMap.newKeySet() }
        saves += save
        save.invokeOnCompletion { saves -= save }
    }

    /** Waits for every tracked write of [bookRoot]'s statistics; readers call it before their initial load. */
    suspend fun awaitPendingStatisticsSaves(bookRoot: File) {
        pendingStatisticsSaves[bookRoot.absolutePath]?.toList()?.joinAll()
    }

    private val statisticsChangeCounter = MutableStateFlow(0L)

    /**
     * Bumped after every statistics sidecar write (reader sessions, manga page turns, sync
     * imports). Screens that aggregate statistics reload on each change, so they always show
     * what the files hold instead of a snapshot taken when they were first opened.
     */
    val statisticsChanges: StateFlow<Long> = statisticsChangeCounter

    suspend fun loadHighlights(bookRoot: File): List<ReaderHighlight> =
        sidecarDataSource.loadHighlights(bookRoot).orEmpty()

    suspend fun saveHighlights(bookRoot: File, highlights: List<ReaderHighlight>) {
        sidecarDataSource.saveHighlights(bookRoot, highlights)
    }

    suspend fun loadBookInfo(bookRoot: File): BookInfo? =
        sidecarDataSource.loadBookInfo(bookRoot)

    override suspend fun loadReaderBookInfo(bookRoot: File): BookInfo? =
        loadBookInfo(bookRoot)

    override suspend fun saveBookInfo(bookRoot: File, bookInfo: BookInfo) {
        sidecarDataSource.saveBookInfo(bookRoot, bookInfo)
    }

    override suspend fun loadSasayakiMatch(bookRoot: File): SasayakiMatchData? =
        sidecarDataSource.loadSasayakiMatch(bookRoot)

    override suspend fun saveSasayakiMatch(bookRoot: File, match: SasayakiMatchData) {
        sidecarDataSource.saveSasayakiMatch(bookRoot, match)
    }

    override suspend fun loadSasayakiPlayback(bookRoot: File): SasayakiPlaybackData? =
        sidecarDataSource.loadSasayakiPlayback(bookRoot)

    override suspend fun saveSasayakiPlayback(bookRoot: File, playback: SasayakiPlaybackData) {
        sidecarDataSource.saveSasayakiPlayback(bookRoot, playback)
    }

    suspend fun loadReadingProgress(bookRoot: File): Double {
        val total = loadBookInfo(bookRoot)?.characterCount ?: return 0.0
        if (total <= 0) return 0.0
        val current = loadBookmark(bookRoot)?.characterCount ?: return 0.0
        return current.toDouble().div(total.toDouble()).coerceIn(0.0, 1.0)
    }

    override fun currentAppleReferenceDateSeconds(): Double = clock.currentAppleReferenceDateSeconds()

    suspend fun importBook(contentResolver: ContentResolver, uri: Uri): File =
        importDataSource.importBook(contentResolver, uri)

    suspend fun importMokuroFolder(
        tree: DocumentFile,
        openInputStream: (Uri) -> java.io.InputStream?,
    ): File = importDataSource.importMokuroFolder(tree, openInputStream)

    private suspend fun File.fallbackMetadata(): BookMetadata = withContext(ioDispatcher) {
        BookMetadata(
            id = name,
            title = null,
            cover = null,
            folder = name,
            lastAccess = (lastModified().toDouble() / 1000.0) - APPLE_REFERENCE_EPOCH_SECONDS,
        )
    }

    private suspend fun migrateLegacyBookForIosBackupCompatibility(
        root: File,
        storedMetadata: BookMetadata?,
    ): LegacyBookMigration {
        val oldId = storedMetadata?.id ?: root.name
        val baseMetadata = storedMetadata ?: root.fallbackMetadata()
        val metadata = baseMetadata.withIosBackupCompatibleFields(root)
        if (metadata != storedMetadata) {
            saveMetadata(root, metadata)
        }
        return LegacyBookMigration(oldId = oldId, metadata = metadata)
    }

    // Legacy migration for Android builds that predate iOS-compatible Books backup.
    // Once supported users have upgraded through this path, remove this function and
    // the associated legacy migration tests; normal writes already produce this shape.
    private suspend fun BookMetadata.withIosBackupCompatibleFields(root: File): BookMetadata =
        copy(
            id = id.takeIf { it.isUuidString() } ?: UUID.randomUUID().toString(),
            folder = folder ?: root.name,
            cover = cover?.let { metadataCoverPath(root, it) ?: it },
        )

    private data class LegacyBookMigration(
        val oldId: String,
        val metadata: BookMetadata,
    )
}

interface ReaderRouteBookRepository {
    suspend fun loadBookEntry(bookId: String): BookEntry?
    suspend fun metadataCoverPath(bookRoot: File, coverHref: String?): String?
    suspend fun saveMetadata(bookRoot: File, metadata: BookMetadata)
    suspend fun loadBookmark(bookRoot: File): Bookmark?
    suspend fun saveBookmark(bookRoot: File, bookmark: Bookmark)
    suspend fun loadStatistics(bookRoot: File): List<ReadingStatistics>
    suspend fun saveStatistics(bookRoot: File, statistics: List<ReadingStatistics>)
    suspend fun loadReaderBookInfo(bookRoot: File): BookInfo?
    suspend fun saveBookInfo(bookRoot: File, bookInfo: BookInfo)
    fun currentAppleReferenceDateSeconds(): Double
}

interface SasayakiSidecarRepository {
    suspend fun loadSasayakiMatch(bookRoot: File): SasayakiMatchData?
    suspend fun saveSasayakiMatch(bookRoot: File, match: SasayakiMatchData)
    suspend fun loadSasayakiPlayback(bookRoot: File): SasayakiPlaybackData?
    suspend fun saveSasayakiPlayback(bookRoot: File, playback: SasayakiPlaybackData)
}

class BookFileDataSource(
    filesDir: File,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    val booksDirectory: File = File(filesDir, "Books")

    val currentBookFile: File = File(booksDirectory, "current.epub")

    suspend fun loadAllBooks(): List<File> = withContext(ioDispatcher) {
        booksDirectory
            .listFiles()
            ?.filter { it.isDirectory && !it.name.startsWith(".") }
            ?.sortedByDescending { it.lastModified() }
            .orEmpty()
    }

    suspend fun createBookDirectory(folder: String = UUID.randomUUID().toString()): File = withContext(ioDispatcher) {
        booksDirectory.mkdirs()
        val root = booksDirectory.resolve(folder).canonicalFile
        val booksRoot = booksDirectory.canonicalFile
        require(root.path == booksRoot.path || root.path.startsWith(booksRoot.path + File.separator)) {
            "Unsafe book folder: $folder"
        }
        root.mkdirs()
        root
    }

    suspend fun createBookDirectoryForImportedTitle(title: String): File {
        val safeTitle = title.sanitizeImportedBookTitle()
        require(safeTitle.isNotBlank()) { "EPUB title is empty" }
        return createBookDirectory(safeTitle)
    }

    suspend fun coverFile(entry: BookEntry): File? = withContext(ioDispatcher) {
        val cover = entry.metadata.cover?.takeIf { it.isNotBlank() } ?: return@withContext null
        resolveCoverFile(entry.root, cover)
    }

    suspend fun metadataCoverPath(bookRoot: File, coverHref: String?): String? = withContext(ioDispatcher) {
        val cover = coverHref?.takeIf { it.isNotBlank() } ?: return@withContext null
        val source = resolveCoverFile(bookRoot, cover) ?: return@withContext null
        val root = bookRoot.canonicalFile
        val destination = root.resolve(source.name).canonicalFile
        if (destination.path != root.path && !destination.path.startsWith(root.path + File.separator)) {
            return@withContext null
        }
        if (source.canonicalFile != destination) {
            source.copyTo(destination, overwrite = true)
        }
        "Books/${root.name}/${destination.name}"
    }

    /**
     * Sync-install variant of [metadataCoverPath]. A root-level copy that arrived inside the
     * payload is origin content and is reused as-is; otherwise the cover is materialized under
     * the hash-excluded [GENERATED_COVER_FILENAME] so the receiver's payload content keeps
     * matching the origin's manifest. (The import-time [metadataCoverPath] copy is different:
     * it happens before upload, travels in the zip, and is therefore consistent everywhere.)
     */
    suspend fun syncedCoverPath(bookRoot: File, coverHref: String?): String? = withContext(ioDispatcher) {
        val cover = coverHref?.takeIf { it.isNotBlank() } ?: return@withContext null
        val source = resolveCoverFile(bookRoot, cover) ?: return@withContext null
        val root = bookRoot.canonicalFile
        val shipped = root.resolve(source.name).canonicalFile
        if (shipped.path == root.path || !shipped.path.startsWith(root.path + File.separator)) {
            return@withContext null
        }
        if (shipped.isFile) return@withContext "Books/${root.name}/${shipped.name}"
        val generated = root.resolve(GENERATED_COVER_FILENAME)
        if (!generated.isFile) {
            runCatching { source.copyTo(generated, overwrite = false) }.getOrNull()
                ?: return@withContext null
        }
        "Books/${root.name}/$GENERATED_COVER_FILENAME"
    }

    private fun resolveCoverFile(bookRoot: File, cover: String): File? {
        val root = bookRoot.canonicalFile
        val rootRelative = root.resolve(cover).canonicalFile
        if ((rootRelative.path == root.path || rootRelative.path.startsWith(root.path + File.separator)) && rootRelative.isFile) {
            return rootRelative
        }
        val appRoot = booksDirectory.parentFile?.canonicalFile ?: return null
        val appRelative = appRoot.resolve(cover).canonicalFile
        if ((appRelative.path == root.path || appRelative.path.startsWith(root.path + File.separator)) && appRelative.isFile) {
            return appRelative
        }
        return null
    }

    suspend fun deleteBook(bookRoot: File) = withContext(ioDispatcher) {
        val root = bookRoot.canonicalFile
        val booksRoot = booksDirectory.canonicalFile
        require(root.path != booksRoot.path && root.path.startsWith(booksRoot.path + File.separator)) {
            "Unsafe book directory: ${bookRoot.path}"
        }
        if (root.exists()) {
            root.deleteRecursively()
        }
    }
}

class BookImportDataSource(
    private val filesDir: File,
    private val fileDataSource: BookFileDataSource,
    private val parser: EpubBookParser = EpubBookParser(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val mokuroImporter: MokuroImporter = MokuroImporter(filesDir, ioDispatcher),
    private val sidecarDataSource: BookSidecarDataSource = BookSidecarDataSource(ioDispatcher),
    private val bookLocks: HttpSyncBookLocks = HttpSyncBookLocks(),
) {
    /**
     * Imports the picked file into a book directory and returns its root. The picked file's
     * display name selects the content path: `.epub` is extracted and parsed as before,
     * `.zip`/`.cbz` is treated as a mokuro manga bundle. Either way the returned directory
     * is a complete book directory; its [moe.antimony.hoshi.epub.ContentType] is derived
     * from disk structure by the caller.
     */
    suspend fun importBook(contentResolver: ContentResolver, uri: Uri): File = withContext(ioDispatcher) {
        val displayName = contentResolver.importDisplayName(uri)
        if (ImportFileType.Mokuro.matchesDisplayName(displayName)) {
            return@withContext importMokuroBundle(contentResolver, uri)
        }
        importEpub(contentResolver, uri)
    }

    private suspend fun importMokuroBundle(contentResolver: ContentResolver, uri: Uri): File =
        mokuroImporter.importFromBundle(
            contentResolver = contentResolver,
            uri = uri,
            targetRootFactory = ::createMokuroBookDirectory,
        ).bookRoot

    /**
     * Imports a mokuro output folder picked via `ACTION_OPEN_DOCUMENT_TREE` and returns the
     * book directory root. [tree] is the picked document tree; [openInputStream] reads a
     * SAF document's bytes.
     */
    suspend fun importMokuroFolder(
        tree: DocumentFile,
        openInputStream: (Uri) -> java.io.InputStream?,
    ): File = mokuroImporter.importFromTree(
        tree = tree,
        openInputStream = openInputStream,
        targetRootFactory = ::createMokuroBookDirectory,
    ).bookRoot

    /**
     * Creates a fresh, empty book directory for a mokuro volume.
     *
     * The directory name is uniquified (`title`, `title-1`, `title-2`, …) rather than reused:
     * two different volumes can share a `volume` / `title` string — or a generic
     * `volume.mokuro` filename — and reusing-then-clearing the directory would destroy the
     * other book's images, bookmark and statistics. Re-importing the same volume therefore
     * adds a second copy rather than overwriting, which is acceptable; silently wiping an
     * unrelated book is not.
     */
    private suspend fun createMokuroBookDirectory(title: String): File {
        val safeTitle = title.sanitizeImportedBookTitle().ifBlank { "Manga" }
        var folder = safeTitle
        var suffix = 1
        while (File(fileDataSource.booksDirectory, folder).exists()) {
            folder = "$safeTitle-${suffix++}"
        }
        return fileDataSource.createBookDirectory(folder)
    }

    private suspend fun importEpub(contentResolver: ContentResolver, uri: Uri): File {
        val displayName = contentResolver.validateImportFile(uri, ImportFileType.Epub)
        val fallbackTitle = displayName
            .substringBeforeLast('.', missingDelimiterValue = displayName)
            .takeIf { it.isNotBlank() }
        val tempRoot = File(filesDir, "ImportTemp/${UUID.randomUUID()}").canonicalFile
        contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "Unable to open selected EPUB" }
            runCatching {
                tempRoot.mkdirs()
                ZipInputStream(input).use { zip ->
                    var entry = zip.nextEntry
                    while (entry != null) {
                        val output = tempRoot.resolve(entry.name).canonicalFile
                        val root = tempRoot.canonicalFile
                        require(output.path == root.path || output.path.startsWith(root.path + File.separator)) {
                            "Unsafe EPUB entry: ${entry.name}"
                        }
                        if (entry.isDirectory) {
                            output.mkdirs()
                        } else {
                            output.parentFile?.mkdirs()
                            output.outputStream().use { zip.copyTo(it) }
                        }
                        zip.closeEntry()
                        entry = zip.nextEntry
                    }
                }
            }.onFailure {
                tempRoot.deleteRecursively()
                throw it
            }
        }
        val parsedBook = runCatching { parser.parse(tempRoot, fallbackTitle = fallbackTitle) }
            .onFailure { tempRoot.deleteRecursively() }
            .getOrThrow()
        val targetRoot = fileDataSource.createBookDirectoryForImportedTitle(parsedBook.title)
        return if (targetRoot.listFiles()?.isNotEmpty() == true) {
            // Re-importing the same title means replacing its immutable EPUB bytes while
            // retaining bookmark/metadata/statistics sidecars. Returning the old directory
            // here used to mark stale bytes as a new local payload and could upload them over
            // the server's newer copy.
            val codec = HttpSyncPayloadCodec(ioDispatcher)
            val contentSha = codec.computePayloadContentSha(tempRoot)
            val existingMetadata = sidecarDataSource.loadMetadata(targetRoot)
            val syncId = existingMetadata?.let(::syncIdForMetadata)
                ?: deriveSyncId(parsedBook.title, targetRoot.name)
                ?: error("Unable to derive a sync identity for ${parsedBook.title}")
            val replaced = bookLocks.withBookLock(targetRoot) {
                HttpSyncActiveBooks.runIfInactive(syncId) {
                    codec.installReplacement(targetRoot, tempRoot, contentSha)
                    // Publish the local generation before releasing the same lock used by remote
                    // installers. A download already waiting here must observe this and abort.
                    codec.markPayloadContentDirty(targetRoot)
                }
            }
            if (!replaced) {
                tempRoot.deleteRecursively()
                error("Cannot replace an EPUB while it is open in the reader.")
            }
            targetRoot
        } else {
            targetRoot.deleteRecursively()
            check(tempRoot.renameTo(targetRoot)) { "Unable to move imported EPUB into Books/${targetRoot.name}" }
            targetRoot
        }
    }
}

class BookSidecarDataSource(
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    @OptIn(ExperimentalSerializationApi::class)
    private val json = Json {
        prettyPrint = true
        prettyPrintIndent = "    "
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    suspend fun loadMetadata(bookRoot: File): BookMetadata? =
        loadJson(BookMetadata.serializer(), bookRoot.resolve(METADATA_FILE_NAME))

    suspend fun saveMetadata(bookRoot: File, metadata: BookMetadata) {
        saveJson(bookRoot, METADATA_FILE_NAME, BookMetadata.serializer(), metadata)
    }

    suspend fun loadBookmark(bookRoot: File): Bookmark? =
        loadJson(Bookmark.serializer(), bookRoot.resolve(BOOKMARK_FILE_NAME))

    suspend fun saveBookmark(bookRoot: File, bookmark: Bookmark) {
        saveJson(bookRoot, BOOKMARK_FILE_NAME, Bookmark.serializer(), bookmark)
    }

    suspend fun loadStatistics(bookRoot: File): List<ReadingStatistics>? =
        loadJson(ListSerializer(ReadingStatistics.serializer()), bookRoot.resolve(STATISTICS_FILE_NAME))
            ?.deduplicateReadingStatistics()

    suspend fun saveStatistics(bookRoot: File, statistics: List<ReadingStatistics>) {
        saveJson(
            bookRoot,
            STATISTICS_FILE_NAME,
            ListSerializer(ReadingStatistics.serializer()),
            statistics.deduplicateReadingStatistics(),
        )
    }

    suspend fun loadMangaTextStatistics(bookRoot: File): List<MangaTextStatistic>? =
        loadJson(ListSerializer(MangaTextStatistic.serializer()), bookRoot.resolve(MANGA_STATISTICS_FILE_NAME))
            ?.deduplicateMangaTextStatistics()

    suspend fun saveMangaTextStatistics(bookRoot: File, statistics: List<MangaTextStatistic>) {
        saveJson(
            bookRoot,
            MANGA_STATISTICS_FILE_NAME,
            ListSerializer(MangaTextStatistic.serializer()),
            statistics.deduplicateMangaTextStatistics(),
        )
    }

    suspend fun loadHighlights(bookRoot: File): List<ReaderHighlight>? =
        loadJson(ListSerializer(ReaderHighlight.serializer()), bookRoot.resolve(HIGHLIGHTS_FILE_NAME))

    suspend fun saveHighlights(bookRoot: File, highlights: List<ReaderHighlight>) {
        saveJson(bookRoot, HIGHLIGHTS_FILE_NAME, ListSerializer(ReaderHighlight.serializer()), highlights)
    }

    suspend fun loadBookInfo(bookRoot: File): BookInfo? =
        loadJson(BookInfo.serializer(), bookRoot.resolve(BOOKINFO_FILE_NAME))

    suspend fun saveBookInfo(bookRoot: File, bookInfo: BookInfo) {
        saveJson(bookRoot, BOOKINFO_FILE_NAME, BookInfo.serializer(), bookInfo)
    }

    suspend fun loadSasayakiMatch(bookRoot: File): SasayakiMatchData? =
        loadJson(SasayakiMatchData.serializer(), bookRoot.resolve(SASAYAKI_MATCH_FILE_NAME))

    suspend fun saveSasayakiMatch(bookRoot: File, match: SasayakiMatchData) {
        saveJson(bookRoot, SASAYAKI_MATCH_FILE_NAME, SasayakiMatchData.serializer(), match)
    }

    suspend fun loadSasayakiPlayback(bookRoot: File): SasayakiPlaybackData? =
        loadJson(SasayakiPlaybackData.serializer(), bookRoot.resolve(SASAYAKI_PLAYBACK_FILE_NAME))

    suspend fun saveSasayakiPlayback(bookRoot: File, playback: SasayakiPlaybackData) {
        saveJson(bookRoot, SASAYAKI_PLAYBACK_FILE_NAME, SasayakiPlaybackData.serializer(), playback)
    }

    suspend fun loadShelves(booksRoot: File): List<BookShelf>? =
        loadJson(ListSerializer(BookShelf.serializer()), booksRoot.resolve(SHELVES_FILE_NAME))

    suspend fun saveShelves(booksRoot: File, shelves: List<BookShelf>) {
        saveJson(booksRoot, SHELVES_FILE_NAME, ListSerializer(BookShelf.serializer()), shelves)
    }

    suspend fun shelvesLastModifiedMillis(booksRoot: File): Long? = withContext(ioDispatcher) {
        booksRoot.resolve(SHELVES_FILE_NAME).takeIf { it.isFile }?.lastModified()
    }

    private suspend fun <T> loadJson(serializer: KSerializer<T>, file: File): T? = withContext(ioDispatcher) {
        if (!file.isFile) return@withContext null
        runCatching { json.decodeFromString(serializer, file.readText()) }.getOrNull()
    }

    private suspend fun <T> saveJson(bookRoot: File, fileName: String, serializer: KSerializer<T>, value: T) = withContext(ioDispatcher) {
        bookRoot.mkdirs()
        val target = bookRoot.resolve(fileName)
        val text = json.encodeToString(serializer, value)
        // Atomic write: encode into a temp sibling, then rename over the target. A bare
        // truncating writeText leaves a torn (or empty) sidecar if the process is killed or
        // storage fills mid-write; loadJson then reads it as absent and silently loses data
        // (e.g. every shelf placement, or the stable id/syncId). POSIX rename within one
        // directory is atomic; mirrors iOS Data.write(options: .atomic) in Core/BookStorage.swift.
        // A unique temp name per write: two writers of the same sidecar (a debounced page-turn
        // save racing a dispose save) must never rename each other's half-written file.
        val tmp = File(bookRoot, "$fileName.${java.util.UUID.randomUUID()}.tmp")
        try {
            tmp.writeText(text)
        } catch (error: Throwable) {
            tmp.delete()
            throw error
        }
        if (!tmp.renameTo(target)) {
            // Rename can fail on exotic filesystems; fall back to delete + rename, then to a
            // plain write (no worse than the previous behavior) as the last resort.
            target.delete()
            if (!tmp.renameTo(target)) {
                tmp.delete()
                target.writeText(text)
            }
        }
    }
}

interface BookClock {
    fun currentAppleReferenceDateSeconds(): Double
}

object SystemBookClock : BookClock {
    override fun currentAppleReferenceDateSeconds(): Double {
        val now = Instant.now()
        return now.epochSecond.toDouble() + (now.nano.toDouble() / 1_000_000_000.0) - APPLE_REFERENCE_EPOCH_SECONDS
    }
}

private const val METADATA_FILE_NAME = "metadata.json"
private const val BOOKMARK_FILE_NAME = "bookmark.json"
private const val STATISTICS_FILE_NAME = "statistics.json"
// Android-only per-day OCR character counts for manga; already in PAYLOAD_EXCLUDED_FILES.
private const val MANGA_STATISTICS_FILE_NAME = "manga_statistics.json"
private const val HIGHLIGHTS_FILE_NAME = "highlights.json"
internal const val BOOKINFO_FILE_NAME = "bookinfo.json"
private const val SHELVES_FILE_NAME = "shelves.json"
private const val SASAYAKI_MATCH_FILE_NAME = "sasayaki_match.json"
private const val SASAYAKI_PLAYBACK_FILE_NAME = "sasayaki_playback.json"
private const val APPLE_REFERENCE_EPOCH_SECONDS = 978_307_200.0

private fun String.sanitizeImportedBookTitle(): String =
    split(Regex("[\\\\/:*?\"<>|\\n\\r\\u0000-\\u001F]"))
        .joinToString("_")
        .trim()

internal fun String.isUuidString(): Boolean =
    runCatching { UUID.fromString(this) }.isSuccess
