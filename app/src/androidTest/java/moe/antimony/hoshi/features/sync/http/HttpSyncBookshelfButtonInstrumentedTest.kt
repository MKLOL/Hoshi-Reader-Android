package moe.antimony.hoshi.features.sync.http

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import moe.antimony.hoshi.HoshiAppContainer
import moe.antimony.hoshi.LocalHoshiAppContainer
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Use an empty emulator, with the local sync test server on adb-reversed port 18795. */
@RunWith(AndroidJUnit4::class)
class HttpSyncBookshelfButtonInstrumentedTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun savedTokenShowsShortcutAndSyncReportsSuccessAndFailure() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val container = HoshiAppContainer(context)
        try {
            runBlocking {
                container.httpSyncSettingsRepository.update {
                    it.copy(baseUrl = "http://127.0.0.1:18795", bearerToken = "")
                }
            }
            compose.setContent {
                CompositionLocalProvider(LocalHoshiAppContainer provides container) {
                    MaterialTheme { HttpSyncBookshelfButton(enabled = true) }
                }
            }
            compose.onNodeWithContentDescription("Sync now").assertDoesNotExist()
            runBlocking {
                container.httpSyncSettingsRepository.update { it.copy(bearerToken = "bookshelf-ui-test") }
            }
            compose.waitUntil(5_000) {
                compose.onAllNodesWithContentDescription("Sync now").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithContentDescription("Sync now").performClick()
            compose.waitUntil(60_000) { container.httpSyncManualSync.status.value is SyncStatus.Done }
            val result = (container.httpSyncManualSync.status.value as SyncStatus.Done).result
            assertTrue(result.errors.toString(), result.errors.isEmpty())
            compose.onNodeWithText("Sync complete:", substring = true).assertIsDisplayed()
            compose.onNodeWithText("Done").performClick()
            runBlocking {
                container.httpSyncSettingsRepository.update { it.copy(bearerToken = "invalid") }
            }
            compose.onNodeWithContentDescription("Sync now").performClick()
            compose.waitUntil(20_000) { container.httpSyncManualSync.status.value is SyncStatus.Failed }
            compose.onNodeWithText("Sync failed:", substring = true).assertIsDisplayed()
            compose.onNodeWithText("Done").performClick()
            runBlocking { container.httpSyncSettingsRepository.update { it.copy(bearerToken = "") } }
            compose.waitUntil(5_000) {
                compose.onAllNodesWithContentDescription("Sync now").fetchSemanticsNodes().isEmpty()
            }
            compose.onNodeWithContentDescription("Sync now").assertDoesNotExist()
        } finally {
            container.appScope.cancel()
        }
    }
}
