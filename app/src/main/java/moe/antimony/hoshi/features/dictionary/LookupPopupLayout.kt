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
            // Floor the width for the same reason height() is floored: a
            // vertical-writing selection that fills the screen width (wide bubble,
            // or heavy pinch-zoom) drives the available side space to ~0 and would
            // otherwise collapse the popup to a 1px, invisible sliver.
            val available = maxOf(spaceLeft(), spaceRight()) - screenBorderPadding
            return minOf(maxOf(available, minPopupWidth), maxWidth)
        }
        return minOf(screenWidth - screenBorderPadding * 2, maxWidth)
    }

    private fun height(): Double {
        if (isVertical || isFullWidth) return maxHeight
        val available = maxOf(spaceAbove(), spaceBelow()) - screenBorderPadding
        // Floor the height so a selection that fills the viewport — a tall vertical
        // manga bubble, or any bubble under heavy pinch-zoom — can't drive `available`
        // to zero/negative and collapse the popup to a 1px, effectively invisible
        // strip (the dp→px conversion coerces to >=1). A visible popup that overlaps a
        // viewport-filling bubble is far better than a popup you can't see at all.
        return minOf(maxOf(available, minPopupHeight), maxHeight)
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
            // Bottom-anchored, but keep the top on screen if a configured popup
            // height exceeds a short (split-screen / e-ink) viewport, rather than
            // letting the sheet hang off the top edge.
            return clampLikeIos(
                screenHeight - height / 2 - screenBorderPadding,
                height / 2 + screenBorderPadding,
                screenHeight - height / 2 - screenBorderPadding,
            )
        }
        if (isVertical) {
            val raw = selectionRect.y + height / 2
            return clampLikeIos(
                raw,
                height / 2 + screenBorderPadding + topInset,
                screenHeight - bottomInset - height / 2 - screenBorderPadding,
            )
        }
        val raw = if (showBelow(height)) {
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
    private fun showBelow(height: Double): Boolean = spaceBelow() >= height

    private fun clampLikeIos(value: Double, minimum: Double, maximum: Double): Double =
        maxOf(minimum, minOf(value, maximum))

    private companion object {
        const val popupPadding = 4.0
        const val screenBorderPadding = 6.0
        // Minimum usable popup height/width (dp) — each capped by maxHeight/maxWidth
        // — so a viewport-filling selection can never collapse the popup to a sliver.
        const val minPopupHeight = 120.0
        const val minPopupWidth = 120.0
    }
}
