package moe.antimony.hoshi.features.sync.http

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** App-owned manual sync, shared by the shelf shortcut and settings. Closing either UI is safe. */
class HttpSyncManualSync(
    private val scope: CoroutineScope,
    private val sync: suspend (onProgress: suspend (HttpSyncProgress) -> Unit) -> HttpSyncResult,
) {
    private val mutableStatus = MutableStateFlow<SyncStatus>(SyncStatus.Idle)
    val status = mutableStatus.asStateFlow()

    fun start() {
        val previous = status.value
        if (previous is SyncStatus.Running) return
        if (!mutableStatus.compareAndSet(previous, SyncStatus.Running())) return
        scope.launch {
            try {
                val result = sync { mutableStatus.value = SyncStatus.Running(it) }
                mutableStatus.value = SyncStatus.Done(result)
            } catch (cancelled: CancellationException) {
                mutableStatus.value = SyncStatus.Idle
                throw cancelled
            } catch (error: Exception) {
                mutableStatus.value = SyncStatus.Failed(error.message)
            }
        }
    }
}

sealed interface SyncStatus {
    data object Idle : SyncStatus
    data class Running(val progress: HttpSyncProgress? = null) : SyncStatus
    data class Done(val result: HttpSyncResult) : SyncStatus
    data class Failed(val message: String?) : SyncStatus
}
