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
        importedAt: String? = null,
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
        importedAt = importedAt,
    )

    private fun remoteBook(
        syncId: String,
        metadata: HttpSyncMetadataBlob? = null,
        manifest: HttpSyncPayloadManifest? = null,
        bookmark: HttpSyncBookmarkBlob? = null,
        chatKeys: Set<String> = emptySet(),
        metadataMalformed: Boolean = false,
        manifestMalformed: Boolean = false,
        bookmarkMalformed: Boolean = false,
    ) = V3RemoteBook(
        syncId = syncId,
        metadata = metadata,
        metadataLastModified = metadata?.shelfUpdatedAt,
        manifest = manifest,
        manifestLastModified = null,
        bookmark = bookmark,
        bookmarkLastModified = bookmark?.lastModified,
        chatKeys = chatKeys,
        metadataMalformed = metadataMalformed,
        manifestMalformed = manifestMalformed,
        bookmarkMalformed = bookmarkMalformed,
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

    // --- re-import-after-tombstone (importedAt > deletedAt) ------------------
    //
    // The protocol contract: when a local book has been freshly re-imported AFTER a
    // remote tombstone was published, the import wins — the planner must NOT emit a
    // `DeleteLocalBook` and must emit a `PushMetadata` whose blob carries
    // `deletedAt = null` and the local `importedAt`. When the local import stamp is
    // older (legacy book, or genuine post-tombstone import that we missed earlier),
    // the conservative behaviour is preserved: the tombstone wins.

    @Test
    fun reImportAfterTombstoneKeepsLocalAndPushesOverrideMetadata() {
        // Local has importedAt T2; remote tombstone has deletedAt T1 < T2. Both sides agree
        // on shelf placement (none) so the shelf-merge branch doesn't interfere with the
        // assertion that no DeleteLocalBook / no tombstone-bearing ApplyRemoteMetadata is
        // emitted for this syncId.
        val l = localBook(
            "reimport_after_tomb",
            importedAt = "2030-06-02T00:00:00Z",
        )
        val r = remoteBook(
            "reimport_after_tomb",
            metadata = HttpSyncMetadataBlob(
                title = "Re-Imported",
                contentType = HttpSyncContentType.Mokuro,
                deletedAt = "2030-06-01T00:00:00Z",
                importedAt = "2030-05-01T00:00:00Z",
            ),
        )
        val plan = planner.compute(snapshot(local = listOf(l)), remoteSnapshot(listOf(r)))

        val kinds = plan.actions.map { it::class.simpleName!! }
        assertFalse(
            "must NOT delete the freshly re-imported local book (kinds=$kinds)",
            kinds.contains("DeleteLocalBook"),
        )
        // The executor's ApplyRemoteMetadata path keys on `blob.deletedAt != null` to delete
        // the local copy — when we override the tombstone we must NOT hand it any blob that
        // still carries the stale `deletedAt`, no matter which branch emitted it.
        assertTrue(
            "must NOT hand executor a tombstone-bearing ApplyRemoteMetadata for the overridden syncId (kinds=$kinds)",
            plan.actions.none {
                it is V3Action.ApplyRemoteMetadata &&
                    it.syncId == "reimport_after_tomb" &&
                    it.blob.deletedAt != null
            },
        )
        // PushMetadata is emitted with `deletedAt = null` and the local importedAt.
        val pushes = plan.actions.filterIsInstance<V3Action.PushMetadata>()
            .filter { it.syncId == "reimport_after_tomb" }
        assertEquals(1, pushes.size)
        val pushed = pushes.single().blob
        assertNull("push must clear the server tombstone", pushed.deletedAt)
        assertEquals(
            "push must carry the local importedAt verbatim so peers compare against it",
            "2030-06-02T00:00:00Z",
            pushed.importedAt,
        )
    }

    @Test
    fun olderLocalImportedAtThanRemoteDeletedAtStillDeletesLocal() {
        // Local has importedAt T1; remote tombstone has deletedAt T2 > T1 → tombstone wins.
        val l = localBook(
            "stale_local",
            importedAt = "2030-05-01T00:00:00Z",
        )
        val r = remoteBook(
            "stale_local",
            metadata = HttpSyncMetadataBlob(
                title = "Stale Local",
                contentType = HttpSyncContentType.Mokuro,
                deletedAt = "2030-06-01T00:00:00Z",
            ),
        )
        val plan = planner.compute(snapshot(local = listOf(l)), remoteSnapshot(listOf(r)))

        val kinds = plan.actions.map { it::class.simpleName!! }
        assertTrue(
            "tombstone must still win when local was imported before the deletion",
            kinds.contains("DeleteLocalBook"),
        )
        assertTrue("must still apply remote tombstone metadata", kinds.contains("ApplyRemoteMetadata"))
        assertFalse("no metadata push when tombstone wins", kinds.contains("PushMetadata"))
    }

    @Test
    fun missingLocalImportedAtFallsBackToTombstoneWin() {
        // Legacy book: no importedAt at all → can't prove a post-tombstone import →
        // conservative path: tombstone wins.
        val l = localBook(
            "legacy_book",
            importedAt = null,
        )
        val r = remoteBook(
            "legacy_book",
            metadata = HttpSyncMetadataBlob(
                title = "Legacy",
                contentType = HttpSyncContentType.Mokuro,
                deletedAt = "2030-06-01T00:00:00Z",
            ),
        )
        val plan = planner.compute(snapshot(local = listOf(l)), remoteSnapshot(listOf(r)))

        val kinds = plan.actions.map { it::class.simpleName!! }
        assertTrue(
            "legacy book with no local importedAt must still honour the remote tombstone",
            kinds.contains("DeleteLocalBook"),
        )
        assertFalse("no metadata push when tombstone wins", kinds.contains("PushMetadata"))
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

    /**
     * Bug 7 reproducer. On a fresh install the AI-settings repository emits a non-null
     * default [AiChatSettings] with `lastEditedAt = null` (DataStore is empty). The
     * planner must treat that as "no real local settings yet" and pull the remote blob
     * — otherwise the device never learns about the server's settings.
     */
    @Test
    fun appSettingsFreshInstallWithRemoteEmitsApply() {
        // Mimic the repository's fresh-install emission: default object, no timestamp.
        val freshLocal = AiChatSettings(lastEditedAt = null)
        val remote = HttpSyncAiChatSettingsBlob(
            model = "remote-model",
            promptText = "remote prompt",
            imagePromptText = "remote image prompt",
            lastModified = "2030-01-01T00:00:00Z",
        )
        val plan = planner.compute(snapshot(aiSettings = freshLocal), remoteSnapshot(aiSettings = remote))
        assertTrue(
            "fresh install must pull remote AI settings, got ${plan.actions}",
            plan.actions.any { it is V3Action.ApplyAiSettings },
        )
        assertFalse(plan.actions.any { it is V3Action.PushAiSettings })
    }

    /**
     * Regression guard for the Bug 7 fix: when local settings DO have a real timestamp
     * and it's newer than remote, LWW must still keep local — the broadened
     * "localAiStamp == null" branch must not steal the LWW comparison.
     */
    @Test
    fun appSettingsLocalStampedAndNewerStillWinsAfterBug7Fix() {
        val local = AiChatSettings(model = "local-model", lastEditedAt = "2040-01-01T00:00:00Z")
        val remote = HttpSyncAiChatSettingsBlob(
            model = "remote-model",
            promptText = "x",
            imagePromptText = "y",
            lastModified = "2030-01-01T00:00:00Z",
        )
        val plan = planner.compute(snapshot(aiSettings = local), remoteSnapshot(aiSettings = remote))
        assertTrue(plan.actions.any { it is V3Action.PushAiSettings })
        assertFalse(plan.actions.any { it is V3Action.ApplyAiSettings })
    }

    // --- Bug 5: malformed remote must not be overwritten by local -------------

    /**
     * Bug 5 regression. When V3RemoteState fails to decode a remote metadata blob, it
     * surfaces an error AND marks the field as malformed on V3RemoteBook. The planner
     * MUST NOT push local metadata over the malformed remote bytes — that would destroy
     * the only remaining copy of corrupt data while the user is told there was an
     * error. Instead, the planner skips the PushMetadata and surfaces a V3Error.
     */
    @Test
    fun malformedRemoteMetadataSkipsPushMetadataAndEmitsError() {
        val l = localBook("m")
        val r = remoteBook("m", metadataMalformed = true)
        val plan = planner.compute(snapshot(local = listOf(l)), remoteSnapshot(listOf(r)))

        assertNull(
            "must NOT push metadata when remote metadata is malformed",
            plan.actions.filterIsInstance<V3Action.PushMetadata>()
                .firstOrNull { it.syncId == "m" },
        )
        assertTrue(
            "must surface a malformed-remote error, got ${plan.errors}",
            plan.errors.any { it.syncId == "m" && "malformed" in it.message.lowercase() },
        )
    }

    @Test
    fun malformedRemoteBookmarkSkipsPushBookmarkAndApplyAndEmitsError() {
        val l = localBook(
            "bm",
            bookmark = Bookmark(1, 0.0, 1, 2_000_000_000.0),
        )
        val r = remoteBook("bm", bookmarkMalformed = true)
        val plan = planner.compute(snapshot(local = listOf(l)), remoteSnapshot(listOf(r)))

        assertNull(
            "must NOT push bookmark when remote bookmark is malformed",
            plan.actions.filterIsInstance<V3Action.PushBookmark>()
                .firstOrNull { it.syncId == "bm" },
        )
        assertNull(
            "must NOT apply remote bookmark when malformed (decoded blob is absent anyway)",
            plan.actions.filterIsInstance<V3Action.ApplyRemoteBookmark>()
                .firstOrNull { it.syncId == "bm" },
        )
        assertTrue(
            "must surface a malformed-remote error, got ${plan.errors}",
            plan.errors.any { it.syncId == "bm" && "malformed" in it.message.lowercase() },
        )
    }

    @Test
    fun malformedRemoteAiSettingsSkipsPushAndEmitsError() {
        val localAi = AiChatSettings(model = "m", lastEditedAt = "2040-01-01T00:00:00Z")
        val local = snapshot(aiSettings = localAi)
        // Remote AI settings malformed: V3RemoteState leaves aiSettings null AND
        // sets aiSettingsMalformed = true on the snapshot.
        val remote = V3RemoteSnapshot(
            books = emptyMap(),
            aiSettings = null,
            aiSettingsLastModified = null,
            aiSettingsMalformed = true,
        )
        val plan = planner.compute(local, remote)

        assertFalse(
            "must NOT push AI settings when remote AI settings are malformed",
            plan.actions.any { it is V3Action.PushAiSettings },
        )
        assertTrue(
            "must surface a malformed-remote AI settings error, got ${plan.errors}",
            plan.errors.any { it.action.contains("AiSettings", ignoreCase = true) && "malformed" in it.message.lowercase() },
        )
    }

    // --- syncId collision across content types --------------------------------

    /**
     * Bug 6 regression. Local has an EPUB book and the server has a Mokuro payload
     * manifest for the same syncId (a different device imported the book as Mokuro
     * under the same title-derived syncId). The planner MUST NOT push EPUB-typed
     * metadata at the server — the metadata would point at a Mokuro payload and
     * corrupt every other client. Instead, the planner surfaces a `V3Error` for
     * the colliding syncId and emits no metadata / bookmark / chat push for it.
     */
    @Test
    fun syncIdCollisionAcrossContentTypesEmitsErrorAndSkipsPushMetadata() {
        // Both sides have a book with the same syncId but different content types
        // (e.g., A imported Mokuro "X", B imported EPUB "X"). Give local a bookmark
        // too so we can assert it ALSO gets blocked (don't push EPUB chapter offsets
        // against a Mokuro-typed book).
        val l = localBook(
            "x",
            contentType = ContentType.Epub,
            bookmark = Bookmark(1, 0.0, 1, 2_000_000_000.0),
        )
        val r = remoteBook(
            "x",
            metadata = HttpSyncMetadataBlob(
                title = "X",
                contentType = HttpSyncContentType.Mokuro,
                shelfUpdatedAt = "2030-01-01T00:00:00Z",
            ),
            manifest = HttpSyncPayloadManifest(
                sha256 = "sha256:0", sizeBytes = 1,
                originalName = "x", format = HttpSyncContentType.Mokuro,
            ),
        )
        val plan = planner.compute(snapshot(local = listOf(l)), remoteSnapshot(listOf(r)))

        // No PushPayload (manifest-existence policy already covers this).
        assertNull(
            "no PushPayload because remote already has a manifest",
            plan.actions.filterIsInstance<V3Action.PushPayload>()
                .firstOrNull { it.syncId == "x" },
        )
        // Bug 6: NO PushMetadata for the colliding syncId.
        assertNull(
            "must NOT push EPUB metadata when remote payload is Mokuro",
            plan.actions.filterIsInstance<V3Action.PushMetadata>()
                .firstOrNull { it.syncId == "x" },
        )
        // Bug 6: also block bookmark push — chapter index / progress are content-type
        // specific and would corrupt the Mokuro reader's view on other devices.
        assertNull(
            "must NOT push bookmark when remote payload is a different content type",
            plan.actions.filterIsInstance<V3Action.PushBookmark>()
                .firstOrNull { it.syncId == "x" },
        )
        // Surface the collision as a structured error.
        assertTrue(
            "planner must surface a V3Error for the colliding syncId, got ${plan.errors}",
            plan.errors.any { it.syncId == "x" },
        )
    }
}
