package moe.antimony.hoshi

import android.app.Application
import moe.antimony.hoshi.features.diagnostics.installCrashDiagnostics
import moe.antimony.hoshi.features.update.UpdateScheduler

class HoshiApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        installCrashDiagnostics(this)
        // This fork does not track upstream releases: the GitHub-release update check,
        // background download, and "Update Downloaded" prompt are all disabled. The
        // scheduler is no longer started; cancel() clears any periodic WorkManager job a
        // previous build of the app may have already persisted.
        UpdateScheduler.cancel(this)
    }
}
