package moe.antimony.hoshi

import android.app.Application
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import moe.antimony.hoshi.features.diagnostics.installCrashDiagnostics
import moe.antimony.hoshi.features.update.AndroidUpdateDownloadManager
import moe.antimony.hoshi.features.update.UpdateApkCleanup
import moe.antimony.hoshi.features.update.UpdateConfig
import moe.antimony.hoshi.features.update.UpdateScheduler
import moe.antimony.hoshi.features.update.UpdateStartupSnapshot
import moe.antimony.hoshi.features.update.updateDownloadStore

class HoshiApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        installCrashDiagnostics(this)
        // The GitHub-release auto-updater is gated by UpdateConfig.AUTO_UPDATE_ENABLED.
        // When it is on, run upstream's startup snapshot + scheduler-sync. When it is off,
        // cancel() clears any periodic job a previous (enabled) build may have already
        // persisted, and the snapshot/cleanup are skipped (no APKs to clean if checks
        // never ran).
        if (UpdateConfig.AUTO_UPDATE_ENABLED) {
            prepareUpdateStartupState()
            UpdateScheduler.sync(this)
        } else {
            UpdateScheduler.cancel(this)
        }
    }

    private fun prepareUpdateStartupState() {
        val store = updateDownloadStore()
        val downloadManager = AndroidUpdateDownloadManager(this, store)
        UpdateStartupSnapshot.initialRecord = runBlocking(Dispatchers.IO) {
            UpdateApkCleanup(
                context = this@HoshiApplication,
                downloadManager = downloadManager,
                store = store,
            ).deleteCurrentVersionApks()
            store.load()
        }
    }
}
