package moe.antimony.hoshi.features.sync.http

import android.util.Log
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import moe.antimony.hoshi.epub.BookRepository
import moe.antimony.hoshi.epub.ContentType
import java.io.File
import java.time.Instant

private const val TAG = "HttpSync"

/**
 * Fire-and-forget auto-push hooks for metadata-class edits (shelf moves, deletes, imports,
 * AI-settings edits). Mirrors iOS `HttpSyncManager`'s hook surface:
 *
 *  - [onShelfPlacementChanged] — records the placement in the shelf-state sidecar, bumps
 *    the metadata key's edit-depth rev, pushes the metadata blob.
 *  - [onBookImported] — bumps the rev and publishes the new book's metadata immediately
 *    (the large payload still uploads on manual sync only).
 *  - [onBookDeleted] — bumps the rev and pushes the tombstone immediately (the staged
 *    sidecar record from `BookshelfRepository.recordHttpSyncTombstone` stays as the retry
 *    path for the next manual sync).
 *  - [onAiSettingsChanged] — debounced ~2s, then runs the existing bidirectional
 *    app-settings LWW sync once (AI settings stay timestamp-LWW — a single small blob with
 *    no per-field merge to protect, so no rev bump; mirrors iOS).
 *
 * All hooks no-op silently when sync is disabled / unconfigured / suppressed; the local
 * bookkeeping (shelf sidecar record + rev bump) still happens so the next manual sync
 * carries the edit. Never throws into callers.
 *
 * **Offline circuit breaker:** shared across every hook on this instance (the container
 * holds exactly one), same thresholds as [HttpSyncReaderHooks]: after
 * [HttpSyncReaderHooks.CONSECUTIVE_FAILURE_THRESHOLD] consecutive failed pushes, suppress
 * for [HttpSyncReaderHooks.BACKOFF_MS] so a down server isn't hammered on every shelf
 * move. Reset by any success or a successful manual Sync now (via [breakerResetSignal]).
 */
class HttpSyncAutoPush(
    private val bookRepository: BookRepository,
    private val pusher: HttpSyncPusher,
    private val reconciler: HttpSyncReconciler,
    private val currentSettings: suspend () -> HttpSyncSettings?,
    private val scope: CoroutineScope,
    private val pushDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val clock: () -> Long = System::currentTimeMillis,
    private val onBooksChanged: () -> Unit = {},
    private val queueBookmark: suspend (File, String?, String?) -> Unit = { _, _, _ -> },
    /** See [HttpSyncReaderHooks]'s parameter of the same name. */
    private val breakerResetSignal: () -> Long = { 0L },
) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private val shelfStateStore = HttpSyncShelfStateStore(json)
    private val revisionStore = HttpSyncRevisionStore(json)
    private val payloadCodec = HttpSyncPayloadCodec()

    private val booksRoot: File get() = bookRepository.booksDirectory

    /**
     * Call after the user moves a book onto [shelfName] (`null` = unshelved). Records the
     * placement in the shelf-state sidecar, bumps the metadata key's rev, and pushes the
     * metadata blob. Mirrors iOS `HttpSyncManager.onShelfPlacementChanged`.
     */
    suspend fun onShelfPlacementChanged(
        title: String?,
        contentType: ContentType,
        importedAt: String?,
        shelfName: String?,
        persistedSyncId: String? = null,
    ) {
        val syncId = persistedSyncId ?: deriveSyncId(title) ?: return
        // Record placement locally even when offline/disabled — the next manual sync uses it.
        // Atomic per-key write (re-reads disk under the store's lock) so a concurrent
        // end-of-sync whole-map save by either engine can't clobber this move.
        val now = Instant.now().toString()
        shelfStateStore.recordPlacement(
            booksRoot,
            syncId,
            HttpSyncShelfPlacementRecord(shelfName = shelfName, updatedAt = now),
        )
        val localRev = revisionStore.bumpForLocalEdit(booksRoot, metadataKey(syncId))
        val settings = activeSettings() ?: return
        launchPush("metadata") {
            pusher.pushMetadata(
                HttpSyncMetadataUpload(
                    syncId = syncId,
                    title = title ?: syncId,
                    contentType = HttpSyncContentType.fromLocal(contentType),
                    shelfName = shelfName,
                    shelfUpdatedAt = now,
                    importedAt = importedAt,
                    deletedAt = null,
                    localRev = localRev,
                ),
                settings,
            )
        }
    }

    /**
     * Call right after a successful import. Publishes the book's metadata immediately so
     * other devices see it; the (large) payload still uploads on the next manual sync only.
     * Mirrors iOS `HttpSyncManager.onBookImported`.
     */
    suspend fun onBookImported(
        bookRoot: File,
        title: String?,
        contentType: ContentType,
        importedAt: String?,
        persistedSyncId: String? = null,
    ) {
        val syncId = persistedSyncId ?: deriveSyncId(title) ?: return
        payloadCodec.ensurePayloadContentSha(bookRoot)
        payloadCodec.markPayloadContentDirty(bookRoot)
        val localRev = revisionStore.bumpForLocalEdit(booksRoot, metadataKey(syncId))
        onBooksChanged()
        val settings = activeSettings() ?: return
        launchPush("metadata") {
            pusher.pushMetadata(
                HttpSyncMetadataUpload(
                    syncId = syncId,
                    title = title ?: syncId,
                    contentType = HttpSyncContentType.fromLocal(contentType),
                    shelfName = null,
                    shelfUpdatedAt = null,
                    importedAt = importedAt,
                    deletedAt = null,
                    localRev = localRev,
                ),
                settings,
            )
        }
    }

    /**
     * Call after a local delete has been staged in the deleted-book sidecar. Pushes the
     * tombstone immediately (the staged record stays as the retry path for manual sync).
     * Mirrors iOS `HttpSyncManager.onBookDeleted`.
     */
    suspend fun onBookDeleted(
        syncId: String,
        title: String,
        contentType: HttpSyncContentType,
        deletedAt: String,
    ) {
        val localRev = revisionStore.bumpForLocalEdit(booksRoot, metadataKey(syncId))
        onBooksChanged()
        val settings = activeSettings() ?: return
        launchPush("tombstone") {
            pusher.pushMetadata(
                HttpSyncMetadataUpload(
                    syncId = syncId,
                    title = title,
                    contentType = contentType,
                    shelfName = null,
                    shelfUpdatedAt = null,
                    importedAt = null,
                    deletedAt = deletedAt,
                    localRev = localRev,
                ),
                settings,
            )
        }
    }

    /**
     * Call after a bookmark-class edit made outside the reader (e.g. "Mark read" writing a
     * fresh end-of-book bookmark). Routes through the exact same bump + fire-and-forget
     * push path as the reader hooks: [HttpSyncPusher.pushBookmark] bumps the bookmark
     * key's edit-depth rev (one edit batch = one bump) before the network round-trip, so
     * the deliberate edit out-revisions stale remote bookmarks instead of relying on
     * timestamps alone.
     */
    suspend fun onBookmarkEdited(bookRoot: File, title: String?, persistedSyncId: String? = null) {
        queueBookmark(bookRoot, title, persistedSyncId)
    }

    /**
     * Call after the ChatGPT model/prompt settings change. Debounced (the prompt fields
     * edit per-keystroke); runs the existing bidirectional app-settings LWW once typing
     * settles. Mirrors iOS `HttpSyncManager.onAiSettingsChanged`.
     */
    fun onAiSettingsChanged() {
        scope.launch(pushDispatcher) {
            val settings = activeSettings() ?: return@launch
            scheduleAiSettingsPush(settings)
        }
    }

    private fun scheduleAiSettingsPush(settings: HttpSyncSettings) {
        synchronized(aiJobLock) {
            aiSettingsPushJob?.cancel()
            aiSettingsPushJob = scope.launch(pushDispatcher) {
                delay(AI_SETTINGS_DEBOUNCE_MS)
                val errors = runCatching { reconciler.syncAppSettingsOnly(settings) }
                    .getOrElse { listOf(it.message ?: it.javaClass.simpleName) }
                notePushOutcome("ai settings", success = errors.isEmpty(), error = errors.firstOrNull())
            }
        }
    }

    private val aiJobLock = Any()
    private var aiSettingsPushJob: Job? = null

    private fun launchPush(kind: String, block: suspend () -> Unit) {
        scope.launch(pushDispatcher) {
            runCatching { block() }
                .onSuccess { notePushOutcome(kind, success = true, error = null) }
                .onFailure { notePushOutcome(kind, success = false, error = it.message) }
        }
    }

    /** The current settings iff sync is configured and the breaker is closed. */
    private suspend fun activeSettings(): HttpSyncSettings? {
        val settings = runCatching { currentSettings() }.getOrNull() ?: return null
        if (!settings.isConfigured) return null
        if (breakerOpen()) return null
        return settings
    }

    // ----- Circuit breaker (same thresholds + reset-signal contract as the reader hooks) --

    private val breakerLock = Any()
    private var consecutiveFailures: Int = 0
    private var suppressUntilMs: Long = 0L
    private var trippedAtMs: Long = 0L

    private fun breakerOpen(): Boolean = synchronized(breakerLock) {
        // If a manual Sync now succeeded AFTER we tripped the breaker, clear it — the
        // server is reachable now, no reason to keep suppressing auto-pushes.
        val resetAt = breakerResetSignal()
        if (suppressUntilMs > 0L && resetAt > trippedAtMs) {
            consecutiveFailures = 0
            suppressUntilMs = 0L
            trippedAtMs = 0L
            return false
        }
        clock() < suppressUntilMs
    }

    private fun notePushOutcome(kind: String, success: Boolean, error: String?) {
        synchronized(breakerLock) {
            if (success) {
                consecutiveFailures = 0
                suppressUntilMs = 0L
                trippedAtMs = 0L
                return
            }
            consecutiveFailures += 1
            if (consecutiveFailures == HttpSyncReaderHooks.CONSECUTIVE_FAILURE_THRESHOLD) {
                val now = clock()
                trippedAtMs = now
                suppressUntilMs = now + HttpSyncReaderHooks.BACKOFF_MS
                Log.w(
                    TAG,
                    "Auto-push of $kind failed ${consecutiveFailures}x; suppressing for " +
                        "${HttpSyncReaderHooks.BACKOFF_MS / 1000}s: $error",
                )
            }
        }
    }

    private companion object {
        const val AI_SETTINGS_DEBOUNCE_MS: Long = 2_000L
    }
}
