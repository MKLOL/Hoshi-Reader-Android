package moe.antimony.hoshi.features.mangareader

import moe.antimony.hoshi.features.dictionary.DictionarySettings
import moe.antimony.hoshi.features.reader.PopupCardStyle
import moe.antimony.hoshi.features.reader.ReaderSettings
import org.junit.Assert.*
import org.junit.Test

class MangaPopupSettingsTest {
    @Test fun mangaUsesTheSameCardAppearanceAndBehaviorAsTheEditor() {
        val style = PopupCardStyle(width = 510.0, height = 430.0, scale = 1.8, fontScale = 1.3)
        val settings = ReaderSettings(dictionaryCard = style, popupScale = 1.25,
            popupFullWidth = true, popupActionBar = true, popupSwipeToDismiss = true,
            popupSwipeThreshold = 55, popupReducedMotionScrolling = true,
            popupReducedMotionScrollPercent = 80, popupReducedMotionSwipeThreshold = 60)
        val options = mangaLookupPopupOptions(settings, DictionarySettings(), false, "Sample")
        assertEquals(style, options.cardStyle)
        assertEquals(1.25, options.popupScale, 0.0)
        assertTrue(options.isFullWidth)
        assertTrue(options.popupActionBar)
        assertTrue(options.swipeToDismiss)
        assertEquals(55, options.swipeThreshold)
        assertTrue(options.reducedMotionScrolling)
        assertEquals(80, options.reducedMotionScrollPercent)
        assertEquals(60, options.reducedMotionSwipeThreshold)
        assertEquals("Sample", options.documentTitle)
    }
}
