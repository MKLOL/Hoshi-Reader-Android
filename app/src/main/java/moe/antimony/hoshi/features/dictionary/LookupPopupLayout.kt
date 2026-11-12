package moe.antimony.hoshi.features.dictionary

import moe.antimony.hoshi.features.reader.MangaActionPlacement
import moe.antimony.hoshi.features.reader.MangaActionSide
import moe.antimony.hoshi.features.reader.MangaActionSide.Above
import moe.antimony.hoshi.features.reader.MangaActionSide.Below
import moe.antimony.hoshi.features.reader.MangaActionSide.Left
import moe.antimony.hoshi.features.reader.MangaActionSide.Right
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
    val uiScale: Double = 1.0,
    val mangaActions: MangaActionPlacement? = null,
) {
    fun calculate(): LookupPopupFrame {
        val boundedWidth = minOf(maxWidth * uiScale, (screenWidth - screenBorderPadding * 2).coerceAtLeast(1.0))
        val boundedHeight = minOf(maxHeight * uiScale, (screenHeight - topInset - bottomInset - screenBorderPadding * 2).coerceAtLeast(1.0))
        if (uiScale != 1.0 || boundedWidth != maxWidth || boundedHeight != maxHeight) {
            return copy(
                maxWidth = boundedWidth,
                maxHeight = boundedHeight,
                uiScale = 1.0,
            ).calculate()
        }
        mangaActions?.let(::frameClearOfMangaActions)?.let { return it }
        val width = width()
        val height = height()
        return LookupPopupFrame(
            width = width,
            height = height,
            centerX = centerX(width),
            centerY = centerY(height),
        )
    }

    private fun frameClearOfMangaActions(actions: MangaActionPlacement): LookupPopupFrame? {
        val left = screenBorderPadding
        val right = screenWidth - screenBorderPadding
        val top = topInset + screenBorderPadding
        val bottom = screenHeight - bottomInset - screenBorderPadding
        if (right <= left || bottom <= top) return null

        val preferred = when (actions.side) {
            Above -> Below
            Below -> Above
            Left -> Right
            Right -> Left
        }
        val requestedWidth = if (isFullWidth) right - left else maxWidth
        fun candidate(side: MangaActionSide, avoid: ReaderSelectionRect): LookupPopupFrame? {
            val laneLeft = if (side == Right) maxOf(left, avoid.x + avoid.width + popupPadding) else left
            val laneRight = if (side == Left) minOf(right, avoid.x - popupPadding) else right
            val laneTop = if (side == Below) maxOf(top, avoid.y + avoid.height + popupPadding) else top
            val laneBottom = if (side == Above) minOf(bottom, avoid.y - popupPadding) else bottom
            val width = minOf(requestedWidth, laneRight - laneLeft)
            val height = minOf(maxHeight, laneBottom - laneTop)
            if (width < 1.0 || height < 1.0) return null
            val x = when (side) {
                Right -> laneLeft
                Left -> laneRight - width
                else -> clampLikeIos(selectionRect.x, laneLeft, laneRight - width)
            }
            val y = when (side) {
                Below -> laneTop
                Above -> laneBottom - height
                else -> clampLikeIos(selectionRect.y, laneTop, laneBottom - height)
            }
            return LookupPopupFrame(width, height, x + width / 2, y + height / 2)
        }

        val bubble = actions.bubble
        val row = actions.row
        val unionLeft = minOf(bubble.x, row.x)
        val unionTop = minOf(bubble.y, row.y)
        val occupied = ReaderSelectionRect(
            unionLeft, unionTop,
            maxOf(bubble.x + bubble.width, row.x + row.width) - unionLeft,
            maxOf(bubble.y + bubble.height, row.y + row.height) - unionTop,
        )
        val sides = MangaActionSide.entries
        fun readable(frame: LookupPopupFrame): Boolean =
            frame.width >= minOf(requestedWidth, minMangaPopupWidth) &&
                frame.height >= minOf(maxHeight, minMangaPopupHeight)

        fun bestClearFrame(avoid: ReaderSelectionRect): LookupPopupFrame? {
            val perpendicular = sides.asSequence().filterNot { it == preferred || it == actions.side }
                .mapNotNull { candidate(it, avoid) }.filter(::readable).maxByOrNull { it.width * it.height }
            // A top or bottom lane can preserve the full-width setting when actions are lateral.
            if (isFullWidth && (preferred == Left || preferred == Right) && perpendicular != null) return perpendicular
            return candidate(preferred, avoid)?.takeIf(::readable) ?: perpendicular
        }

        // In a tight viewport the popup may need some of the bubble's space. Keep the
        // action row protected even then, so Translate and Copy stay tappable.
        return bestClearFrame(occupied) ?: bestClearFrame(row)
            ?: candidate(actions.side, occupied)?.takeIf(::readable)
            ?: candidate(actions.side, row)?.takeIf(::readable)
            ?: sides.mapNotNull { candidate(it, row) }.maxByOrNull { it.width * it.height }
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
        const val minMangaPopupWidth = 120.0
        const val minMangaPopupHeight = 96.0
        // Smaller gaps cannot present a meaningful dictionary result without overlapping.
        const val minimumUsableNonOverlappingSize = 48.0
    }
}
