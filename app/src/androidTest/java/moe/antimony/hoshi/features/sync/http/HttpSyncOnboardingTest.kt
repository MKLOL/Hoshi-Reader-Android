package moe.antimony.hoshi.features.sync.http

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HttpSyncOnboardingTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun firstLaunchRequiresATokenOrExplainsTheExactSettingsPath() {
        val state = mutableStateOf(HttpSyncOnboardingUiState(HttpSyncOnboardingStep.Token))
        var finishes = 0
        compose.setContent {
            MaterialTheme {
                HttpSyncOnboardingContent(
                    state.value, SyncStatus.Idle,
                    onUseToken = { error("No token was provided") },
                    onWithoutToken = { state.value = HttpSyncOnboardingUiState(HttpSyncOnboardingStep.Later) },
                    onFinishLater = { finishes++ },
                    onDismissStatus = {}, onEditToken = {}, onStop = {},
                )
            }
        }
        compose.onNodeWithText("Do you have a sync token?").assertIsDisplayed()
        compose.onNodeWithText("Sync now").assertIsNotEnabled()
        compose.onNodeWithText("I don’t have a token").performClick()
        compose.onNodeWithText("Settings → Advanced → HTTP Sync", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Done").performClick()
        compose.runOnIdle { assertEquals(1, finishes) }
    }

    @Test
    fun aRejectedTokenShowsEditAndPreservesTheTokenDraftForRetry() {
        val state = mutableStateOf(HttpSyncOnboardingUiState(HttpSyncOnboardingStep.Token))
        val status = mutableStateOf<SyncStatus>(SyncStatus.Idle)
        val submissions = mutableListOf<String>()
        compose.setContent {
            MaterialTheme {
                HttpSyncOnboardingContent(
                    state.value, status.value,
                    onUseToken = {
                        submissions += it
                        state.value = HttpSyncOnboardingUiState(HttpSyncOnboardingStep.Status)
                        status.value = SyncStatus.Failed(
                            "Server rejected the bearer token (HTTP 401).", tokenRejected = true,
                        )
                    },
                    onWithoutToken = {}, onFinishLater = {}, onDismissStatus = {},
                    onEditToken = { state.value = HttpSyncOnboardingUiState(HttpSyncOnboardingStep.Token) },
                    onStop = {},
                )
            }
        }
        compose.onNodeWithText("Bearer token").performTextReplacement("first-token")
        compose.onNodeWithText("Sync now").performClick()
        compose.onNodeWithText("Sync failed:", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Edit token").performClick()
        compose.onNodeWithContentDescription("Show token").performClick()
        compose.onNodeWithText("Bearer token").assertTextContains("first-token")
        compose.onNodeWithText("Sync now").performClick()
        compose.runOnIdle { assertEquals(listOf("first-token", "first-token"), submissions) }
    }

    @Test
    fun aSyncThatRanWithSomeErrorsOffersNoTokenEdit() {
        val partlyFailed = HttpSyncResult(
            uploadedBookmarks = 0, uploadedChatEntries = 0, uploadedMetadata = 0,
            downloadedBookmarks = 0, downloadedChatEntries = 0, remoteOnlyBooks = 0,
            errors = listOf("Shared book: download failed (HTTP 500)."),
        )
        compose.setContent {
            MaterialTheme {
                HttpSyncOnboardingContent(
                    HttpSyncOnboardingUiState(HttpSyncOnboardingStep.Status), SyncStatus.Done(partlyFailed),
                    onUseToken = {}, onWithoutToken = {}, onFinishLater = {}, onDismissStatus = {},
                    onEditToken = { error("A working token must not be offered for editing") }, onStop = {},
                )
            }
        }
        compose.onNodeWithText("Done").assertIsDisplayed()
        compose.onAllNodesWithText("Edit token").assertCountEquals(0)
    }

    @Test
    fun tokenIsHiddenUntilTheUserAsksToShowItAndSavingPreventsDuplicateSubmission() {
        val state = mutableStateOf(HttpSyncOnboardingUiState(HttpSyncOnboardingStep.Token))
        var submitted: String? = null
        compose.setContent {
            MaterialTheme {
                HttpSyncOnboardingContent(
                    state.value, SyncStatus.Idle,
                    onUseToken = {
                        submitted = it
                        state.value = state.value.copy(saving = true)
                    },
                    onWithoutToken = {}, onFinishLater = {}, onDismissStatus = {}, onEditToken = {}, onStop = {},
                )
            }
        }
        compose.onNodeWithText("Bearer token").performTextReplacement("shared-token")
        compose.onNodeWithText("Sync now").performClick()
        compose.onNodeWithText("Sync now").assertIsNotEnabled()
        compose.onNodeWithText("I don’t have a token").assertIsNotEnabled()
        compose.runOnIdle { assertEquals("shared-token", submitted) }
    }

    @Test
    fun defaultDictionariesOfferOptInAndShowProgressAndRetry() {
        val state = mutableStateOf(HttpSyncOnboardingUiState(HttpSyncOnboardingStep.Dictionaries))
        var downloads = 0
        var skips = 0
        compose.setContent {
            MaterialTheme {
                HttpSyncOnboardingContent(
                    state.value, SyncStatus.Idle,
                    onUseToken = {}, onWithoutToken = {}, onFinishLater = {}, onDismissStatus = {}, onEditToken = {}, onStop = {},
                    onDownloadDictionaries = {
                        downloads++
                        state.value = state.value.copy(installingDictionaries = true)
                    },
                    onSkipDictionaries = { skips++ },
                )
            }
        }
        compose.onNodeWithText("Add the default dictionaries?").assertIsDisplayed()
        compose.onNodeWithText("Download JMdict", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Download dictionaries").performClick()
        compose.onNodeWithText("Download dictionaries").assertIsNotEnabled()
        compose.onNodeWithText("Skip for now").assertIsNotEnabled()
        compose.runOnIdle {
            assertEquals(1, downloads)
            state.value = state.value.copy(installingDictionaries = false, dictionaryInstallFailed = true)
        }
        compose.onNodeWithText("Could not finish installing", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Skip for now").performClick()
        compose.runOnIdle { assertEquals(1, skips) }
    }
}
