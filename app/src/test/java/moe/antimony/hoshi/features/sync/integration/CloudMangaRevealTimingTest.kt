package moe.antimony.hoshi.features.sync.integration

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import moe.antimony.hoshi.epub.BookEntry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.ClassRule
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.Instant

/**
 * A tapped manga appears on the shelf (it leaves the catalogue, and `BookshelfView` reloads)
 * only once its reading position is in place: opening it must never start at the first page and
 * then publish that page over the position the other device holds.
 */
class CloudMangaRevealTimingTest {
    companion object {
        @JvmField @ClassRule val serverRule = SyncTestServerRule()
    }

    @get:Rule val temp = TemporaryFolder()

    private val server get() = serverRule.server
    private val devices = mutableListOf<SyncDevice>()

    @Before
    fun resetServer() = server.reset()

    @After
    fun closeDevices() {
        devices.forEach { it.close() }
        devices.clear()
    }

    private fun device(name: String) =
        SyncDevice(name, SyncEngine.V3, temp.newFolder(name), server, cloudCatalogue = true).also { devices += it }

    private fun assertClean(outcome: SyncOutcome) =
        assertEquals("sync errors: ${outcome.errors}", emptyList<String>(), outcome.errors)

    private fun appleSecondsNow(): Double = (Instant.now().epochSecond - 978_307_200L).toDouble()

    /**
     * Runs [onRevealed] synchronously, on the sync's own thread, at the instant the catalogue
     * drops [syncId]: exactly when the shelf's `LaunchedEffect(cloudSyncIds)` reloads and the
     * installed book appears. [onRevealed] gets the book as the reloaded shelf would list it.
     */
    private fun CoroutineScope.watchReveal(device: SyncDevice, syncId: String, onRevealed: (BookEntry?) -> Unit) =
        launch(Dispatchers.Unconfined) {
            var seen = false
            device.cloudBooks!!.books.collect { records ->
                if (records.any { it.syncId == syncId }) {
                    seen = true
                } else if (seen) {
                    seen = false
                    onRevealed(runBlocking { device.bookOrNull(syncId) })
                }
            }
        }

    @Test
    fun aTappedMangaAppearsOnlyWithItsReadingPosition() = runBlocking {
        val a = device("A")
        val manga = a.importManga()
        val syncId = a.repo.loadMetadata(manga)!!.syncId!!
        assertClean(a.sync())
        a.turnPage(manga, SyncCorpus.MANGA_TITLE, SyncCorpus.bookmark(chapter = 2, appleSeconds = 800_000_100.0))
        val b = device("B")
        assertClean(b.sync())
        assertTrue(b.cloudBooks!!.requestDownload(syncId))

        var revealedBook: BookEntry? = null
        var chapterWhenRevealed: Int? = -1
        val watcher = watchReveal(b, syncId) { entry ->
            revealedBook = entry
            chapterWhenRevealed = entry?.let { runBlocking { b.repo.loadBookmark(it.root) } }?.chapterIndex
        }
        assertClean(b.sync())
        watcher.cancel()

        assertTrue("the reloaded shelf lists the book", revealedBook != null)
        assertEquals("position present when the book appears", 2, chapterWhenRevealed)
    }

    @Test
    fun readingTheMangaAsSoonAsItAppearsContinuesFromTheOtherDevicesPosition() = runBlocking {
        val a = device("A")
        val manga = a.importManga()
        val syncId = a.repo.loadMetadata(manga)!!.syncId!!
        assertClean(a.sync())
        a.turnPage(manga, SyncCorpus.MANGA_TITLE, SyncCorpus.bookmark(chapter = 2, appleSeconds = 800_000_100.0))
        val b = device("B")
        assertClean(b.sync())
        assertTrue(b.cloudBooks!!.requestDownload(syncId))

        // The user opens the book the moment it appears and turns one page on from where the
        // reader opened it; the manga reader saves that position with the current time.
        var openedAt: Int? = null
        val watcher = watchReveal(b, syncId) { entry ->
            val root = entry!!.root
            runBlocking {
                openedAt = b.repo.loadBookmark(root)?.chapterIndex
                b.repo.saveBookmark(root, SyncCorpus.bookmark(chapter = (openedAt ?: 0) + 1, appleSeconds = appleSecondsNow()))
                b.batchState.queueBookmark(root, SyncCorpus.MANGA_TITLE, syncId)
            }
        }
        assertClean(b.sync())
        watcher.cancel()
        assertClean(a.sync())

        assertEquals("the reader opened at A's position", 2, openedAt)
        assertEquals(3, b.repo.loadBookmark(b.book(syncId).root)!!.chapterIndex)
        assertEquals("A follows the page turned on B", 3, a.repo.loadBookmark(manga)!!.chapterIndex)
    }
}
