package moe.antimony.hoshi.features.sasayaki

import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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

    @Test
    fun closingTheReaderDuringTheClipExportStillAddsTheNoteWithoutReplyingToIt() = runBlocking {
        val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val reader = Job()
        val started = CompletableDeferred<Unit>()
        val releaseEncoder = CountDownLatch(1)
        var minedWith: String? = "not mined"
        val replies = mutableListOf<Boolean>()
        val mining = launchSasayakiMining(
            scope = appScope,
            exportCueAudio = {
                dispatchSasayakiCueAudioExport {
                    started.complete(Unit)
                    check(releaseEncoder.await(5, TimeUnit.SECONDS))
                    File("cue.aac")
                }
            },
            mine = { audioPath, onResult -> minedWith = audioPath; onResult(true) },
            // The reader's reply touches its popup, so it is skipped once the reader is gone.
            onResult = { mined -> if (reader.isActive) replies += mined },
        )
        try {
            withTimeout(5_000) { started.await() }
            // Back, a switch to sentence mode or a reload closes the reader mid-export.
            reader.cancel()
        } finally {
            releaseEncoder.countDown()
        }
        withTimeout(5_000) { mining.join() }
        assertEquals(File("cue.aac").absolutePath, minedWith)
        assertEquals(emptyList<Boolean>(), replies)
        appScope.cancel()
    }

    @Test
    fun aFailedClipExportAnswersFalseWithoutMining() = runBlocking {
        val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        var mined = false
        val replies = mutableListOf<Boolean>()
        val mining = launchSasayakiMining(
            scope = appScope,
            exportCueAudio = { throw java.io.IOException("unreadable audio") },
            mine = { _, onResult -> mined = true; onResult(true) },
            onResult = { replies += it },
        )
        withTimeout(5_000) { mining.join() }
        assertFalse(mined)
        assertEquals(listOf(false), replies)
        appScope.cancel()
    }
}
