package moe.antimony.hoshi.features.mangareader

import kotlin.math.ceil
import kotlin.math.floor

internal const val MANGA_SCREENSHOT_CROP_MIN_SIZE_PX = 32

internal data class MangaScreenshotCropRect(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
}

internal fun normalizedMangaScreenshotCropRect(
    startX: Float,
    startY: Float,
    endX: Float,
    endY: Float,
    containerWidth: Int,
    containerHeight: Int,
    minSize: Int = MANGA_SCREENSHOT_CROP_MIN_SIZE_PX,
): MangaScreenshotCropRect? {
    if (containerWidth <= 0 || containerHeight <= 0) return null
    val left = floor(minOf(startX, endX)).toInt().coerceIn(0, containerWidth)
    val top = floor(minOf(startY, endY)).toInt().coerceIn(0, containerHeight)
    val right = ceil(maxOf(startX, endX)).toInt().coerceIn(0, containerWidth)
    val bottom = ceil(maxOf(startY, endY)).toInt().coerceIn(0, containerHeight)
    val rect = MangaScreenshotCropRect(left = left, top = top, right = right, bottom = bottom)
    return rect.takeIf { it.width >= minSize && it.height >= minSize }
}
