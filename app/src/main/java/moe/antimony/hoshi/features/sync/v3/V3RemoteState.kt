package moe.antimony.hoshi.features.sync.v3

import moe.antimony.hoshi.features.sync.http.HttpSyncKvTransport

/**
 * Step 2 of the v3 algorithm. Reads everything an immediate sync would need to know
 * about the remote KV server into a [V3RemoteSnapshot].
 *
 * Implementation guidance: see `docs/SYNC_V3_SPEC.md` § Step 2.
 *
 *  - LIST `books/` paginated until exhausted (no `since=` filter — v3.0 does a full
 *    pull every time; see [V3SyncEngine] kdoc).
 *  - LIST `app/` paginated, just for the global ai_chat_settings key.
 *  - For every key returned, GROUP by syncId and KIND. Chat keys go straight into
 *    [V3RemoteBook.chatKeys] (no body fetch here).
 *  - For metadata, manifest, bookmark, ai_chat_settings: FETCH the body so the
 *    planner can decide.
 *  - Decoding errors on individual blobs surface as a per-key error in the returned
 *    snapshot's errors list (NEW: pass back via a different return type if needed,
 *    or simply skip and let the executor's per-action error path catch them on retry).
 *    Recommend: return both [V3RemoteSnapshot] and a `List<V3Error>` and let the
 *    engine append them to the executor's errors.
 */
class V3RemoteState {
    /**
     * @return a snapshot of the server state. Any per-key decoding errors are
     *   embedded in the returned [V3RemoteSnapshotResult.errors] list so the engine
     *   can surface them in the final [V3SyncResult].
     */
    suspend fun read(
        transport: HttpSyncKvTransport,
        onProgress: suspend (V3Progress) -> Unit,
    ): V3RemoteSnapshotResult {
        TODO("Implementation agent: fill per V3_SPEC § Step 2")
    }
}

/** Pair of snapshot + per-key errors. See [V3RemoteState.read]. */
data class V3RemoteSnapshotResult(
    val snapshot: V3RemoteSnapshot,
    val errors: List<V3Error>,
)
