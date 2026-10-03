package moe.antimony.hoshi

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import moe.antimony.hoshi.features.sync.http.httpSyncSettingsRepository
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
    internal val appContainer by lazy { HoshiAppContainer(this) }
    internal val podcastRepository by lazy {
        moe.antimony.hoshi.features.podcasts.PodcastRepository(
            this, httpSyncSettingsRepository(),
        )
    }
    override fun onCreate() {
        super.onCreate()
        installCrashDiagnostics(this)
        // HTTP sync polls only while a screen is visible, and pulls at once when one appears.
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            private var started = 0
            private val handler = Handler(Looper.getMainLooper())
            private val leave = Runnable {
                if (started == 0) appContainer.httpSyncBookmarkScheduler.setForeground(false)
            }

            override fun onActivityStarted(activity: Activity) {
                started += 1
                handler.removeCallbacks(leave)
                appContainer.httpSyncBookmarkScheduler.setForeground(true)
            }

            override fun onActivityStopped(activity: Activity) {
                started = (started - 1).coerceAtLeast(0)
                // A rotation stops and restarts the activity, and a quick switch (a lookup
                // from another app) comes straight back: neither is leaving the app.
                if (started == 0 && !activity.isChangingConfigurations) {
                    handler.removeCallbacks(leave)
                    handler.postDelayed(leave, BACKGROUND_SETTLE_MS)
                }
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
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

    private companion object {
        /** How long the app must stay out of sight before HTTP sync treats it as backgrounded. */
        const val BACKGROUND_SETTLE_MS = 1_500L
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
