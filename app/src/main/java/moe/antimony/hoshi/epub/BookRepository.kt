package moe.antimony.hoshi.epub

import android.content.ContentResolver
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import moe.antimony.hoshi.mokuro.MangaTextStatistic
import moe.antimony.hoshi.mokuro.attributedTo
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
import moe.antimony.hoshi.storage.writeSidecarAtomically
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.attribute.BasicFileAttributes
import java.time.Instant
import java.time.ZoneId
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
    /**
     * The device statistics entries without a device are attributed to: everything this
     * install recorded before devices were tracked, plus anything handed in without one (a
     * ッツ import). Null only in tests that do not care about devices.
     */
    private val deviceIdentity: DeviceIdentity? = null,
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

    suspend fun metadataCoverPath(bookRoot: File, coverHref: String?): String? =
        fileDataSource.metadataCoverPath(bookRoot, coverHref)

    override suspend fun syncedCoverPath(bookRoot: File, coverHref: String?): String? =
        fileDataSource.syncedCoverPath(bookRoot, coverHref)

    suspend fun deleteBook(
        bookRoot: File,
        releasePersistedSasayakiAudioUri: (String) -> Unit = {},
    ) {
        val metadata = loadMetadata(bookRoot)
        val removedId = metadata?.id ?: bookRoot.name
        loadSasayakiPlayback(bookRoot)?.audioUri?.let { uri ->
            runCatching { releasePersistedSasayakiAudioUri(uri) }
        }
        // The book goes, its reading history stays: Today, the streak and every total still
        // count the days read in it, here and (through sync) on every other device. The folder
        // is first moved aside in one step, so a statistics save landing meanwhile (a reader's
        // last one included) either went into it and is kept from the moved copy, or finds it
        // gone and goes to the history. Nothing here waits for a book lock or a save: sync
        // deletes inside its open-book gate, and re-imports take the book lock before that gate.
        val syncId = metadata?.let(::syncIdForMetadata)?.takeIf(::isUsableStatisticsHistoryName)
        if (syncId != null) retiredBookRoots[bookRoot.canonicalPath] = syncId
        // Once started, never stopped halfway: a moved folder would hide the book's days.
        withContext(NonCancellable) {
            val retired = fileDataSource.retireBook(bookRoot) ?: return@withContext
            retiring += retired.name
            try {
                val kept = syncId == null || try {
                    keepStatisticsHistory(retired, syncId, metadata.displayTitle)
                    true
                } catch (_: Exception) {
                    false
                }
                // A failed copy leaves the moved folder (off the shelf already) for the next start.
                if (kept) fileDataSource.deleteRetired(retired)
            } finally {
                retiring -= retired.name
            }
        }
        invalidateHistoryNames()
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

    override suspend fun loadStatistics(bookRoot: File): List<ReadingStatistics> {
        foldStatisticsHistorySafely(bookRoot)
        return bookLocks.withBookLock(bookRoot) {
            val onDisk = readStatisticsSidecar(bookRoot)
            val attributed = onDisk.legacyAttributed(bookRoot)
            // A file from before devices were tracked is rewritten once, so every reader of the
            // file (screens, sync uploads) sees the same attribution.
            if (attributed != onDisk) runCatching { sidecarDataSource.saveStatistics(bookRoot, attributed) }
            attributed
        }
    }

    /**
     * Merges [statistics] into the sidecar entry by entry (newest `lastStatisticModified` wins
     * per day and device) instead of overwriting it. A reader only knows the days it loaded
     * plus today, so a plain overwrite would drop days that a sync import added while the book
     * was open. Entries without a device are stored as they are (see [legacyAttributed]).
     * A save never brings a deleted book's folder back: one for a book deleted meanwhile goes
     * to its reading history, any other is dropped.
     */
    override suspend fun saveStatistics(bookRoot: File, statistics: List<ReadingStatistics>) {
        // Not cancellable: the manga reader cancels its debounced save when the next page turn
        // arrives, and a write that has already reached the file must still signal the change.
        withContext(NonCancellable) {
            writeStatistics(bookRoot) { target ->
                val onDisk = readStatisticsSidecar(target, forWrite = true).legacyAttributed(target)
                sidecarDataSource.saveStatistics(target, (statistics + onDisk).deduplicateReadingStatistics())
            }
            statisticsChangeCounter.update { it + 1 }
        }
    }

    /** Overwrites the sidecar wholesale; readers never call this. */
    suspend fun replaceStatistics(bookRoot: File, statistics: List<ReadingStatistics>) {
        withContext(NonCancellable) {
            writeStatistics(bookRoot) { target -> sidecarDataSource.saveStatistics(target, statistics) }
            statisticsChangeCounter.update { it + 1 }
        }
    }

    /** Deleted book folders -> their sync id, so a statistics save landing after the delete reaches the history. */
    private val retiredBookRoots = ConcurrentHashMap<String, String>()

    /**
     * Runs a statistics write on [bookRoot] under its lock, only while the folder exists: the
     * sidecar writes never create a folder, so a write racing a delete fails instead of bringing
     * the deleted book back as an untitled one. A write for a book deleted meanwhile is redone
     * on its history folder; any other write for a missing folder is dropped.
     */
    private suspend fun writeStatistics(bookRoot: File, write: suspend (File) -> Unit) {
        val wrote = try {
            bookLocks.withBookLock(bookRoot) {
                if (!bookRoot.isDirectory) return@withBookLock false
                write(bookRoot)
                true
            }
        } catch (error: IOException) {
            // The folder is still there: the file could not be read or written. Nothing was
            // overwritten, and a reader's next save carries the same day's running total.
            if (bookRoot.isDirectory) return
            false
        }
        if (wrote) return
        val syncId = retiredBookRoots[bookRoot.canonicalPath] ?: return
        try {
            writeHistory(syncId, write = write)
        } catch (_: IOException) {
        }
    }

    /**
     * A statistics sidecar's entries. An unreadable file is moved to `Books/.unreadable/` first:
     * the next save then starts from the entries that are readable instead of overwriting the
     * only copy of the rest.
     */
    private suspend fun readStatisticsSidecar(root: File, forWrite: Boolean = false): List<ReadingStatistics> =
        readSidecar(forWrite) { sidecarDataSource.readStatisticsStrictly(root) }

    private suspend fun readMangaTextSidecar(root: File, forWrite: Boolean = false): List<MangaTextStatistic> =
        readSidecar(forWrite) { sidecarDataSource.readMangaTextStatisticsStrictly(root) }

    /**
     * A damaged file (it cannot be decoded) is set aside and reads as empty. A file that cannot
     * be read at the moment reads as empty for display, but fails a read made to write: writing
     * then would replace days that are still there.
     */
    private suspend fun <T> readSidecar(forWrite: Boolean, read: suspend () -> List<T>?): List<T> = try {
        read().orEmpty()
    } catch (damaged: DamagedSidecarException) {
        preserveUnreadable(damaged.file)
        emptyList()
    } catch (error: IOException) {
        if (forWrite) throw error
        emptyList()
    }

    private fun preserveUnreadable(file: File) {
        if (!file.isFile || file.length() == 0L) return
        val aside = booksDirectory.resolve(UNREADABLE_DIRECTORY_NAME)
        aside.mkdirs()
        val target = aside.resolve("${file.parentFile?.name}-${file.name}-${System.currentTimeMillis()}")
        if (!file.renameTo(target)) runCatching { file.copyTo(target, overwrite = true) }
    }

    /**
     * Makes each day's total across devices match [dayTotals] — device-less per-day entries,
     * as a ッツ statistics file holds them — by adjusting only this device's entry for the
     * day. The other devices' entries are theirs and stay untouched, so this device's share
     * becomes the day's total minus what the other devices recorded (never below zero). A day
     * whose total already matches is left alone, so importing what was just exported changes
     * nothing, and the file is not written at all when nothing changed. With [replaceOtherDays]
     * this device's entries for days missing from [dayTotals] are dropped (ッツ "Replace").
     *
     * @return whether the sidecar changed.
     */
    suspend fun applyDayTotals(
        bookRoot: File,
        dayTotals: List<ReadingStatistics>,
        replaceOtherDays: Boolean,
    ): Boolean = withContext(NonCancellable) {
        val device = deviceIdentity
        val changed = bookLocks.withBookLock(bookRoot) {
            if (!bookRoot.isDirectory) return@withBookLock false
            val next = readStatisticsSidecar(bookRoot, forWrite = true).legacyAttributed(bookRoot).toMutableList()
            var changed = false
            val totals = dayTotals.collapsedByDay()
            for (total in totals) {
                val sameDay = next.filter { it.dateKey == total.dateKey }
                val own = sameDay.firstOrNull { it.deviceId == device?.id }
                val others = sameDay.filter { it.deviceId != device?.id }
                val ownTime = (total.readingTime - others.sumOf { it.readingTime }).coerceAtLeast(0.0)
                val ownCharacters = (total.charactersRead - others.sumOf { it.charactersRead }).coerceAtLeast(0)
                if (own == null && ownTime <= 0.0 && ownCharacters <= 0) continue
                if (own != null && own.readingTime == ownTime && own.charactersRead == ownCharacters) continue
                // A collapsed export's hours belong to ALL devices. Subtract only when
                // every other device's time is located; otherwise keep trustworthy local
                // evidence and let the streak reconstruction handle the unknown remainder.
                val hoursFromTotal = total.readingTimeByHour.isNotEmpty() && others.all {
                    kotlin.math.abs(it.readingTime - it.readingTimeByHour.values.sum()) < 0.001
                }
                val hours = if (hoursFromTotal) {
                    val otherHours = others.map { it.readingTimeByHour }.sumReadingHours()
                    total.readingTimeByHour.mapValues { (hour, seconds) ->
                        (seconds - (otherHours[hour] ?: 0.0)).coerceAtLeast(0.0)
                    }.filterValues { it > 0.0 }.takeIf { it.values.sum() <= ownTime + 0.001 }.orEmpty()
                } else {
                    own?.readingTimeByHour?.takeIf { it.values.sum() <= ownTime + 0.001 }.orEmpty()
                }
                // The stamp below comes from ッツ, so it says nothing about the hours' zone:
                // name the zone they were recorded in.
                val zone = if (hours.isEmpty()) null else {
                    val deviceZone = ZoneId.systemDefault()
                    (if (hoursFromTotal) total.timeZone else null)
                        ?: own?.let { it.timeZone ?: it.recordedZone(deviceZone)?.id }
                        ?: deviceZone.id
                }
                val updated = total.copy(
                    readingTime = ownTime,
                    readingTimeByHour = hours,
                    charactersRead = ownCharacters,
                    lastReadingSpeed = if (ownTime > 0.0) (ownCharacters / ownTime * 3600.0).toInt() else 0,
                    // Newer than the entry it replaces, so HTTP sync carries it to the other devices.
                    lastStatisticModified = maxOf(total.lastStatisticModified, (own?.lastStatisticModified ?: 0L) + 1),
                    deviceId = device?.id,
                    deviceName = device?.name,
                    timeZone = zone,
                )
                if (own != null) next.remove(own)
                next += updated
                changed = true
            }
            if (replaceOtherDays) {
                val keep = totals.map { it.dateKey }.toSet()
                if (next.removeAll { it.deviceId == device?.id && it.dateKey !in keep }) changed = true
            }
            if (changed) sidecarDataSource.saveStatistics(bookRoot, next.deduplicateReadingStatistics())
            changed
        }
        if (changed) statisticsChangeCounter.update { it + 1 }
        changed
    }

    suspend fun loadMangaTextStatistics(bookRoot: File): List<MangaTextStatistic> {
        foldStatisticsHistorySafely(bookRoot)
        return bookLocks.withBookLock(bookRoot) {
            val onDisk = readMangaTextSidecar(bookRoot)
            val attributed = onDisk.legacyAttributed(bookRoot)
            if (attributed != onDisk) runCatching { sidecarDataSource.saveMangaTextStatistics(bookRoot, attributed) }
            attributed
        }
    }

    /** Same day-by-day merge, and the same rule for deleted folders, as [saveStatistics]. */
    suspend fun saveMangaTextStatistics(bookRoot: File, statistics: List<MangaTextStatistic>) {
        withContext(NonCancellable) {
            writeStatistics(bookRoot) { target ->
                val onDisk = readMangaTextSidecar(target, forWrite = true).legacyAttributed(target)
                sidecarDataSource.saveMangaTextStatistics(target, (statistics + onDisk).deduplicateMangaTextStatistics())
            }
            statisticsChangeCounter.update { it + 1 }
        }
    }

    /** For paths that replace book directories wholesale (backup restore) and cannot go through a save. */
    fun notifyStatisticsChanged() {
        invalidateHistoryNames()
        statisticsChangeCounter.update { it + 1 }
    }

    /** Where the [StatisticsHistoryEntry] folders live. */
    val statisticsHistoryDirectory: File get() = booksDirectory.resolve(STATISTICS_HISTORY_DIRECTORY_NAME)

    /** Every book whose reading history this install keeps without the book's folder. */
    suspend fun loadStatisticsHistory(): List<StatisticsHistoryEntry> = withContext(ioDispatcher) {
        recoverRetiredBooks()
        statisticsHistoryDirectory.listFiles().orEmpty()
            .filter { it.isDirectory && isUsableStatisticsHistoryName(it.name) }
            .mapNotNull { root ->
                val info = readStatisticsHistoryInfo(root) ?: return@mapNotNull null
                StatisticsHistoryEntry(
                    syncId = info.syncId,
                    root = root,
                    title = info.title?.takeIf { it.isNotBlank() } ?: info.syncId,
                    contentType = statisticsContentType(root),
                    progress = info.progress,
                )
            }
            .sortedBy { it.syncId }
    }

    /**
     * The history folder of [syncId], created on first use; null for a name that is not a
     * usable folder name. A [title], [contentType] or [progress] fills in one the folder does
     * not know yet. A new folder starts from [seedExchangeState] (the deleted book's HTTP
     * exchange state) or else an empty one: its presence means device-less days in the folder
     * came from other installs, so they are never claimed as this device's own (see
     * [legacyAttributed]).
     */
    suspend fun statisticsHistoryRoot(
        syncId: String,
        title: String? = null,
        contentType: ContentType? = null,
        seedExchangeState: File? = null,
        progress: Double? = null,
    ): File? = writeHistory(syncId, title, contentType, seedExchangeState, progress) {}

    /**
     * Creates or completes [syncId]'s history folder and runs [write] on it under one lock, so a
     * fold that removes the folder can never fall between the two.
     */
    private suspend fun writeHistory(
        syncId: String,
        title: String? = null,
        contentType: ContentType? = null,
        seedExchangeState: File? = null,
        progress: Double? = null,
        write: suspend (File) -> Unit,
    ): File? = withContext(ioDispatcher) {
        if (!isUsableStatisticsHistoryName(syncId)) return@withContext null
        val root = statisticsHistoryDirectory.resolve(syncId)
        bookLocks.withBookLock(root) {
            val existing = readStatisticsHistoryInfo(root)
            val info = StatisticsHistoryInfo(
                syncId = syncId,
                title = existing?.title?.takeIf { it.isNotBlank() } ?: title?.takeIf { it.isNotBlank() },
                contentType = existing?.contentType ?: contentType,
                progress = progress ?: existing?.progress,
            )
            val state = root.resolve(STATISTICS_SYNC_STATE_FILE_NAME)
            if (!state.isFile) {
                root.mkdirs()
                val seed = seedExchangeState?.takeIf { it.isFile }?.let { runCatching { it.readText() }.getOrNull() }
                writeSidecarAtomically(state, seed ?: "{}")
            }
            if (info != existing) writeStatisticsHistoryInfo(root, info)
            write(root)
        }
        invalidateHistoryNames()
        root
    }

    /**
     * Moves a history folder's days into [bookRoot] once that book is installed again, then
     * removes the folder. Normally done by [loadStatistics] before anything reads the book's
     * statistics, so a reader opening the reinstalled book continues today's count instead of
     * starting it again at zero (which would replace it: the newest entry of a day wins).
     */
    suspend fun absorbStatisticsHistory(history: StatisticsHistoryEntry, bookRoot: File) {
        absorbStatisticsHistory(history.root, bookRoot)
    }

    private suspend fun absorbStatisticsHistory(history: File, bookRoot: File) {
        withContext(NonCancellable + ioDispatcher) {
            // No lock is held while waiting for another: sync deletes hold the open-book gate
            // while they keep history, and re-imports hold the book lock before that gate.
            val snapshot = bookLocks.withBookLock(history) {
                if (readStatisticsHistoryInfo(history) == null) return@withBookLock null
                Triple(
                    readStatisticsSidecar(history, forWrite = true),
                    readMangaTextSidecar(history, forWrite = true),
                    historyStamp(history),
                )
            } ?: return@withContext
            val (reading, mangaText, stamp) = snapshot
            val merged = bookLocks.withBookLock(bookRoot) {
                if (!bookRoot.isDirectory) return@withBookLock false
                // Claim the book's own pre-device days first, then mark it exchanged, so the
                // device-less days of other installs the history held are never claimed.
                val ownReading = readStatisticsSidecar(bookRoot, forWrite = true).legacyAttributed(bookRoot)
                val ownMangaText = readMangaTextSidecar(bookRoot, forWrite = true).legacyAttributed(bookRoot)
                val state = bookRoot.resolve(STATISTICS_SYNC_STATE_FILE_NAME)
                if (!state.isFile) writeSidecarAtomically(state, "{}")
                if (reading.isNotEmpty() || ownReading.isNotEmpty()) {
                    sidecarDataSource.saveStatistics(bookRoot, (reading + ownReading).deduplicateReadingStatistics())
                }
                if (mangaText.isNotEmpty() || ownMangaText.isNotEmpty()) {
                    sidecarDataSource.saveMangaTextStatistics(bookRoot, (mangaText + ownMangaText).deduplicateMangaTextStatistics())
                }
                true
            }
            // The book vanished meanwhile (deleted): the history stays.
            if (!merged) return@withContext
            bookLocks.withBookLock(history) {
                // Days that reached the history meanwhile stay for the next fold.
                if (historyStamp(history) != stamp) return@withBookLock
                // history.json first: a folder half removed by a crash is never taken for history.
                history.resolve(STATISTICS_HISTORY_INFO_FILE_NAME).delete()
                history.deleteRecursively()
            }
            invalidateHistoryNames()
            statisticsChangeCounter.update { it + 1 }
        }
    }

    /** Identity, time and size of the history's statistics files: an atomic replace changes the identity. */
    private fun historyStamp(history: File): List<Any?> =
        listOf(STATISTICS_FILE_NAME, MANGA_STATISTICS_FILE_NAME).map { name ->
            runCatching {
                val attributes = Files.readAttributes(history.resolve(name).toPath(), BasicFileAttributes::class.java)
                Triple(attributes.fileKey(), attributes.lastModifiedTime(), attributes.size())
            }.getOrNull()
        }

    /** [foldStatisticsHistory] that never fails a load: the history then stays for the next attempt. */
    private suspend fun foldStatisticsHistorySafely(bookRoot: File) {
        try {
            foldStatisticsHistory(bookRoot)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
        }
    }

    /** Folds the history kept for [bookRoot]'s book back into it (see [absorbStatisticsHistory]). */
    private suspend fun foldStatisticsHistory(bookRoot: File) {
        if (bookRoot.parentFile?.name == STATISTICS_HISTORY_DIRECTORY_NAME) return
        recoverRetiredBooks()
        val names = historyNames()
        if (names.isEmpty() || !bookRoot.isDirectory) return
        val syncId = sidecarDataSource.loadMetadata(bookRoot)?.let(::syncIdForMetadata) ?: return
        if (syncId !in names) return
        absorbStatisticsHistory(statisticsHistoryDirectory.resolve(syncId), bookRoot)
    }

    private val historyGeneration = java.util.concurrent.atomic.AtomicLong()
    @Volatile private var historyNamesCache: Pair<Long, Set<String>>? = null

    /** Names of the history folders; listed again after any change this repository makes to them. */
    private suspend fun historyNames(): Set<String> {
        val generation = historyGeneration.get()
        historyNamesCache?.takeIf { it.first == generation }?.let { return it.second }
        val names = withContext(ioDispatcher) {
            statisticsHistoryDirectory.list().orEmpty().filter(::isUsableStatisticsHistoryName).toSet()
        }
        // Stored under the generation it was listed for: a change meanwhile makes it stale.
        historyNamesCache = generation to names
        return names
    }

    private fun invalidateHistoryNames() {
        historyGeneration.incrementAndGet()
    }

    /**
     * Copies a deleted book's statistics into its history folder, from the folder [retired] the
     * book was moved to. Reads without the book lock (the files are replaced atomically);
     * creating and writing the history happen under one lock.
     */
    private suspend fun keepStatisticsHistory(retired: File, syncId: String, title: String) {
        val reading = readStatisticsSidecar(retired, forWrite = true).legacyAttributed(retired)
        val contentType = bookContentType(retired)
        val mangaText = if (contentType == ContentType.Mokuro) {
            readMangaTextSidecar(retired, forWrite = true).legacyAttributed(retired)
        } else {
            emptyList()
        }
        if (reading.isEmpty() && mangaText.isEmpty()) return
        writeHistory(
            syncId = syncId,
            title = title.takeIf { it.isNotBlank() },
            contentType = contentType,
            seedExchangeState = retired.resolve(STATISTICS_SYNC_STATE_FILE_NAME),
            progress = knownReadingProgress(retired),
        ) { history ->
            if (reading.isNotEmpty()) {
                val kept = readStatisticsSidecar(history, forWrite = true)
                sidecarDataSource.saveStatistics(history, (reading + kept).deduplicateReadingStatistics())
            }
            if (mangaText.isNotEmpty()) {
                val kept = readMangaTextSidecar(history, forWrite = true)
                sidecarDataSource.saveMangaTextStatistics(history, (mangaText + kept).deduplicateMangaTextStatistics())
            }
        } ?: throw IOException("No history folder for $syncId")
    }

    /** The share of the book read, as the Statistics screens show it ([loadReadingProgress]); null when unknown. */
    private suspend fun knownReadingProgress(root: File): Double? {
        val total = loadBookInfo(root)?.characterCount?.takeIf { it > 0 } ?: return null
        val current = loadBookmark(root)?.characterCount ?: return null
        return (current.toDouble() / total.toDouble()).coerceIn(0.0, 1.0)
    }

    /** Names of folders a delete in this process is still moving aside or copying from. */
    private val retiring: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val retiredRecovery = kotlinx.coroutines.sync.Mutex()
    @Volatile private var retiredRecovered = false

    /**
     * A book whose deletion was interrupted (the app was stopped after the folder was moved
     * aside) still holds the only copy of its statistics: keep them, then finish the deletion.
     * Once per process, before anything lists or folds the reading history.
     */
    private suspend fun recoverRetiredBooks() {
        if (retiredRecovered) return
        retiredRecovery.withLock {
            if (retiredRecovered) return@withLock
            withContext(NonCancellable) {
                for (retired in fileDataSource.leftoverRetiredBooks()) {
                    if (retired.name in retiring) continue
                    try {
                        val metadata = sidecarDataSource.loadMetadata(retired)
                        val syncId = metadata?.let(::syncIdForMetadata)?.takeIf(::isUsableStatisticsHistoryName)
                        if (syncId != null) keepStatisticsHistory(retired, syncId, metadata.displayTitle)
                        fileDataSource.deleteRetired(retired)
                    } catch (_: Exception) {
                    }
                }
            }
            retiredRecovered = true
        }
    }

    /** The device new statistics entries of this install are attributed to, when known. */
    val statisticsDevice: DeviceIdentity? get() = deviceIdentity

    /**
     * Statistics written before devices were tracked carry no device. They are this device's
     * own history exactly when the file has never been touched by a device-aware build (no
     * entry names a device) and the book's statistics have never been exchanged over HTTP
     * sync — otherwise the same device-less days already sit on the other side under the
     * "unknown device" key, and claiming them here would count them twice after the next
     * sync. Anything that arrives without a device later (from an older client, through
     * sync) stays in that "unknown device" bucket, which is what the Statistics screens show.
     */
    private fun List<ReadingStatistics>.legacyAttributed(bookRoot: File): List<ReadingStatistics> {
        val device = deviceIdentity ?: return this
        if (!legacyAttributionApplies(bookRoot, any { it.deviceId != null })) return this
        return attributedTo(device)
    }

    @JvmName("legacyAttributedMangaText")
    private fun List<MangaTextStatistic>.legacyAttributed(bookRoot: File): List<MangaTextStatistic> {
        val device = deviceIdentity ?: return this
        if (!legacyAttributionApplies(bookRoot, any { it.deviceId != null })) return this
        return attributedTo(device)
    }

    private fun legacyAttributionApplies(bookRoot: File, anyEntryNamesADevice: Boolean): Boolean =
        !anyEntryNamesADevice && !bookRoot.resolve(STATISTICS_SYNC_STATE_FILE_NAME).isFile

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
    suspend fun syncedCoverPath(bookRoot: File, coverHref: String?): String?
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
        require(root.path != booksRoot.path && root.path.startsWith(booksRoot.path + File.separator)) {
            "Unsafe book folder: $folder"
        }
        check(root.isDirectory || root.mkdirs()) { "Unable to create book directory: $folder" }
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
        val root = safeBookRoot(bookRoot)
        if (root.exists()) {
            root.deleteRecursively()
        }
    }

    /**
     * Moves a book's folder aside in one rename (`Books/.deleting-…`, hidden from the shelf), so
     * nothing can write into it or recreate it while it is being taken apart. Null when there is
     * no such folder; the folder itself when it cannot be moved.
     */
    suspend fun retireBook(bookRoot: File): File? = withContext(ioDispatcher) {
        val root = safeBookRoot(bookRoot)
        if (!root.exists()) return@withContext null
        val retired = booksDirectory.resolve("$RETIRED_PREFIX${UUID.randomUUID()}")
        if (root.renameTo(retired)) retired else root
    }

    suspend fun deleteRetired(retired: File) = withContext(ioDispatcher) {
        if (!retired.exists()) return@withContext
        // Statistics first: a folder half deleted by a crash must never keep its days without
        // the exchange state that says whose device-less days they are.
        retired.resolve(STATISTICS_FILE_NAME).delete()
        retired.resolve(MANGA_STATISTICS_FILE_NAME).delete()
        retired.deleteRecursively()
    }

    /** Folders [retireBook] moved aside that a stopped app never finished deleting. */
    suspend fun leftoverRetiredBooks(): List<File> = withContext(ioDispatcher) {
        booksDirectory.listFiles().orEmpty().filter { it.isDirectory && it.name.startsWith(RETIRED_PREFIX) }
    }

    private fun safeBookRoot(bookRoot: File): File {
        val root = bookRoot.canonicalFile
        val booksRoot = booksDirectory.canonicalFile
        require(root.path != booksRoot.path && root.path.startsWith(booksRoot.path + File.separator)) {
            "Unsafe book directory: ${bookRoot.path}"
        }
        return root
    }

    private companion object {
        const val RETIRED_PREFIX = ".deleting-"
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

    /** Never creates [bookRoot]: a statistics write for a deleted book must fail, not recreate it. */
    suspend fun saveStatistics(bookRoot: File, statistics: List<ReadingStatistics>) {
        saveJson(
            bookRoot,
            STATISTICS_FILE_NAME,
            ListSerializer(ReadingStatistics.serializer()),
            statistics.deduplicateReadingStatistics(),
            createDirectory = false,
        )
    }

    suspend fun loadMangaTextStatistics(bookRoot: File): List<MangaTextStatistic>? =
        loadJson(ListSerializer(MangaTextStatistic.serializer()), bookRoot.resolve(MANGA_STATISTICS_FILE_NAME))
            ?.deduplicateMangaTextStatistics()

    /**
     * [bookRoot]'s statistics; null when there is no file. Unlike [loadStatistics] it tells a
     * damaged file ([DamagedSidecarException]) from one that cannot be read now (IOException).
     */
    suspend fun readStatisticsStrictly(bookRoot: File): List<ReadingStatistics>? =
        readStrictly(ListSerializer(ReadingStatistics.serializer()), bookRoot.resolve(STATISTICS_FILE_NAME))
            ?.deduplicateReadingStatistics()

    suspend fun readMangaTextStatisticsStrictly(bookRoot: File): List<MangaTextStatistic>? =
        readStrictly(ListSerializer(MangaTextStatistic.serializer()), bookRoot.resolve(MANGA_STATISTICS_FILE_NAME))
            ?.deduplicateMangaTextStatistics()

    private suspend fun <T> readStrictly(serializer: KSerializer<T>, file: File): T? = withContext(ioDispatcher) {
        if (!file.isFile) return@withContext null
        val text = file.readText()
        try {
            json.decodeFromString(serializer, text)
        } catch (error: IllegalArgumentException) {
            // kotlinx.serialization's decoding failures (SerializationException) are these.
            throw DamagedSidecarException(file, error)
        }
    }

    suspend fun saveMangaTextStatistics(bookRoot: File, statistics: List<MangaTextStatistic>) {
        saveJson(
            bookRoot,
            MANGA_STATISTICS_FILE_NAME,
            ListSerializer(MangaTextStatistic.serializer()),
            statistics.deduplicateMangaTextStatistics(),
            createDirectory = false,
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

    private suspend fun <T> saveJson(
        bookRoot: File,
        fileName: String,
        serializer: KSerializer<T>,
        value: T,
        createDirectory: Boolean = true,
    ) = withContext(ioDispatcher) {
        if (createDirectory) bookRoot.mkdirs() else if (!bookRoot.isDirectory) throw java.io.FileNotFoundException(bookRoot.path)
        val target = bookRoot.resolve(fileName)
        val text = json.encodeToString(serializer, value)
        writeSidecarAtomically(target, text)
    }
}

/** A sidecar file whose content cannot be decoded (as opposed to one that cannot be read now). */
internal class DamagedSidecarException(val file: File, cause: Throwable) : IOException("Damaged ${file.path}", cause)

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

/** HTTP sync's per-book statistics exchange state; its presence means the book's statistics were synced at least once. */
internal const val STATISTICS_SYNC_STATE_FILE_NAME = ".http_sync_statistics.json"
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
