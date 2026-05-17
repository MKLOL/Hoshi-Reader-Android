package moe.antimony.hoshi.features.sync.v3

/**
 * Step 3 of the v3 algorithm — **PURE**. Given a snapshot of local and remote
 * state, produces a deterministic [V3Plan]. No I/O, no transport, no side effects.
 *
 * Implementation guidance: see `docs/SYNC_V3_SPEC.md` § Step 3 for the full set of
 * planner rules. The implementation must:
 *  - Be testable in isolation with hand-crafted snapshots (see [V3PlannerTest]).
 *  - Produce actions in the ordering listed in the spec.
 *  - Sort within each action-kind bucket by syncId so the output is byte-for-byte
 *    reproducible across runs.
 *
 * Notes for the implementation agent:
 *  - LWW on bookmark uses [moe.antimony.hoshi.features.sync.http.compareRfc3339] +
 *    [moe.antimony.hoshi.features.sync.http.appleSecondsToRfc3339] to make local
 *    `Bookmark.lastModified` (Apple reference seconds) comparable to remote
 *    `HttpSyncBookmarkBlob.lastModified` (RFC 3339).
 *  - Shelf merge uses the same logic v2 has in `HttpSyncReconciler` —
 *    `shouldApplyRemoteShelfPlacement` style. Reuse the helpers if visible; otherwise
 *    re-implement with identical semantics.
 *  - Tombstones short-circuit everything else for that syncId.
 *  - Local pending-deletion short-circuits everything else for that syncId.
 *  - Pending-remote-only set holds syncIds for which remote has any keys but no
 *    manifest — they go into [V3Plan.pendingRemoteOnlyBooks] so the result can
 *    surface the count without firing any action.
 */
class V3Planner {
    fun compute(local: V3LocalSnapshot, remote: V3RemoteSnapshot): V3Plan {
        TODO("Implementation agent: fill per V3_SPEC § Step 3")
    }
}
