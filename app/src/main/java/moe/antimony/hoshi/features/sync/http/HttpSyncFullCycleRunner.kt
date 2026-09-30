package moe.antimony.hoshi.features.sync.http

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch

/** Shares both the result AND live progress when manual sync joins an existing background pass. */
class HttpSyncFullCycleRunner(private val scope: CoroutineScope) {
    private class Flight(
        val result: Deferred<HttpSyncResult>,
        val progress: MutableStateFlow<HttpSyncProgress?>,
        val statisticsOnly: Boolean,
    )
    private val lock = Any()
    private var flight: Flight? = null

    suspend fun run(
        onProgress: suspend (HttpSyncProgress) -> Unit = {},
        statisticsOnly: Boolean = false,
        requireOwnPass: Boolean = false,
        block: suspend (report: suspend (HttpSyncProgress) -> Unit) -> HttpSyncResult,
    ): HttpSyncResult = coroutineScope {
        while (true) {
            var ownsFlight = false
            val selected = synchronized(lock) {
                flight?.takeIf { !it.result.isCompleted } ?: run {
                    ownsFlight = true
                    val progress = MutableStateFlow<HttpSyncProgress?>(null)
                    val result = scope.async(start = CoroutineStart.LAZY) { block { progress.value = it } }
                    Flight(result, progress, statisticsOnly).also { created ->
                        flight = created
                        result.invokeOnCompletion {
                            synchronized(lock) { if (flight === created) flight = null }
                        }
                    }
                }
            }
            val observer = launch(start = CoroutineStart.UNDISPATCHED) {
                selected.progress.filterNotNull().collect { onProgress(it) }
            }
            val result = try {
                selected.result.start()
                selected.result.await()
            } finally {
                // Leaving one caller must not cancel the app-owned sync or another caller's updates.
                observer.cancel()
            }
            // A full caller may wait for a narrower background pass, but that pass cannot satisfy
            // its request. Start/join the full work after the narrower flight releases the slot.
            if (ownsFlight || (!requireOwnPass && (statisticsOnly || !selected.statisticsOnly))) {
                return@coroutineScope result
            }
        }
        @Suppress("UNREACHABLE_CODE")
        error("unreachable")
    }
}
