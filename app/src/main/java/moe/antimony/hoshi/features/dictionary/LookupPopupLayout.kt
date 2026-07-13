package moe.antimony.hoshi.features.dictionary

import moe.antimony.hoshi.features.reader.ReaderSelectionRect

data class LookupPopupFrame(
    val width: Double,
    val height: Double,
    val centerX: Double,
    val centerY: Double,
)

data class LookupPopupLayout(
    val selectionRect: ReaderSelectionRect,
    val screenWidth: Double,
    val screenHeight: Double,
    val maxWidth: Double,
    val maxHeight: Double,
    val isVertical: Boolean,
    val isFullWidth: Boolean = false,
    val topInset: Double = 0.0,
    val bottomInset: Double = 0.0,
) {
    fun calculate(): LookupPopupFrame {
        val width = width()
        val height = height()
        return LookupPopupFrame(
            width = width,
            height = height,
            centerX = centerX(width),
            centerY = centerY(height),
        )
    }

    private fun width(): Double {
        if (isFullWidth) return screenWidth - screenBorderPadding * 2
        if (isVertical) {
            val available = maxOf(spaceLeft(), spaceRight()) - screenBorderPadding
            return popupSize(available, maxWidth, minPopupWidth)
        }
        return minOf(screenWidth - screenBorderPadding * 2, maxWidth)
    }

    private fun height(): Double {
        if (isFullWidth) {
            val safeViewportHeight =
                (screenHeight - topInset - bottomInset - screenBorderPadding * 2).coerceAtLeast(1.0)
            return minOf(maxHeight, safeViewportHeight)
        }
        if (isVertical) return maxHeight
        val available = maxOf(spaceAbove(), spaceBelow()) - screenBorderPadding
        return popupSize(available, maxHeight, minPopupHeight)
    }

    private fun centerX(width: Double): Double {
        if (isFullWidth) return width / 2 + screenBorderPadding
        if (isVertical) {
            val raw = if (showOnRight()) {
                selectionRect.x + selectionRect.width + popupPadding + width / 2
            } else {
                selectionRect.x - popupPadding - width / 2
            }
            return clampLikeIos(raw, width / 2, screenWidth - width / 2)
        }
        val raw = selectionRect.x + width / 2
        return clampLikeIos(raw, width / 2 + screenBorderPadding, screenWidth - width / 2 - screenBorderPadding)
    }

    private fun centerY(height: Double): Double {
        if (isFullWidth) {
            // Bottom-anchored ABOVE the bottom inset (nav/gesture bar), like every
            // other branch, but keep the top on screen if a configured popup height
            // exceeds a short (split-screen / e-ink) viewport rather than letting the
            // sheet hang off the top edge.
            val anchor = screenHeight - bottomInset - height / 2 - screenBorderPadding
            return clampLikeIos(anchor, height / 2 + topInset + screenBorderPadding, anchor)
        }
        if (isVertical) {
            val raw = selectionRect.y + height / 2
            return clampLikeIos(
                raw,
                height / 2 + screenBorderPadding + topInset,
                screenHeight - bottomInset - height / 2 - screenBorderPadding,
            )
        }
        val raw = if (showBelow()) {
            selectionRect.y + selectionRect.height + popupPadding + height / 2
        } else {
            selectionRect.y - popupPadding - height / 2
        }
        return clampLikeIos(
            raw,
            height / 2 + topInset + screenBorderPadding,
            screenHeight - bottomInset - height / 2 - screenBorderPadding,
        )
    }

    private fun spaceLeft(): Double = selectionRect.x - popupPadding
    private fun spaceRight(): Double = screenWidth - selectionRect.x - selectionRect.width - popupPadding
    private fun spaceAbove(): Double = selectionRect.y - topInset - popupPadding
    private fun spaceBelow(): Double = screenHeight - bottomInset - selectionRect.y - selectionRect.height - popupPadding
    private fun showOnRight(): Boolean = spaceRight() >= spaceLeft() || spaceRight() >= maxWidth
    // Choose the side with MORE room (mirrors showOnRight), preferring below when it
    // fully fits. Comparing against the popup HEIGHT would be wrong once height is
    // floored: a bubble whose larger gap is below but under the floor would be thrown
    // to the smaller (above) side, and the clamp would then shove the popup onto the
    // bubble's top — covering the text even though there was more room below.
    private fun showBelow(): Boolean = spaceBelow() >= spaceAbove() || spaceBelow() >= maxHeight

    private fun clampLikeIos(value: Double, minimum: Double, maximum: Double): Double =
        maxOf(minimum, minOf(value, maximum))

    /**
     * Preserve space that can hold a minimally usable popup without covering the selection.
     * Only use the visibility floor when the remaining gap is itself a sliver, where some
     * overlap is unavoidable and a visible popup is preferable to an unusable strip.
     */
    private fun popupSize(available: Double, maximum: Double, minimumVisible: Double): Double =
        minOf(
            if (available >= minimumUsableNonOverlappingSize) available else minimumVisible,
            maximum,
        )

    private companion object {
        const val popupPadding = 4.0
        const val screenBorderPadding = 6.0
        // Minimum usable popup height/width (dp) — each capped by maxHeight/maxWidth
        // — so a viewport-filling selection can never collapse the popup to a sliver.
        const val minPopupHeight = 120.0
        const val minPopupWidth = 120.0
        // Smaller gaps cannot present a meaningful dictionary result without overlapping.
        const val minimumUsableNonOverlappingSize = 48.0
    }
}
