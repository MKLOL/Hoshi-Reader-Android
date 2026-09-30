package moe.antimony.hoshi.features.sync.http

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import moe.antimony.hoshi.R
import moe.antimony.hoshi.ui.UiText
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HttpSyncTransferProgressTest {
    private var now = 0L
    private val meter = HttpSyncTransferMeter { now }
    private fun record(seconds: Double, done: Long, total: Long = 10_000_000) {
        now = (seconds * 1e9).toLong()
        meter.record(done, total)
    }

    @Test fun resumedBytesAreNotCountedAsNewThroughput() {
        record(0.0, 4_000_000)
        assertNull(meter.snapshot()!!.bytesPerSecond)
        record(2.0, 6_000_000)
        val progress = meter.snapshot()!!
        assertEquals(1_000_000.0, progress.bytesPerSecond!!, 0.01)
        assertEquals(4L, progress.remainingSeconds)
        assertEquals(0.6f, progress.fraction!!, 0.0001f)
    }

    @Test fun recentWindowAdaptsToSlowerTransfers() {
        for (second in 0..5) record(second.toDouble(), second * 1_000_000L)
        for (second in 6..10) record(second.toDouble(), 5_000_000L + (second - 5) * 100_000L)
        assertEquals(100_000.0, meter.snapshot()!!.bytesPerSecond!!, 0.01)
        assertEquals(45L, meter.snapshot()!!.remainingSeconds)
    }

    @Test fun silenceDropsSpeedToZeroAndRemovesEta() {
        record(0.0, 0)
        record(1.0, 1_000_000)
        record(1.1, 1_100_000) // Last callback need not be on a sampling boundary.
        now = 7_000_000_000
        assertEquals(0.0, meter.snapshot()!!.bytesPerSecond!!, 0.0)
        assertNull(meter.snapshot()!!.remainingSeconds)
    }

    @Test fun ignoredRangeOrChangedTotalStartsAFreshEstimate() {
        record(0.0, 0)
        record(1.0, 1_000_000)
        record(2.0, 0)
        assertNull(meter.snapshot()!!.bytesPerSecond)
        record(3.0, 500_000)
        assertEquals(500_000.0, meter.snapshot()!!.bytesPerSecond!!, 0.01)
        record(4.0, 500_000, 20_000_000)
        assertNull(meter.snapshot()!!.bytesPerSecond)
    }

    @Test fun speedRecoversAfterALongPauseWithoutIncludingTheEntirePause() {
        record(0.0, 0)
        record(1.0, 1_000_000)
        now = 60_000_000_000
        assertEquals(0.0, meter.snapshot()!!.bytesPerSecond!!, 0.0)
        record(61.0, 2_000_000)
        assertEquals(200_000.0, meter.snapshot()!!.bytesPerSecond!!, 0.01)
        assertEquals(40L, meter.snapshot()!!.remainingSeconds)
    }

    @Test fun unknownLengthHasSpeedButNoEtaOrFraction() {
        record(0.0, 0, -1)
        record(2.0, 2_000_000, -1)
        assertEquals(1_000_000.0, meter.snapshot()!!.bytesPerSecond!!, 0.01)
        assertNull(meter.snapshot()!!.fraction)
        assertNull(meter.snapshot()!!.remainingSeconds)
    }

    @Test fun largeFilesUseLongByteCountsAndExactZeroStart() {
        val total = 8_000_000_000L
        record(0.0, 0, total)
        assertEquals(0f, HttpSyncProgress(transfer = meter.snapshot()).fraction!!, 0f)
        record(2.0, total / 2, total)
        assertEquals(0.5f, HttpSyncProgress(transfer = meter.snapshot()).fraction!!, 0f)
        record(4.0, total, total)
        assertEquals(1f, meter.snapshot()!!.fraction!!, 0f)
        assertEquals(0L, meter.snapshot()!!.remainingSeconds)
    }

    @Test fun warmupAndRoundedUpEtaAvoidInfinityOrEarlyZero() {
        record(0.0, 0)
        record(0.1, 1_000_000)
        assertNull(meter.snapshot()!!.bytesPerSecond)
        record(1.0, 9_999_999)
        assertEquals(1L, meter.snapshot()!!.remainingSeconds)
    }

    @Test fun formattingUsesLocalizedResourcesAndDecimalMegabytes() {
        val transfer = HttpSyncTransferProgress(2_000_000, 10_000_000, 1_250_000.0, 3661)
        assertEquals(UiText.Resource(R.string.http_sync_transfer_volume, 2.0, 10.0), transfer.volumeText())
        assertEquals(UiText.Resource(R.string.http_sync_transfer_eta_hours, 1.25, 1L, 1L, 1L), transfer.speedText())
        assertEquals(UiText.Resource(R.string.http_sync_transfer_eta_minutes, 1.25, 1L, 5L),
            transfer.copy(remainingSeconds = 65).speedText())
        assertEquals(UiText.Resource(R.string.http_sync_transfer_eta_unknown, 0.0),
            transfer.copy(bytesPerSecond = 0.0, remainingSeconds = null).speedText())
    }

    @Test fun bridgeReportsStallsWithoutMoreByteCallbacksAndStopsOnCompletion() = runTest {
        val reports = mutableListOf<HttpSyncTransferProgress>()
        val finish = CompletableDeferred<Unit>()
        val task = async {
            withHttpSyncTransferProgress(
                onProgress = { reports.add(it); Unit }, makeProgress = { it },
                meter = HttpSyncTransferMeter { testScheduler.currentTime * 1_000_000 },
            ) { report ->
                report(0, 10_000_000)
                delay(500)
                report(1_000_000, 10_000_000)
                finish.await()
            }
        }
        advanceTimeBy(1_001)
        assertEquals(1_000_000.0, reports.last().bytesPerSecond!!, 0.01)
        advanceTimeBy(5_000)
        assertEquals(0.0, reports.last().bytesPerSecond!!, 0.0)
        assertNull(reports.last().remainingSeconds)
        finish.complete(Unit)
        task.await()
        val size = reports.size
        advanceTimeBy(5_000)
        assertEquals(size, reports.size)
    }

    @Test fun fastTransferDoesNotWaitForATimerAndCancellationStopsSampling() = runTest {
        val reports = mutableListOf<HttpSyncTransferProgress>()
        withHttpSyncTransferProgress(onProgress = { reports.add(it); Unit }, makeProgress = { it }) { report ->
            report(0, 10)
            report(10, 10)
        }
        assertEquals(0L, testScheduler.currentTime)
        assertEquals(10L, reports.single().transferredBytes)
        val task = async {
            withHttpSyncTransferProgress(onProgress = { reports.add(it); Unit }, makeProgress = { it }) { report ->
                report(0, 10)
                awaitCancellation()
            }
        }
        runCurrent()
        task.cancelAndJoin()
        val size = reports.size
        advanceTimeBy(10_000)
        assertEquals(size, reports.size)
    }
}
