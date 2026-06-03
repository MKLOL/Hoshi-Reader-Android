package moe.antimony.hoshi.features.reader

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import kotlin.math.max
import kotlin.math.min

internal data class ReaderFullscreenImageTransform(
    val scale: Float = MIN_SCALE,
    val offset: Offset = Offset.Zero,
) {
    fun doubleTapZoomTo(
        targetScale: Float,
        centroid: Offset,
        viewport: Size,
        fittedImage: Size,
        panAllowance: ReaderFullscreenImagePanAllowance = ReaderFullscreenImagePanAllowance.None,
    ): ReaderFullscreenImageTransform {
        val nextScale = targetScale.coerceIn(MIN_SCALE, MAX_SCALE)
        if (nextScale <= MIN_SCALE) return ReaderFullscreenImageTransform()

        val center = Offset(viewport.width / 2f, viewport.height / 2f)
        val tappedImagePoint = (centroid - center - offset) / scale
        val nextOffset = tappedImagePoint * -nextScale
        return ReaderFullscreenImageTransform(nextScale, nextOffset).constrainedTo(viewport, fittedImage, panAllowance)
    }

    fun transformBy(
        centroid: Offset,
        pan: Offset,
        zoomChange: Float,
        viewport: Size,
        fittedImage: Size,
        panAllowance: ReaderFullscreenImagePanAllowance = ReaderFullscreenImagePanAllowance.None,
    ): ReaderFullscreenImageTransform {
        val nextScale = (scale * zoomChange).coerceIn(MIN_SCALE, MAX_SCALE)
        if (nextScale <= MIN_SCALE) return ReaderFullscreenImageTransform()

        val ratio = nextScale / scale
        val center = Offset(viewport.width / 2f, viewport.height / 2f)
        val anchor = centroid - center
        val nextOffset = (offset - anchor) * ratio + anchor + pan * ratio
        return ReaderFullscreenImageTransform(nextScale, nextOffset).constrainedTo(viewport, fittedImage, panAllowance)
    }

    fun constrainedTo(
        viewport: Size,
        fittedImage: Size,
        panAllowance: ReaderFullscreenImagePanAllowance = ReaderFullscreenImagePanAllowance.None,
    ): ReaderFullscreenImageTransform {
        if (scale <= MIN_SCALE) return ReaderFullscreenImageTransform()
        val maxOffsetX = max(0f, (fittedImage.width * scale - viewport.width) / 2f)
        val maxOffsetY = max(0f, (fittedImage.height * scale - viewport.height) / 2f)
        return copy(
            offset = Offset(
                x = offset.x.coerceIn(-maxOffsetX, maxOffsetX),
                y = offset.y.coerceIn(
                    minimumValue = -maxOffsetY - panAllowance.bottomPx,
                    maximumValue = maxOffsetY + panAllowance.topPx,
                ),
            ),
        )
    }

    companion object {
        const val MIN_SCALE = 1f
        const val MAX_SCALE = 5f
    }
}

internal fun readerFullscreenFittedImageSize(image: Size, viewport: Size): Size {
    if (image.width <= 0f || image.height <= 0f || viewport.width <= 0f || viewport.height <= 0f) {
        return Size.Zero
    }
    val fittedScale = min(viewport.width / image.width, viewport.height / image.height)
    return Size(width = image.width * fittedScale, height = image.height * fittedScale)
}

internal data class ReaderFullscreenImagePanAllowance(
    val topPx: Float,
    val bottomPx: Float,
) {
    companion object {
        val None = ReaderFullscreenImagePanAllowance(topPx = 0f, bottomPx = 0f)
    }
}
