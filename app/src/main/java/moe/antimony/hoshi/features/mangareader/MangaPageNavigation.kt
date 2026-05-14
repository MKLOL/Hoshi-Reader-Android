package moe.antimony.hoshi.features.mangareader

import moe.antimony.hoshi.features.reader.ReaderNavigationDirection

/**
 * Page-index math for the mokuro manga reader.
 *
 * Manga reads **right-to-left**: "forward" (advance in reading order) means moving to the
 * next page, which sits on the *left*. So a swipe to the left (or the left-hand chrome
 * button) advances the story and a swipe to the right goes back. This object keeps that
 * mapping in one pure, unit-testable place; the WebView/gesture code only deals in
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
     * Maps a horizontal swipe to a reading-direction navigation. In a right-to-left manga a
     * swipe to the *left* drags the next (left-hand) page into view, i.e. moves forward.
     */
    fun directionForSwipe(swipe: MangaSwipeDirection): ReaderNavigationDirection =
        when (swipe) {
            MangaSwipeDirection.Left -> ReaderNavigationDirection.Forward
            MangaSwipeDirection.Right -> ReaderNavigationDirection.Backward
        }
}

internal enum class MangaSwipeDirection {
    Left,
    Right,
}
