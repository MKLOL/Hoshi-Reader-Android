package moe.antimony.hoshi.features.ai

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** A translation request and where it stands. */
data class AiChatRequest(
    val id: Long,
    /** Null for a book without a directory; its replies are not saved. */
    val bookRoot: File?,
    /** Asking about the same bubble again joins this request; null (a screenshot) never matches. */
    val key: String?,
    val bubbleText: String,
    val onDevice: Boolean,
    /** True while a popup shows it; false once it was closed and runs on in the background. */
    val visible: Boolean,
    val status: Status,
) {
    sealed interface Status {
        data object Running : Status

        /**
         * The reply arrived. Out of sight, [entry] is kept (for a bubble) so asking about that
         * bubble again shows it instead of paying for a new reply. [saved] turns true once the
         * reply is in the history, which is when the history can show it.
         */
        data class Done(val entry: AiChatEntry?, val saved: Boolean = false) : Status

        data class Failed(val message: String) : Status
    }
}

/**
 * ChatGPT and on-device translation requests that outlive their popup. Closing the popup (a
 * stray tap outside it, the close button, Back or leaving the reader) only hides the request:
 * it keeps running in the app scope, its reply is appended to the book's history, and the
 * history lists it with a spinner until then. A request that fails out of sight stays listed
 * there with its error until it is retried or dismissed.
 */
class AiChatRequests(
    private val scope: CoroutineScope,
    private val historyStore: AiChatHistoryStore = AiChatHistoryStore(),
    /** A request started running; the app keeps itself (and its network) alive until it ends. */
    private val onRunning: () -> Unit = {},
) {
    private class Work(
        val run: suspend () -> AiChatEntry,
        val failureMessage: (Throwable) -> String,
        val onSaved: (AiChatEntry) -> Unit,
    )

    private val nextId = AtomicLong()

    /** The closures of requests that can still run: running, or failed and waiting for Retry. */
    private val work = ConcurrentHashMap<Long, Work>()
    private val _requests = MutableStateFlow<List<AiChatRequest>>(emptyList())
    val requests: StateFlow<List<AiChatRequest>> = _requests.asStateFlow()

    private val _historyRevisions = MutableStateFlow<Map<String, Int>>(emptyMap())

    /** Bumped for a book each time a reply is appended to its history file. */
    val historyRevisions: StateFlow<Map<String, Int>> = _historyRevisions.asStateFlow()

    /**
     * Starts a request, shown by the caller's popup, and returns its id. A request already
     * running for the same bubble, or a reply to it that has not been seen, is shown again
     * instead of sending a new one.
     */
    fun start(
        bookRoot: File?,
        key: String?,
        bubbleText: String,
        onDevice: Boolean,
        failureMessage: (Throwable) -> String,
        onSaved: (AiChatEntry) -> Unit = {},
        run: suspend () -> AiChatEntry,
    ): Long {
        if (key != null) {
            val existing = _requests.value.firstOrNull { it.matches(bookRoot, key) }
            val status = existing?.status
            if (existing != null && (status is AiChatRequest.Status.Running ||
                    (status is AiChatRequest.Status.Done && status.entry != null))
            ) {
                update(existing.id) { it.copy(visible = true) }
                return existing.id
            }
        }
        val id = nextId.incrementAndGet()
        work[id] = Work(run, failureMessage, onSaved)
        // A failed or already-seen request about the same bubble is replaced.
        val replaced = if (key == null) emptySet() else _requests.value.filter { it.matches(bookRoot, key) }.toSet()
        replaced.forEach { work.remove(it.id) }
        _requests.update { current ->
            current - replaced +
                AiChatRequest(id, bookRoot, key, bubbleText, onDevice, visible = true, AiChatRequest.Status.Running)
        }
        launch(id, bookRoot)
        onRunning()
        return id
    }

    /** Runs a failed request again, from the history. */
    fun retry(id: Long) {
        val request = _requests.value.firstOrNull { it.id == id } ?: return
        if (request.status !is AiChatRequest.Status.Failed || work[id] == null) return
        update(id) { it.copy(status = AiChatRequest.Status.Running) }
        launch(id, request.bookRoot)
        onRunning()
    }

    /** The popup showing [id] closed; the request carries on in the background. */
    fun hide(id: Long) {
        update(id) { request ->
            when (val status = request.status) {
                // Seen in the popup, or no longer needed.
                is AiChatRequest.Status.Failed -> null
                // A screenshot reply cannot be asked for again: keep only the unseen mark, and
                // not even that when the history could not take it.
                is AiChatRequest.Status.Done -> when {
                    request.key != null -> request.copy(visible = false)
                    status.saved -> request.copy(visible = false, status = status.copy(entry = null))
                    else -> null
                }
                AiChatRequest.Status.Running -> request.copy(visible = false)
            }
        }
        trim()
    }

    /** A popup took the outcome of [id] (its reply or error); forget the request. */
    fun consume(id: Long) = update(id) { null }

    /** Forgets a failed request without retrying it. */
    fun dismiss(id: Long) = update(id) { request ->
        request.takeIf { it.status !is AiChatRequest.Status.Failed }
    }

    /** The history of [bookRoot] was opened: the replies it now shows are seen. */
    fun markHistorySeen(bookRoot: File) {
        val seen = _requests.value.filter { request ->
            val status = request.status
            request.bookRoot?.path == bookRoot.path && !request.visible &&
                status is AiChatRequest.Status.Done && status.saved
        }.toSet()
        if (seen.isNotEmpty()) _requests.update { current -> current - seen }
    }

    private fun launch(id: Long, bookRoot: File?) {
        val job = work[id] ?: return
        scope.launch {
            val entry = try {
                job.run()
            } catch (error: Throwable) {
                // A timeout is also a CancellationException; only a cancelled scope is not a failure.
                if (error is CancellationException) currentCoroutineContext().ensureActive()
                val message = runCatching { job.failureMessage(error) }.getOrNull()
                    ?: error.message.orEmpty()
                update(id) { it.copy(status = AiChatRequest.Status.Failed(message)) }
                trim()
                return@launch
            }
            // A finished request no longer needs its closure (screenshot bytes, settings).
            work.remove(id)
            // Shown at once; the history append (a large file with screenshots) follows.
            update(id) { request ->
                if (request.status != AiChatRequest.Status.Running) return@update request
                // A screenshot cannot be asked about again, so only a shown one keeps its reply.
                val kept = if (request.visible || request.key != null) entry else null
                request.copy(status = AiChatRequest.Status.Done(kept))
            }
            // A book deleted meanwhile must not be recreated around its history file.
            val saved = bookRoot != null && bookRoot.isDirectory &&
                runCatching { historyStore.append(bookRoot, entry) }
                    .onFailure { if (it is CancellationException) throw it }
                    .isSuccess
            if (saved) {
                val path = bookRoot!!.path
                _historyRevisions.update { revisions -> revisions + (path to (revisions[path] ?: 0) + 1) }
                runCatching { job.onSaved(entry) }
            }
            update(id) { request ->
                val status = request.status as? AiChatRequest.Status.Done ?: return@update request
                when {
                    saved -> request.copy(status = status.copy(saved = true))
                    // Out of sight and impossible to ask for again: nothing could show it.
                    !request.visible && status.entry == null -> null
                    else -> request
                }
            }
            trim()
        }
    }

    /**
     * Requests out of sight are a convenience: keep only each book's most recent unseen replies
     * and failures in memory.
     */
    private fun trim() {
        val dropped = _requests.value
            .filter { !it.visible && it.status != AiChatRequest.Status.Running }
            .groupBy { (it.bookRoot?.path ?: "") to (it.status is AiChatRequest.Status.Failed) }
            .values
            .flatMap { group -> group.sortedBy { it.id }.dropLast(MAX_OUT_OF_SIGHT_PER_BOOK) }
            .toSet()
        if (dropped.isEmpty()) return
        dropped.forEach { work.remove(it.id) }
        _requests.update { current -> current - dropped }
    }

    /** Replaces request [id] with [transform]'s result, or removes it when that is null. */
    private fun update(id: Long, transform: (AiChatRequest) -> AiChatRequest?) {
        var removed = false
        _requests.update { current ->
            current.mapNotNull { request ->
                if (request.id != id) request else transform(request).also { removed = it == null }
            }
        }
        if (removed) work.remove(id)
    }

    private fun AiChatRequest.matches(bookRoot: File?, key: String) =
        this.key == key && this.bookRoot?.path == bookRoot?.path

    private companion object {
        const val MAX_OUT_OF_SIGHT_PER_BOOK = 20
    }
}

/**
 * Keeps a popup in step with request [id]: Loading while it runs, then its reply or error, which
 * the popup takes over. [onUpdate] gets the popup's new state (null when there is nothing to
 * show) and whether the request has ended. Stops, without touching the popup, as soon as the
 * popup no longer shows the request ([isShown] false, or the request hidden): a closed popup
 * must not be reopened by an update that reached it late.
 */
suspend fun AiChatRequests.followInPopup(
    id: Long,
    isShown: () -> Boolean,
    onUpdate: (state: AiChatUiState?, ended: Boolean) -> Unit,
) {
    requests.first { list ->
        val request = list.firstOrNull { it.id == id }
        if (!isShown() || (request != null && !request.visible)) return@first true
        when (val status = request?.status) {
            null -> {
                onUpdate(null, true)
                true
            }
            AiChatRequest.Status.Running -> {
                onUpdate(AiChatUiState.Loading(request.bubbleText, onDevice = request.onDevice), false)
                false
            }
            is AiChatRequest.Status.Done -> {
                consume(id)
                onUpdate(status.entry?.let { AiChatUiState.Loaded(it, onDevice = request.onDevice) }, true)
                true
            }
            is AiChatRequest.Status.Failed -> {
                consume(id)
                onUpdate(AiChatUiState.Failed(request.bubbleText, status.message, onDevice = request.onDevice), true)
                true
            }
        }
    }
}
