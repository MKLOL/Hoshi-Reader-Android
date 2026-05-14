package moe.antimony.hoshi.features.mangareader

import moe.antimony.hoshi.features.reader.ReaderNavigationDirection

/**
 * Page-index math for the mokuro manga reader.
 *
 * Manga reads **right-to-left**: "forward" (advance in reading order) means moving to the
 * next page, which sits on the *left*. The chrome's left-hand button advances the story,
 * but a *swipe* follows the page like a filmstrip (page 1 at the right): dragging to the
 * right slides the current page off and pulls the next page in from the left, so a right
 * swipe moves forward and a left swipe goes back. This object keeps that mapping in one
 * pure, unit-testable place; the WebView/gesture code only deals in
 * [ReaderNavigationDirection].
 *
 * Page turning is intentionally **not** bound to taps on the page: a tap is reserved for
 * selecting a word for dictionary lookup, so taps never move the page.
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
     * Maps a horizontal swipe to a reading-direction navigation. The swipe drags the page
     * like a filmstrip: a swipe to the *right* slides the current page off to the right and
     * pulls the next (left-hand) page in, i.e. moves forward; a left swipe goes back.
     */
    fun directionForSwipe(swipe: MangaSwipeDirection): ReaderNavigationDirection =
        when (swipe) {
            MangaSwipeDirection.Left -> ReaderNavigationDirection.Backward
            MangaSwipeDirection.Right -> ReaderNavigationDirection.Forward
        }
}

internal enum class MangaSwipeDirection {
    Left,
    Right,
}
