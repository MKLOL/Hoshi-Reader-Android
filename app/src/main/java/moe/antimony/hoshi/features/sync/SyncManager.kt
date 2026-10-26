package moe.antimony.hoshi.features.sync

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import moe.antimony.hoshi.epub.BookEntry
import moe.antimony.hoshi.epub.BookRepository
import moe.antimony.hoshi.epub.Bookmark
import moe.antimony.hoshi.epub.ReadingStatistics
import moe.antimony.hoshi.epub.collapsedByDay
import moe.antimony.hoshi.epub.SasayakiPlaybackData

class SyncManager(
    private val bookRepository: BookRepository,
    private val drive: DriveSyncDataSource,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val nowUnixMillis: () -> Long = { System.currentTimeMillis() },
) {
    // Normal and sentence readers can overlap while switching modes. Their TTU exchanges
    // must share one lock, otherwise an old export can replace a new import mid-read.
    private val bookSyncLocks = ConcurrentHashMap<String, Mutex>()

    suspend fun syncBook(
        entry: BookEntry,
        direction: SyncDirection?,
        syncStats: Boolean,
        statsSyncMode: StatisticsSyncMode,
        syncAudioBook: Boolean,
        importOnly: Boolean = false,
        /**
         * Called when this sync's import changed a day's reading time or characters here,
         * whether or not the position moved. An open reader then rebuilds on the new totals.
         */
        onStatisticsImported: () -> Unit = {},
    ): SyncResult = bookSyncLocks.computeIfAbsent(entry.root.canonicalPath) { Mutex() }.withLock {
        try {
            syncBookOnce(entry, direction, syncStats, statsSyncMode, syncAudioBook, importOnly, onStatisticsImported)
        } catch (error: GoogleDriveApiException) {
            if (!error.isStaleCacheError) throw error
            drive.clearCache()
            syncBookOnce(entry, direction, syncStats, statsSyncMode, syncAudioBook, importOnly, onStatisticsImported)
        }
    }

    private suspend fun syncBookOnce(
        entry: BookEntry,
        direction: SyncDirection?,
        syncStats: Boolean,
        statsSyncMode: StatisticsSyncMode,
        syncAudioBook: Boolean,
        importOnly: Boolean,
        onStatisticsImported: () -> Unit,
    ): SyncResult {
        val title = entry.metadata.title ?: return SyncResult.Skipped
        entry.metadata.folder ?: return SyncResult.Skipped
        val displayTitle = entry.displayTitle

        val rootFolderId = drive.findRootFolder()
        val driveFolderId = drive.ensureBookFolder(
            bookTitle = title,
            rootFolderId = rootFolderId,
            coverImageDataProvider = {
                withContext(ioDispatcher) {
                    bookRepository.coverFile(entry)?.takeIf { it.isFile }?.readBytes()
                }
            },
        )
        val localBookmark = bookRepository.loadBookmark(entry.root)
        val syncFiles = drive.listSyncFiles(driveFolderId)
        val syncDirection = direction ?: TtuSyncRules.determineDirection(localBookmark, syncFiles.progress)

        val progressFileId = syncFiles.progress?.id
        val statsFileId = if (syncStats) syncFiles.statistics?.id else null
        val audioBookFileId = if (syncAudioBook) syncFiles.audioBook?.id else null

        if (syncDirection == SyncDirection.Synced || (importOnly && syncDirection != SyncDirection.ImportFromTtu)) {
            // Reading time can change without a bookmark move. Refresh statistics on open
            // even when progress is equal or this device has the newer position; neither
            // case grants an import permission to replace that position or audio settings.
            val publishMergedToFolder = driveFolderId.takeIf {
                !importOnly && syncDirection == SyncDirection.Synced && statsSyncMode == StatisticsSyncMode.Merge
            }
            if (syncStats && (statsFileId != null || publishMergedToFolder != null)) {
                reconcileStatistics(entry, statsFileId, statsSyncMode, onStatisticsImported, publishMergedToFolder)
            }
            return if (syncDirection == SyncDirection.Synced) SyncResult.Synced(displayTitle) else SyncResult.Skipped
        }

        return when (syncDirection) {
            SyncDirection.ImportFromTtu -> importFromTtu(
                title = displayTitle,
                entry = entry,
                progressFileId = progressFileId,
                statsFileId = statsFileId,
                audioBookFileId = audioBookFileId,
                syncStats = syncStats,
                statsSyncMode = statsSyncMode,
                syncAudioBook = syncAudioBook,
                // Chosen by the reader rather than by comparing timestamps.
                explicit = direction == SyncDirection.ImportFromTtu,
                onStatisticsImported = onStatisticsImported,
            )
            SyncDirection.ExportToTtu -> exportToTtu(
                title = displayTitle,
                entry = entry,
                driveFolderId = driveFolderId,
                progressFileId = progressFileId,
                statsFileId = statsFileId,
                audioBookFileId = audioBookFileId,
                localBookmark = localBookmark,
                syncStats = syncStats,
                statsSyncMode = statsSyncMode,
                syncAudioBook = syncAudioBook,
            )
            SyncDirection.Synced -> SyncResult.Synced(displayTitle)
        }
    }

    private suspend fun importFromTtu(
        title: String,
        entry: BookEntry,
        progressFileId: String?,
        statsFileId: String?,
        audioBookFileId: String?,
        syncStats: Boolean,
        statsSyncMode: StatisticsSyncMode,
        syncAudioBook: Boolean,
        explicit: Boolean,
        onStatisticsImported: () -> Unit,
    ): SyncResult {
        val progress = progressFileId?.let { drive.getProgressFile(it) } ?: return SyncResult.Skipped
        importProgress(entry, progress, explicit)
        if (syncStats) reconcileStatistics(entry, statsFileId, statsSyncMode, onStatisticsImported)
        if (syncAudioBook) {
            audioBookFileId?.let { fileId ->
                importAudioBook(entry, drive.getAudioBookFile(fileId))
            }
        }
        return SyncResult.Imported(title, progress.exploredCharCount)
    }

    private suspend fun reconcileStatistics(
        entry: BookEntry,
        statsFileId: String?,
        statsSyncMode: StatisticsSyncMode,
        onStatisticsImported: () -> Unit,
        publishMergedToFolder: String? = null,
    ) {
        // ッツ keeps one device-less entry per day, i.e. every device's reading added up.
        // The imported remainder has a shared origin, so several devices cannot count it twice.
        // The download comes first: the merge reads this book's days under its lock.
        val remoteStats = statsFileId?.let { drive.getStatsFile(it) }.orEmpty()
        val applied = bookRepository.mergeDayTotals(
            entry.root,
            // Replace without a ッツ file has nothing to replace with.
            replaceOtherDays = statsSyncMode == StatisticsSyncMode.Replace && statsFileId != null,
        ) { localDays -> TtuSyncRules.mergeStatistics(localDays, remoteStats, statsSyncMode) }
        // Only different day totals are new statistics for a reader.
        if (applied.totalsChanged) onStatisticsImported()
        val merged = applied.totals
        if (publishMergedToFolder != null && merged.isNotEmpty() &&
            merged.sortedBy { it.dateKey } != remoteStats.sortedBy { it.dateKey }) {
            // The checkpoint above is durable before this collapsed total becomes visible
            // to another device. An import-only refresh never supplies a publish folder.
            drive.updateStatsFile(publishMergedToFolder, statsFileId, merged)
        }
    }

    private suspend fun exportToTtu(
        title: String,
        entry: BookEntry,
        driveFolderId: String,
        progressFileId: String?,
        statsFileId: String?,
        audioBookFileId: String?,
        localBookmark: Bookmark?,
        syncStats: Boolean,
        statsSyncMode: StatisticsSyncMode,
        syncAudioBook: Boolean,
    ): SyncResult {
        val bookmark = localBookmark ?: return if (syncStats && exportStatistics(
            entry, driveFolderId, statsFileId, statsSyncMode, requireLocalReading = true,
        )) SyncResult.Synced(title) else SyncResult.Skipped
        val remoteProgress = progressFileId?.let { drive.getProgressFile(it) }
        exportProgress(entry, driveFolderId, progressFileId, bookmark, remoteProgress)

        if (syncStats) exportStatistics(entry, driveFolderId, statsFileId, statsSyncMode)

        if (syncAudioBook) {
            val playback = bookRepository.loadSasayakiPlayback(entry.root)
            if (playback != null) {
                drive.updateAudioBookFile(
                    folderId = driveFolderId,
                    fileId = audioBookFileId,
                    audioBook = TtuAudioBook(
                        title = title,
                        playbackPosition = playback.lastPosition,
                        lastAudioBookModified = nowUnixMillis(),
                    ),
                )
            }
        }
        return SyncResult.Exported(title, bookmark.characterCount)
    }

    private suspend fun exportStatistics(
        entry: BookEntry,
        driveFolderId: String,
        statsFileId: String?,
        statsSyncMode: StatisticsSyncMode,
        requireLocalReading: Boolean = false,
    ): Boolean {
        val localStats = bookRepository.loadStatistics(entry.root).collapsedByDay()
        // A fresh reader can accumulate time before its first navigation. That time may
        // sync independently, without inventing a bookmark or exporting unrelated audio.
        if (requireLocalReading && localStats.none { it.readingTime > 0.0 || it.charactersRead > 0 }) return false
        val remoteStats = statsFileId?.let { drive.getStatsFile(it) }.orEmpty()
        // Share the device counters included in this collapsed export before another
        // install imports it. Its independent import can then absorb them on HTTP sync. The
        // export merges the days read under the book lock, so a save made during the download
        // is either in it or stays outside the counters it includes.
        val statsToExport = bookRepository.mergeDayTotals(entry.root, replaceOtherDays = false) { localDays ->
            TtuSyncRules.mergeStatistics(remoteStats, localDays, statsSyncMode)
        }.totals
        if (statsToExport.isEmpty()) return false
        if (statsToExport.sortedBy { it.dateKey } != remoteStats.sortedBy { it.dateKey }) {
            drive.updateStatsFile(driveFolderId, statsFileId, statsToExport)
        }
        return true
    }

    private suspend fun importProgress(entry: BookEntry, progress: TtuProgress, explicit: Boolean) {
        val bookInfo = bookRepository.loadBookInfo(entry.root) ?: return
        val resolved = bookInfo.resolveTtuCharacterPosition(progress.exploredCharCount)
        val bookmark = Bookmark(
            chapterIndex = resolved?.spineIndex ?: 0,
            progress = resolved?.progress ?: 0.0,
            characterCount = progress.exploredCharCount,
            lastModified = TtuSyncRules.unixMillisToAppleReferenceSeconds(progress.lastBookmarkModified),
        )
        // An explicit Import applies ッツ's position even when it is older, as on iOS. The
        // automatic import only ever adopts a newer one.
        if (explicit) {
            bookRepository.importReadingPosition(entry.root, bookmark, action = "ttuImport")
        } else {
            bookRepository.saveBookmark(entry.root, bookmark)
        }
    }

    private suspend fun exportProgress(
        entry: BookEntry,
        driveFolderId: String,
        progressFileId: String?,
        localBookmark: Bookmark,
        remoteProgress: TtuProgress?,
    ) {
        val bookInfo = bookRepository.loadBookInfo(entry.root) ?: return
        val lastModified = localBookmark.lastModified ?: return
        val unixTimestamp = TtuSyncRules.appleReferenceSecondsToUnixMillis(lastModified)
        val progress = TtuProgress(
            dataId = remoteProgress?.dataId ?: 0,
            exploredCharCount = localBookmark.characterCount,
            progress = if (bookInfo.characterCount > 0) {
                localBookmark.characterCount.toDouble() / bookInfo.characterCount.toDouble()
            } else {
                0.0
            },
            lastBookmarkModified = unixTimestamp,
        )
        drive.updateProgressFile(driveFolderId, progressFileId, progress)
        bookRepository.saveBookmark(
            entry.root,
            localBookmark.copy(lastModified = TtuSyncRules.unixMillisToAppleReferenceSeconds(unixTimestamp)),
        )
    }

    private suspend fun importAudioBook(entry: BookEntry, audioBook: TtuAudioBook) {
        val playback = bookRepository.loadSasayakiPlayback(entry.root)
            ?: SasayakiPlaybackData(lastPosition = 0.0)
        bookRepository.saveSasayakiPlayback(
            entry.root,
            playback.copy(lastPosition = audioBook.playbackPosition),
        )
    }
}
