package moe.antimony.hoshi.features.sync.http

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import moe.antimony.hoshi.HoshiApplication
import moe.antimony.hoshi.LocalHoshiAppContainer
import moe.antimony.hoshi.features.sync.v3.StubKvServer
import moe.antimony.hoshi.features.sync.v3.freshDevice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

/** Runs only on a dedicated disposable emulator: exercises the app's actual sync owner. */
@RunWith(AndroidJUnit4::class)
class HttpSyncOnboardingIntegrationTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val temp = TemporaryFolder()

    @Test
    fun submittingASharedTokenDownloadsTheFriendsEpubThroughTheRealSyncOwner() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val container = (context as HoshiApplication).appContainer
        StubKvServer().use { server ->
            server.start()
            runBlocking {
                val sender = freshDevice(server, "friend", temp.newFolder())
                sender.importEpub("Shared token onboarding EPUB")
                assertTrue(sender.sync().errors.isEmpty())
                container.httpSyncSettingsRepository.update {
                    it.copy(
                        baseUrl = server.baseUrl, bearerToken = "", hasCompletedOnboarding = false,
                        hasCompletedDictionaryOnboarding = true,
                    )
                }
            }
            try {
                compose.setContent {
                    CompositionLocalProvider(LocalHoshiAppContainer provides container) {
                        MaterialTheme { HttpSyncOnboarding() }
                    }
                }
                compose.waitUntil(5_000) {
                    compose.onAllNodesWithText("Do you have a sync token?").fetchSemanticsNodes().isNotEmpty()
                }
                compose.onNodeWithText("Bearer token").performTextReplacement(server.token)
                compose.onNodeWithText("Sync now").performClick()
                compose.waitUntil(60_000) { container.httpSyncManualSync.status.value is SyncStatus.Done }
                val result = (container.httpSyncManualSync.status.value as SyncStatus.Done).result
                assertTrue(result.errors.toString(), result.errors.isEmpty())
                compose.onNodeWithText("Sync complete:", substring = true).assertIsDisplayed()
                runBlocking {
                    val received = container.bookRepository.loadBookEntries()
                        .single { it.metadata.title == "Shared token onboarding EPUB" }
                    assertTrue(received.root.resolve("OEBPS/chapter.xhtml").isFile)
                    assertEquals(server.token, container.httpSyncSettingsRepository.settings.first().bearerToken)
                }
            } finally {
                runBlocking {
                    container.httpSyncSettingsRepository.update { it.copy(bearerToken = "") }
                }
            }
        }
    }
}
