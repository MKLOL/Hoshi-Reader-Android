package moe.antimony.hoshi.features.sync.http

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HttpSyncSettingsInputTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun delayedPersistenceCannotReplaceNewerTypingAndSyncUsesVisibleCredentials() {
        val persisted = mutableStateOf(HttpSyncSettings(baseUrl = "https://old.test", bearerToken = "old"))
        val edits = mutableListOf<Pair<String, String>>()
        var submitted: Pair<String, String>? = null
        compose.setContent {
            MaterialTheme {
                HttpSyncSettingsContent(
                    settings = persisted.value,
                    status = SyncStatus.Idle,
                    onCredentialsChange = { url, token -> edits += url to token },
                    onSync = { url, token -> submitted = url to token },
                    onStop = {},
                )
            }
        }
        compose.onNodeWithText("Base URL").performTextReplacement("https://new.test")
        compose.onNodeWithText("Bearer token").performTextReplacement("new-token")
        // The URL write returns after the token has already been edited.
        compose.runOnIdle { persisted.value = persisted.value.copy(baseUrl = "https://new.test") }
        compose.onNodeWithText("Base URL").assertTextContains("https://new.test")
        compose.onNodeWithText("Sync now").performClick()
        compose.runOnIdle {
            assertEquals("https://new.test" to "new-token", submitted)
            assertEquals(submitted, edits.last())
        }
        compose.onNodeWithText("Bearer token").performTextReplacement("")
        compose.onNodeWithText("Sync now").assertIsNotEnabled()
    }

    @Test
    fun aRunningSyncCanBeStoppedAndAnIdleOneOffersNoStop() {
        val status = mutableStateOf<SyncStatus>(SyncStatus.Running())
        var stops = 0
        compose.setContent {
            MaterialTheme {
                HttpSyncSettingsContent(
                    settings = HttpSyncSettings(baseUrl = "https://sync.test", bearerToken = "token"),
                    status = status.value,
                    onCredentialsChange = { _, _ -> },
                    onSync = { _, _ -> },
                    onStop = { stops++ },
                )
            }
        }
        compose.onNodeWithText("Stop sync").performClick()
        compose.runOnIdle {
            assertEquals(1, stops)
            status.value = SyncStatus.Failed(null, moe.antimony.hoshi.R.string.http_sync_no_network)
        }
        compose.onNodeWithText("Stop sync").assertDoesNotExist()
        compose.onNodeWithText("No network", substring = true).assertExists()
        compose.onNodeWithText("Sync now").assertIsEnabled()
    }

    @Test
    fun theBooksSyncDialogOffersStopOnlyWhileASyncRuns() {
        val status = mutableStateOf<SyncStatus>(SyncStatus.Running())
        var stops = 0
        var dismissals = 0
        compose.setContent {
            MaterialTheme {
                HttpSyncStatusDialog(status = status.value, onDismiss = { dismissals++ }, onStop = { stops++ })
            }
        }
        compose.onNodeWithText("Stop sync").performClick()
        compose.runOnIdle {
            assertEquals(1, stops)
            assertEquals(0, dismissals)
            status.value = SyncStatus.Idle
        }
        compose.onNodeWithText("Stop sync").assertDoesNotExist()
        compose.onNodeWithText("Done").performClick()
        compose.runOnIdle { assertEquals(1, dismissals) }
    }
}
