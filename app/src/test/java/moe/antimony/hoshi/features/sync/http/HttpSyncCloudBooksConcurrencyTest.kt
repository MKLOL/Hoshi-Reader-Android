package moe.antimony.hoshi.features.sync.http

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong

/**
 * The UI taps while a sync pass lists, covers, installs and finishes on other threads. No tap
 * may be lost, and a tap the pass did not plan must always leave hasUnplannedRequests() true.
 */
class HttpSyncCloudBooksConcurrencyTest {
    @get:Rule val tempFolder = TemporaryFolder()

    private val tick = AtomicLong(0)

    private fun store(booksDirectory: java.io.File) = HttpSyncCloudBooks(
        booksDirectory,
        coverThumbnail = { it },
        // Distinct, increasing stamps from every thread.
        now = { Instant.parse("2030-01-01T00:00:00Z").plusMillis(tick.incrementAndGet()) },
        ioDispatcher = Dispatchers.IO,
    )

    private fun listing(syncId: String) =
        HttpSyncCloudBookListing(syncId, syncId, HttpSyncContentType.Mokuro, 100, "sha256:" + "1".repeat(64), null, null)

    @Test
    fun tapsRacingAPassAreNeverLostAndUnplannedTapsAreAlwaysReported() = runBlocking {
        val ids = (0 until 20).map { "manga_$it" }
        repeat(30) { round ->
            val store = store(tempFolder.newFolder("Books$round"))
            store.recordListed(ids.map(::listing))
            val planned = async(Dispatchers.IO) {
                // The pass: refresh the listing, take the plan, save covers, end the pass.
                store.recordListed(ids.map(::listing))
                val plan = store.takeRequestsForPlan()
                for (id in ids) store.saveCover(id, "sha256:" + "1".repeat(64), byteArrayOf(round.toByte()))
                store.finishPass(ids.toSet(), emptyMap())
                plan
            }
            val tapped = ids.map { id -> async(Dispatchers.IO) { if (store.requestDownload(id)) id else null } }
            val taps = tapped.awaitAll().filterNotNull()
            val plan = planned.await()

            assertEquals("every tap reached the catalogue", ids.toSet(), taps.toSet())
            val requested = store.records().filter { it.downloadRequestedAt != null }.map { it.syncId }.toSet()
            assertEquals("no tap was lost by a concurrent write", ids.toSet(), requested)
            if (!plan.keys.containsAll(ids)) {
                assertTrue("a tap the plan missed must trigger another pass", store.hasUnplannedRequests())
            }
            assertTrue("every record keeps its cover", store.records().all { store.coverFile(it)?.isFile == true })
        }
    }

    @Test
    fun aFreshStoreOnTheSameDirectorySeesTheTapsButNoUnplannedGeneration() = runBlocking {
        val directory = tempFolder.newFolder("Books")
        val first = store(directory)
        first.recordListed(listOf(listing("a")))
        val jobs = List(10) { launch(Dispatchers.IO) { first.requestDownload("a") } }
        jobs.forEach { it.join() }

        val restarted = store(directory)
        assertTrue(restarted.hasRequestedDownloads())
        assertEquals(setOf("a"), restarted.takeRequestsForPlan().keys)
    }
}
