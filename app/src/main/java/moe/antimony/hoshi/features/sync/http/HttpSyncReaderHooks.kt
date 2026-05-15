package moe.antimony.hoshi.features.sync.http

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import moe.antimony.hoshi.LocalHoshiAppContainer
import moe.antimony.hoshi.features.ai.AiChatEntry
import java.io.File

/**
 * Reader-side auto-push hooks for the v2 KV sync, packaged so the call site in the manga
 * reader only takes one `remember…` and three method calls. Keeping the counter logic /
 * scope plumbing in this fork-owned file (instead of inline in
 * [moe.antimony.hoshi.features.mangareader.MangaReaderScreen]) makes upstream merge
 * conflicts a 3-line re-apply at most.
 *
 * Triggers, all fire-and-forget on the [persistenceScope]:
 *  - Every [PAGE_TURN_PUSH_THRESHOLD] page-turn saves: PUT the current bookmark.
 *  - On reader leave: PUT the bookmark iff there's been any unpushed activity.
 *  - On every new ChatGPT response that gets persisted: PUT that one chat entry at its
 *    content-addressable key (write-once on the server; safe to retry).
 *
 * Skipped when [HttpSyncSettings.isConfigured] is false (no base URL / no token).
 * Network errors are swallowed — the next manual "Sync now" tap will reconcile.
 */
class HttpSyncReaderHooks internal constructor(
    private val bookRoot: File,
    private val title: String,
    private val pushBookmark: suspend (File, String, HttpSyncSettings) -> Unit,
    private val pushChatEntry: suspend (String, AiChatEntry, HttpSyncSettings) -> Unit,
    private val currentSettings: () -> HttpSyncSettings?,
    private val persistenceScope: CoroutineScope,
) {
    private var unpushedPageTurns: Int = 0

    /** Call this from your existing post-save callback (after the local-bookmark file write). */
    fun onPageTurnPersisted() {
        unpushedPageTurns += 1
        if (unpushedPageTurns >= PAGE_TURN_PUSH_THRESHOLD) {
            flushBookmarkIfActivity()
        }
    }

    /** Call this from the reader's `onDispose` after the local-side flush has been scheduled. */
    fun onLeave() {
        flushBookmarkIfActivity()
    }

    /** Call this once for every chat entry that gets appended to the local log. */
    fun onChatEntryPersisted(entry: AiChatEntry) {
        val settings = currentSettings() ?: return
        if (!settings.isConfigured) return
        persistenceScope.launch {
            runCatching { pushChatEntry(title, entry, settings) }
        }
    }

    private fun flushBookmarkIfActivity() {
        if (unpushedPageTurns <= 0) return
        val settings = currentSettings() ?: return
        if (!settings.isConfigured) return
        unpushedPageTurns = 0
        persistenceScope.launch {
            runCatching { pushBookmark(bookRoot, title, settings) }
        }
    }

    /** Test-only accessor; never read in production. */
    internal val pendingTurns: Int get() = unpushedPageTurns

    companion object {
        /**
         * How many bookmark saves to coalesce into one PUT. Smaller = the server tracks
         * reality more tightly, more HTTPS round-trips; larger = cheaper but more pages
         * lost when the phone dies mid-read. Five balances out at ~250 B per push, plus a
         * force-push on leave so the bookmark is never more than 4 turns out of date.
         */
        const val PAGE_TURN_PUSH_THRESHOLD: Int = 5
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
    val manager = appContainer.httpSyncManager
    val settings by appContainer.httpSyncSettingsRepository.settings.collectAsState(initial = null)
    val settingsRef = rememberUpdatedState(settings)
    return remember(bookRoot, title, persistenceScope) {
        HttpSyncReaderHooks(
            bookRoot = bookRoot,
            title = title,
            pushBookmark = manager::pushBookmark,
            pushChatEntry = manager::pushChatEntry,
            currentSettings = { settingsRef.value },
            persistenceScope = persistenceScope,
        )
    }
}
