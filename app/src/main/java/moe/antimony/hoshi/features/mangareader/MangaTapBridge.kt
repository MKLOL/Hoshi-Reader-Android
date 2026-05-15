package moe.antimony.hoshi.features.mangareader

import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface

/**
 * JavaScript bridge for manga page taps that select nothing. DOM click events already carry
 * WebView-correct client coordinates after pinch zoom and pan, so [MangaPageHtml] handles
 * bubble hit-testing in-page and calls this bridge only when the current lookup popup should
 * be cleared.
 */
internal class MangaTapBridge(
    private val onSelectedNothing: () -> Unit,
) {
    @JavascriptInterface
    fun selectedNothing() {
        mainHandler.post { onSelectedNothing() }
    }

    private companion object {
        val mainHandler = Handler(Looper.getMainLooper())
    }
}
