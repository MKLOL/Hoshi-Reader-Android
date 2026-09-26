package moe.antimony.hoshi.features.dictionary

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProcessTextLookupOverlayLayoutTest {
    @Test fun tabletPopupStaysCenteredAfterEnlargement() {
        val selection = ProcessTextLookupOverlayLayout.rootSelectionRect(
            1260.0, 1680.0, 320.0, 250.0, 24.0, 24.0, uiScale = 1.5,
        )
        val frame = LookupPopupLayout(
            selection, 1260.0, 1680.0, 320.0, 250.0, false,
            topInset = 24.0, bottomInset = 24.0, uiScale = 1.5,
        ).calculate()
        assertEquals(630.0, frame.centerX, 0.0)
        assertEquals(840.0, frame.centerY, 0.0)
    }

    @Test
    fun rootSelectionRectCentersTheFirstOverlayPopup() {
        val selectionRect = ProcessTextLookupOverlayLayout.rootSelectionRect(
            screenWidth = 400.0,
            screenHeight = 800.0,
            popupMaxWidth = 320.0,
            popupMaxHeight = 250.0,
            topInset = 0.0,
            bottomInset = 0.0,
        )

        val frame = LookupPopupLayout(
            selectionRect = selectionRect,
            screenWidth = 400.0,
            screenHeight = 800.0,
            maxWidth = 320.0,
            maxHeight = 250.0,
            isVertical = false,
        ).calculate()

        assertEquals(200.0, frame.centerX, 0.0)
        assertEquals(400.0, frame.centerY, 0.0)
    }

    @Test
    fun rootSelectionRectCentersPopupInsideSafeArea() {
        val selectionRect = ProcessTextLookupOverlayLayout.rootSelectionRect(
            screenWidth = 400.0,
            screenHeight = 800.0,
            popupMaxWidth = 320.0,
            popupMaxHeight = 250.0,
            topInset = 96.0,
            bottomInset = 24.0,
        )

        val frame = LookupPopupLayout(
            selectionRect = selectionRect,
            screenWidth = 400.0,
            screenHeight = 800.0,
            maxWidth = 320.0,
            maxHeight = 250.0,
            isVertical = false,
            topInset = 96.0,
            bottomInset = 24.0,
        ).calculate()

        assertEquals(200.0, frame.centerX, 0.0)
        assertEquals(436.0, frame.centerY, 0.0)
        assertTrue(frame.centerY - frame.height / 2 >= 102.0)
    }
}
