package moe.antimony.hoshi.features.ai

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Models MangaReaderScreen's `LaunchedEffect(aiChatRequestId)` popup follower with the real
 * [AiChatRequests].
 *
 * Compose ordering modelled here: the effect runs on AndroidUiDispatcher, whose frame callback
 * (`AndroidUiDispatcher.dispatchCallback.doFrame`) runs `performTrampolineDispatch()` (queued
 * coroutine resumptions) BEFORE `performFrameDispatch()` (the Recomposer's frame, where a changed
 * LaunchedEffect key cancels the old effect). So a StateFlow emission caused by `hide()` inside a
 * click handler is collected by the OLD effect before recomposition cancels it. Modelled as:
 * click handler -> runCurrent() (trampoline) -> recompose() (cancel/relaunch on key change).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AiChatPopupFollowerModelTest {
    @get:Rule val tempFolder = TemporaryFolder()

    private class Screen(val requests: AiChatRequests, val ui: CoroutineScope) {
        var aiChatState: AiChatUiState? = null
        var aiChatRequestId: Long? = null
        private var effectKey: Long? = null
        private var effect: Job? = null

        /** The screen's LaunchedEffect(aiChatRequestId) body, verbatim. */
        private fun launchEffect(): Job? {
            val id = aiChatRequestId ?: return null
            return ui.launch {
                requests.followInPopup(id, isShown = { aiChatRequestId == id }) { state, ended ->
                    if (ended) aiChatRequestId = null
                    if (state != null) {
                        aiChatState = state
                    } else if (aiChatState is AiChatUiState.Loading) {
                        aiChatState = null
                    }
                }
            }
        }

        /** A frame: a changed key cancels the old effect and starts the new one. */
        fun recompose() {
            if (effectKey != aiChatRequestId) {
                effect?.cancel()
                effectKey = aiChatRequestId
                effect = launchEffect()
            }
        }

        private fun detachAiRequest() {
            aiChatRequestId?.let(requests::hide)
            aiChatRequestId = null
        }

        fun askAi(text: String, run: suspend () -> AiChatEntry) {
            aiChatState = AiChatUiState.Loading(text)
            val id = requests.start(null, text, text, onDevice = false, failureMessage = { "failed" }, run = run)
            if (aiChatRequestId != id) detachAiRequest()
            aiChatRequestId = id
        }

        fun dismissAiChat() {
            detachAiRequest()
            aiChatState = null
        }
    }

    private fun TestScope.screen(): Screen {
        val main = StandardTestDispatcher(testScheduler)
        val requests = AiChatRequests(CoroutineScope(main + SupervisorJob()), AiChatHistoryStore(main))
        return Screen(requests, CoroutineScope(main + SupervisorJob()))
    }

    private fun TestScope.frame(screen: Screen) {
        runCurrent() // performTrampolineDispatch
        screen.recompose() // performFrameDispatch
        runCurrent()
    }

    @Test
    fun tappingOutsideALoadingPopupClosesIt() = runTest {
        val screen = screen()
        val reply = CompletableDeferred<AiChatEntry>()
        screen.askAi("もうすぐ") { reply.await() }
        frame(screen)
        assertEquals(AiChatUiState.Loading("もうすぐ"), screen.aiChatState)

        screen.dismissAiChat() // tap outside, close button or Back
        frame(screen)

        assertNull("the popup re-opened after it was closed", screen.aiChatState)
    }

    @Test
    fun aReplyWhileThePopupIsOpenIsShownAndKeptNowhereElse() = runTest {
        val screen = screen()
        val reply = CompletableDeferred<AiChatEntry>()
        screen.askAi("もうすぐ") { reply.await() }
        frame(screen)
        reply.complete(AiChatEntry("もうすぐ", "p", "m", "soon", 1.0))
        advanceUntilIdle()
        frame(screen)

        assertEquals("soon", (screen.aiChatState as AiChatUiState.Loaded).entry.response)
        assertNull(screen.aiChatRequestId)
        assertTrue(screen.requests.requests.value.isEmpty())
    }

    @Test
    fun aClosedPopupStaysClosedWhenTheReplyArrives() = runTest {
        val screen = screen()
        val reply = CompletableDeferred<AiChatEntry>()
        screen.askAi("もうすぐ") { reply.await() }
        frame(screen)
        screen.dismissAiChat()
        frame(screen)
        assertNull(screen.aiChatState)
        reply.complete(AiChatEntry("もうすぐ", "p", "m", "soon", 1.0))
        advanceUntilIdle()
        frame(screen)
        assertNull(screen.aiChatState)
        assertEquals(1, screen.requests.requests.value.size) // unseen reply kept for the dot
    }
}
