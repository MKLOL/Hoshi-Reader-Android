package moe.antimony.hoshi.ui.theme

import moe.antimony.hoshi.features.dictionary.LookupPopupLayout
import moe.antimony.hoshi.features.dictionary.LookupPopupItem
import moe.antimony.hoshi.features.dictionary.LookupPopupState
import moe.antimony.hoshi.features.reader.ReaderSelectionData
import moe.antimony.hoshi.features.reader.ReaderLookupPopupViewport
import moe.antimony.hoshi.features.reader.ReaderLookupPopupFramePayload
import moe.antimony.hoshi.features.reader.ReaderSelectionRect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AdaptiveUiTest {
    @Test fun foldAndSmallerWindowsKeepExactExistingPopupGeometry() {
        for ((width, height) in listOf(706.0 to 935.0, 935.0 to 706.0, 840.0 to 1100.0, 420.0 to 930.0)) {
            val scale = largeScreenUiScale(width, height)
            assertEquals(1.0, scale, 0.0)
            for (vertical in listOf(false, true)) for (fullWidth in listOf(false, true)) {
                val baseline = popup(width, height, vertical, fullWidth)
                assertEquals(baseline.calculate(), baseline.copy(uiScale = scale).calculate())
            }
        }
    }

    @Test fun tabletGrowsBothPopupDimensionsAndRespectsWindowBounds() {
        val scale = largeScreenUiScale(1260.0, 1680.0)
        assertEquals(1.5, scale, 0.0)
        assertEquals(scale, largeScreenUiScale(1680.0, 1260.0), 0.0)
        val layout = popup(1260.0, 1680.0, false, false).copy(uiScale = scale)
        val frame = layout.calculate()
        assertEquals(480.0, frame.width, 0.0)
        assertEquals(375.0, frame.height, 0.0)
        assertTrue(frame.centerY - frame.height / 2 >= 24)
        assertTrue(frame.centerY + frame.height / 2 <= 1656)
        val oversized = layout.copy(maxWidth = 4000.0, maxHeight = 4000.0, isFullWidth = true).calculate()
        assertTrue(oversized.height <= 1680 - 48 - 12)
        assertTrue(oversized.width <= 1260 - 12)
    }

    @Test fun epubIframeUsesSameScaleForFrameAndControlOffsets() {
        val popup = LookupPopupItem(state = LookupPopupState(
            selection = ReaderSelectionData("いつも", "いつも", ReaderSelectionRect(100.0, 100.0, 50.0, 80.0), null, null),
            results = emptyList(), isVertical = false, popupActionBar = true,
        ))
        val frame = ReaderLookupPopupFramePayload.fromPopup(
            popup, 0, ReaderLookupPopupViewport(1260.0, 1680.0, uiScale = 1.5), iframeUrl = "https://hoshi.local/popup/",
        )
        assertEquals(480.0, frame.frame.width, 0.0)
        assertEquals(375.0, frame.frame.height, 0.0)
        assertEquals(1.5, frame.uiScale, 0.0)
        assertEquals(frame.frame.top + 37.0 * 1.5, frame.selectionOffsetY, 0.0)
    }

    @Test fun scalingIsContinuousBoundedAndReturnsToBaselineInSplitScreen() {
        assertEquals(1.0, largeScreenUiScale(840.0, 1400.0), 0.0)
        assertEquals(841.0 / 840, largeScreenUiScale(841.0, 1400.0), 0.0)
        assertEquals(1.75, largeScreenUiScale(4000.0, 4000.0), 0.0)
        assertEquals(1.0, largeScreenUiScale(630.0, 1680.0), 0.0)
        assertEquals(1.0, largeScreenUiScale(0.0, 0.0), 0.0)
    }

    private fun popup(w: Double, h: Double, vertical: Boolean, fullWidth: Boolean) = LookupPopupLayout(
        ReaderSelectionRect(100.0, 100.0, 40.0, 80.0), w, h, 320.0, 250.0,
        vertical, fullWidth, topInset = 24.0, bottomInset = 24.0,
    )
}
