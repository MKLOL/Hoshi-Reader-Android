package moe.antimony.hoshi.webview

import android.view.View
import android.webkit.WebSettings
import android.webkit.WebView

internal interface HoshiWebViewSettings {
    var javaScriptEnabled: Boolean
    var domStorageEnabled: Boolean
    var allowFileAccess: Boolean
    var allowContentAccess: Boolean
}

internal fun HoshiWebViewSettings.applyHoshiWebViewSecurityDefaults() {
    javaScriptEnabled = true
    domStorageEnabled = false
    allowFileAccess = false
    allowContentAccess = false
}

fun WebView.applyHoshiWebViewSecurityDefaults() {
    AndroidHoshiWebViewSettings(settings).applyHoshiWebViewSecurityDefaults()
    disableNativeOverscrollStretch()
    enableWebViewDebuggingForDebugBuilds()
}

/**
 * Enables `chrome://inspect` device-link debugging for the WebView on debug builds only.
 * Reads BuildConfig.DEBUG via reflection so this file can stay in the non-app module — a
 * release build will never trigger the call. Lets `adb forward` + Chrome devtools attach
 * to a running WebView and inspect the OCR overlay's runtime state (computed font-sizes,
 * scrollWidth measurements, the wrap-fallback's binary search progression).
 */
private fun WebView.enableWebViewDebuggingForDebugBuilds() {
    val isDebug = runCatching {
        Class.forName("moe.antimony.hoshi.BuildConfig")
            .getDeclaredField("DEBUG")
            .getBoolean(null)
    }.getOrDefault(false)
    if (isDebug) WebView.setWebContentsDebuggingEnabled(true)
}

fun WebView.disableNativeOverscrollStretch() {
    overScrollMode = View.OVER_SCROLL_NEVER
}

private class AndroidHoshiWebViewSettings(
    private val settings: WebSettings,
) : HoshiWebViewSettings {
    override var javaScriptEnabled: Boolean
        get() = settings.javaScriptEnabled
        set(value) {
            settings.javaScriptEnabled = value
        }

    override var domStorageEnabled: Boolean
        get() = settings.domStorageEnabled
        set(value) {
            settings.domStorageEnabled = value
        }

    override var allowFileAccess: Boolean
        get() = settings.allowFileAccess
        set(value) {
            settings.allowFileAccess = value
        }

    override var allowContentAccess: Boolean
        get() = settings.allowContentAccess
        set(value) {
            settings.allowContentAccess = value
        }
}
