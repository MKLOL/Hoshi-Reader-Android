package moe.antimony.hoshi.features.sync.v3

import moe.antimony.hoshi.epub.ContentType
import moe.antimony.hoshi.features.sync.http.HttpSyncContentType
import moe.antimony.hoshi.features.sync.http.HttpSyncMetadataBlob
import moe.antimony.hoshi.features.sync.http.HttpSyncPayloadManifest
import moe.antimony.hoshi.features.sync.http.StatisticsSyncKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.Instant

/**
 * Statistics must be planned by the v3 engine, which is the production default; the
 * pretranslations feature once shipped wired into v2 only and never reached a device.
 */
class V3StatisticsPlannerTest {
    @get:Rule val temp = TemporaryFolder()

    private val planner = V3Planner()

    private fun localBook(root: File, contentType: ContentType = ContentType.Mokuro) = V3LocalBook(
        bookId = "id-shirokuma",
        syncId = "shirokuma",
        title = "Shirokuma",
        root = root,
        contentType = contentType,
        shelfName = null,
        shelfUpdatedAt = null,
        bookmark = null,
        chatEntries = emptyList(),
        pendingDeletion = null,
        importedAt = null,
    )

    private fun remoteBook(format: HttpSyncContentType = HttpSyncContentType.Mokuro, withStatistics: Boolean) = V3RemoteBook(
        syncId = "shirokuma",
        metadata = HttpSyncMetadataBlob(title = "Shirokuma", contentType = format),
        manifest = HttpSyncPayloadManifest(sha256 = "sha256:abc", sizeBytes = 1, originalName = "Shirokuma", format = format),
        statisticsKey = "books/shirokuma/statistics".takeIf { withStatistics },
        statisticsSize = 321.takeIf { withStatistics },
        mangaStatisticsKey = "books/shirokuma/manga_statistics".takeIf { withStatistics },
        mangaStatisticsSize = 99.takeIf { withStatistics },
    )

    private fun localSnapshot(books: List<V3LocalBook>) = V3LocalSnapshot(books = books, aiSettings = null, readAt = Instant.parse("2026-09-12T00:00:00Z"))
    private fun remoteSnapshot(books: List<V3RemoteBook>) = V3RemoteSnapshot(books = books.associateBy { it.syncId }, aiSettings = null, aiSettingsLastModified = null)

    private fun statisticsActions(local: List<V3LocalBook>, remote: List<V3RemoteBook>) =
        planner.compute(localSnapshot(local), remoteSnapshot(remote)).actions.filterIsInstance<V3Action.SyncStatistics>()

    @Test
    fun aMangaOnBothSidesSyncsBothKindsWithTheListedKeysAndSizes() {
        val root = temp.newFolder("book")
        val actions = statisticsActions(listOf(localBook(root)), listOf(remoteBook(withStatistics = true)))

        assertEquals(listOf(StatisticsSyncKind.Reading, StatisticsSyncKind.MangaText), actions.map { it.kind })
        val reading = actions.first { it.kind == StatisticsSyncKind.Reading }
        assertEquals("books/shirokuma/statistics", reading.remoteKey)
        assertEquals(321, reading.remoteSize)
        assertEquals(root, reading.root)
        assertEquals(99, actions.first { it.kind == StatisticsSyncKind.MangaText }.remoteSize)
    }

    @Test
    fun anEpubSyncsReadingStatisticsOnly() {
        val root = temp.newFolder("novel")
        val actions = statisticsActions(listOf(localBook(root, ContentType.Epub)), listOf(remoteBook(HttpSyncContentType.Epub, withStatistics = true)))

        assertEquals(listOf(StatisticsSyncKind.Reading), actions.map { it.kind })
    }

    @Test
    fun aLocalBookWithoutRemoteStatisticsIsStillPlannedSoItsDaysGetUploaded() {
        val root = temp.newFolder("book")
        val actions = statisticsActions(listOf(localBook(root)), listOf(remoteBook(withStatistics = false)))

        assertEquals(2, actions.size)
        assertTrue(actions.all { it.remoteKey == null && it.remoteSize == null })
    }

    @Test
    fun aRemoteOnlyBookImportsItsStatisticsAlongWithThePayload() {
        val actions = statisticsActions(emptyList(), listOf(remoteBook(withStatistics = true)))

        assertEquals(listOf(StatisticsSyncKind.Reading, StatisticsSyncKind.MangaText), actions.map { it.kind })
        assertTrue(actions.all { it.remoteKey != null })
        assertTrue(statisticsActions(emptyList(), listOf(remoteBook(withStatistics = false))).isEmpty())
    }

    @Test
    fun statisticsAreAppliedBeforeAnythingIsPushed() {
        val root = temp.newFolder("book")
        val actions = planner.compute(localSnapshot(listOf(localBook(root))), remoteSnapshot(listOf(remoteBook(withStatistics = true)))).actions
        val lastStatistics = actions.indexOfLast { it is V3Action.SyncStatistics }
        val firstPush = actions.indexOfFirst { it is V3Action.PushMetadata || it is V3Action.PushPayload || it is V3Action.PushBookmark }
        assertTrue(lastStatistics >= 0)
        assertTrue(firstPush == -1 || lastStatistics < firstPush)
    }
}
