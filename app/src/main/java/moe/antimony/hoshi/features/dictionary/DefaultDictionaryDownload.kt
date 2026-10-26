package moe.antimony.hoshi.features.dictionary

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.antimony.hoshi.dictionary.DictionaryUpdateProgress

/**
 * The first-launch download of the default dictionaries, owned by the app like iOS's
 * `DictionaryManager.shared` import: closing or recreating the setup screen does not stop it, a
 * setup screen opened later shows its progress and result, and only one runs at a time. While it
 * runs, [onRunning] keeps the app alive ([DefaultDictionaryDownloadService]), as for AI requests.
 */
internal class DefaultDictionaryDownload(
    private val scope: CoroutineScope,
    private val install: suspend (onProgress: (DictionaryUpdateProgress) -> Unit) -> Unit,
    /** Records the finished setup, so it is not offered again even when no setup screen is open. */
    private val onInstalled: suspend () -> Unit = {},
    private val onRunning: () -> Unit = {},
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    sealed interface Status {
        data object Idle : Status
        data class Running(val progress: DictionaryUpdateProgress? = null) : Status
        data object Installed : Status
        data object Failed : Status
    }

    private val mutableStatus = MutableStateFlow<Status>(Status.Idle)
    val status: StateFlow<Status> = mutableStatus.asStateFlow()

    /** Starts the download unless one is running. A failed one is retried by starting again. */
    fun start() {
        val previous = mutableStatus.value
        if (previous is Status.Running) return
        if (!mutableStatus.compareAndSet(previous, Status.Running())) return
        onRunning()
        scope.launch {
            mutableStatus.value = try {
                withContext(ioDispatcher) {
                    install { progress ->
                        mutableStatus.update { if (it is Status.Running) Status.Running(progress) else it }
                    }
                }
                onInstalled()
                Status.Installed
            } catch (cancelled: CancellationException) {
                mutableStatus.value = Status.Idle
                throw cancelled
            } catch (_: Exception) {
                // Dictionaries a failed attempt already installed stay; a retry skips them.
                Status.Failed
            }
        }
    }
}
