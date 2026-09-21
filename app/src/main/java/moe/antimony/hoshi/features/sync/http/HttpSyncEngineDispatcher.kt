package moe.antimony.hoshi.features.sync.http

import moe.antimony.hoshi.features.sync.v3.V3Progress
import moe.antimony.hoshi.features.sync.v3.V3SyncEngine
import moe.antimony.hoshi.features.sync.v3.V3SyncResult

/**
 * Cutover safety net for the v2 → v3 sync engine swap.
 *
 * Reads [HttpSyncSettings.useV3Sync] and dispatches a single "Sync now" full-reconcile
 * call to either:
 *  - [HttpSyncReconciler] (the v2 engine, the production default), or
 *  - [V3SyncEngine] (the new engine).
 *
 * Both engines write the same on-disk + remote state (shared `.http_sync_*.json`
 * sidecars and identical KV keys), so the flag can be flipped at any time without a
 * data migration. The flag defaults to `false` (v2), and the v2 path is bit-for-bit
 * the production code path that shipped before the dispatcher existed.
 *
 * Reader-hook decision:
 *  Page-turn / chat-reply fire-and-forget pushes go through [HttpSyncPusher] from
 *  [HttpSyncReaderHooks] and **do not** consult this flag — they always use the v2
 *  push path, regardless of `useV3Sync`. That keeps a flag flip narrowly scoped to
 *  the full-reconcile path and lets us roll the v3 cutover back instantly without
 *  touching the reader. The downside is that with `useV3Sync = true` the reader
 *  hooks and the manual "Sync now" path use different push code paths; this is
 *  fine because both push paths produce wire-compatible KV writes.
 *  TODO(v3-cutover): once v3 is the default, decide whether reader hooks should
 *  also flip to a v3-native push entry point (`V3PushOps`-driven) or whether the
 *  v2 push code stays as the lightweight shared write path forever.
 *
 * The dispatcher converts v3's [V3SyncResult] / [V3Progress] into v2's
 * [HttpSyncResult] / [HttpSyncProgress] so existing UI (status line + summary) is
 * unchanged regardless of which engine actually ran. The translation is lossy
 * by design — `newLastSyncedAt` is always `null` for v3 because v3 does not use the
 * v2 `?since=` cursor (it lists fresh every pass).
 */
object HttpSyncEngineDispatcher {

    /**
     * Run one "Sync now" pass against the selected engine.
     *
     * @param reconciler the v2 engine (always supplied; selected when [HttpSyncSettings.useV3Sync] is false).
     * @param v3Engine the v3 engine (always supplied; selected when [HttpSyncSettings.useV3Sync] is true).
     * @param settings the user's HTTP sync settings, including the `useV3Sync` flag.
     * @param onProgress UI callback; receives [HttpSyncProgress] from either engine.
     */
    suspend fun syncOnce(
        reconciler: HttpSyncReconciler,
        v3Engine: V3SyncEngine,
        settings: HttpSyncSettings,
        transport: HttpSyncKvTransport? = null,
        onProgress: suspend (HttpSyncProgress) -> Unit = {},
    ): HttpSyncResult = syncOnce(
        settings = settings,
        v2 = { s, p -> reconciler.syncOnce(s, transport, p) },
        v3 = { s, p -> v3Engine.syncOnce(s, transport, p) },
        onProgress = onProgress,
    )

    /**
     * Lambda-keyed overload used by tests so we don't have to subclass the
     * final [HttpSyncReconciler] / [V3SyncEngine] classes. Production callers should
     * use the engine-typed overload above.
     */
    internal suspend fun syncOnce(
        settings: HttpSyncSettings,
        v2: suspend (HttpSyncSettings, suspend (HttpSyncProgress) -> Unit) -> HttpSyncResult,
        v3: suspend (HttpSyncSettings, suspend (V3Progress) -> Unit) -> V3SyncResult,
        onProgress: suspend (HttpSyncProgress) -> Unit = {},
    ): HttpSyncResult {
        return if (settings.useV3Sync) {
            val result = v3(settings) { progress ->
                onProgress(progress.toHttpSyncProgress())
            }
            result.toHttpSyncResult()
        } else {
            v2(settings, onProgress)
        }
    }
}

/**
 * v3 → v2 progress adapter. Both types are shape-compatible (message, detail,
 * completed, total). The v3 `phase` is intentionally dropped — the v2 UI doesn't
 * use it.
 */
internal fun V3Progress.toHttpSyncProgress(): HttpSyncProgress = HttpSyncProgress(
    message = message,
    detail = detail,
    completed = completed,
    total = total,
)

/**
 * v3 → v2 result adapter. v3's `applied` / `pushed` count buckets map onto v2's
 * uploaded/downloaded fields field-by-field. `newLastSyncedAt` is always `null`
 * because v3 doesn't use the `?since=` cursor (it re-lists fresh every pass), so
 * the caller should leave the stored cursor untouched on a v3 sync.
 */
internal fun V3SyncResult.toHttpSyncResult(): HttpSyncResult = HttpSyncResult(
    uploadedBookmarks = pushed.bookmarks,
    uploadedChatEntries = pushed.chatEntries,
    uploadedMetadata = pushed.metadata,
    uploadedPayloads = pushed.payloads,
    uploadedAppSettings = pushed.aiSettings > 0,
    downloadedBookmarks = applied.bookmarks,
    downloadedChatEntries = applied.chatEntries,
    downloadedPayloads = applied.payloads,
    downloadedSentenceTranslations = applied.sentenceTranslations,
    uploadedStatistics = pushed.statistics,
    downloadedStatistics = applied.statistics,
    downloadedAppSettings = applied.aiSettings > 0,
    remoteOnlyBooks = remoteOnlyBooks,
    errors = errors.map { e ->
        val prefix = listOfNotNull(e.syncId, e.action).joinToString(":")
        if (prefix.isEmpty()) e.message else "$prefix: ${e.message}"
    },
    newLastSyncedAt = null,
)
