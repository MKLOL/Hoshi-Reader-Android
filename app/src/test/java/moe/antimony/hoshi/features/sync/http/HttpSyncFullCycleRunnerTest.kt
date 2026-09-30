package moe.antimony.hoshi.features.sync.http

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HttpSyncFullCycleRunnerTest {
    private fun result() = HttpSyncResult(
        uploadedBookmarks = 0, uploadedChatEntries = 0, uploadedMetadata = 0,
        downloadedBookmarks = 0, downloadedChatEntries = 0, remoteOnlyBooks = 0, errors = emptyList(),
    )

    @Test(timeout = 5_000)
    fun manualJoinReceivesExistingAndFutureBackgroundProgress() = runBlocking {
        val runner = HttpSyncFullCycleRunner(this)
        val started = CompletableDeferred<Unit>()
        val advance = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val existingReceived = CompletableDeferred<Unit>()
        val nextReceived = CompletableDeferred<Unit>()
        val messages = mutableListOf<String>()
        var invocations = 0
        val background = async {
            runner.run { report ->
                invocations++
                report(HttpSyncProgress("Reading remote history"))
                started.complete(Unit)
                advance.await()
                report(HttpSyncProgress("Merging statistics"))
                finish.await()
                result()
            }
        }
        started.await()
        val manual = async {
            runner.run(onProgress = {
                messages += it.message
                if (it.message == "Reading remote history") existingReceived.complete(Unit)
                if (it.message == "Merging statistics") nextReceived.complete(Unit)
            }) {
                error("Manual sync must join the background pass")
            }
        }
        existingReceived.await()
        advance.complete(Unit)
        nextReceived.await()
        finish.complete(Unit)
        assertEquals(background.await(), manual.await())
        assertEquals(1, invocations)
        assertEquals(listOf("Reading remote history", "Merging statistics"), messages)
    }

    @Test(timeout = 5_000)
    fun cancellingOneWaiterKeepsSharedSyncAndOtherObserversRunning() = runBlocking {
        val runner = HttpSyncFullCycleRunner(this)
        val started = CompletableDeferred<Unit>()
        val joined = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val first = async {
            runner.run { report ->
                report(HttpSyncProgress("Working"))
                started.complete(Unit)
                release.await()
                result()
            }
        }
        started.await()
        val second = async {
            runner.run(onProgress = { joined.complete(Unit) }) {
                error("Second waiter must share the first pass")
            }
        }
        joined.await()
        first.cancelAndJoin()
        release.complete(Unit)
        assertEquals(result(), second.await())
        var ranAgain = false
        assertEquals(result(), runner.run { ranAgain = true; result() })
        assertEquals(true, ranAgain)
    }

    @Test(timeout = 5_000)
    fun immediatelyCompletedFlightDoesNotLeaveAStaleResult() = runBlocking {
        val runner = HttpSyncFullCycleRunner(this)
        var invocations = 0
        repeat(2) { runner.run { invocations++; result() } }
        assertEquals(2, invocations)
    }

    @Test(timeout = 5_000)
    fun fullManualSyncWaitsForNarrowBackgroundWorkThenRunsTheFullRequest() = runBlocking {
        val runner = HttpSyncFullCycleRunner(this)
        val started = CompletableDeferred<Unit>()
        val manualJoined = CompletableDeferred<Unit>()
        val finishStatistics = CompletableDeferred<Unit>()
        var fullPasses = 0
        val background = async {
            runner.run(statisticsOnly = true) { report ->
                report(HttpSyncProgress("History"))
                started.complete(Unit)
                finishStatistics.await()
                result().copy(downloadedStatistics = 1)
            }
        }
        started.await()
        val manual = async {
            runner.run(onProgress = { manualJoined.complete(Unit) }) {
                fullPasses++
                result().copy(downloadedPayloads = 1)
            }
        }
        manualJoined.await()
        assertEquals(0, fullPasses)
        finishStatistics.complete(Unit)
        assertEquals(1, background.await().downloadedStatistics)
        assertEquals(1, manual.await().downloadedPayloads)
        assertEquals(1, fullPasses)
    }

    @Test(timeout = 5_000)
    fun aManualRequestRunsItsOwnFreshPassAfterJoiningAnEarlierFullPass() = runBlocking {
        val runner = HttpSyncFullCycleRunner(this)
        val started = CompletableDeferred<Unit>()
        val joined = CompletableDeferred<Unit>()
        val finishBackground = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        val background = async {
            runner.run { report ->
                events += "background"
                report(HttpSyncProgress("Earlier snapshot"))
                started.complete(Unit)
                finishBackground.await()
                result().copy(downloadedStatistics = 1)
            }
        }
        started.await()
        val manual = async {
            runner.run(requireOwnPass = true, onProgress = { joined.complete(Unit) }) {
                events += "fresh manual"
                result().copy(downloadedStatistics = 2)
            }
        }
        joined.await()
        assertEquals(listOf("background"), events)
        finishBackground.complete(Unit)
        assertEquals(1, background.await().downloadedStatistics)
        assertEquals(2, manual.await().downloadedStatistics)
        assertEquals(listOf("background", "fresh manual"), events)
    }

    @Test(timeout = 5_000)
    fun aFailedFlightReleasesItsSlotAndTheNextCallRetries() = runBlocking {
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val runner = HttpSyncFullCycleRunner(owner)
            val failure = IllegalStateException("offline")
            val observed = runCatching { runner.run { throw failure } }.exceptionOrNull()
            assertEquals(failure.javaClass, observed?.javaClass)
            assertEquals(failure.message, observed?.message)
            var retried = false
            assertEquals(result(), runner.run { retried = true; result() })
            assertEquals(true, retried)
        } finally {
            owner.cancel()
        }
    }


    @Test(timeout = 5_000)
    fun aNewRequestWhileAStoppedPassUnwindsRunsItsOwnPassAfterwards() = runBlocking {
        val runner = HttpSyncFullCycleRunner(this)
        val entered = CompletableDeferred<Unit>()
        val unwinding = CompletableDeferred<Unit>()
        val finishUnwinding = CompletableDeferred<Unit>()
        val stopped = async {
            runCatching {
                runner.run { _ ->
                    entered.complete(Unit)
                    try {
                        awaitCancellation()
                    } finally {
                        withContext(NonCancellable) {
                            unwinding.complete(Unit)
                            finishUnwinding.await()
                        }
                    }
                }
            }
        }
        entered.await()
        // The user pressed Stop, then Sync again before the old pass finished closing files.
        runner.cancelActive()
        unwinding.await()
        var passes = 0
        val retry = async { runner.run { _ -> passes++; result() } }
        yield()
        assertEquals("A new pass must not overlap the stopped one", 0, passes)

        finishUnwinding.complete(Unit)

        assertEquals(result(), retry.await())
        assertEquals(1, passes)
        assertTrue(stopped.await().exceptionOrNull() is kotlinx.coroutines.CancellationException)
    }
}
