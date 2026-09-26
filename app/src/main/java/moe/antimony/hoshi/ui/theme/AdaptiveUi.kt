package moe.antimony.hoshi.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Density

/**
 * Phones and unfolded Fold-sized windows retain their existing dimensions exactly. Larger
 * tablet windows enlarge controls and type together, without changing the user's font scale.
 * Use the shorter window dimension so rotation is stable and split-screen remains usable.
 */
internal fun largeScreenUiScale(widthDp: Double, heightDp: Double): Double {
    val shortest = minOf(widthDp, heightDp)
    return if (shortest.isFinite()) (shortest / 840.0).coerceIn(1.0, 1.75) else 1.0
}

internal val LocalPlatformDensity = staticCompositionLocalOf<Density?> { null }

@Composable
internal fun currentLargeScreenUiScale(): Double {
    val density = LocalPlatformDensity.current ?: LocalDensity.current
    val window = LocalWindowInfo.current.containerSize
    return largeScreenUiScale(window.width / density.density.toDouble(), window.height / density.density.toDouble())
}

@Composable
internal fun AdaptiveUi(content: @Composable () -> Unit) {
    val platformDensity = LocalPlatformDensity.current ?: LocalDensity.current
    val scale = currentLargeScreenUiScale().toFloat()
    CompositionLocalProvider(
        LocalPlatformDensity provides platformDensity,
        LocalDensity provides if (scale == 1f) platformDensity else Density(
            density = platformDensity.density * scale,
            fontScale = platformDensity.fontScale,
        ),
        content = content,
    )
}

/** WebView page layout, selection coordinates and crops always use the platform CSS/dp grid. */
@Composable
internal fun PlatformReaderUi(content: @Composable () -> Unit) {
    CompositionLocalProvider(
        LocalDensity provides (LocalPlatformDensity.current ?: LocalDensity.current),
        content = content,
    )
}
