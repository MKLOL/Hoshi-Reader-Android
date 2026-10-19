package moe.antimony.hoshi.features.bookshelf

import moe.antimony.hoshi.epub.BookSortOption
import moe.antimony.hoshi.features.sync.http.HttpSyncCloudBookRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CloudBookshelfTest {
    private fun record(
        syncId: String,
        title: String = syncId,
        discoveredAt: String = "2030-01-01T00:00:00Z",
        requestedAt: String? = null,
        error: String? = null,
        cover: String? = null,
    ) = HttpSyncCloudBookRecord(
        syncId = syncId,
        title = title,
        archiveSha256 = "sha256:" + "0".repeat(64),
        discoveredAt = discoveredAt,
        coverFile = cover,
        downloadRequestedAt = requestedAt,
        downloadError = error,
    )

    private fun items(
        records: List<HttpSyncCloudBookRecord>,
        downloads: Map<String, Float?> = emptyMap(),
        installed: Set<String> = emptySet(),
        syncRunning: Boolean = false,
        sort: BookSortOption = BookSortOption.Recent,
    ) = cloudBookItems(records, downloads, installed, syncRunning, sort) { it.coverFile?.let { name -> File("/covers/$name") } }

    @Test
    fun eachCoverShowsWhereItsDownloadIs() {
        val result = items(
            records = listOf(
                record("idle"),
                record("queued", requestedAt = "2030-01-01T00:00:01Z"),
                record("starting"),
                record("half"),
                record("failed", error = "HTTP 500"),
            ),
            downloads = mapOf("starting" to null, "half" to 0.5f),
            syncRunning = true,
        ).associateBy { it.syncId }

        assertEquals(CloudBookState.Available, result.getValue("idle").state)
        assertEquals(CloudBookState.Waiting, result.getValue("queued").state)
        assertEquals(CloudBookState.Waiting, result.getValue("starting").state)
        assertEquals(CloudBookState.Downloading(0.5f), result.getValue("half").state)
        assertEquals(CloudBookState.Failed("HTTP 500"), result.getValue("failed").state)
        assertTrue(result.getValue("idle").canDownload)
        assertTrue(result.getValue("failed").canDownload)
        assertFalse(result.getValue("half").canDownload)
        assertFalse(result.getValue("queued").canDownload)
    }

    @Test
    fun aRequestWithNoSyncRunningCanBeTappedAgain() {
        val item = items(listOf(record("queued", requestedAt = "2030-01-01T00:00:01Z"))).single()

        assertEquals(CloudBookState.Available, item.state)
        assertTrue(item.canDownload)
    }

    @Test
    fun installedBooksAreHiddenAndOrderFollowsTheShelfSort() {
        val records = listOf(
            record("b", title = "beta", discoveredAt = "2030-01-01T00:00:00Z", cover = "b.jpg"),
            record("a", title = "Alpha", discoveredAt = "2030-01-03T00:00:00Z"),
            record("c", title = "gamma", discoveredAt = "2030-01-02T00:00:00Z"),
        )

        assertEquals(listOf("a", "c"), items(records, installed = setOf("b")).map { it.syncId })
        assertEquals(listOf("a", "b", "c"), items(records, sort = BookSortOption.Title).map { it.syncId })
        val withCover = items(records).single { it.syncId == "b" }
        assertEquals("/covers/b.jpg", withCover.coverSource!!.path)
        assertNull(items(records).single { it.syncId == "a" }.coverSource)
    }
}
