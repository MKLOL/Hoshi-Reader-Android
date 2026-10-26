package moe.antimony.hoshi.features.reader.sentence

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import moe.antimony.hoshi.epub.Bookmark
import moe.antimony.hoshi.epub.ReadingStatistics
import moe.antimony.hoshi.navigation.ReaderRouteLoadState
import moe.antimony.hoshi.navigation.ReaderRouteStateHolder

/** Writes sentence navigation through the normal durable bookmark, history, and sync path. */
internal class SentenceReaderProgressSession(
    private val state: ReaderRouteLoadState.Ready,
    private val progress: SentenceReadingProgress,
    private val stateHolder: ReaderRouteStateHolder,
    private val scope: CoroutineScope,
    private val now: () -> Double,
    private val onBookmarkSaved: () -> Unit,
) {
    private var latestSave: Job? = null
    private var latestPoint: SentenceProgressPoint? = state.bookmark?.let {
        SentenceProgressPoint(it.chapterIndex, it.progress, it.characterCount)
    }

    // Constructing or restoring a session does not write progress or finish a book.
    fun navigate(position: SentencePosition): Job? = progress.at(position)?.let { save(it, "sentenceTurn") }

    /** Reading the final sentence needs an explicit action, even in a one-sentence book. */
    fun finish(position: SentencePosition): Job? {
        if (!progress.isLast(position)) return null
        return progress.completionPoint?.let { save(it, "finishBook") }
    }

    /** Statistics-only writes must not refresh the bookmark timestamp or navigation intent. */
    fun saveStatistics(statistics: List<ReadingStatistics>, flush: Boolean = true): Job = enqueue {
        stateHolder.saveStatistics(state, statistics, flush)
    }

    suspend fun awaitSaves() {
        latestSave?.join()
    }

    private fun save(point: SentenceProgressPoint, action: String): Job? {
        val previous = latestPoint
        if (previous != null && previous.chapterIndex == point.chapterIndex && kotlin.math.abs(previous.progress - point.progress) < 0.00000001) return null
        val occurredAt = now()
        val event = state.positionSession?.navigate(
            Bookmark(point.chapterIndex, point.progress, point.characterCount, occurredAt), action,
        )
        latestPoint = point
        if (state.positionSession != null && event == null) return null
        return enqueue {
            stateHolder.saveBookmark(
                state = state,
                chapterIndex = point.chapterIndex,
                progress = point.progress,
                characterCount = point.characterCount,
                onBookmarkSaved = onBookmarkSaved,
                occurredAt = occurredAt,
                event = event,
                captureAlreadyDone = true,
            )
        }
    }

    private fun enqueue(save: suspend () -> Unit): Job {
        val previous = latestSave
        return scope.launch {
            previous?.join()
            save()
        }.also { latestSave = it }
    }
}
