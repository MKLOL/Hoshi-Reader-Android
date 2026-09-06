package moe.antimony.hoshi.features.mangareader

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.widget.Toast
import moe.antimony.hoshi.R

/**
 * JavaScript bridge that copies a whole mokuro speech bubble's OCR text to the system
 * clipboard. Bound to `window.HoshiMangaClipboard`; the manga page's per-bubble copy button
 * (shown once a bubble is revealed) calls [copyBubbleText] with that bubble's full text.
 *
 * Constructed with the application context so it never outlives or leaks the reader Activity.
 */
internal class MangaClipboardBridge(context: Context) {
    private val appContext = context.applicationContext

    @JavascriptInterface
    fun copyBubbleText(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        // @JavascriptInterface callbacks arrive on a binder thread; both the clipboard write
        // and the Toast need to run on the main thread.
        mainHandler.post {
            val clipboard = appContext.getSystemService(Context.CLIPBOARD_SERVICE)
                as? ClipboardManager ?: return@post
            clipboard.setPrimaryClip(
                ClipData.newPlainText(appContext.getString(R.string.manga_clipboard_label), trimmed),
            )
            Toast.makeText(
                appContext,
                appContext.getString(R.string.manga_clipboard_bubble_copied),
                Toast.LENGTH_SHORT,
            ).show()
        }
    }

    private companion object {
        val mainHandler = Handler(Looper.getMainLooper())
    }
}
