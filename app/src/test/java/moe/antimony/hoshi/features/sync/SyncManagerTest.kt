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
import moe.antimony.hoshi.epub.collapsedByDay
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
    fun openingImportsStatisticsWithoutReplacingAnEqualOrNewerLocalBookmark() = runBlocking {
        for (localBookmarkMillis in listOf(2_000L, 4_000L)) for (syncStats in listOf(true, false)) {
            val repository = BookRepository(tempFolder.newFolder())
            val entry = repository.createEntry()
            val localBookmark = Bookmark(0, 0.5, 100, TtuSyncRules.unixMillisToAppleReferenceSeconds(localBookmarkMillis))
            repository.saveBookmark(entry.root, localBookmark)
            val localStatistic = ReadingStatistics(
                "Title", "2026-10-24", charactersRead = 100, readingTime = 100.0,
                lastStatisticModified = 1_000,
            )
            repository.saveStatistics(entry.root, listOf(localStatistic))
            val localPlayback = SasayakiPlaybackData(lastPosition = 10.0, rate = 1.5f, audioUri = "content://audio")
            repository.saveSasayakiPlayback(entry.root, localPlayback)
            val drive = FakeDriveSyncDataSource(
                progress = TtuProgress(7, 100, 0.5, 2_000),
                // The same displayed page acquired another minute of reading time on TTU.
                statistics = listOf(localStatistic.copy(readingTime = 160.0, lastStatisticModified = 3_000)),
                audioBook = TtuAudioBook("Title", 88.0, 3_000),
            )

            val result = SyncManager(repository, drive).syncBook(
                entry, direction = null, syncStats = syncStats,
                statsSyncMode = StatisticsSyncMode.Merge, syncAudioBook = true, importOnly = true,
            )

            assertEquals(if (localBookmarkMillis == 2_000L) SyncResult.Synced("Title") else SyncResult.Skipped, result)
            assertEquals(localBookmark, repository.loadBookmark(entry.root))
            assertEquals(localPlayback, repository.loadSasayakiPlayback(entry.root))
            assertEquals(if (syncStats) 160.0 else 100.0, repository.loadStatistics(entry.root).readingTotals().readingTime, 0.0)
            assertEquals(if (syncStats) 1 else 0, drive.getStatsFileCalls)
            assertEquals(0, drive.getProgressFileCalls)
            assertEquals(0, drive.getAudioBookFileCalls)
            assertEquals(null, drive.updatedProgress)
            assertEquals(emptyList<ReadingStatistics>(), drive.updatedStatistics)
            assertEquals(null, drive.updatedAudioBook)
        }
    }

    @Test
    fun equalBookmarkMergeReconcilesStatisticsInBothDirectionsAndCheckpointsBeforePublishing() = runBlocking {
        for (localNewer in listOf(true, false)) for (importOnly in listOf(true, false)) {
            val repository = BookRepository(tempFolder.newFolder(), deviceIdentity = DeviceIdentity("phone", "Phone"))
            val entry = repository.createEntry()
            val bookmark = Bookmark(0, 0.5, 100, TtuSyncRules.unixMillisToAppleReferenceSeconds(2_000))
            repository.saveBookmark(entry.root, bookmark)
            val older = ReadingStatistics("Title", "2026-10-24", 100, 100.0, lastStatisticModified = 1_000)
            val newer = older.copy(charactersRead = 200, readingTime = 200.0, lastStatisticModified = 3_000)
            val local = if (localNewer) newer else older
            repository.saveStatistics(entry.root, listOf(local.copy(deviceId = "phone", deviceName = "Phone")))
            var checkpointVerified = false
            val drive = FakeDriveSyncDataSource(
                progress = TtuProgress(7, 100, 0.5, 2_000),
                statistics = listOf(if (localNewer) older else newer),
                audioBook = TtuAudioBook("Title", 88.0, 3_000),
                beforeUpdateStats = { published ->
                    val saved = repository.loadStatistics(entry.root)
                    assertEquals(published, saved.collapsedByDay())
                    val baseline = saved.single { it.deviceId == TTU_STATISTICS_DEVICE_ID }.importedBaseline!!
                    assertEquals(local.charactersRead, baseline.includedDevices.getValue("phone").charactersRead)
                    checkpointVerified = true
                },
            )

            val result = SyncManager(repository, drive).syncBook(
                entry, direction = null, syncStats = true,
                statsSyncMode = StatisticsSyncMode.Merge, syncAudioBook = true, importOnly = importOnly,
            )

            assertEquals(SyncResult.Synced("Title"), result)
            assertEquals(bookmark, repository.loadBookmark(entry.root))
            assertEquals(200.0, repository.loadStatistics(entry.root).readingTotals().readingTime, 0.0)
            val publishes = localNewer && !importOnly
            assertEquals(if (publishes) 1 else 0, drive.updateStatsFileCalls)
            assertEquals(publishes, checkpointVerified)
            if (publishes) assertEquals(200.0, drive.updatedStatistics.single().readingTime, 0.0)
            assertEquals(1, drive.getStatsFileCalls)
            assertEquals(0, drive.getProgressFileCalls)
            assertEquals(0, drive.getAudioBookFileCalls)
            assertEquals(null, drive.updatedProgress)
            assertEquals(null, drive.updatedAudioBook)
        }
    }

    @Test
    fun equalBookmarkMergeCreatesAMissingStatisticsFileOnlyForAFullSyncWithLocalReading() = runBlocking {
        for (hasReading in listOf(true, false)) for (syncStats in listOf(true, false)) for (importOnly in listOf(true, false)) {
            val repository = BookRepository(tempFolder.newFolder())
            val entry = repository.createEntry()
            val bookmark = Bookmark(0, 0.5, 100, TtuSyncRules.unixMillisToAppleReferenceSeconds(2_000))
            repository.saveBookmark(entry.root, bookmark)
            if (hasReading) repository.saveStatistics(entry.root, listOf(ReadingStatistics(
                "Title", "2026-10-24", 100, 100.0, lastStatisticModified = 3_000,
            )))
            val drive = FakeDriveSyncDataSource(progress = TtuProgress(7, 100, 0.5, 2_000))

            SyncManager(repository, drive).syncBook(
                entry, direction = null, syncStats = syncStats,
                statsSyncMode = StatisticsSyncMode.Merge, syncAudioBook = false, importOnly = importOnly,
            )

            assertEquals(if (hasReading && syncStats && !importOnly) 1 else 0, drive.updateStatsFileCalls)
            assertEquals(0, drive.getStatsFileCalls)
            assertEquals(bookmark, repository.loadBookmark(entry.root))
            assertEquals(null, drive.updatedProgress)
        }
    }

    @Test
    fun equalBookmarkMergeDoesNotRepublishTheSameDaysInAnotherOrder() = runBlocking {
        val repository = BookRepository(tempFolder.newFolder())
        val entry = repository.createEntry()
        val bookmark = Bookmark(0, 0.5, 100, TtuSyncRules.unixMillisToAppleReferenceSeconds(2_000))
        repository.saveBookmark(entry.root, bookmark)
        val days = listOf(
            ReadingStatistics("Title", "2026-10-23", 100, 100.0, lastStatisticModified = 1_000, lastReadingSpeed = 3_600),
            ReadingStatistics("Title", "2026-10-24", 200, 200.0, lastStatisticModified = 3_000, lastReadingSpeed = 3_600),
        )
        repository.saveStatistics(entry.root, days)
        val drive = FakeDriveSyncDataSource(progress = TtuProgress(7, 100, 0.5, 2_000), statistics = days.reversed())

        SyncManager(repository, drive).syncBook(
            entry, direction = null, syncStats = true,
            statsSyncMode = StatisticsSyncMode.Merge, syncAudioBook = false,
        )

        assertEquals(1, drive.getStatsFileCalls)
        assertEquals(0, drive.updateStatsFileCalls)
    }

    @Test
    fun openingWithAnEqualBookmarkCanReplaceExplicitlyEmptyImportedStatistics() = runBlocking {
        for (importOnly in listOf(true, false)) {
            val repository = BookRepository(tempFolder.newFolder(), deviceIdentity = DeviceIdentity("phone", "Phone"))
            val entry = repository.createEntry()
            val bookmark = Bookmark(0, 0.5, 100, TtuSyncRules.unixMillisToAppleReferenceSeconds(2_000))
            repository.saveBookmark(entry.root, bookmark)
            val physicalReading = ReadingStatistics(
                "Title", "2026-10-24", charactersRead = 10, readingTime = 10.0,
                lastStatisticModified = 1, deviceId = "phone", deviceName = "Phone",
            )
            repository.saveStatistics(entry.root, listOf(physicalReading))
            repository.applyDayTotals(entry.root, listOf(physicalReading.copy(
                charactersRead = 100, readingTime = 100.0, lastStatisticModified = 2,
                deviceId = null, deviceName = null,
            )), false)
            val drive = FakeDriveSyncDataSource(
                progress = TtuProgress(7, 100, 0.5, 2_000),
                statistics = emptyList(),
            )

            val result = SyncManager(repository, drive).syncBook(
                entry, direction = null, syncStats = true,
                statsSyncMode = StatisticsSyncMode.Replace, syncAudioBook = false, importOnly = importOnly,
            )

            assertEquals(SyncResult.Synced("Title"), result)
            assertEquals(bookmark, repository.loadBookmark(entry.root))
            assertEquals(10.0, repository.loadStatistics(entry.root).readingTotals().readingTime, 0.0)
            assertEquals(physicalReading, repository.loadStatistics(entry.root).single { it.deviceId == "phone" })
            assertEquals(1, drive.getStatsFileCalls)
        }
    }

    @Test
    fun explicitImportAppliesAnOlderTtuPositionAsANewNavigation() = runBlocking {
        val repository = BookRepository(tempFolder.newFolder(), deviceIdentity = DeviceIdentity("phone", "Phone"))
        val entry = repository.createEntry()
        val local = Bookmark(1, 0.9, 190, TtuSyncRules.unixMillisToAppleReferenceSeconds(5_000))
        repository.saveBookmark(entry.root, local)
        val drive = FakeDriveSyncDataSource(progress = TtuProgress(7, 150, 0.5, 2_000))

        val result = SyncManager(repository, drive).syncBook(
            entry, SyncDirection.ImportFromTtu, syncStats = false,
            statsSyncMode = StatisticsSyncMode.Merge, syncAudioBook = false,
        )

        assertEquals(SyncResult.Imported("Title", 150), result)
        val imported = repository.loadBookmark(entry.root)!!
        assertEquals(listOf(1, 150), listOf(imported.chapterIndex, imported.characterCount))
        assertEquals(0.5, imported.progress, 0.0)
        // Newer than the position it replaced, so the bookmark maps and older clients take it too.
        assertTrue(imported.lastModified!! > local.lastModified!!)
        val state = repository.readingPositions.snapshot(entry.root)
        assertEquals("ttuImport", state.winner()!!.action)
        assertTrue("queued for HTTP sync", state.winner()!!.id in state.pending)
    }

    @Test
    fun automaticImportNeverReplacesAPositionNewerThanTtu() = runBlocking {
        val repository = BookRepository(tempFolder.newFolder(), deviceIdentity = DeviceIdentity("phone", "Phone"))
        val entry = repository.createEntry()
        repository.saveBookmark(entry.root, Bookmark(0, 0.1, 10, TtuSyncRules.unixMillisToAppleReferenceSeconds(1_000)))
        val pageTurn = Bookmark(0, 0.3, 30, TtuSyncRules.unixMillisToAppleReferenceSeconds(3_000))
        // ッツ is newer when the direction is decided; a page turn lands before its file is read.
        val drive = FakeDriveSyncDataSource(
            progress = TtuProgress(7, 150, 0.5, 2_000),
            beforeGetProgress = { repository.navigateReadingPosition(entry.root, pageTurn, "pageTurn") },
        )

        val result = SyncManager(repository, drive).syncBook(
            entry, direction = null, syncStats = false,
            statsSyncMode = StatisticsSyncMode.Merge, syncAudioBook = false, importOnly = true,
        )

        assertEquals(SyncResult.Imported("Title", 150), result)
        val bookmark = repository.loadBookmark(entry.root)!!
        assertEquals(0, bookmark.chapterIndex)
        assertEquals(0.3, bookmark.progress, 0.0)
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
    fun firstPageTimeExportsWithoutCreatingAPositionOrExportingAudio() = runBlocking {
        for (mode in StatisticsSyncMode.entries) for (hasRemoteFiles in listOf(true, false)) {
            val repository = BookRepository(tempFolder.newFolder(), deviceIdentity = DeviceIdentity("phone", "Phone"))
            val entry = repository.createEntry()
            val local = ReadingStatistics(
                "Title", "2026-10-24", charactersRead = 0, readingTime = 100.0,
                lastStatisticModified = 3_000, deviceId = "phone", deviceName = "Phone",
            )
            repository.saveStatistics(entry.root, listOf(local))
            val playback = SasayakiPlaybackData(lastPosition = 10.0, rate = 1.5f, audioUri = "content://audio")
            repository.saveSasayakiPlayback(entry.root, playback)
            val remote = ReadingStatistics("Title", "2026-10-23", 0, 50.0, lastStatisticModified = 9_000)
            val drive = FakeDriveSyncDataSource(
                progress = if (hasRemoteFiles) TtuProgress(7, 150, 0.75, 2_000) else null,
                statistics = if (hasRemoteFiles) listOf(remote) else null,
                audioBook = if (hasRemoteFiles) TtuAudioBook("Title", 88.0, 3_000) else null,
                beforeUpdateStats = { published ->
                    assertEquals(published.sortedBy { it.dateKey }, repository.loadStatistics(entry.root).collapsedByDay().sortedBy { it.dateKey })
                },
            )

            val result = SyncManager(repository, drive).syncBook(
                entry, SyncDirection.ExportToTtu, syncStats = true,
                statsSyncMode = mode, syncAudioBook = true,
            )

            assertEquals(SyncResult.Synced("Title"), result)
            assertEquals(null, repository.loadBookmark(entry.root))
            assertEquals(playback, repository.loadSasayakiPlayback(entry.root))
            val expectedDays = if (hasRemoteFiles && mode == StatisticsSyncMode.Merge) {
                setOf("2026-10-23", "2026-10-24")
            } else setOf("2026-10-24")
            assertEquals(expectedDays, drive.updatedStatistics.map { it.dateKey }.toSet())
            assertEquals(100.0, drive.updatedStatistics.single { it.dateKey == local.dateKey }.readingTime, 0.0)
            assertEquals(1, drive.updateStatsFileCalls)
            assertEquals(if (hasRemoteFiles) 1 else 0, drive.getStatsFileCalls)
            assertEquals(0, drive.getProgressFileCalls)
            assertEquals(0, drive.getAudioBookFileCalls)
            assertEquals(null, drive.updatedProgress)
            assertEquals(null, drive.updatedAudioBook)
        }
    }

    @Test
    fun firstPageExportSkipsDisabledStatisticsOrNoRecordedReadingWithoutReadingRemoteSidecars() = runBlocking {
        for (syncStats in listOf(true, false)) for (recordedSeconds in listOf(null, 0.0, 100.0)) {
            if (syncStats && recordedSeconds == 100.0) continue
            val repository = BookRepository(tempFolder.newFolder())
            val entry = repository.createEntry()
            if (recordedSeconds != null) repository.saveStatistics(entry.root, listOf(ReadingStatistics(
                "Title", "2026-10-24", 0, recordedSeconds, lastStatisticModified = 3_000,
            )))
            val drive = FakeDriveSyncDataSource(
                progress = TtuProgress(7, 150, 0.75, 2_000),
                statistics = listOf(ReadingStatistics("Title", "2026-10-23", 0, 50.0, lastStatisticModified = 9_000)),
                audioBook = TtuAudioBook("Title", 88.0, 3_000),
            )

            val result = SyncManager(repository, drive).syncBook(
                entry, SyncDirection.ExportToTtu, syncStats = syncStats,
                statsSyncMode = StatisticsSyncMode.Replace, syncAudioBook = true,
            )

            assertEquals(SyncResult.Skipped, result)
            assertEquals(null, repository.loadBookmark(entry.root))
            assertEquals(0, drive.getStatsFileCalls)
            assertEquals(0, drive.updateStatsFileCalls)
            assertEquals(0, drive.getProgressFileCalls)
            assertEquals(0, drive.getAudioBookFileCalls)
            assertEquals(null, drive.updatedProgress)
            assertEquals(null, drive.updatedAudioBook)
        }
    }

    @Test
    fun firstPageStatisticsAlreadyOnTtuAreSyncedWithoutRewritingTheFile() = runBlocking {
        val repository = BookRepository(tempFolder.newFolder())
        val entry = repository.createEntry()
        val statistics = ReadingStatistics("Title", "2026-10-24", 0, 100.0, lastStatisticModified = 3_000)
        repository.saveStatistics(entry.root, listOf(statistics))
        val drive = FakeDriveSyncDataSource(statistics = listOf(statistics))

        val result = SyncManager(repository, drive).syncBook(
            entry, SyncDirection.ExportToTtu, syncStats = true,
            statsSyncMode = StatisticsSyncMode.Merge, syncAudioBook = true,
        )

        assertEquals(SyncResult.Synced("Title"), result)
        assertEquals(1, drive.getStatsFileCalls)
        assertEquals(0, drive.updateStatsFileCalls)
        assertEquals(null, repository.loadBookmark(entry.root))
        assertEquals(null, drive.updatedAudioBook)
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

    @Test
    fun anImportOnlyRefreshReportsStatisticsOnlyWhenADaysTotalsChange() = runBlocking {
        for (remoteSeconds in listOf(160.0, 100.0)) for (hasStatisticsFile in listOf(true, false)) {
            val repository = BookRepository(tempFolder.newFolder(), deviceIdentity = DeviceIdentity("phone", "Phone"))
            val entry = repository.createEntry()
            repository.saveBookmark(entry.root, Bookmark(0, 0.5, 100, TtuSyncRules.unixMillisToAppleReferenceSeconds(2_000)))
            val local = ReadingStatistics(
                "Title", "2026-10-24", 100, 100.0, lastStatisticModified = 1_000, deviceId = "phone", deviceName = "Phone",
            )
            repository.saveStatistics(entry.root, listOf(local))
            val drive = FakeDriveSyncDataSource(
                progress = TtuProgress(7, 100, 0.5, 2_000),
                statistics = listOf(local.copy(readingTime = remoteSeconds, lastStatisticModified = 3_000, deviceId = null, deviceName = null))
                    .takeIf { hasStatisticsFile },
            )
            var reported = 0

            val result = SyncManager(repository, drive).syncBook(
                entry, direction = null, syncStats = true, statsSyncMode = StatisticsSyncMode.Merge,
                syncAudioBook = false, importOnly = true, onStatisticsImported = { reported++ },
            )

            assertEquals(SyncResult.Synced("Title"), result)
            // An equal newer total still rewrites the file (this device's counters are checkpointed),
            // but it is no new reading: an open reader must not be rebuilt for it.
            assertEquals("$remoteSeconds s, file $hasStatisticsFile", if (hasStatisticsFile && remoteSeconds != 100.0) 1 else 0, reported)
        }
    }

    @Test
    fun aReaderLeftRunningThroughAForegroundImportCannotOverwriteWhatItImported() = runBlocking {
        val device = DeviceIdentity("phone", "Phone")
        val repository = BookRepository(tempFolder.newFolder(), deviceIdentity = device)
        val entry = repository.createEntry()
        val today = java.time.LocalDate.of(2026, 10, 25)
        repository.saveBookmark(entry.root, Bookmark(0, 0.1, 10, TtuSyncRules.unixMillisToAppleReferenceSeconds(1_000)))
        repository.saveStatistics(entry.root, listOf(ReadingStatistics(
            "Title", today.toString(), 100, 100.0, lastStatisticModified = 1_000, deviceId = device.id, deviceName = device.name,
        )))
        val clock = object : moe.antimony.hoshi.features.reader.ReaderStatisticsClock {
            var millis = 2_000L
            override fun currentTimeMillis() = millis
            override fun currentDate() = today
        }
        // The reader shown before the import keeps counting from the base it loaded.
        val tracker = moe.antimony.hoshi.features.reader.ReaderStatisticsTracker(
            "Title", repository.loadStatistics(entry.root), true, clock, device,
        )
        tracker.start(10)
        // Another device read to character 150 and 400 s in total today.
        val drive = FakeDriveSyncDataSource(
            progress = TtuProgress(7, 150, 0.75, 3_000),
            statistics = listOf(ReadingStatistics("Title", today.toString(), 400, 400.0, lastStatisticModified = 3_000)),
        )
        var statisticsImported = false

        val result = SyncManager(repository, drive).syncBook(
            entry, direction = null, syncStats = true, statsSyncMode = StatisticsSyncMode.Merge,
            syncAudioBook = false, importOnly = true, onStatisticsImported = { statisticsImported = true },
        )
        assertEquals(SyncResult.Imported("Title", 150), result)
        assertTrue(statisticsImported)

        // The old reader reads on and saves from its stale base, as on a pause or when the reload
        // retires it: its own new reading is added, the imported history and position stay.
        clock.millis += 10_000
        tracker.pause(20)
        repository.saveStatistics(entry.root, tracker.statisticsForPersistence())
        val stored = repository.loadStatistics(entry.root)
        assertEquals(410, stored.readingTotals().charactersRead)
        assertEquals(410.0, stored.readingTotals().readingTime, 0.0)
        assertEquals(110, stored.single { it.deviceId == device.id }.charactersRead)
        assertEquals(150, repository.loadBookmark(entry.root)?.characterCount)
    }

    @Test
    fun aReaderSaveDuringTheTtuStatisticsDownloadKeepsItsReadingTime() = runBlocking {
        for (direction in listOf(SyncDirection.ImportFromTtu, SyncDirection.ExportToTtu)) {
            val device = DeviceIdentity("phone", "Phone")
            val repository = BookRepository(tempFolder.newFolder(), deviceIdentity = device)
            val entry = repository.createEntry()
            repository.saveBookmark(entry.root, Bookmark(0, 0.5, 100, TtuSyncRules.unixMillisToAppleReferenceSeconds(2_000)))
            val mine = ReadingStatistics(
                "Title", "2026-10-24", 30, 30.0, lastStatisticModified = 1_000, deviceId = device.id, deviceName = device.name,
            )
            repository.saveStatistics(entry.root, listOf(mine))
            // 100 s read elsewhere (iOS or the ッツ web reader) were imported earlier today.
            val ttuDay = ReadingStatistics("Title", "2026-10-24", 130, 130.0, lastStatisticModified = 2_000)
            repository.applyDayTotals(entry.root, listOf(ttuDay), replaceOtherDays = false)
            val drive = FakeDriveSyncDataSource(
                progress = TtuProgress(7, 100, 0.5, 2_000),
                statistics = listOf(ttuDay),
                // The open reader saves a page turn while the statistics file downloads.
                beforeGetStats = {
                    repository.saveStatistics(
                        entry.root,
                        listOf(mine.copy(charactersRead = 35, readingTime = 35.0, lastStatisticModified = 3_000)),
                    )
                },
            )

            SyncManager(repository, drive).syncBook(
                entry, direction, syncStats = true, statsSyncMode = StatisticsSyncMode.Merge, syncAudioBook = false,
            )

            val stored = repository.loadStatistics(entry.root)
            assertEquals("$direction", 135, stored.readingTotals().charactersRead)
            assertEquals("$direction", 135.0, stored.readingTotals().readingTime, 0.0)
            assertEquals("$direction", 100, stored.single { it.deviceId == TTU_STATISTICS_DEVICE_ID }.charactersRead)
            if (direction == SyncDirection.ExportToTtu) {
                assertEquals(135, drive.updatedStatistics.single().charactersRead)
            }
        }
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
    private val beforeUpdateStats: suspend (List<ReadingStatistics>) -> Unit = {},
    private val beforeGetProgress: suspend () -> Unit = {},
    private val beforeGetStats: suspend () -> Unit = {},
) : DriveSyncDataSource {
    private val progressFile = progress?.let { DriveFile("progress", TtuSyncRules.progressFileName(it)) }
    private val statisticsFile = statistics?.let { DriveFile("statistics", TtuSyncRules.statisticsFileName(it)) }
    private val audioBookFile = audioBook?.let { DriveFile("audio", TtuSyncRules.audioBookFileName(it)) }
    private val progressById = progressFile?.let { mapOf(it.id to requireNotNull(progress)) }.orEmpty()
    private val statisticsById = statisticsFile?.let { mapOf(it.id to requireNotNull(statistics)) }.orEmpty()
    private val audioBookById = audioBookFile?.let { mapOf(it.id to requireNotNull(audioBook)) }.orEmpty()

    var clearCacheCalls = 0
    var listSyncFilesCalls = 0
    var getProgressFileCalls = 0
    var getStatsFileCalls = 0
    var updateStatsFileCalls = 0
    var getAudioBookFileCalls = 0
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

    override suspend fun getProgressFile(fileId: String): TtuProgress {
        beforeGetProgress()
        getProgressFileCalls += 1
        return progressById.getValue(fileId)
    }

    override suspend fun getStatsFile(fileId: String): List<ReadingStatistics> {
        getStatsFileCalls += 1
        beforeGetStats()
        return statisticsById.getValue(fileId)
    }

    override suspend fun getAudioBookFile(fileId: String): TtuAudioBook {
        getAudioBookFileCalls += 1
        return audioBookById.getValue(fileId)
    }

    override suspend fun updateProgressFile(folderId: String, fileId: String?, progress: TtuProgress) {
        assertEquals("book-folder", folderId)
        assertTrue(fileId == null || fileId == progressFile?.id)
        updatedProgress = progress
    }

    override suspend fun updateStatsFile(folderId: String, fileId: String?, stats: List<ReadingStatistics>) {
        assertEquals("book-folder", folderId)
        assertTrue(fileId == null || fileId == statisticsFile?.id)
        beforeUpdateStats(stats)
        updateStatsFileCalls += 1
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
