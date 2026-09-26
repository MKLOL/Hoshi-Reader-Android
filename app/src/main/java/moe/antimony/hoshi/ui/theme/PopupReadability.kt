package moe.antimony.hoshi.ui.theme

import android.content.Context
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Density
import kotlin.math.hypot

internal data class PopupReadability(
    val textScale: Double = 1.0,
    val frameScale: Double = 1.0,
)

/**
 * Popup reading size is a physical-tablet decision, unlike the rest of the app's dp layout.
 * A 10.3-inch BOOX can report fewer logical dp than an unfolded phone at its default app DPI.
 * Use the current window so small split-screen panes don't inherit the full panel's size.
 */
internal fun popupReadability(
    widthPx: Int,
    heightPx: Int,
    density: Double,
    xdpi: Double,
    ydpi: Double,
    manufacturer: String = "",
    model: String = "",
): PopupReadability {
    val baseline = PopupReadability()
    if (!density.isFinite() || density <= 0 || minOf(widthPx, heightPx) / density < 600) return baseline
    // Preserve Galaxy foldables even when an OEM reports unreliable physical DPI.
    if (manufacturer.equals("samsung", ignoreCase = true) && model.startsWith("SM-F", ignoreCase = true)) return baseline
    val validDpi = xdpi in 100.0..1000.0 && ydpi in 100.0..1000.0 && xdpi / ydpi in 0.8..1.25
    val physicalTablet = validDpi &&
        minOf(widthPx / xdpi, heightPx / ydpi) >= 5.2 &&
        hypot(widthPx / xdpi, heightPx / ydpi) >= 9.0
    val boox = manufacturer.contains("onyx", ignoreCase = true) || manufacturer.contains("boox", ignoreCase = true)
    // Some BOOX firmware reports logical DPI in the physical fields. The full-size tablet
    // window fallback excludes Palma-sized displays and narrow split-screen windows.
    val booxTabletWindow = boox && minOf(widthPx, heightPx) >= 1500
    val fallbackTablet = !validDpi && minOf(widthPx, heightPx) / density >= 900
    return if (physicalTablet || booxTabletWindow || fallbackTablet) {
        PopupReadability(textScale = 1.6, frameScale = 1.4)
    } else baseline
}

internal fun popupReadabilityForWindow(context: Context, widthPx: Int, heightPx: Int): PopupReadability {
    val metrics = context.resources.displayMetrics
    return popupReadability(widthPx, heightPx, metrics.density.toDouble(), metrics.xdpi.toDouble(),
        metrics.ydpi.toDouble(), Build.MANUFACTURER, Build.MODEL)
}

@Composable
internal fun currentPopupReadability(): PopupReadability {
    val window = LocalWindowInfo.current.containerSize
    return popupReadabilityForWindow(LocalContext.current, window.width, window.height)
}

/** Grow text and line heights, keeping the reader page and existing control density intact. */
@Composable
internal fun PopupTypography(content: @Composable () -> Unit) {
    val scale = currentPopupReadability().textScale.toFloat()
    val density = LocalDensity.current
    CompositionLocalProvider(
        LocalDensity provides if (scale == 1f) density else Density(density.density, density.fontScale * scale),
        content = content,
    )
}
