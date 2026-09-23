package moe.antimony.hoshi.features.mangareader

import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface

/**
 * JavaScript bridge for manga page taps that select nothing. DOM click events already carry
 * WebView-correct client coordinates after pinch zoom and pan, so [MangaPageHtml] handles
 * bubble hit-testing in-page and calls this bridge when the current lookup popup should be
 * cleared, and when a hidden bubble is revealed (for the usage log).
 */
internal class MangaTapBridge(
    private val onSelectedNothing: () -> Unit,
    private val onBubbleRevealed: (String, String?) -> Unit = { _, _ -> },
) {
    @JavascriptInterface
    fun selectedNothing() {
        mainHandler.post { onSelectedNothing() }
    }

    /** @param blockId the bubble's mokuro address (`p{page}b{block}`), or empty when unknown. */
    @JavascriptInterface
    fun bubbleRevealed(text: String, blockId: String) {
        mainHandler.post { onBubbleRevealed(text, blockId.ifEmpty { null }) }
    }

    private companion object {
        val mainHandler = Handler(Looper.getMainLooper())
    }
}
