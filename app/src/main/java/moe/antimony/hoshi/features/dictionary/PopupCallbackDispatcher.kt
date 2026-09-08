package moe.antimony.hoshi.features.dictionary

/** Queues bridge work on the UI thread and invalidates it before its WebView is destroyed. */
internal class PopupCallbackDispatcher(
    private val enqueue: (() -> Unit) -> Unit,
    private val clearQueue: () -> Unit,
) {
    @Volatile
    var isReleased: Boolean = false
        private set

    fun post(action: () -> Unit) {
        if (isReleased) return
        // Release can race with a message arriving from WebView's JavaScript thread. Checking
        // again at execution also covers work enqueued just after clearQueue() has run.
        enqueue { runIfActive(action) }
    }

    /** For callbacks already delivered on the UI thread, including WebView visual-state replies. */
    fun runIfActive(action: () -> Unit) {
        if (!isReleased) action()
    }

    /** Called on the UI thread before destroying the WebView. */
    fun release() {
        if (isReleased) return
        isReleased = true
        clearQueue()
    }
}
