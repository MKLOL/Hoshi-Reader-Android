package moe.antimony.hoshi.navigation

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal class ReaderAutoSyncExportController(
    private val scope: CoroutineScope,
    private val debounceMillis: Long = ReaderAutoSyncDebounceMillis,
) {
    private var latestSaveJob: Job? = null
    private var debounceJob: Job? = null
    private var exportJob: Job? = null
    private var pendingExport: (suspend () -> Unit)? = null
    private var importsInProgress = false

    fun launchSave(save: suspend () -> Unit): Job {
        val previousSaveJob = latestSaveJob
        val job = scope.launch {
            previousSaveJob?.join()
            save()
        }
        latestSaveJob = job
        return job
    }

    /** Import/reload must start only after the retiring reader's final writes have landed. */
    suspend fun awaitSaves() {
        latestSaveJob?.join()
    }

    /** A pending export must never replace the external base while an import reads it. */
    suspend fun pauseExportsForImport() {
        importsInProgress = true
        debounceJob?.cancel()
        debounceJob = null
        exportJob?.join()
        awaitSaves()
    }

    fun resumeExports(enabled: Boolean) {
        importsInProgress = false
        pendingExport?.let { scheduleExport(enabled, it) }
    }

    fun scheduleExport(enabled: Boolean, export: suspend () -> Unit) {
        if (!enabled) return
        pendingExport = export
        if (importsInProgress) return
        if (debounceJob?.isActive == true) return
        debounceJob = scope.launch {
            delay(debounceMillis)
            debounceJob = null
            runPendingExport()
        }
    }

    fun flushExport(enabled: Boolean): Job? {
        if (!enabled || importsInProgress || pendingExport == null) return null
        debounceJob?.cancel()
        debounceJob = null
        return scope.launch {
            runPendingExport()
        }
    }

    private suspend fun runPendingExport() {
        exportJob?.join()
        if (importsInProgress) return
        val export = pendingExport ?: return
        pendingExport = null
        val saveJob = latestSaveJob
        val job = scope.launch {
            saveJob?.join()
            export()
        }
        exportJob = job
        try {
            job.join()
        } finally {
            if (exportJob == job) {
                exportJob = null
            }
        }
    }
}

internal const val ReaderAutoSyncDebounceMillis = 30_000L
