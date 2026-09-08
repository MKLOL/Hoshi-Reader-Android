package moe.antimony.hoshi.features.dictionary

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PopupCallbackDispatcherTest {
    @Test
    fun livePopupDeliversQueuedCallbacksInOrder() {
        val queue = ArrayDeque<() -> Unit>()
        val calls = mutableListOf<String>()
        val dispatcher = PopupCallbackDispatcher(queue::addLast, queue::clear)

        dispatcher.post { calls += "content ready" }
        dispatcher.post { calls += "button frames" }
        assertTrue(calls.isEmpty())
        while (queue.isNotEmpty()) queue.removeFirst().invoke()

        assertEquals(listOf("content ready", "button frames"), calls)
    }

    @Test
    fun replacingPopupDropsItsQueuedRedrawAndAlreadyDequeuedCallback() {
        val queue = ArrayDeque<() -> Unit>()
        val calls = mutableListOf<String>()
        val dispatcher = PopupCallbackDispatcher(queue::addLast, queue::clear)
        dispatcher.post { calls += "old visual-state request" }
        val dequeued = queue.removeFirst()
        dispatcher.post { calls += "old duplicate-check reply" }

        dispatcher.release()
        assertTrue(queue.isEmpty())
        dequeued()

        val replacement = PopupCallbackDispatcher(queue::addLast, queue::clear)
        replacement.post { calls += "new popup" }
        queue.removeFirst().invoke()
        assertEquals(listOf("new popup"), calls)
    }

    @Test
    fun javascriptMessageEnqueuedDuringReleaseCannotUseDestroyedWebView() {
        val queue = ArrayDeque<() -> Unit>()
        var webViewCalls = 0
        lateinit var dispatcher: PopupCallbackDispatcher
        dispatcher = PopupCallbackDispatcher(
            enqueue = { action ->
                // JS passed the initial lifetime check; the UI thread destroys the popup before
                // the JS thread finishes enqueueing. Removing existing messages alone misses it.
                dispatcher.release()
                queue.addLast(action)
            },
            clearQueue = queue::clear,
        )

        dispatcher.post { webViewCalls += 1 }
        queue.removeFirst().invoke()

        assertEquals(0, webViewCalls)
    }

    @Test
    fun lateAsyncRepliesAndVisualStateCompletionsAreIgnoredAfterRelease() {
        val queue = ArrayDeque<() -> Unit>()
        var webViewCalls = 0
        var clears = 0
        val dispatcher = PopupCallbackDispatcher(queue::addLast) {
            clears += 1
            queue.clear()
        }
        val asyncReply = { dispatcher.post { webViewCalls += 1 } }
        val visualStateCompletion = { dispatcher.runIfActive { webViewCalls += 1 } }

        dispatcher.release()
        dispatcher.release()
        asyncReply()
        visualStateCompletion()

        assertTrue(queue.isEmpty())
        assertEquals(0, webViewCalls)
        assertEquals(1, clears)
    }
}
