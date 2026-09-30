package moe.antimony.hoshi.features.sync.http

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Where a tapped sync runs once [HttpSyncManualSync.start] has accepted it. */
enum class HttpSyncStartRoute {
    /** Android's transfer job/worker owns the pass and calls [HttpSyncManualSync.execute]. */
    AndroidTransfer,

    /** The app runs the pass itself, as it did before transfers moved to Android. */
    InProcess,
}

/** App-owned manual sync, shared by the shelf shortcut and settings. Closing either UI is safe. */
class HttpSyncManualSync(
    private val scope: CoroutineScope,
    private val schedule: (suspend () -> HttpSyncStartRoute)? = null,
    private val unschedule: () -> Unit = {},
    private val onCancelled: () -> Unit = {},
    private val sync: suspend (onProgress: suspend (HttpSyncProgress) -> Unit) -> HttpSyncResult,
) {
    private val execution = Mutex()
    private val mutableStatus = MutableStateFlow<SyncStatus>(SyncStatus.Idle)
    val status = mutableStatus.asStateFlow()
    private var starting: Job? = null

    internal fun backgroundStartFailed() {
        mutableStatus.value = SyncStatus.Failed(null, moe.antimony.hoshi.R.string.http_sync_background_start_failed)
    }

    internal fun cancelledBeforeExecution() {
        // A queued owner must not reset/cancel another owner's running pass.
        if (!execution.tryLock()) return
        try {
            val previous = mutableStatus.value
            if (previous is SyncStatus.Running) mutableStatus.compareAndSet(previous, SyncStatus.Idle)
        } finally {
            execution.unlock()
        }
    }

    fun start() {
        val previous = status.value
        if (previous is SyncStatus.Running) return
        if (!mutableStatus.compareAndSet(previous, SyncStatus.Running())) return
        starting = scope.launch {
            try {
                val route = try {
                    schedule?.invoke() ?: HttpSyncStartRoute.InProcess
                } catch (error: Exception) {
                    if (error !is CancellationException) withdrawEarlierTransfer()
                    throw error
                }
                if (route != HttpSyncStartRoute.AndroidTransfer) {
                    withdrawEarlierTransfer()
                    execute()
                }
            } catch (cancelled: CancellationException) {
                mutableStatus.value = SyncStatus.Idle
                throw cancelled
            } catch (error: Exception) {
                mutableStatus.value = SyncStatus.Failed(error.message, (error as? HttpSyncException)?.messageResource)
            }
        }
    }

    /**
     * A job queued before the process died would otherwise wait for the network and run a
     * second, unseen pass after this tap has already failed or synced in the app.
     */
    private fun withdrawEarlierTransfer() {
        if (schedule != null) unschedule()
    }

    /**
     * Stops a tapped sync wherever it is: still being handed to Android, waiting for Android to
     * start it, or transferring. Books already imported stay imported; the next sync resumes.
     */
    fun cancel() {
        if (status.value !is SyncStatus.Running) return
        starting?.cancel()
        unschedule()
        if (execution.isLocked) onCancelled()
        mutableStatus.value = SyncStatus.Idle
    }

    /** Called by Android's transfer job/worker, including after process recreation. */
    suspend fun execute() = execution.withLock {
        mutableStatus.value = SyncStatus.Running()
        try {
            // After cancel() the UI is idle; late progress from the stopping pass must not revive it.
            val result = sync { progress -> mutableStatus.update { if (it is SyncStatus.Running) SyncStatus.Running(progress) else it } }
            mutableStatus.value = SyncStatus.Done(result)
        } catch (cancelled: CancellationException) {
            onCancelled()
            mutableStatus.value = SyncStatus.Idle
            throw cancelled
        } catch (error: Exception) {
            mutableStatus.value = SyncStatus.Failed(error.message, (error as? HttpSyncException)?.messageResource)
        }
    }
}

sealed interface SyncStatus {
    data object Idle : SyncStatus
    data class Running(val progress: HttpSyncProgress? = null) : SyncStatus
    data class Done(val result: HttpSyncResult) : SyncStatus
    data class Failed(val message: String?, val messageResource: Int? = null) : SyncStatus
}
