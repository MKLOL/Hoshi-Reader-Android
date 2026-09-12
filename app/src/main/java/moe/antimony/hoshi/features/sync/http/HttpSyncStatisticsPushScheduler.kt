package moe.antimony.hoshi.features.sync.http

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import kotlin.coroutines.coroutineContext

/**
 * Debounces statistics pushes from the readers: a session writes `statistics.json` on every
 * page turn, and pushing each write would be the chatter that once kept statistics out of the
 * sync entirely. One push per book runs [delayMs] after the last change (sooner on
 * [flushNow], when the reader is left), and a converged book costs the push no request at all
 * (see [HttpSyncStatisticsSync]). After a few consecutive failures pushes pause for a while,
 * like the reader hooks' circuit breaker, so an offline device does not retry every turn.
 */
class HttpSyncStatisticsPushScheduler(
    private val scope: CoroutineScope,
    private val currentSettings: suspend () -> HttpSyncSettings?,
    private val push: suspend (bookRoot: File, title: String, settings: HttpSyncSettings, persistedSyncId: String?) -> Unit,
    private val delayMs: Long = DEFAULT_DELAY_MS,
    private val flushDelayMs: Long = DEFAULT_FLUSH_DELAY_MS,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val lock = Any()
    private val pending = mutableMapOf<String, Job>()
    private var consecutiveFailures = 0
    private var suppressUntilMs = 0L

    /** A statistics sidecar of [bookRoot] was written. */
    fun onStatisticsChanged(bookRoot: File, title: String, persistedSyncId: String?) {
        schedule(bookRoot, title, persistedSyncId, delayMs)
    }

    /** The reader is being left: push soon, after its final local saves have landed. */
    fun flushNow(bookRoot: File, title: String, persistedSyncId: String?) {
        schedule(bookRoot, title, persistedSyncId, flushDelayMs)
    }

    /** True while a push is scheduled or running for [bookRoot]. */
    fun isPending(bookRoot: File): Boolean = synchronized(lock) { pending.containsKey(bookRoot.absolutePath) }

    private fun schedule(bookRoot: File, title: String, persistedSyncId: String?, delay: Long) {
        val id = bookRoot.absolutePath
        synchronized(lock) {
            pending.remove(id)?.cancel()
            pending[id] = scope.launch {
                if (delay > 0) delay(delay)
                try {
                    pushNow(bookRoot, title, persistedSyncId)
                } finally {
                    val self = coroutineContext[Job]
                    synchronized(lock) {
                        if (pending[id] === self) pending.remove(id)
                    }
                }
            }
        }
    }

    private suspend fun pushNow(bookRoot: File, title: String, persistedSyncId: String?) {
        if (clock() < suppressUntilMs) return
        val settings = currentSettings() ?: return
        if (!settings.isConfigured) return
        runCatching { push(bookRoot, title, settings, persistedSyncId) }
            .onSuccess { consecutiveFailures = 0 }
            .onFailure {
                consecutiveFailures += 1
                if (consecutiveFailures >= FAILURE_THRESHOLD) {
                    suppressUntilMs = clock() + BACKOFF_MS
                    consecutiveFailures = 0
                }
            }
    }

    companion object {
        const val DEFAULT_DELAY_MS: Long = 30_000L
        const val DEFAULT_FLUSH_DELAY_MS: Long = 1_500L
        const val FAILURE_THRESHOLD: Int = 3
        const val BACKOFF_MS: Long = 5 * 60_000L
    }
}
