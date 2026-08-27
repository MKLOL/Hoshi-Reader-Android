package moe.antimony.hoshi.features.sync.http

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Cross-platform conformance vectors for the pure sync decision functions.
 *
 * **iOS mirrors this exact table** (same inputs, same expected outcomes) against its
 * `SyncCore.compareRevisioned` / `SyncCore.shouldApplyRemoteShelfPlacement`. If a row here
 * changes, the iOS table must change in lockstep — these two functions decide which device
 * wins a sync conflict, so any divergence corrupts cross-device state.
 */
class SyncConformanceTest {

    private data class RevCase(
        val name: String,
        val localRev: Int?,
        val remoteRev: Int?,
        val localStamp: String?,
        val remoteStamp: String?,
        val expected: SyncComparison,
    )

    private val older = "2030-01-01T00:00:00Z"
    private val newer = "2030-01-02T00:00:00Z"

    @Test
    fun compareRevisionedConformance() {
        val cases = listOf(
            // All four nil/0 combos: legacy blobs degrade to pure stamp LWW. NOTE: a
            // deliberate edit (rev >= 1) intentionally beats ANY legacy rev-less blob
            // regardless of stamps — each pre-rollout server key is exposed to that once,
            // until its first revisioned write.
            RevCase("null vs null, newer remote stamp → stamp fallback, remote", null, null, older, newer, SyncComparison.REMOTE_WINS),
            RevCase("0 vs 0, newer local stamp → stamp fallback, local", 0, 0, newer, older, SyncComparison.LOCAL_WINS),
            RevCase("0 vs null, newer local stamp → stamp fallback, local", 0, null, newer, older, SyncComparison.LOCAL_WINS),
            RevCase("null vs 0, newer remote stamp → stamp fallback, remote", null, 0, older, newer, SyncComparison.REMOTE_WINS),
            // Event time dominates revision depth, including upgraded clients without sidecars.
            RevCase("2 vs 1, newer remote stamp → remote", 2, 1, older, newer, SyncComparison.REMOTE_WINS),
            RevCase("1 vs 2, newer local stamp → local", 1, 2, newer, older, SyncComparison.LOCAL_WINS),
            // Equal revs → timestamps break the tie.
            RevCase("same rev, newer local stamp → local", 3, 3, newer, older, SyncComparison.LOCAL_WINS),
            RevCase("same rev, newer remote stamp → remote", 3, 3, older, newer, SyncComparison.REMOTE_WINS),
            RevCase("same rev, same stamp → tie", 3, 3, newer, newer, SyncComparison.TIE),
            RevCase("nil rev with newer local stamp → local", null, 3, newer, older, SyncComparison.LOCAL_WINS),
            RevCase("newer remote stamp beats local rev", 3, null, older, newer, SyncComparison.REMOTE_WINS),
        )
        for (case in cases) {
            assertEquals(
                case.name,
                case.expected,
                compareRevisioned(
                    localRev = case.localRev,
                    remoteRev = case.remoteRev,
                    localStamp = case.localStamp,
                    remoteStamp = case.remoteStamp,
                ),
            )
        }
    }

    /**
     * Pins the legacy-overwrite case explicitly: a local rev = 1 (the user made a deliberate
     * edit on a revisioned build) beats a remote legacy blob with `rev = null` even when the
     * remote stamp is NEWER. This is the intended migration semantics: rev-less blobs are
     * rev 0 by definition, so the first deliberate edit after the rollout wins the key once
     * and converts it to revisioned state; from then on normal rev-then-stamp ordering
     * applies. Each pre-rollout server key is exposed to this overwrite exactly once.
     */
    @Test
    fun newerLegacyTimestampBeatsOlderRevisionedEdit() {
        assertEquals(
            SyncComparison.REMOTE_WINS,
            compareRevisioned(
                localRev = 1,
                remoteRev = null,
                localStamp = older,
                remoteStamp = newer,
            ),
        )
    }

    private data class ShelfCase(
        val name: String,
        val remoteShelfUpdatedAt: String?,
        val localShelvesUpdatedAt: String?,
        val expected: Boolean,
    )

    @Test
    fun shouldApplyRemoteShelfPlacementConformance() {
        val cases = listOf(
            ShelfCase("null local → remote applies", remoteShelfUpdatedAt = older, localShelvesUpdatedAt = null, expected = true),
            ShelfCase("null remote → local kept", remoteShelfUpdatedAt = null, localShelvesUpdatedAt = older, expected = false),
            ShelfCase("remote newer → remote applies", remoteShelfUpdatedAt = newer, localShelvesUpdatedAt = older, expected = true),
            ShelfCase("tie → remote applies (converges)", remoteShelfUpdatedAt = newer, localShelvesUpdatedAt = newer, expected = true),
            ShelfCase("remote older → local kept", remoteShelfUpdatedAt = older, localShelvesUpdatedAt = newer, expected = false),
        )
        for (case in cases) {
            assertEquals(
                case.name,
                case.expected,
                shouldApplyRemoteShelfPlacement(
                    remoteShelfUpdatedAt = case.remoteShelfUpdatedAt,
                    localShelvesUpdatedAt = case.localShelvesUpdatedAt,
                ),
            )
        }
    }

    /**
     * Wire-format conformance: the production Json config (encodeDefaults = true, default
     * explicitNulls) must emit `"rev":null` for legacy/unrevisioned blobs — iOS encodes
     * every key explicitly, with `null` for absent values, and both platforms must produce
     * the same key set on the wire.
     */
    @Test
    fun revFieldIsAlwaysEmittedOnTheWire() {
        val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }
        val unrevisioned = json.encodeToString(
            HttpSyncBookmarkBlob.serializer(),
            HttpSyncBookmarkBlob(chapterIndex = 1, progress = 0.5, characterCount = 10, lastModified = older),
        )
        assertTrue("legacy blob must carry \"rev\":null like iOS: $unrevisioned", "\"rev\":null" in unrevisioned)
        val revisioned = json.encodeToString(
            HttpSyncBookmarkBlob.serializer(),
            HttpSyncBookmarkBlob(chapterIndex = 1, progress = 0.5, characterCount = 10, lastModified = older, rev = 3),
        )
        assertTrue("revisioned blob must carry \"rev\":3: $revisioned", "\"rev\":3" in revisioned)
        // Legacy blobs (no rev key at all) must still decode, with rev == null.
        val decodedLegacy = json.decodeFromString(
            HttpSyncBookmarkBlob.serializer(),
            """{"chapterIndex":1,"progress":0.5,"characterCount":10,"lastModified":"$older"}""",
        )
        assertEquals(null, decodedLegacy.rev)
    }
}
