package moe.antimony.hoshi

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import moe.antimony.hoshi.features.ai.offline.OfflineLlmManager
import moe.antimony.hoshi.features.diagnostics.installCrashDiagnostics
import moe.antimony.hoshi.features.update.AndroidUpdateDownloadManager
import moe.antimony.hoshi.features.update.UpdateApkCleanup
import moe.antimony.hoshi.features.update.UpdateConfig
import moe.antimony.hoshi.features.update.UpdateScheduler
import moe.antimony.hoshi.features.update.UpdateStartup
import moe.antimony.hoshi.features.update.UpdateStartupSnapshot
import moe.antimony.hoshi.features.update.updateDownloadStore

class HoshiApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        installCrashDiagnostics(this)
        // Reclaim disk from models an older build downloaded into internal storage before the
        // model dir moved to external app-specific storage (one-time, in the background).
        CoroutineScope(Dispatchers.IO).launch {
            runCatching { OfflineLlmManager.cleanupLegacyInternalModels(this@HoshiApplication) }
        }
        // The GitHub-release auto-updater is gated by UpdateConfig.AUTO_UPDATE_ENABLED.
        // When it is on, run upstream's startup snapshot + scheduler-sync. When it is off,
        // cancel() clears any periodic job a previous (enabled) build may have already
        // persisted, and the snapshot/cleanup are skipped (no APKs to clean if checks
        // never ran).
        if (UpdateConfig.AUTO_UPDATE_ENABLED) {
            prepareUpdateStartupState()
        } else {
            UpdateScheduler.cancel(this)
        }
    }

    private fun prepareUpdateStartupState() {
        val store = updateDownloadStore()
        val downloadManager = AndroidUpdateDownloadManager(this, store)
        val cleanup = UpdateApkCleanup(context = this, downloadManager = downloadManager, store = store)
        val startup = UpdateStartup(
            store = store,
            currentVersionName = BuildConfig.VERSION_NAME,
            discardInstalledUpdate = downloadManager::discardInstalledUpdate,
            deleteCurrentVersionApks = cleanup::deleteCurrentVersionApks,
            refresh = { downloadManager.refresh() },
        )
        // Only the persisted record is read synchronously. Reconciling with DownloadManager
        // parses and hashes APKs, so it runs in the background; the prompt and About observe
        // the record flow and update when it finishes. The scheduled check follows the
        // reconciliation so an already-installed update is discarded before a newer release
        // can replace its record.
        UpdateStartupSnapshot.initialRecord = runBlocking(Dispatchers.IO) { startup.snapshot() }
        CoroutineScope(Dispatchers.IO).launch {
            startup.reconcile()
            UpdateScheduler.syncNow(this@HoshiApplication)
        }
    }
}
