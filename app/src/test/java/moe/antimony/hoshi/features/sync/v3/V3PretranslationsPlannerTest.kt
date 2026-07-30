package moe.antimony.hoshi.features.sync.v3

import moe.antimony.hoshi.epub.ContentType
import moe.antimony.hoshi.features.ai.PRETRANSLATIONS_FILENAME
import moe.antimony.hoshi.features.sync.http.HttpSyncMetadataBlob
import moe.antimony.hoshi.features.sync.http.HttpSyncContentType
import moe.antimony.hoshi.features.sync.http.HttpSyncPayloadManifest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.Instant

/**
 * Regression tests for the bug that made the whole offline-translation feature dead on arrival:
 * it was wired into the v2 reconciler only, while `HttpSyncSettings.useV3Sync` defaults to **true**
 * — so v3 never even listed `books/{syncId}/pretranslations` and the blob never reached the device.
 */
class V3PretranslationsPlannerTest {

    @get:Rule val temp = TemporaryFolder()

    private val planner = V3Planner()

    private fun localBook(root: File) = V3LocalBook(
        bookId = "id-004yotsubato",
        syncId = "004yotsubato",
        title = "004Yotsubato",
        root = root,
        contentType = ContentType.Mokuro,
        shelfName = null,
        shelfUpdatedAt = null,
        bookmark = null,
        chatEntries = emptyList(),
        pendingDeletion = null,
        importedAt = null,
    )

    private fun remoteBook(size: Int?) = V3RemoteBook(
        syncId = "004yotsubato",
        metadata = HttpSyncMetadataBlob(
            title = "004Yotsubato",
            contentType = HttpSyncContentType.Mokuro,
        ),
        manifest = HttpSyncPayloadManifest(
            sha256 = "sha256:abc",
            sizeBytes = 1,
            originalName = "004Yotsubato",
            format = HttpSyncContentType.Mokuro,
        ),
        pretranslationsKey = "books/004yotsubato/pretranslations",
        pretranslationsSize = size,
    )

    private fun snapshot(root: File) = V3LocalSnapshot(
        books = listOf(localBook(root)),
        aiSettings = null,
        readAt = Instant.parse("2026-05-15T00:00:00Z"),
    )

    private fun remoteSnapshot(books: List<V3RemoteBook>) = V3RemoteSnapshot(
        books = books.associateBy { it.syncId },
        aiSettings = null,
        aiSettingsLastModified = null,
    )

    private fun plan(root: File, remoteSize: Int?) = planner.compute(
        snapshot(root), remoteSnapshot(listOf(remoteBook(remoteSize))),
    ).actions.filterIsInstance<V3Action.ImportPretranslations>()

    @Test
    fun missingLocalBlobIsImported() {
        val root = temp.newFolder("book")
        val actions = plan(root, remoteSize = 768362)
        assertEquals(1, actions.size)
        assertEquals("books/004yotsubato/pretranslations", actions.single().key)
    }

    @Test
    fun unchangedBlobIsNotRedownloaded() {
        val root = temp.newFolder("book")
        val body = "x".repeat(500)
        File(root, PRETRANSLATIONS_FILENAME).writeText(body)
        assertTrue(plan(root, remoteSize = body.length).isEmpty())
    }

    @Test
    fun changedBlobIsReimported() {
        val root = temp.newFolder("book")
        File(root, PRETRANSLATIONS_FILENAME).writeText("x".repeat(100))
        assertEquals(1, plan(root, remoteSize = 999).size)
    }

    @Test
    fun serverWithoutPretranslationsPlansNothing() {
        val root = temp.newFolder("book")
        val actions = planner.compute(
            snapshot(root),
            remoteSnapshot(listOf(remoteBook(null).copy(pretranslationsKey = null))),
        ).actions.filterIsInstance<V3Action.ImportPretranslations>()
        assertTrue(actions.isEmpty())
    }
}
