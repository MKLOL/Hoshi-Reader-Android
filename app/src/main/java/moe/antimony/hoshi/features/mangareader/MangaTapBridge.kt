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
    private val onBubbleRevealed: (String) -> Unit = {},
) {
    @JavascriptInterface
    fun selectedNothing() {
        mainHandler.post { onSelectedNothing() }
    }

    @JavascriptInterface
    fun bubbleRevealed(text: String) {
        mainHandler.post { onBubbleRevealed(text) }
    }

    private companion object {
        val mainHandler = Handler(Looper.getMainLooper())
    }
}
