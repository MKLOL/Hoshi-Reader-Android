package moe.antimony.hoshi.features.reader.sentence

import moe.antimony.hoshi.epub.DeviceIdentity
import moe.antimony.hoshi.epub.ReadingStatistics
import moe.antimony.hoshi.features.dictionary.LookupPopupItem
import moe.antimony.hoshi.features.reader.ReaderSelectionData
import moe.antimony.hoshi.features.reader.ReaderStatisticsClock
import moe.antimony.hoshi.features.reader.ReaderStatisticsState
import moe.antimony.hoshi.features.reader.ReaderStatisticsTracker
import moe.antimony.hoshi.features.reader.SystemReaderStatisticsClock
import moe.antimony.hoshi.features.usage.ReaderUsageSession
import moe.antimony.hoshi.features.usage.UsageLookupSource
import moe.antimony.hoshi.features.usage.logLookup
import java.time.ZoneId

/**
 * Sentence mode's foreground reading session, using the normal EPUB statistics and usage log.
 * Character positions are absolute counts from the EPUB's BookInfo, not sentence UTF-16 offsets.
 * Starting at a saved sentence establishes a baseline; only subsequent movement is credited.
 */
internal class SentenceReaderStatisticsSession(
    title: String,
    initialStatistics: List<ReadingStatistics>,
    initialCharacter: Int,
    device: DeviceIdentity? = null,
    private val usageSession: ReaderUsageSession? = null,
    clock: ReaderStatisticsClock = SystemReaderStatisticsClock,
    resetHour: () -> Int = { 0 },
    statisticsZone: () -> ZoneId? = { null },
) {
    private var currentCharacter = initialCharacter.coerceAtLeast(0)
    private var closed = false
    private val tracker = ReaderStatisticsTracker(
        title = title,
        initialStatistics = initialStatistics,
        enabled = true,
        clock = clock,
        device = device,
        onTrackingChanged = { usageSession?.readingChanged(it) },
        resetHour = resetHour,
        statisticsZone = statisticsZone,
    )

    val state: ReaderStatisticsState get() = tracker.state

    /** Call when resumed, including an opening that is already in the foreground. */
    fun resume() {
        if (closed || tracker.state.isTracking) return
        usageSession?.opened(page = null)
        tracker.start(currentCharacter)
    }

    /** Count elapsed foreground time without crediting the displayed sentence again. */
    fun tick() {
        if (!closed) tracker.update(currentCharacter)
    }

    /** A real sentence turn; [chapter] is 1-based, as in the normal EPUB usage log. */
    fun navigate(chapter: Int, character: Int) {
        if (closed) return
        currentCharacter = character.coerceAtLeast(0)
        tracker.update(currentCharacter)
        usageSession?.epubPageTurned(chapter, currentCharacter)
    }

    /** Flush the last foreground interval before persisting on pause or disposal. */
    fun pause() {
        if (!closed) tracker.pause(currentCharacter)
    }

    /** Ends the usage session once, even when both lifecycle pause and disposal are delivered. */
    fun close() {
        if (closed) return
        pause()
        usageSession?.closed(page = null)
        closed = true
    }

    /** Normal per-day/device entries, ready for ReaderRouteStateHolder.saveStatistics. */
    fun statisticsForPersistenceOrNull(): List<ReadingStatistics>? =
        tracker.statisticsForPersistenceOrNull()

    fun logLookup(selection: ReaderSelectionData, popup: LookupPopupItem?, source: UsageLookupSource) {
        if (!closed) usageSession?.logLookup(selection, popup, source)
    }
}
