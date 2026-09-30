package moe.antimony.hoshi.features.sync.http

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
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
}
