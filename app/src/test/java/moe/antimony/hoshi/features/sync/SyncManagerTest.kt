package moe.antimony.hoshi.features.sync

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import moe.antimony.hoshi.epub.BookEntry
import moe.antimony.hoshi.epub.BookInfo
import moe.antimony.hoshi.epub.BookMetadata
import moe.antimony.hoshi.epub.BookRepository
import moe.antimony.hoshi.epub.Bookmark
import moe.antimony.hoshi.epub.ReadingStatistics
import moe.antimony.hoshi.epub.readingTotals
import moe.antimony.hoshi.epub.DeviceIdentity
import moe.antimony.hoshi.epub.SasayakiPlaybackData
import moe.antimony.hoshi.epub.TTU_STATISTICS_DEVICE_ID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SyncManagerTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun importFromTtuUpdatesProgressStatisticsAndOnlySasayakiPosition() = runBlocking {
        val repository = BookRepository(tempFolder.root)
        val entry = repository.createEntry()
        repository.saveBookmark(entry.root, Bookmark(0, 0.1, 10, TtuSyncRules.unixMillisToAppleReferenceSeconds(1_000)))
        repository.saveStatistics(
            entry.root,
            listOf(ReadingStatistics(title = "Title", dateKey = "2026-05-12", charactersRead = 100, lastStatisticModified = 100)),
        )
        repository.saveSasayakiPlayback(
            entry.root,
            SasayakiPlaybackData(lastPosition = 1.0, delay = 0.2, rate = 1.5f, audioUri = "content://audio"),
        )
        val drive = FakeDriveSyncDataSource(
            progress = TtuProgress(7, 150, 0.5, 2_000),
            statistics = listOf(
                ReadingStatistics(title = "Title", dateKey = "2026-05-12", charactersRead = 120, lastStatisticModified = 200),
            ),
            audioBook = TtuAudioBook("Title", 88.5, 2_000),
        )
        val manager = SyncManager(repository, drive, nowUnixMillis = { 9_999 })

        val result = manager.syncBook(
            entry = entry,
            direction = SyncDirection.ImportFromTtu,
            syncStats = true,
            statsSyncMode = StatisticsSyncMode.Merge,
            syncAudioBook = true,
        )

        assertEquals(SyncResult.Imported("Title", 150), result)
        assertEquals(
            Bookmark(1, 0.5, 150, TtuSyncRules.unixMillisToAppleReferenceSeconds(2_000)),
            repository.loadBookmark(entry.root),
        )
        assertEquals(120, repository.loadStatistics(entry.root).readingTotals().charactersRead)
        assertEquals(
            SasayakiPlaybackData(lastPosition = 88.5, delay = 0.2, rate = 1.5f, audioUri = "content://audio"),
            repository.loadSasayakiPlayback(entry.root),
        )
    }

    @Test
    fun importingCompletedBookKeepsFullCompletionIncludingTrailingEmptySpines() = runBlocking {
        val repository = BookRepository(tempFolder.root)
        val entry = repository.createEntry()
        val info = repository.loadBookInfo(entry.root)!!
        repository.saveBookInfo(entry.root, info.copy(chapterInfo = info.chapterInfo +
            ("credits" to BookInfo.ChapterInfo(spineIndex = 2, currentTotal = info.characterCount, chapterCount = 0))))
        val drive = FakeDriveSyncDataSource(progress = TtuProgress(7, info.characterCount, 1.0, 2_000))
        SyncManager(repository, drive).syncBook(
            entry, SyncDirection.ImportFromTtu, syncStats = false,
            statsSyncMode = StatisticsSyncMode.Merge, syncAudioBook = false,
        )
        val bookmark = repository.loadBookmark(entry.root)!!
        assertEquals(2, bookmark.chapterIndex)
        assertEquals(1.0, bookmark.progress, 0.0)
        assertEquals(info.characterCount, bookmark.characterCount)
    }

    @Test
    fun importFromTtuPreservesDeviceReadingsAndIsStableAcrossRepeatedImports() = runBlocking {
        val phone = DeviceIdentity(id = "phone-id", name = "Pixel 8")
        val tablet = DeviceIdentity(id = "tablet-id", name = "Galaxy Tab")
        val repository = BookRepository(tempFolder.root, deviceIdentity = phone)
        val entry = repository.createEntry()
        repository.saveBookmark(entry.root, Bookmark(0, 0.1, 10, TtuSyncRules.unixMillisToAppleReferenceSeconds(1_000)))
        repository.saveStatistics(
            entry.root,
            listOf(
                ReadingStatistics(title = "Title", dateKey = "2026-05-12", charactersRead = 100, readingTime = 600.0, lastStatisticModified = 5, deviceId = phone.id, deviceName = phone.name),
                ReadingStatistics(title = "Title", dateKey = "2026-05-12", charactersRead = 40, readingTime = 300.0, lastStatisticModified = 9, deviceId = tablet.id, deviceName = tablet.name),
            ),
        )
        // ッツ holds the day as one total (what an export wrote) that has since grown on ッツ's side.
        val drive = FakeDriveSyncDataSource(
            progress = TtuProgress(7, 150, 0.5, 2_000),
            statistics = listOf(ReadingStatistics(title = "Title", dateKey = "2026-05-12", charactersRead = 160, readingTime = 1000.0, lastStatisticModified = 20)),
        )
        val manager = SyncManager(repository, drive, nowUnixMillis = { 9_999 })
        val sync = suspend {
            manager.syncBook(entry = entry, direction = SyncDirection.ImportFromTtu, syncStats = true, statsSyncMode = StatisticsSyncMode.Merge, syncAudioBook = false)
        }

        sync()
        val once = repository.loadStatistics(entry.root)
        sync()
        val twice = repository.loadStatistics(entry.root)

        assertEquals(600.0, once.single { it.deviceId == phone.id }.readingTime, 0.0)
        assertEquals(300.0, once.single { it.deviceId == tablet.id }.readingTime, 0.0)
        assertEquals(1000.0, once.readingTotals().readingTime, 0.0)
        assertEquals("a second import of the same file must not grow the day", once.toSet(), twice.toSet())
    }

    @Test
    fun replacingFromAnExplicitlyEmptyStatsFileClearsOnlyImportedHistory() = runBlocking {
        for ((mode, hasStatisticsFile, expectedCharacters) in listOf(
            Triple(StatisticsSyncMode.Replace, true, 10),
            Triple(StatisticsSyncMode.Replace, false, 300),
            Triple(StatisticsSyncMode.Merge, true, 300),
        )) {
            val phone = DeviceIdentity("phone", "Phone")
            val repository = BookRepository(tempFolder.newFolder(), deviceIdentity = phone)
            val entry = repository.createEntry()
            val physicalReading = ReadingStatistics(
                "Title", "2026-10-24", charactersRead = 10, readingTime = 10.0,
                lastStatisticModified = 1, deviceId = phone.id, deviceName = phone.name,
            )
            repository.saveStatistics(entry.root, listOf(physicalReading))
            repository.applyDayTotals(entry.root, listOf(physicalReading.copy(
                charactersRead = 300, readingTime = 300.0, lastStatisticModified = 2,
                deviceId = null, deviceName = null,
            )), false)
            val drive = FakeDriveSyncDataSource(
                progress = TtuProgress(7, 150, 0.5, 2_000),
                statistics = if (hasStatisticsFile) emptyList() else null,
            )

            SyncManager(repository, drive).syncBook(
                entry, SyncDirection.ImportFromTtu, syncStats = true,
                statsSyncMode = mode, syncAudioBook = false,
            )

            val stored = repository.loadStatistics(entry.root)
            assertEquals(expectedCharacters, stored.readingTotals().charactersRead)
            assertEquals(physicalReading, stored.single { it.deviceId == phone.id })
            if (expectedCharacters == 10) {
                // A zero imported checkpoint carries the removal to other HTTP devices.
                assertEquals(0, stored.single { it.deviceId == TTU_STATISTICS_DEVICE_ID }.charactersRead)
                val once = stored.toSet()
                repository.applyDayTotals(entry.root, emptyList(), replaceOtherDays = true)
                assertEquals(once, repository.loadStatistics(entry.root).toSet())
            }
        }
    }

    @Test
    fun exportToTtuUploadsIosCompatibleSidecarsAndRoundsLocalBookmarkTimestamp() = runBlocking {
        val repository = BookRepository(tempFolder.root)
        val entry = repository.createEntry()
        repository.saveBookmark(entry.root, Bookmark(0, 0.5, 100, TtuSyncRules.unixMillisToAppleReferenceSeconds(4_321)))
        repository.saveStatistics(
            entry.root,
            listOf(ReadingStatistics(title = "Title", dateKey = "2026-05-12", charactersRead = 220, lastStatisticModified = 200)),
        )
        repository.saveSasayakiPlayback(entry.root, SasayakiPlaybackData(lastPosition = 45.0))
        val remoteDataId = -1_959_131_391_809_764L
        val drive = FakeDriveSyncDataSource(
            progress = TtuProgress(remoteDataId, 80, 0.4, 3_000),
            statistics = listOf(
                ReadingStatistics(title = "Title", dateKey = "2026-05-12", charactersRead = 100, lastStatisticModified = 100),
            ),
        )
        val manager = SyncManager(repository, drive, nowUnixMillis = { 9_999 })

        val result = manager.syncBook(
            entry = entry,
            direction = SyncDirection.ExportToTtu,
            syncStats = true,
            statsSyncMode = StatisticsSyncMode.Merge,
            syncAudioBook = true,
        )

        assertEquals(SyncResult.Exported("Title", 100), result)
        assertEquals(TtuProgress(remoteDataId, 100, 0.5, 4_321), drive.updatedProgress)
        assertEquals(220, drive.updatedStatistics.single().charactersRead)
        assertEquals(TtuAudioBook("Title", 45.0, 9_999), drive.updatedAudioBook)
        assertEquals(4_321, TtuSyncRules.appleReferenceSecondsToUnixMillis(repository.loadBookmark(entry.root)!!.lastModified!!))
    }

    @Test
    fun simultaneousModesSerializeOneBooksExchangeWhileAnotherBookCanSync() = runBlocking {
        val repository = BookRepository(tempFolder.root)
        val entry = repository.createEntry()
        val otherRoot = repository.createBookDirectory("other-book")
        val other = entry.copy(root = otherRoot, metadata = entry.metadata.copy(id = "other", folder = "other-book"))
        val firstStarted = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        var starts = 0
        val drive = FakeDriveSyncDataSource(beforeFindRoot = {
            starts += 1
            if (starts == 1) {
                firstStarted.complete(Unit)
                releaseFirst.await()
            }
        })
        val manager = SyncManager(repository, drive)
        suspend fun sync(book: BookEntry) = manager.syncBook(
            book, null, false, StatisticsSyncMode.Merge, false,
        )
        val first = launch { sync(entry) }
        firstStarted.await()
        val second = launch { sync(entry) }
        val independent = launch { sync(other) }
        yield()
        independent.join()
        assertEquals("only the first mode and the unrelated book can enter Drive", 2, starts)
        releaseFirst.complete(Unit)
        first.join()
        second.join()
        assertEquals(3, starts)
    }

    @Test
    fun staleDriveCacheIsClearedAndSyncRetriesOnce() = runBlocking {
        val repository = BookRepository(tempFolder.root)
        val entry = repository.createEntry()
        val drive = FakeDriveSyncDataSource(staleOnFirstList = true)
        val manager = SyncManager(repository, drive)

        val result = manager.syncBook(
            entry = entry,
            direction = null,
            syncStats = false,
            statsSyncMode = StatisticsSyncMode.Merge,
            syncAudioBook = false,
        )

        assertEquals(SyncResult.Synced("Title"), result)
        assertEquals(1, drive.clearCacheCalls)
        assertEquals(2, drive.listSyncFilesCalls)
    }

    private suspend fun BookRepository.createEntry(): BookEntry {
        val root = createBookDirectory("book")
        val metadata = BookMetadata(
            id = "book-id",
            title = "Title",
            cover = null,
            folder = root.name,
            lastAccess = 0.0,
        )
        saveMetadata(root, metadata)
        saveBookInfo(
            root,
            BookInfo(
                characterCount = 200,
                chapterInfo = mapOf(
                    "c0" to BookInfo.ChapterInfo(spineIndex = 0, currentTotal = 0, chapterCount = 100),
                    "c1" to BookInfo.ChapterInfo(spineIndex = 1, currentTotal = 100, chapterCount = 100),
                ),
            ),
        )
        return BookEntry(root, metadata)
    }
}

private class FakeDriveSyncDataSource(
    progress: TtuProgress? = null,
    statistics: List<ReadingStatistics>? = null,
    audioBook: TtuAudioBook? = null,
    private val staleOnFirstList: Boolean = false,
    private val beforeFindRoot: suspend () -> Unit = {},
) : DriveSyncDataSource {
    private val progressFile = progress?.let { DriveFile("progress", TtuSyncRules.progressFileName(it)) }
    private val statisticsFile = statistics?.let { DriveFile("statistics", TtuSyncRules.statisticsFileName(it)) }
    private val audioBookFile = audioBook?.let { DriveFile("audio", TtuSyncRules.audioBookFileName(it)) }
    private val progressById = progressFile?.let { mapOf(it.id to requireNotNull(progress)) }.orEmpty()
    private val statisticsById = statisticsFile?.let { mapOf(it.id to requireNotNull(statistics)) }.orEmpty()
    private val audioBookById = audioBookFile?.let { mapOf(it.id to requireNotNull(audioBook)) }.orEmpty()

    var clearCacheCalls = 0
    var listSyncFilesCalls = 0
    var updatedProgress: TtuProgress? = null
    var updatedStatistics: List<ReadingStatistics> = emptyList()
    var updatedAudioBook: TtuAudioBook? = null

    override suspend fun findRootFolder(): String { beforeFindRoot(); return "root" }

    override suspend fun ensureBookFolder(
        bookTitle: String,
        rootFolderId: String,
        coverImageDataProvider: (suspend () -> ByteArray?)?,
    ): String {
        assertEquals("Title", bookTitle)
        return "book-folder"
    }

    override suspend fun listSyncFiles(folderId: String): DriveSyncFiles {
        assertEquals("book-folder", folderId)
        listSyncFilesCalls += 1
        if (staleOnFirstList && listSyncFilesCalls == 1) {
            throw GoogleDriveApiException("Not found", statusCode = 404)
        }
        return DriveSyncFiles(
            progress = progressFile,
            statistics = statisticsFile,
            audioBook = audioBookFile,
        )
    }

    override suspend fun getProgressFile(fileId: String): TtuProgress = progressById.getValue(fileId)

    override suspend fun getStatsFile(fileId: String): List<ReadingStatistics> = statisticsById.getValue(fileId)

    override suspend fun getAudioBookFile(fileId: String): TtuAudioBook = audioBookById.getValue(fileId)

    override suspend fun updateProgressFile(folderId: String, fileId: String?, progress: TtuProgress) {
        assertEquals("book-folder", folderId)
        assertTrue(fileId == null || fileId == progressFile?.id)
        updatedProgress = progress
    }

    override suspend fun updateStatsFile(folderId: String, fileId: String?, stats: List<ReadingStatistics>) {
        assertEquals("book-folder", folderId)
        assertTrue(fileId == null || fileId == statisticsFile?.id)
        updatedStatistics = stats
    }

    override suspend fun updateAudioBookFile(folderId: String, fileId: String?, audioBook: TtuAudioBook) {
        assertEquals("book-folder", folderId)
        assertTrue(fileId == null || fileId == audioBookFile?.id)
        updatedAudioBook = audioBook
    }

    override fun clearCache() {
        clearCacheCalls += 1
    }
}
