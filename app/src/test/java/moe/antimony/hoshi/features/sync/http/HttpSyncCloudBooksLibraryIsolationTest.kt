package moe.antimony.hoshi.features.sync.http

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import moe.antimony.hoshi.epub.BookRepository
import moe.antimony.hoshi.features.backup.HoshiBackupRepository
import moe.antimony.hoshi.features.storage.StorageCleanupRepository
import moe.antimony.hoshi.features.sync.integration.SyncCorpus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * The catalogue lives inside `Books/` (`.http_sync_cloud/`). Everything else that walks that
 * folder must leave it alone: the library scan, reading history, interrupted-delete recovery
 * and storage cleanup. A Books backup carries it, so a restore brings the listing back.
 */
class HttpSyncCloudBooksLibraryIsolationTest {
    @get:Rule val temp = TemporaryFolder()

    private fun listing(syncId: String) = HttpSyncCloudBookListing(
        syncId, "Cloud $syncId", HttpSyncContentType.Mokuro, 100, "sha256:" + "1".repeat(64), null, null,
    )

    private fun populated(filesDir: File): Pair<BookRepository, HttpSyncCloudBooks> = runBlocking {
        val repo = BookRepository(filesDir)
        SyncCorpus.importManga(repo)
        val cloud = HttpSyncCloudBooks(repo.booksDirectory, coverThumbnail = { it }, ioDispatcher = Dispatchers.Unconfined)
        cloud.recordListed(listOf(listing("cloud_only")))
        cloud.saveCover("cloud_only", "sha256:" + "1".repeat(64), byteArrayOf(1, 2, 3))
        cloud.requestDownload("cloud_only")
        assertTrue(repo.booksDirectory.resolve(HttpSyncCloudBooks.DIRECTORY_NAME).resolve("books.json").isFile)
        repo to cloud
    }

    @Test
    fun theLibraryHistoryAndCleanupNeverTreatTheCatalogueAsABook() = runBlocking {
        val filesDir = temp.newFolder("files")
        val (repo, cloud) = populated(filesDir)

        assertEquals(listOf(SyncCorpus.MANGA_SYNC_ID), repo.loadBookEntries().map { it.metadata.syncId })
        assertEquals(null, repo.loadBookEntry("cloud_only"))
        assertTrue(repo.loadStatisticsHistory().isEmpty())

        val cleanup = StorageCleanupRepository(filesDir, temp.newFolder("cache"))
        val report = cleanup.scan()
        val catalogue = repo.booksDirectory.resolve(HttpSyncCloudBooks.DIRECTORY_NAME).canonicalPath
        assertTrue(
            "storage cleanup offers nothing of the catalogue: ${report.categories.map { it.targets }}",
            report.categories.flatMap { it.targets }.none { it.canonicalPath.startsWith(catalogue) },
        )
        cleanup.clean(report)
        assertEquals(listOf("cloud_only"), cloud.records().map { it.syncId })
        assertTrue(cloud.coverFile(cloud.records().single())!!.isFile)
    }

    @Test
    fun aBooksBackupLeavesTheCatalogueOutSoARestoreStartsNoDownload() = runBlocking {
        val source = temp.newFolder("source")
        populated(source)
        val backup = ByteArrayOutputStream()
        HoshiBackupRepository(source, Dispatchers.Unconfined).exportBooks(backup)

        val target = temp.newFolder("target")
        HoshiBackupRepository(target, Dispatchers.Unconfined).restoreBooks(ByteArrayInputStream(backup.toByteArray()))

        val repo = BookRepository(target)
        val cloud = HttpSyncCloudBooks(repo.booksDirectory, coverThumbnail = { it }, ioDispatcher = Dispatchers.Unconfined)
        assertEquals(listOf(SyncCorpus.MANGA_SYNC_ID), repo.loadBookEntries().map { it.metadata.syncId })
        // The next sync lists the server's manga again; a tap saved in the backup must not
        // start a download on the restored install's first launch.
        assertTrue(cloud.records().isEmpty())
        assertFalse(cloud.hasRequestedDownloads())
        assertFalse(repo.booksDirectory.resolve(HttpSyncCloudBooks.DIRECTORY_NAME).exists())
    }
}
