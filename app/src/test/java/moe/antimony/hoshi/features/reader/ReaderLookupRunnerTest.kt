package moe.antimony.hoshi.features.reader

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderLookupRunnerTest {
    @Test
    fun lookupRunsOffTheCallerThreadAndTheResultIsDeliveredBackOnIt() = runBlocking {
        withHarness { h ->
            val callerThread = Thread.currentThread()
            var lookupThread: Thread? = null
            var resultThread: Thread? = null
            h.runner.launch(
                lookup = { lookupThread = Thread.currentThread(); "word" },
                onResult = { resultThread = Thread.currentThread() },
            )
            h.awaitIdle()
            assertNotEquals(callerThread, lookupThread)
            assertEquals(callerThread, resultThread)
            assertFalse(h.runner.isPending)
        }
    }

    @Test
    fun newerRequestDropsTheResultOfTheOneItSupersedes() = runBlocking {
        withHarness { h ->
            val firstStarted = CompletableDeferred<Unit>()
            val firstMayFinish = CountDownLatch(1)
            val delivered = mutableListOf<String>()
            h.runner.launch(
                lookup = {
                    firstStarted.complete(Unit)
                    firstMayFinish.await(5, TimeUnit.SECONDS)
                    "first"
                },
                onResult = { delivered += it },
            )
            firstStarted.await()
            h.runner.launch(lookup = { "second" }, onResult = { delivered += it })
            firstMayFinish.countDown()
            h.awaitIdle()
            assertEquals(listOf("second"), delivered)
        }
    }

    @Test
    fun cancelDropsAResultThatArrivesAfterThePopupWasDismissed() = runBlocking {
        withHarness { h ->
            val started = CompletableDeferred<Unit>()
            val mayFinish = CountDownLatch(1)
            val delivered = mutableListOf<String>()
            h.runner.launch(
                lookup = {
                    started.complete(Unit)
                    mayFinish.await(5, TimeUnit.SECONDS)
                    "late"
                },
                onResult = { delivered += it },
            )
            started.await()
            assertTrue(h.runner.isPending)
            h.runner.cancel()
            assertFalse(h.runner.isPending)
            mayFinish.countDown()
            h.awaitIdle()
            assertTrue(delivered.isEmpty())
        }
    }

    @Test
    fun supersededAndCancelledRequestsAreToldTheirResultWasDroppedExactlyOnce() = runBlocking {
        withHarness { h ->
            val firstStarted = CompletableDeferred<Unit>()
            val firstMayFinish = CountDownLatch(1)
            var firstDropped = 0
            var secondDropped = 0
            h.runner.launch(
                lookup = {
                    firstStarted.complete(Unit)
                    firstMayFinish.await(5, TimeUnit.SECONDS)
                    "first"
                },
                onResult = {},
                onDropped = { firstDropped++ },
            )
            firstStarted.await()
            h.runner.launch(lookup = { "second" }, onResult = {}, onDropped = { secondDropped++ })
            assertEquals(1, firstDropped)
            firstMayFinish.countDown()
            h.awaitIdle()
            assertEquals(1, firstDropped)
            assertEquals(0, secondDropped)

            val thirdStarted = CompletableDeferred<Unit>()
            val thirdMayFinish = CountDownLatch(1)
            var thirdDropped = 0
            h.runner.launch(
                lookup = { thirdStarted.complete(Unit); thirdMayFinish.await(5, TimeUnit.SECONDS) },
                onResult = {},
                onDropped = { thirdDropped++ },
            )
            thirdStarted.await()
            h.runner.cancel()
            h.runner.cancel()
            thirdMayFinish.countDown()
            h.awaitIdle()
            assertEquals(1, thirdDropped)
        }
    }

    @Test
    fun aDeliveredRequestIsNeverReportedAsDropped() = runBlocking {
        withHarness { h ->
            var dropped = 0
            var delivered = 0
            h.runner.launch(lookup = { "word" }, onResult = { delivered++ }, onDropped = { dropped++ })
            h.awaitIdle()
            h.runner.cancel()
            assertEquals(1, delivered)
            assertEquals(0, dropped)
        }
    }

    @Test
    fun resultsAreDeliveredInOrderWhenRequestsDoNotOverlap() = runBlocking {
        withHarness { h ->
            val delivered = mutableListOf<Int>()
            for (request in 1..3) {
                h.runner.launch(lookup = { request }, onResult = { delivered += it })
                h.awaitIdle()
            }
            assertEquals(listOf(1, 2, 3), delivered)
        }
    }

    /**
     * The runner's scope shares the test's runBlocking event loop, so results resume on the
     * test thread the way results resume on the main thread for a rememberCoroutineScope.
     */
    private class Harness(val scope: CoroutineScope) {
        val runner = ReaderLookupRunner(scope, Dispatchers.IO)

        /**
         * Lets every launched lookup run to completion, cancelled ones included, and every
         * continuation run on the event loop. Waiting on completion rather than activity is what
         * proves a cancelled lookup's late result is dropped instead of merely not yet delivered.
         */
        suspend fun awaitIdle() = withTimeout(5_000) {
            while (scope.coroutineContext.job.children.any { !it.isCompleted }) yield()
        }
    }

    private suspend fun CoroutineScope.withHarness(block: suspend (Harness) -> Unit) {
        val runnerScope = CoroutineScope(coroutineContext + Job(coroutineContext.job))
        try {
            block(Harness(runnerScope))
        } finally {
            runnerScope.cancel()
        }
    }
}
