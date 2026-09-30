package moe.antimony.hoshi.features.sync.http

import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import moe.antimony.hoshi.R
import moe.antimony.hoshi.ui.UiText
import kotlin.math.ceil

/** Byte counts and estimates for the current file, never the whole library. */
data class HttpSyncTransferProgress(
    val transferredBytes: Long,
    val totalBytes: Long?,
    val bytesPerSecond: Double? = null,
    val remainingSeconds: Long? = null,
) {
    val fraction: Float?
        get() = totalBytes?.takeIf { it > 0 }?.let {
            (transferredBytes.toDouble() / it).coerceIn(0.0, 1.0).toFloat()
        }

    fun volumeText(): UiText = if (totalBytes != null) {
        UiText.Resource(R.string.http_sync_transfer_volume, transferredBytes / 1e6, totalBytes / 1e6)
    } else {
        UiText.Resource(R.string.http_sync_transfer_volume_unknown, transferredBytes / 1e6)
    }

    fun speedText(): UiText {
        val speed = bytesPerSecond ?: return UiText.Resource(R.string.http_sync_transfer_measuring)
        val seconds = remainingSeconds ?: return UiText.Resource(R.string.http_sync_transfer_eta_unknown, speed / 1e6)
        return if (seconds >= 3600) {
            UiText.Resource(R.string.http_sync_transfer_eta_hours, speed / 1e6,
                seconds / 3600, seconds / 60 % 60, seconds % 60)
        } else {
            UiText.Resource(R.string.http_sync_transfer_eta_minutes, speed / 1e6, seconds / 60, seconds % 60)
        }
    }
}

/** Bounded five-second window, using a monotonic clock and excluding saved resume bytes. */
internal class HttpSyncTransferMeter(private val nowNanos: () -> Long = System::nanoTime) {
    private data class Sample(val bytes: Long, val time: Long)
    private val samples = ArrayDeque<Sample>()
    private var latest: Sample? = null
    private var total: Long? = null

    @Synchronized
    fun record(transferred: Long, totalBytes: Long) {
        val sample = Sample(transferred.coerceAtLeast(0), nowNanos())
        val size = totalBytes.takeIf { it > 0 }
        val previous = latest
        if (previous == null || sample.bytes < previous.bytes || size != total || sample.time < previous.time) {
            samples.clear()
        }
        total = size
        latest = sample
        trim(sample.time)
        if (samples.isEmpty() || sample.time - samples.last().time >= 250_000_000L) {
            samples.addLast(sample)
        }
    }

    @Synchronized
    fun snapshot(): HttpSyncTransferProgress? {
        val end = latest ?: return null
        val now = nowNanos()
        trim(now)
        val start = samples.first()
        // Sparse callbacks can straddle a long pause. The older byte baseline still
        // applies at the window boundary, but time before that boundary does not.
        val elapsed = (now - maxOf(start.time, now - 5_000_000_000L)) / 1e9
        val speed = when {
            elapsed < 0.5 -> null
            now - end.time >= 5_000_000_000L -> 0.0
            else -> (end.bytes - start.bytes).coerceAtLeast(0) / elapsed
        }
        val remaining = total?.let { (it - end.bytes).coerceAtLeast(0) }
        val eta = when {
            remaining == 0L -> 0L
            remaining != null && speed != null && speed > 0 -> ceil(remaining / speed).toLong()
            else -> null
        }
        return HttpSyncTransferProgress(end.bytes, total, speed, eta)
    }

    private fun trim(now: Long) {
        while (samples.size > 1 && samples[1].time <= now - 5_000_000_000L) samples.removeFirst()
    }
}

/** Both engines sample the same meter; IO callbacks never wait for the UI or notifications. */
internal suspend fun <T, P> withHttpSyncTransferProgress(
    onProgress: suspend (P) -> Unit,
    makeProgress: (HttpSyncTransferProgress) -> P,
    meter: HttpSyncTransferMeter = HttpSyncTransferMeter(),
    block: suspend (onByteProgress: (Long, Long) -> Unit) -> T,
): T = coroutineScope {
    val updates = launch {
        while (isActive) {
            delay(1_000)
            meter.snapshot()?.let { onProgress(makeProgress(it)) }
        }
    }
    try {
        val result = block(meter::record)
        updates.cancelAndJoin()
        meter.snapshot()?.let { onProgress(makeProgress(it)) }
        result
    } finally {
        updates.cancel()
    }
}
