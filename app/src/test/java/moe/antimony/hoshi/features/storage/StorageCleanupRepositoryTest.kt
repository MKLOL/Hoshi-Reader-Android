package moe.antimony.hoshi.features.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class StorageCleanupRepositoryTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun scanFindsPrivateRestoreImportCacheAndOrphanAudioResidues() {
        val filesDir = temporaryFolder.newFolder("files")
        val cacheDir = temporaryFolder.newFolder("cache")
        filesDir.resolve(".books-restore-deadbeef/part").also { file ->
            file.parentFile!!.mkdirs()
            file.writeBytes(byteArrayOf(1, 2, 3))
        }
        filesDir.resolve(".dictionaries-restore-deadbeef.hoshi").writeBytes(byteArrayOf(1, 2))
        filesDir.resolve("ImportTemp/import-a/chapter.xhtml").also { file ->
            file.parentFile!!.mkdirs()
            file.writeBytes(byteArrayOf(1, 2, 3, 4))
        }
        filesDir.resolve("Audio/android.db.tmp").also { file ->
            file.parentFile!!.mkdirs()
            file.writeBytes(byteArrayOf(1, 2, 3, 4, 5))
        }
        val book = filesDir.resolve("Books/Book")
        book.resolve("sasayaki_playback.json").also { file ->
            file.parentFile!!.mkdirs()
            file.writeText("""{"lastPosition":0.0,"audioFileName":"sasayaki_audio.m4b"}""")
        }
        book.resolve("Sasayaki/sasayaki_audio.m4b").also { file ->
            file.parentFile!!.mkdirs()
            file.writeBytes(byteArrayOf(1))
        }
        book.resolve("Sasayaki/orphan.mp3").writeBytes(byteArrayOf(1, 2, 3, 4, 5, 6))
        cacheDir.resolve("anki-media/hoshi_audio.mp3").also { file ->
            file.parentFile!!.mkdirs()
            file.writeBytes(byteArrayOf(1, 2, 3, 4, 5, 6, 7))
        }
        filesDir.resolve("Dictionaries/Term/.dictionary-import-deadbeef/JMdict/index.json").also { file ->
            file.parentFile!!.mkdirs()
            file.writeBytes(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8))
        }

        val report = StorageCleanupRepository(filesDir, cacheDir).scan()

        assertEquals(
            listOf(
                StorageCleanupCategoryId.AnkiMediaCache,
                StorageCleanupCategoryId.EpubImportResidue,
                StorageCleanupCategoryId.BackupRestoreResidue,
                StorageCleanupCategoryId.DictionaryImportResidue,
                StorageCleanupCategoryId.LocalAudioImportResidue,
                StorageCleanupCategoryId.OrphanSasayakiAudio,
            ),
            report.categories.map { it.id },
        )
        assertEquals(35L, report.totalSizeBytes)
        assertEquals(7, report.totalItemCount)
    }

    @Test
    fun cleanDeletesOnlyScannedTargets() {
        val filesDir = temporaryFolder.newFolder("clean-files")
        val cacheDir = temporaryFolder.newFolder("clean-cache")
        val keeper = filesDir.resolve("Books/Book/Sasayaki/sasayaki_audio.m4b").also { file ->
            file.parentFile!!.mkdirs()
            file.writeText("keep")
        }
        filesDir.resolve("Books/Book/sasayaki_playback.json")
            .writeText("""{"lastPosition":0.0,"audioFileName":"sasayaki_audio.m4b"}""")
        val orphan = filesDir.resolve("Books/Book/Sasayaki/orphan.mp3").also { file ->
            file.writeText("delete")
        }
        val cache = cacheDir.resolve("anki-media/hoshi_audio.mp3").also { file ->
            file.parentFile!!.mkdirs()
            file.writeText("delete")
        }

        val repository = StorageCleanupRepository(filesDir, cacheDir)
        val report = repository.scan()
        repository.clean(report)

        assertTrue(keeper.exists())
        assertFalse(orphan.exists())
        assertFalse(cache.exists())
    }

    @Test
    fun scanCountsEmptyResidueDirectoriesAsCleanableItems() {
        val filesDir = temporaryFolder.newFolder("empty-files")
        val cacheDir = temporaryFolder.newFolder("empty-cache")
        filesDir.resolve("ImportTemp").mkdirs()

        val report = StorageCleanupRepository(filesDir, cacheDir).scan()

        assertTrue(report.hasCleanableItems)
        assertEquals(listOf(StorageCleanupCategoryId.EpubImportResidue), report.categories.map { it.id })
        assertEquals(0L, report.totalSizeBytes)
        assertEquals(1, report.totalItemCount)
    }

    @Test
    fun scanCleansOnlyCompletedDictionaryReplacementBackups() {
        val filesDir = temporaryFolder.newFolder("dictionary-replace-files")
        val cacheDir = temporaryFolder.newFolder("dictionary-replace-cache")
        val typeDirectory = filesDir.resolve("Dictionaries/Term")
        val completedBackup = typeDirectory.resolve(".Existing-replace-done/index.json").also { file ->
            file.parentFile!!.mkdirs()
            file.writeText("old")
        }
        val onlyBackup = typeDirectory.resolve(".Missing-replace-incomplete/index.json").also { file ->
            file.parentFile!!.mkdirs()
            file.writeText("only-copy")
        }
        typeDirectory.resolve("Existing/index.json").also { file ->
            file.parentFile!!.mkdirs()
            file.writeText("new")
        }

        val repository = StorageCleanupRepository(filesDir, cacheDir)
        repository.clean(repository.scan())

        assertFalse(completedBackup.exists())
        assertTrue(onlyBackup.exists())
    }

    @Test
    fun scanCleansOnlyCompletedRestoreBackups() {
        val filesDir = temporaryFolder.newFolder("restore-backup-files")
        val cacheDir = temporaryFolder.newFolder("restore-backup-cache")
        val completedBackup = filesDir.resolve(".books-restore-backup-done/old.txt").also { file ->
            file.parentFile!!.mkdirs()
            file.writeText("old")
        }
        val onlyBackup = filesDir.resolve(".dictionaries-restore-backup-incomplete/old.txt").also { file ->
            file.parentFile!!.mkdirs()
            file.writeText("only-copy")
        }
        filesDir.resolve("Books/new.txt").also { file ->
            file.parentFile!!.mkdirs()
            file.writeText("new")
        }

        val repository = StorageCleanupRepository(filesDir, cacheDir)
        repository.clean(repository.scan())

        assertFalse(completedBackup.exists())
        assertTrue(onlyBackup.exists())
    }

    @Test
    fun cleanPreservesAudioLinkedAfterThePreviewAndUnpreviewedOrphans() {
        val filesDir = temporaryFolder.newFolder("stale-audio-files")
        val cacheDir = temporaryFolder.newFolder("stale-audio-cache")
        val book = filesDir.resolve("Books/Book").apply { mkdirs() }
        val audio = book.resolve("Sasayaki").apply { mkdirs() }
        val newlyLinked = audio.resolve("selected.m4b").apply { writeText("keep") }
        val oldOrphan = audio.resolve("old.mp3").apply { writeText("delete") }
        val playback = book.resolve("sasayaki_playback.json").apply { writeText("{}") }
        val repository = StorageCleanupRepository(filesDir, cacheDir)
        val preview = repository.scan()

        playback.writeText("""{"audioFileName":"selected.m4b"}""")
        val newOrphan = audio.resolve("unpreviewed.mp3").apply { writeText("keep") }
        repository.clean(preview)

        assertTrue(newlyLinked.exists())
        assertTrue(newOrphan.exists())
        assertFalse(oldOrphan.exists())
    }

    @Test
    fun malformedPlaybackMetadataCannotMakeCopiedAudioCleanable() {
        val filesDir = temporaryFolder.newFolder("damaged-playback-files")
        val cacheDir = temporaryFolder.newFolder("damaged-playback-cache")
        val book = filesDir.resolve("Books/Book").apply { mkdirs() }
        val audio = book.resolve("Sasayaki/book.m4b").apply {
            parentFile!!.mkdirs()
            writeText("keep")
        }
        val playback = book.resolve("sasayaki_playback.json").apply { writeText("{}") }
        val repository = StorageCleanupRepository(filesDir, cacheDir)
        val preview = repository.scan()

        playback.writeText("""{"audioFileName":"book.m4b"""")
        assertFalse(repository.scan().hasCleanableItems)
        repository.clean(preview)
        assertTrue(audio.exists())
    }

    @Test
    fun cleanPreservesReplacementBackupsThatBecameTheOnlyCopyAfterPreview() {
        val filesDir = temporaryFolder.newFolder("stale-backup-files")
        val cacheDir = temporaryFolder.newFolder("stale-backup-cache")
        val books = filesDir.resolve("Books").apply { mkdirs() }
        val dictionary = filesDir.resolve("Dictionaries/Term/Existing").apply { mkdirs() }
        val booksBackup = filesDir.resolve(".books-restore-backup-done/old.txt").apply {
            parentFile!!.mkdirs()
            writeText("only remaining library")
        }
        val dictionaryBackup = filesDir.resolve("Dictionaries/Term/.Existing-replace-done/index.json").apply {
            parentFile!!.mkdirs()
            writeText("only remaining dictionary")
        }
        val repository = StorageCleanupRepository(filesDir, cacheDir)
        val preview = repository.scan()
        assertEquals(2, preview.totalItemCount)

        assertTrue(books.deleteRecursively())
        assertTrue(dictionary.deleteRecursively())
        repository.clean(preview)

        assertTrue(booksBackup.exists())
        assertTrue(dictionaryBackup.exists())
    }

    @Test
    fun interruptedSyncDownloadsAreListedButATransferStillWritingIsNot() {
        val filesDir = temporaryFolder.newFolder("sync-files")
        val cacheDir = temporaryFolder.newFolder("sync-cache")
        val now = System.currentTimeMillis()
        val hourAgo = now - java.util.concurrent.TimeUnit.HOURS.toMillis(1)
        val partial = filesDir.resolve(".http-sync-downloads/account-key/archive.zip").also { file ->
            file.parentFile!!.mkdirs()
            file.writeBytes(ByteArray(10))
            file.setLastModified(hourAgo)
            file.parentFile!!.setLastModified(hourAgo)
        }.parentFile!!
        val staging = filesDir.resolve(".http-sync-import-123/mokuro.json").also { file ->
            file.parentFile!!.mkdirs()
            file.writeBytes(ByteArray(5))
            file.setLastModified(hourAgo)
            file.parentFile!!.setLastModified(hourAgo)
        }.parentFile!!
        val writing = filesDir.resolve(".http-sync-downloads/other-key/archive.zip").also { file ->
            file.parentFile!!.mkdirs()
            file.writeBytes(ByteArray(7))
            file.parentFile!!.setLastModified(hourAgo)
        }.parentFile!!
        val repository = StorageCleanupRepository(filesDir, cacheDir, now = { now })

        val report = repository.scan()
        val category = report.categories.single()
        assertEquals(StorageCleanupCategoryId.SyncDownloadResidue, category.id)
        // Unpacking folders wait while any archive is written, since one may belong to it.
        assertEquals(setOf(partial), category.targets.toSet())
        assertEquals(10L, category.sizeBytes)

        assertFalse(repository.clean(report).hasCleanableItems)
        assertFalse(partial.exists())
        assertTrue(staging.exists())
        assertTrue(writing.resolve("archive.zip").exists())
    }

    @Test
    fun anEmptyUnpackingFolderIsKeptWhileAnyArchiveIsStillDownloading() {
        val filesDir = temporaryFolder.newFolder("downloading-files")
        val cacheDir = temporaryFolder.newFolder("downloading-cache")
        val now = System.currentTimeMillis()
        val hourAgo = now - java.util.concurrent.TimeUnit.HOURS.toMillis(1)
        // The reader created this folder before a long download started and fills it afterwards.
        val waiting = filesDir.resolve(".http-sync-import-456").apply {
            mkdirs()
            setLastModified(hourAgo)
        }
        val archive = filesDir.resolve(".http-sync-downloads/account-key/archive.zip").also { file ->
            file.parentFile!!.mkdirs()
            file.writeBytes(ByteArray(3))
            file.parentFile!!.setLastModified(hourAgo)
        }
        val repository = StorageCleanupRepository(filesDir, cacheDir, now = { now })

        assertFalse(repository.scan().hasCleanableItems)

        archive.setLastModified(hourAgo)
        assertEquals(
            setOf(waiting, archive.parentFile!!),
            repository.scan().categories.single().targets.toSet(),
        )
    }
}
