package moe.antimony.hoshi.features.sasayaki

import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SasayakiCueAudioDispatchTest {
    @Test
    fun blockedEncoderLeavesCallerResponsiveAndReturnsToCallerBeforeMining() = runBlocking {
        val callerThread = Thread.currentThread()
        val started = CompletableDeferred<Thread>()
        val releaseEncoder = CountDownLatch(1)
        val output = File("cue.aac")
        var delivered: File? = null
        var deliveryThread: Thread? = null
        val preparation = launch {
            delivered = dispatchSasayakiCueAudioExport {
                started.complete(Thread.currentThread())
                check(releaseEncoder.await(5, TimeUnit.SECONDS))
                output
            }
            deliveryThread = Thread.currentThread()
        }
        try {
            assertNotEquals(callerThread, withTimeout(5_000) { started.await() })
            // This assertion runs on the UI-equivalent caller while encoding is blocked.
            assertFalse(preparation.isCompleted)
            assertEquals(null, delivered)
        } finally {
            releaseEncoder.countDown()
        }
        withTimeout(5_000) { preparation.join() }
        assertEquals(output, delivered)
        assertEquals(callerThread, deliveryThread)
    }

    @Test
    fun closingOwnerDuringEncodingCannotDeliverLateContextOrStartMining() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val releaseEncoder = CountDownLatch(1)
        var miningStarted = false
        val preparation = launch {
            dispatchSasayakiCueAudioExport {
                started.complete(Unit)
                check(releaseEncoder.await(5, TimeUnit.SECONDS))
                File("late-cue.aac")
            }
            miningStarted = true
        }
        try {
            withTimeout(5_000) { started.await() }
            preparation.cancel()
        } finally {
            releaseEncoder.countDown()
        }
        withTimeout(5_000) { preparation.cancelAndJoin() }
        assertTrue(preparation.isCancelled)
        assertFalse(miningStarted)
    }
}
