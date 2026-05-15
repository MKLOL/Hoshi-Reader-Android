package moe.antimony.hoshi

import android.app.Application
import moe.antimony.hoshi.features.diagnostics.installCrashDiagnostics
import moe.antimony.hoshi.features.update.UpdateConfig
import moe.antimony.hoshi.features.update.UpdateScheduler

class HoshiApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        installCrashDiagnostics(this)
        // The GitHub-release auto-updater is gated by UpdateConfig.AUTO_UPDATE_ENABLED.
        // When it is on, sync() keeps the periodic WorkManager job aligned with the user's
        // autoDownloadUpdates setting. When it is off, cancel() clears any periodic job a
        // previous (enabled) build may have already persisted so it stops running.
        if (UpdateConfig.AUTO_UPDATE_ENABLED) {
            UpdateScheduler.sync(this)
        } else {
            UpdateScheduler.cancel(this)
        }
    }
}
