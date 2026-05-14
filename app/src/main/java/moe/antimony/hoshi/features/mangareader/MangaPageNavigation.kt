package moe.antimony.hoshi.features.mangareader

import moe.antimony.hoshi.features.reader.ReaderNavigationDirection

/**
 * Page-index math for the mokuro manga reader.
 *
 * Manga reads **right-to-left**: "forward" (advance in reading order) means moving to the
 * next page, which sits on the *left*. So a swipe/tap on the left edge advances the story,
 * a swipe/tap on the right edge goes back. This object keeps that mapping in one pure,
 * unit-testable place; the WebView/gesture code only deals in [ReaderNavigationDirection].
 */
internal object MangaPageNavigation {
    /** The page index reached by moving [direction] from [currentIndex], or `null` at a limit. */
    fun targetIndex(
        currentIndex: Int,
        pageCount: Int,
        direction: ReaderNavigationDirection,
    ): Int? {
        if (pageCount <= 0) return null
        val next = when (direction) {
            ReaderNavigationDirection.Forward -> currentIndex + 1
            ReaderNavigationDirection.Backward -> currentIndex - 1
        }
        return next.takeIf { it in 0 until pageCount }
    }

    /**
     * Maps a horizontal swipe to a reading-direction navigation. In a right-to-left manga a
     * swipe to the *left* drags the next (left-hand) page into view, i.e. moves forward.
     */
    fun directionForSwipe(swipe: MangaSwipeDirection): ReaderNavigationDirection =
        when (swipe) {
            MangaSwipeDirection.Left -> ReaderNavigationDirection.Forward
            MangaSwipeDirection.Right -> ReaderNavigationDirection.Backward
        }

    /**
     * Maps a tap at horizontal fraction [xFraction] (0 = left edge, 1 = right edge) of the
     * page to a navigation direction, or `null` for the central "no navigation" zone.
     * [edgeZoneFraction] is the width of each tap-to-turn edge zone.
     */
    fun directionForTap(
        xFraction: Float,
        edgeZoneFraction: Float = DEFAULT_TAP_EDGE_ZONE_FRACTION,
    ): ReaderNavigationDirection? = when {
        xFraction <= edgeZoneFraction -> ReaderNavigationDirection.Forward
        xFraction >= 1f - edgeZoneFraction -> ReaderNavigationDirection.Backward
        else -> null
    }

    const val DEFAULT_TAP_EDGE_ZONE_FRACTION = 0.2f
}

internal enum class MangaSwipeDirection {
    Left,
    Right,
}
