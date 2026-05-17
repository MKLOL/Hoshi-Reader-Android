package moe.antimony.hoshi.features.sync.v3

import moe.antimony.hoshi.epub.Bookmark
import moe.antimony.hoshi.epub.ContentType
import moe.antimony.hoshi.features.ai.AiChatEntry
import moe.antimony.hoshi.features.ai.AiChatSettings
import moe.antimony.hoshi.features.sync.http.HttpSyncAiChatSettingsBlob
import moe.antimony.hoshi.features.sync.http.HttpSyncBookmarkBlob
import moe.antimony.hoshi.features.sync.http.HttpSyncContentType
import moe.antimony.hoshi.features.sync.http.HttpSyncDeletedBookRecord
import moe.antimony.hoshi.features.sync.http.HttpSyncMetadataBlob
import moe.antimony.hoshi.features.sync.http.HttpSyncPayloadManifest
import moe.antimony.hoshi.features.sync.http.appleSecondsToRfc3339
import moe.antimony.hoshi.features.sync.http.chatEntryKeySuffix
import moe.antimony.hoshi.features.sync.http.chatKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.File
import java.time.Instant

/**
 * Pure-function tests for [V3Planner]. Each test constructs hand-crafted snapshots and
 * asserts the exact action list (including ordering / bucket sort).
 */
class V3PlannerTest {

    private val planner = V3Planner()

    // --- helpers --------------------------------------------------------------

    private fun localBook(
        syncId: String,
        title: String = syncId.replace('_', ' '),
        bookId: String = "id-$syncId",
        root: File = File("/local/$syncId"),
        contentType: ContentType = ContentType.Mokuro,
        shelfName: String? = null,
        shelfUpdatedAt: String? = null,
        bookmark: Bookmark? = null,
        chatEntries: List<AiChatEntry> = emptyList(),
        pendingDeletion: HttpSyncDeletedBookRecord? = null,
    ) = V3LocalBook(
        bookId = bookId,
        syncId = syncId,
        title = title,
        root = root,
        contentType = contentType,
        shelfName = shelfName,
        shelfUpdatedAt = shelfUpdatedAt,
        bookmark = bookmark,
        chatEntries = chatEntries,
        pendingDeletion = pendingDeletion,
    )

    private fun remoteBook(
        syncId: String,
        metadata: HttpSyncMetadataBlob? = null,
        manifest: HttpSyncPayloadManifest? = null,
        bookmark: HttpSyncBookmarkBlob? = null,
        chatKeys: Set<String> = emptySet(),
    ) = V3RemoteBook(
        syncId = syncId,
        metadata = metadata,
        metadataLastModified = metadata?.shelfUpdatedAt,
        manifest = manifest,
        manifestLastModified = null,
        bookmark = bookmark,
        bookmarkLastModified = bookmark?.lastModified,
        chatKeys = chatKeys,
    )

    private fun snapshot(
        local: List<V3LocalBook> = emptyList(),
        aiSettings: AiChatSettings? = null,
    ) = V3LocalSnapshot(
        books = local,
        aiSettings = aiSettings,
        readAt = Instant.parse("2026-05-15T00:00:00Z"),
    )

    private fun remoteSnapshot(
        books: List<V3RemoteBook> = emptyList(),
        aiSettings: HttpSyncAiChatSettingsBlob? = null,
    ) = V3RemoteSnapshot(
        books = books.associateBy { it.syncId },
        aiSettings = aiSettings,
        aiSettingsLastModified = aiSettings?.lastModified,
    )

    private fun manifest(syncId: String) = HttpSyncPayloadManifest(
        sha256 = "sha256:0",
        sizeBytes = 1,
        originalName = syncId,
        format = HttpSyncContentType.Mokuro,
    )

    // --- empty / trivial ------------------------------------------------------

    @Test
    fun emptyLocalAndEmptyRemoteProducesEmptyPlan() {
        val plan = planner.compute(snapshot(), remoteSnapshot())
        assertEquals(emptyList<V3Action>(), plan.actions)
        assertEquals(emptySet<String>(), plan.pendingRemoteOnlyBooks)
    }

    @Test
    fun localOnlyEmitsOnlyPushActions() {
        val book = localBook(
            "a_book",
            bookmark = Bookmark(1, 0.0, 1, 800_000_000.0),
        )
        val plan = planner.compute(snapshot(local = listOf(book)), remoteSnapshot())

        val kinds = plan.actions.map { it::class.simpleName!! }
        assertTrue("plan should contain a PushBookmark, got $kinds", kinds.contains("PushBookmark"))
        assertTrue("plan should contain a PushMetadata, got $kinds", kinds.contains("PushMetadata"))
        assertTrue("plan should contain a PushPayload, got $kinds", kinds.contains("PushPayload"))
        // No apply-side actions.
        assertFalse(kinds.contains("ApplyRemoteBookmark"))
        assertFalse(kinds.contains("ApplyRemoteMetadata"))
    }

    // --- remote-only with manifest -------------------------------------------

    @Test
    fun remoteOnlyWithManifestEmitsImportAndApplyActions() {
        val r = remoteBook(
            "remote_only",
            manifest = manifest("remote_only"),
            bookmark = HttpSyncBookmarkBlob(5, 0.0, 5, "2030-01-01T00:00:00Z"),
        )
        val plan = planner.compute(snapshot(), remoteSnapshot(listOf(r)))

        val kinds = plan.actions.map { it::class.simpleName!! }
        assertTrue(kinds.contains("ImportRemoteBook"))
        assertTrue(kinds.contains("ApplyRemoteBookmark"))
        assertEquals(emptySet<String>(), plan.pendingRemoteOnlyBooks)
    }

    @Test
    fun remoteOnlyWithoutManifestGoesIntoPendingSet() {
        val r = remoteBook(
            "no_manifest_yet",
            bookmark = HttpSyncBookmarkBlob(1, 0.0, 1, "2030-01-01T00:00:00Z"),
        )
        val plan = planner.compute(snapshot(), remoteSnapshot(listOf(r)))

        assertEquals(setOf("no_manifest_yet"), plan.pendingRemoteOnlyBooks)
        assertTrue("no actions emitted for pending-only book", plan.actions.none { it.syncId == "no_manifest_yet" })
    }

    // --- bookmark LWW ---------------------------------------------------------

    @Test
    fun bookmarkLwwRemoteNewerEmitsApply() {
        val l = localBook(
            "b",
            bookmark = Bookmark(1, 0.0, 1, 100.0), // very old apple-seconds
        )
        val r = remoteBook(
            "b",
            bookmark = HttpSyncBookmarkBlob(9, 0.0, 9, "2099-01-01T00:00:00Z"),
        )
        val plan = planner.compute(snapshot(local = listOf(l)), remoteSnapshot(listOf(r)))

        val applies = plan.actions.filterIsInstance<V3Action.ApplyRemoteBookmark>()
        assertEquals(1, applies.size)
        assertEquals(9, applies.first().blob.chapterIndex)
        // No PushBookmark for this book.
        assertTrue(plan.actions.none { it is V3Action.PushBookmark && it.syncId == "b" })
    }

    @Test
    fun bookmarkLwwLocalNewerEmitsPush() {
        val l = localBook(
            "b",
            bookmark = Bookmark(7, 0.0, 7, 2_000_000_000.0),
        )
        val r = remoteBook(
            "b",
            bookmark = HttpSyncBookmarkBlob(1, 0.0, 1, "2000-01-01T00:00:00Z"),
        )
        val plan = planner.compute(snapshot(local = listOf(l)), remoteSnapshot(listOf(r)))

        val pushes = plan.actions.filterIsInstance<V3Action.PushBookmark>()
        assertEquals(1, pushes.size)
        assertEquals(7, pushes.first().bookmark.chapterIndex)
    }

    @Test
    fun bookmarkLwwTieEmitsNoBookmarkAction() {
        val stamp = 1_500_000_000.0
        val l = localBook("b", bookmark = Bookmark(3, 0.0, 3, stamp))
        val r = remoteBook(
            "b",
            bookmark = HttpSyncBookmarkBlob(3, 0.0, 3, appleSecondsToRfc3339(stamp)),
        )
        val plan = planner.compute(snapshot(local = listOf(l)), remoteSnapshot(listOf(r)))

        assertTrue(
            "no bookmark action expected for tie, got ${plan.actions}",
            plan.actions.none { it is V3Action.PushBookmark || it is V3Action.ApplyRemoteBookmark },
        )
    }

    // --- chat set-union -------------------------------------------------------

    @Test
    fun chatSetUnionEmitsImportForRemoteOnlyAndPushForLocalOnly() {
        val syncId = "chat"
        val localEntry = AiChatEntry("loc", "p", "m", "r", 1.0)
        val remoteEntry = AiChatEntry("rem", "p", "m", "r2", 2.0)
        val l = localBook(
            syncId,
            chatEntries = listOf(localEntry),
        )
        val remoteKey = chatKey(syncId, chatEntryKeySuffix(remoteEntry.timestampSeconds, remoteEntry.bubbleText, remoteEntry.response))
        val r = remoteBook(syncId, chatKeys = setOf(remoteKey))
        val plan = planner.compute(snapshot(local = listOf(l)), remoteSnapshot(listOf(r)))

        val imports = plan.actions.filterIsInstance<V3Action.ImportChat>()
        val pushes = plan.actions.filterIsInstance<V3Action.PushChat>()
        assertEquals("expected one ImportChat for remote-only entry", 1, imports.size)
        assertEquals(remoteKey, imports.first().key)
        assertEquals("expected one PushChat for local-only entry", 1, pushes.size)
        assertEquals(localEntry, pushes.first().entry)
    }

    @Test
    fun chatSetUnionDedupesContentAddressableMatch() {
        val syncId = "chat_dup"
        val entry = AiChatEntry("hi", "p", "m", "r", 5.0)
        val key = chatKey(syncId, chatEntryKeySuffix(entry.timestampSeconds, entry.bubbleText, entry.response))
        val l = localBook(syncId, chatEntries = listOf(entry))
        val r = remoteBook(syncId, chatKeys = setOf(key))
        val plan = planner.compute(snapshot(local = listOf(l)), remoteSnapshot(listOf(r)))

        assertTrue(plan.actions.none { it is V3Action.ImportChat })
        assertTrue(plan.actions.none { it is V3Action.PushChat })
    }

    // --- tombstones -----------------------------------------------------------

    @Test
    fun remoteTombstoneOverridesEverythingElseForSyncId() {
        val l = localBook(
            "tomb",
            bookmark = Bookmark(1, 0.0, 1, 100.0),
            chatEntries = listOf(AiChatEntry("c", "p", "m", "r", 1.0)),
        )
        val r = remoteBook(
            "tomb",
            metadata = HttpSyncMetadataBlob(
                title = "Tomb",
                contentType = HttpSyncContentType.Mokuro,
                deletedAt = "2030-06-01T00:00:00Z",
            ),
            bookmark = HttpSyncBookmarkBlob(99, 0.0, 99, "2099-01-01T00:00:00Z"),
            chatKeys = setOf("books/tomb/chat/fake"),
        )
        val plan = planner.compute(snapshot(local = listOf(l)), remoteSnapshot(listOf(r)))

        val kinds = plan.actions.map { it::class.simpleName!! }
        assertTrue("tombstone must emit DeleteLocalBook", kinds.contains("DeleteLocalBook"))
        assertTrue("tombstone must emit ApplyRemoteMetadata", kinds.contains("ApplyRemoteMetadata"))
        assertFalse("no PushBookmark when tombstoned", kinds.contains("PushBookmark"))
        assertFalse("no ApplyRemoteBookmark when tombstoned", kinds.contains("ApplyRemoteBookmark"))
        assertFalse("no ImportChat when tombstoned", kinds.contains("ImportChat"))
        assertFalse("no PushMetadata when tombstoned", kinds.contains("PushMetadata"))
    }

    @Test
    fun localPendingDeletionOverridesEverythingElseForSyncId() {
        val pending = HttpSyncDeletedBookRecord(
            title = "Gone",
            contentType = HttpSyncContentType.Mokuro,
            deletedAt = "2030-01-01T00:00:00Z",
        )
        val l = localBook(
            "gone",
            pendingDeletion = pending,
            bookmark = Bookmark(1, 0.0, 1, 100.0),
        )
        val r = remoteBook(
            "gone",
            bookmark = HttpSyncBookmarkBlob(9, 0.0, 9, "2099-01-01T00:00:00Z"),
        )
        val plan = planner.compute(snapshot(local = listOf(l)), remoteSnapshot(listOf(r)))

        val tombstones = plan.actions.filterIsInstance<V3Action.PushTombstone>()
        assertEquals(1, tombstones.size)
        assertEquals("gone", tombstones.first().syncId)
        // No bookmark push/apply for this syncId.
        assertTrue(plan.actions.none { it is V3Action.PushBookmark && it.syncId == "gone" })
        assertTrue(plan.actions.none { it is V3Action.ApplyRemoteBookmark && it.syncId == "gone" })
    }

    // --- shelf merge ----------------------------------------------------------

    @Test
    fun shelfMergeRemoteNewerEmitsApplyRemoteMetadata() {
        val l = localBook(
            "shelf",
            shelfName = "Old",
            shelfUpdatedAt = "2020-01-01T00:00:00Z",
        )
        val r = remoteBook(
            "shelf",
            metadata = HttpSyncMetadataBlob(
                title = "Shelf",
                contentType = HttpSyncContentType.Mokuro,
                shelfName = "Reading",
                shelfUpdatedAt = "2030-01-01T00:00:00Z",
            ),
        )
        val plan = planner.compute(snapshot(local = listOf(l)), remoteSnapshot(listOf(r)))

        val applies = plan.actions.filterIsInstance<V3Action.ApplyRemoteMetadata>()
        assertEquals(1, applies.size)
        assertEquals("Reading", applies.first().blob.shelfName)
    }

    @Test
    fun shelfMergeLocalNewerSkipsApply() {
        val l = localBook(
            "shelf2",
            shelfName = "Local",
            shelfUpdatedAt = "2050-01-01T00:00:00Z",
        )
        val r = remoteBook(
            "shelf2",
            metadata = HttpSyncMetadataBlob(
                title = "Shelf 2",
                contentType = HttpSyncContentType.Mokuro,
                shelfName = "Older",
                shelfUpdatedAt = "2020-01-01T00:00:00Z",
            ),
        )
        val plan = planner.compute(snapshot(local = listOf(l)), remoteSnapshot(listOf(r)))

        assertTrue(
            "expected no ApplyRemoteMetadata when local is newer, got ${plan.actions}",
            plan.actions.none { it is V3Action.ApplyRemoteMetadata && it.syncId == "shelf2" },
        )
    }

    // --- ordering -------------------------------------------------------------

    @Test
    fun actionsAreEmittedInDocumentedOrder() {
        // One pending local deletion + one remote tombstone + one remote-only import +
        // local bookmark push.
        val pending = HttpSyncDeletedBookRecord("A", HttpSyncContentType.Mokuro, "2030-01-01T00:00:00Z")
        val a = localBook("a", pendingDeletion = pending)
        val b = localBook("c", bookmark = Bookmark(1, 0.0, 1, 2_000_000_000.0))
        val remoteB = remoteBook(
            "b",
            metadata = HttpSyncMetadataBlob(
                title = "B",
                contentType = HttpSyncContentType.Mokuro,
                deletedAt = "2030-01-01T00:00:00Z",
            ),
        )
        // No local for b → no DeleteLocalBook action emitted for b (nothing to delete).
        val remoteD = remoteBook("d", manifest = manifest("d"))
        val plan = planner.compute(
            snapshot(local = listOf(a, b)),
            remoteSnapshot(listOf(remoteB, remoteD)),
        )

        val kinds = plan.actions.map { it::class.simpleName!! }
        // PushTombstone < ApplyRemoteMetadata < ImportRemoteBook < PushBookmark < PushMetadata < PushPayload.
        val idxTomb = kinds.indexOf("PushTombstone")
        val idxApply = kinds.indexOf("ApplyRemoteMetadata")
        val idxImport = kinds.indexOf("ImportRemoteBook")
        val idxPushBookmark = kinds.indexOf("PushBookmark")
        val idxPushMetadata = kinds.indexOf("PushMetadata")
        assertTrue("PushTombstone should appear: $kinds", idxTomb >= 0)
        assertTrue("ApplyRemoteMetadata should appear: $kinds", idxApply >= 0)
        assertTrue("ImportRemoteBook should appear: $kinds", idxImport >= 0)
        assertTrue("PushBookmark should appear: $kinds", idxPushBookmark >= 0)
        assertTrue("PushMetadata should appear: $kinds", idxPushMetadata >= 0)
        assertTrue("PushTombstone before ApplyRemoteMetadata", idxTomb < idxApply)
        assertTrue("ApplyRemoteMetadata before ImportRemoteBook", idxApply < idxImport)
        assertTrue("ImportRemoteBook before PushBookmark", idxImport < idxPushBookmark)
        assertTrue("PushBookmark before PushMetadata", idxPushBookmark < idxPushMetadata)
    }

    @Test
    fun withinBucketActionsAreSortedBySyncId() {
        val b1 = localBook("zebra", bookmark = Bookmark(1, 0.0, 1, 2_000_000_000.0))
        val b2 = localBook("aardvark", bookmark = Bookmark(1, 0.0, 1, 2_000_000_000.0))
        val plan = planner.compute(snapshot(local = listOf(b1, b2)), remoteSnapshot())

        val pushBookmarks = plan.actions.filterIsInstance<V3Action.PushBookmark>().map { it.syncId }
        assertEquals(listOf("aardvark", "zebra"), pushBookmarks)
    }

    // --- app settings LWW -----------------------------------------------------

    @Test
    fun appSettingsLocalNewerEmitsPush() {
        val local = AiChatSettings(model = "m1", lastEditedAt = "2030-01-01T00:00:00Z")
        val remote = HttpSyncAiChatSettingsBlob(
            model = "m0", promptText = "x", imagePromptText = "y", lastModified = "2020-01-01T00:00:00Z",
        )
        val plan = planner.compute(snapshot(aiSettings = local), remoteSnapshot(aiSettings = remote))
        assertTrue(plan.actions.any { it is V3Action.PushAiSettings })
        assertFalse(plan.actions.any { it is V3Action.ApplyAiSettings })
    }

    @Test
    fun appSettingsRemoteNewerEmitsApply() {
        val local = AiChatSettings(model = "m0", lastEditedAt = "2020-01-01T00:00:00Z")
        val remote = HttpSyncAiChatSettingsBlob(
            model = "m1", promptText = "x", imagePromptText = "y", lastModified = "2030-01-01T00:00:00Z",
        )
        val plan = planner.compute(snapshot(aiSettings = local), remoteSnapshot(aiSettings = remote))
        assertTrue(plan.actions.any { it is V3Action.ApplyAiSettings })
        assertFalse(plan.actions.any { it is V3Action.PushAiSettings })
    }

    @Test
    fun appSettingsTieEmitsNoAction() {
        val stamp = "2025-01-01T00:00:00Z"
        val local = AiChatSettings(model = "m", lastEditedAt = stamp)
        val remote = HttpSyncAiChatSettingsBlob(
            model = "m", promptText = "x", imagePromptText = "y", lastModified = stamp,
        )
        val plan = planner.compute(snapshot(aiSettings = local), remoteSnapshot(aiSettings = remote))
        assertFalse(plan.actions.any { it is V3Action.PushAiSettings })
        assertFalse(plan.actions.any { it is V3Action.ApplyAiSettings })
    }

    // --- syncId collision across content types --------------------------------

    @Test
    fun syncIdCollisionAcrossContentTypesProducesStablePlan() {
        // Both sides have a book with the same syncId but different content types
        // (e.g., A imported Mokuro "X", B imported EPUB "X").
        val l = localBook("x", contentType = ContentType.Epub)
        val r = remoteBook(
            "x",
            manifest = HttpSyncPayloadManifest(
                sha256 = "sha256:0", sizeBytes = 1,
                originalName = "x", format = HttpSyncContentType.Mokuro,
            ),
        )
        // Planner sees a manifest on the server; since local exists, no ImportRemoteBook.
        val plan = planner.compute(snapshot(local = listOf(l)), remoteSnapshot(listOf(r)))
        // The local book has a payload manifest on the server, so PushPayload is skipped
        // (manifest-existence policy). PushMetadata still emitted for the local book.
        assertNull(
            "no PushPayload because remote already has a manifest",
            plan.actions.filterIsInstance<V3Action.PushPayload>()
                .firstOrNull { it.syncId == "x" },
        )
        assertNotNull(
            "still pushes its local metadata",
            plan.actions.filterIsInstance<V3Action.PushMetadata>()
                .firstOrNull { it.syncId == "x" },
        )
    }
}
