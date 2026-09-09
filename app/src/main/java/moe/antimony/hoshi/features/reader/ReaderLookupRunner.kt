package moe.antimony.hoshi.features.reader

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Runs the reader's dictionary lookups off the main thread.
 *
 * The native lookup shares one monitor with dictionary rebuilds, so a query issued while an
 * import or a Dictionary search rebuilds the index waits for that rebuild. Running it on
 * [lookupDispatcher] keeps the UI thread free, exactly like the manga reader and the popup
 * overlay already do. Each [launch] supersedes the pending request, and [cancel] drops a
 * result whose popup was dismissed while the query ran, so a late lookup can never reopen
 * a popup the user has already closed. A request that is dropped either way is told so
 * through its `onDropped` callback, which lets a caller resolve a JavaScript promise that
 * would otherwise wait forever.
 *
 * All methods must be called from the thread that owns [scope] (the main thread).
 */
internal class ReaderLookupRunner(
    private val scope: CoroutineScope,
    private val lookupDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private var job: Job? = null
    private var onDropped: (() -> Unit)? = null

    /** True while a lookup is running whose result has not been delivered or dropped yet. */
    val isPending: Boolean
        get() = job?.isActive == true

    fun <T> launch(lookup: () -> T, onResult: (T) -> Unit, onDropped: (() -> Unit)? = null) {
        cancel()
        this.onDropped = onDropped
        job = scope.launch {
            val result = withContext(lookupDispatcher) { lookup() }
            ensureActive()
            if (job === coroutineContext.job) {
                job = null
                this@ReaderLookupRunner.onDropped = null
            }
            onResult(result)
        }
    }

    /** Cancels the pending lookup, if any, and tells it that its result will never be delivered. */
    fun cancel() {
        val pending = job
        val dropped = onDropped
        job = null
        onDropped = null
        if (pending == null || !pending.isActive) return
        pending.cancel()
        dropped?.invoke()
    }
}
