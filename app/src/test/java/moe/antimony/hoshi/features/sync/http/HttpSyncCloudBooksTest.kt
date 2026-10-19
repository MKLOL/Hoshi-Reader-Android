package moe.antimony.hoshi.features.sync.http

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.Instant

class HttpSyncCloudBooksTest {
    @get:Rule val tempFolder = TemporaryFolder()

    private var clock = Instant.parse("2030-01-01T00:00:00Z")

    private fun store(booksDirectory: java.io.File = tempFolder.newFolder("Books")) = HttpSyncCloudBooks(
        booksDirectory,
        coverThumbnail = { it },
        now = { clock.also { clock = clock.plusSeconds(1) } },
        ioDispatcher = Dispatchers.Unconfined,
    )

    private fun listing(syncId: String, archive: String = "sha256:" + "1".repeat(64), title: String = syncId) =
        HttpSyncCloudBookListing(syncId, title, HttpSyncContentType.Mokuro, 100, archive, null, null)

    @Test
    fun relistingRefreshesDetailsButKeepsCoverAndRequest() = runBlocking {
        val store = store()
        assertEquals(1, store.recordListed(listOf(listing("a"))))
        store.saveCover("a", "sha256:" + "1".repeat(64), byteArrayOf(1, 2, 3))
        store.requestDownload("a")

        assertEquals(0, store.recordListed(listOf(listing("a", title = "Renamed"))))

        val record = store.records().single()
        assertEquals("Renamed", record.title)
        assertArrayEquals(byteArrayOf(1, 2, 3), store.coverFile(record)!!.readBytes())
        assertTrue(record.downloadRequestedAt != null)
    }

    @Test
    fun aNewArchiveNeedsANewCoverAndReplacesTheOldFile() = runBlocking {
        val store = store()
        val first = "sha256:" + "1".repeat(64)
        val second = "sha256:" + "2".repeat(64)
        store.recordListed(listOf(listing("a", first)))
        store.saveCover("a", first, byteArrayOf(1))
        val oldCover = store.coverFile(store.records().single())!!
        assertFalse(store.needsCover("a", first))

        store.recordListed(listOf(listing("a", second)))
        assertTrue(store.needsCover("a", second))
        // An unreadable new archive keeps showing the old cover.
        store.saveCover("a", second, null)
        assertEquals(oldCover, store.coverFile(store.records().single()))
        store.saveCover("a", second, byteArrayOf(2))

        val newCover = store.coverFile(store.records().single())!!
        assertArrayEquals(byteArrayOf(2), newCover.readBytes())
        assertFalse(oldCover.exists())
    }

    @Test
    fun finishingAPassDropsUnlistedBooksAndAnswersOnlyTheAttemptedRequest() = runBlocking {
        val store = store()
        store.recordListed(listOf(listing("a"), listing("b"), listing("gone")))
        store.saveCover("gone", "sha256:" + "1".repeat(64), byteArrayOf(9))
        val goneCover = store.coverFile(store.records().single { it.syncId == "gone" })!!
        store.requestDownload("a")
        store.requestDownload("b")
        val attempted = store.takeRequestsForPlan()
        // "b" is tapped again while its first attempt is failing.
        store.requestDownload("b")

        store.finishPass(
            listed = setOf("a", "b"),
            failedRequests = mapOf(
                "a" to FailedCloudRequest(attempted.getValue("a"), "boom"),
                "b" to FailedCloudRequest(attempted.getValue("b"), "boom"),
            ),
        )

        val records = store.records().associateBy { it.syncId }
        assertEquals(setOf("a", "b"), records.keys)
        assertNull(records.getValue("a").downloadRequestedAt)
        assertEquals("boom", records.getValue("a").downloadError)
        assertTrue("the newer tap is still pending", records.getValue("b").downloadRequestedAt != null)
        assertFalse(goneCover.exists())
        assertTrue(store.hasUnplannedRequests())
    }

    @Test
    fun aTapThatCouldNotStartASyncIsNotLeftWaiting() = runBlocking {
        val store = store()
        store.recordListed(listOf(listing("a"), listing("b")))
        store.requestDownload("a")
        store.requestDownload("b")

        store.cancelRequest("a")

        assertEquals(listOf("b"), store.records().filter { it.downloadRequestedAt != null }.map { it.syncId })
    }

    @Test
    fun interruptedDownloadsAreResumedOncePerProcessAndNewMangaAreCountedByDiscovery() = runBlocking {
        val store = store()
        store.recordListed(listOf(listing("old")))
        val since = clock
        store.recordListed(listOf(listing("new1"), listing("new2")))

        assertEquals(2, store.countDiscoveredSince(since))
        assertTrue(store.claimInterruptedDownloads())
        assertFalse(store.claimInterruptedDownloads())
    }

    @Test
    fun stoppingWithdrawsEveryRequestAndItsProgress() = runBlocking {
        val store = store()
        store.recordListed(listOf(listing("a"), listing("b")))
        store.requestDownload("a")
        store.requestDownload("b")
        store.reportProgress("a", 0.3f)

        store.withdrawRequests()

        assertFalse(store.hasRequestedDownloads())
        assertFalse(store.hasUnplannedRequests())
        assertTrue(store.downloads.value.isEmpty())
        assertEquals(2, store.records().size)
    }

    @Test
    fun aDownloadedBookLeavesAtOnceWithItsProgress() = runBlocking {
        val store = store()
        store.recordListed(listOf(listing("a"), listing("b")))
        store.reportProgress("a", null)
        assertEquals(mapOf<String, Float?>("a" to null), store.downloads.value)
        store.reportProgress("a", 0.4567f)
        assertEquals(0.45f, store.downloads.value.getValue("a"))

        store.markDownloaded("a")

        assertEquals(listOf("b"), store.books.value.map { it.syncId })
        assertTrue(store.downloads.value.isEmpty())
    }

    @Test
    fun theCatalogueSurvivesAProcessRestart() = runBlocking {
        val books = tempFolder.newFolder("Shared")
        store(books).apply {
            recordListed(listOf(listing("a")))
            requestDownload("a")
        }

        val reopened = store(books)
        assertTrue(reopened.books.value.isEmpty())
        reopened.reload()

        assertEquals(listOf("a"), reopened.books.value.map { it.syncId })
        assertTrue(reopened.hasRequestedDownloads())
        // The earlier process's tap waits for a plan.
        assertTrue(reopened.hasUnplannedRequests())
        reopened.takeRequestsForPlan()
        assertFalse(reopened.hasUnplannedRequests())
        assertTrue(books.resolve(HttpSyncCloudBooks.DIRECTORY_NAME).name.startsWith("."))
    }
}
