package moe.antimony.hoshi.features.sync.http

import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import moe.antimony.hoshi.LocalHoshiAppContainer
import moe.antimony.hoshi.features.ai.AiChatEntry
import java.io.File

private const val TAG = "HttpSync"

/**
 * Reader-side auto-push hooks for the v2 KV sync, packaged so the call site in the manga
 * reader only takes one `remember…` and three method calls. Keeping the counter logic /
 * scope plumbing in this fork-owned file (instead of inline in
 * [moe.antimony.hoshi.features.mangareader.MangaReaderScreen]) makes upstream merge
 * conflicts a 3-line re-apply at most.
 *
 * Triggers, all fire-and-forget on the [persistenceScope]:
 *  - Every persisted page turn queues the latest bookmark for the five-second map exchange.
 *  - On reader leave: flush the queued bookmark immediately.
 *  - On every new ChatGPT response that gets persisted: PUT that one chat entry at its
 *    content-addressable key (write-once on the server; safe to retry).
 *
 * Gated by [HttpSyncSettings.isConfigured]. Network errors are swallowed — the next
 * manual "Sync now" tap will reconcile.
 *
 * **Offline circuit breaker:** after [CONSECUTIVE_FAILURE_THRESHOLD] consecutive failed
 * pushes we suppress new pushes for [BACKOFF_MS] milliseconds. This keeps the IO thread
 * pool clean on airplane mode / captive portal / dead server and reduces logcat noise.
 * The next successful push (or a manual Sync now) resets the breaker.
 */
class HttpSyncReaderHooks internal constructor(
    private val bookRoot: File,
    private val title: String,
    private val pushBookmark: suspend (File, String, HttpSyncSettings) -> Unit,
    private val pushChatEntry: suspend (String, AiChatEntry, HttpSyncSettings) -> Unit,
    private val currentSettings: () -> HttpSyncSettings?,
    private val persistenceScope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
    /**
     * Read each time we evaluate the circuit breaker. When the settings view fires a
     * successful manual Sync now, it bumps this signal to the current wallclock; if the
     * signal is at least as new as our [suppressUntilMs], the breaker resets — the
     * server is clearly reachable now, no reason to keep silencing auto-pushes.
     */
    private val breakerResetSignal: () -> Long = { 0L },
    private val persistedSyncId: String? = null,
    private val persistedSyncIdProvider: () -> String? = { persistedSyncId },
    private val identityReady: () -> Boolean = { true },
    private val pushBookmarkWithSyncId: (suspend (File, String, HttpSyncSettings, String?) -> Unit)? = null,
    private val pushChatEntryWithSyncId: (suspend (String, AiChatEntry, HttpSyncSettings, String?) -> Unit)? = null,
    private val queueBookmark: (suspend (File, String, String?) -> Unit)? = null,
    private val flushQueuedBookmarks: (() -> Unit)? = null,
    private val queueStatistics: ((File, String, String?) -> Unit)? = null,
    private val flushStatistics: ((File, String, String?) -> Unit)? = null,
) {
    private var unpushedPageTurns: Int = 0
    private var consecutiveFailures: Int = 0
    private var suppressUntilMs: Long = 0L
    /** Wallclock at which the breaker tripped. Used by [breakerOpen] for the reset-signal compare. */
    private var trippedAtMs: Long = 0L

    /** Call this from your existing post-save callback (after the local-bookmark file write). */
    fun onPageTurnPersisted() {
        if (queueBookmark != null) {
            persistenceScope.launch {
                queueBookmark.invoke(bookRoot, title, persistedSyncIdProvider())
            }
            return
        }
        unpushedPageTurns += 1
        if (unpushedPageTurns >= PAGE_TURN_PUSH_THRESHOLD) {
            flushBookmarkIfActivity()
        }
    }

    /** Call this after a statistics sidecar was written; the push is debounced per book. */
    fun onStatisticsPersisted() {
        if (!identityReady()) return
        if (activeSettings() == null) return
        queueStatistics?.invoke(bookRoot, title, persistedSyncIdProvider())
    }

    /** Call this from the reader's `onDispose` after the local-side flush has been scheduled. */
    fun onLeave() {
        if (identityReady() && activeSettings() != null) {
            flushStatistics?.invoke(bookRoot, title, persistedSyncIdProvider())
        }
        if (queueBookmark != null) {
            // Queue the bookmark in the same coroutine before starting the network flush. This
            // includes a final debounced save that completed during disposal and avoids a race
            // where flushNow observed the preceding outbox state.
            persistenceScope.launch {
                queueBookmark.invoke(bookRoot, title, persistedSyncIdProvider())
                flushQueuedBookmarks?.invoke()
            }
            return
        }
        flushBookmarkIfActivity()
    }

    /** Call this once for every chat entry that gets appended to the local log. */
    fun onChatEntryPersisted(entry: AiChatEntry) {
        if (!identityReady()) return
        val settings = activeSettings() ?: return
        if (breakerOpen()) return
        persistenceScope.launch {
            runCatching {
                pushChatEntryWithSyncId?.invoke(title, entry, settings, persistedSyncIdProvider())
                    ?: pushChatEntry(title, entry, settings)
            }
                .onSuccess { onPushSuccess() }
                .onFailure { onPushFailure("chat", it) }
        }
    }

    private fun flushBookmarkIfActivity() {
        if (unpushedPageTurns <= 0) return
        if (!identityReady()) return
        val settings = activeSettings() ?: return
        if (breakerOpen()) {
            // We're suppressed; keep the unpushed counter so a later success can pick up
            // the work. Reset it here would silently drop progress.
            return
        }
        unpushedPageTurns = 0
        persistenceScope.launch {
            runCatching {
                pushBookmarkWithSyncId?.invoke(bookRoot, title, settings, persistedSyncIdProvider())
                    ?: pushBookmark(bookRoot, title, settings)
            }
                .onSuccess { onPushSuccess() }
                .onFailure { onPushFailure("bookmark", it) }
        }
    }

    /** Returns the current settings iff sync is configured (base URL + bearer token set). */
    private fun activeSettings(): HttpSyncSettings? {
        val s = currentSettings() ?: return null
        if (!s.isConfigured) return null
        return s
    }

    private fun breakerOpen(): Boolean {
        // If a manual Sync now succeeded AFTER we tripped the breaker, clear it — the
        // server is reachable now, no reason to keep suppressing the reader's auto-push.
        val resetAt = breakerResetSignal()
        if (suppressUntilMs > 0L && resetAt > trippedAtMs) {
            consecutiveFailures = 0
            suppressUntilMs = 0L
            trippedAtMs = 0L
            return false
        }
        return clock() < suppressUntilMs
    }

    private fun onPushSuccess() {
        consecutiveFailures = 0
        suppressUntilMs = 0L
        trippedAtMs = 0L
    }

    private fun onPushFailure(kind: String, error: Throwable) {
        consecutiveFailures += 1
        // Log only on the failure that tripped the breaker; further failures within the
        // backoff window are dropped silently (which is the whole point of the breaker).
        if (consecutiveFailures == CONSECUTIVE_FAILURE_THRESHOLD) {
            val now = clock()
            trippedAtMs = now
            suppressUntilMs = now + BACKOFF_MS
            Log.w(
                TAG,
                "Auto-push of $kind for '$title' failed ${consecutiveFailures}x; suppressing for ${BACKOFF_MS / 1000}s: ${error.message}",
            )
        }
    }

    /** Test-only accessor; never read in production. */
    internal val pendingTurns: Int get() = unpushedPageTurns

    /** Test-only accessor for circuit-breaker state. */
    internal val isSuppressed: Boolean get() = breakerOpen()

    companion object {
        /**
         * Legacy direct-PUT fallback only. Production injects [queueBookmark] and queues
         * every persisted page turn into the five-second batch exchange instead.
         */
        const val PAGE_TURN_PUSH_THRESHOLD: Int = 5

        /**
         * Number of consecutive failed pushes before the breaker trips. The first
         * [CONSECUTIVE_FAILURE_THRESHOLD] failures are silent (each push gets its own
         * shot); the Nth logs once and starts suppression.
         */
        const val CONSECUTIVE_FAILURE_THRESHOLD: Int = 3

        /** How long the breaker stays open after tripping. 5 min = airplane-mode-friendly. */
        const val BACKOFF_MS: Long = 5 * 60 * 1000L
    }
}

/**
 * Composable factory for [HttpSyncReaderHooks], keyed on the book identity so each
 * manga reader instance gets its own counter. The returned object is stable across
 * recompositions; it reads the latest settings via [rememberUpdatedState] so callers
 * don't need to thread the settings through their own state.
 */
@Composable
fun rememberHttpSyncReaderHooks(
    bookRoot: File,
    title: String,
    persistenceScope: CoroutineScope,
): HttpSyncReaderHooks {
    val appContainer = LocalHoshiAppContainer.current
    val pusher = appContainer.httpSyncPusher
    val bookmarkScheduler = appContainer.httpSyncBookmarkScheduler
    val statisticsScheduler = appContainer.httpSyncStatisticsPushScheduler
    val manualSyncSuccessAt = appContainer.httpSyncManualSyncSuccessAt
    val settings by appContainer.httpSyncSettingsRepository.settings.collectAsState(initial = null)
    val settingsRef = rememberUpdatedState(settings)
    var identityLoaded by remember(bookRoot) { mutableStateOf(false) }
    val persistedSyncId by produceState<String?>(
        initialValue = null,
        key1 = bookRoot,
        key2 = appContainer.bookRepository,
    ) {
        value = appContainer.bookRepository.loadMetadata(bookRoot)?.let(::syncIdForMetadata)
            ?: deriveSyncId(title)
        identityLoaded = true
    }
    val persistedSyncIdRef = rememberUpdatedState(persistedSyncId)
    val identityLoadedRef = rememberUpdatedState(identityLoaded)
    return remember(bookRoot, title, persistenceScope) {
        HttpSyncReaderHooks(
            bookRoot = bookRoot,
            title = title,
            pushBookmark = pusher::pushBookmark,
            pushChatEntry = pusher::pushChatEntry,
            currentSettings = { settingsRef.value },
            persistenceScope = persistenceScope,
            breakerResetSignal = { manualSyncSuccessAt.value },
            persistedSyncIdProvider = { persistedSyncIdRef.value },
            identityReady = { identityLoadedRef.value },
            pushBookmarkWithSyncId = pusher::pushBookmark,
            pushChatEntryWithSyncId = pusher::pushChatEntry,
            queueBookmark = bookmarkScheduler::onBookmarkChanged,
            flushQueuedBookmarks = bookmarkScheduler::flushNow,
            queueStatistics = statisticsScheduler::onStatisticsChanged,
            flushStatistics = statisticsScheduler::flushNow,
        )
    }
}
