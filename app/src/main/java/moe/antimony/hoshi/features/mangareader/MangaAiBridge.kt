package moe.antimony.hoshi.features.mangareader

import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface

/**
 * JavaScript bridge for the manga speech-bubble ChatGPT button. Bound to `window.HoshiMangaAi`;
 * the in-page tap handler ([MangaPageHtml]) calls [askAboutBubble] with a revealed bubble's
 * full OCR text when its ChatGPT button is tapped.
 *
 * `@JavascriptInterface` callbacks arrive on a binder thread, so [onAskAboutBubble] is
 * dispatched to the main thread before it reaches Compose state in [MangaReaderScreen].
 */
internal class MangaAiBridge(
    private val onAskAboutBubble: (String) -> Unit,
) {
    @JavascriptInterface
    fun askAboutBubble(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        mainHandler.post { onAskAboutBubble(trimmed) }
    }

    private companion object {
        val mainHandler = Handler(Looper.getMainLooper())
    }
}
