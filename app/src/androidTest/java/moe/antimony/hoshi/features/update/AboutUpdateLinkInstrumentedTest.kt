package moe.antimony.hoshi.features.update

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import moe.antimony.hoshi.HoshiAppContainer
import moe.antimony.hoshi.LocalHoshiAppContainer
import moe.antimony.hoshi.R
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Dedicated emulator only: verifies the actual About UI with a disappeared system download. */
@RunWith(AndroidJUnit4::class)
class AboutUpdateLinkInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun latestReleaseLinkRemainsUsableWhenPersistedDownloadHasDisappeared() {
        val context = compose.activity
        val container = HoshiAppContainer(context)
        val recordingContext = RecordingContext(context)
        val store = container.updateDownloadStore
        runBlocking { check(store.load() == null) { "Requires an empty update store on a dedicated device" } }
        try {
            val update = AvailableUpdate(
                versionName = "99.0.0", releaseUrl = UpdateConfig.LATEST_RELEASE_URL,
                assetName = "missing.apk", downloadUrl = "https://github.com/example/missing.apk", sha256 = null,
            )
            runBlocking { store.saveDownloading(update, "missing-test.apk", Long.MAX_VALUE, update.downloadUrl) }
            compose.setContent {
                CompositionLocalProvider(LocalContext provides recordingContext, LocalHoshiAppContainer provides container) {
                    MaterialTheme { AboutScreen(onClose = {}) }
                }
            }
            val linkLabel = context.getString(R.string.about_latest_release)
            compose.waitUntil(5_000) { runBlocking { store.load()?.status == UpdateDownloadRecordStatus.Failed } }
            compose.onNode(hasScrollAction()).performScrollToNode(hasText(linkLabel))
            compose.onNodeWithText(context.getString(R.string.about_update_last_download_failed)).assertExists()
            compose.onNodeWithText(context.getString(R.string.action_download)).assertIsEnabled()
            compose.onNodeWithText(linkLabel).assertIsEnabled().performClick()
            compose.runOnIdle {
                assertEquals(Intent.ACTION_VIEW, recordingContext.opened?.action)
                assertEquals(UpdateConfig.LATEST_RELEASE_URL, recordingContext.opened?.dataString)
            }
        } finally {
            runBlocking { store.clear() }
        }
    }

    private class RecordingContext(base: Context) : ContextWrapper(base) {
        var opened: Intent? = null
        override fun startActivity(intent: Intent) { opened = intent }
    }
}
