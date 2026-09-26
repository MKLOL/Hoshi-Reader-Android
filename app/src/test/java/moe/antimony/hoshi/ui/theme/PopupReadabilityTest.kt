package moe.antimony.hoshi.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Test

class PopupReadabilityTest {
    @Test fun booxTextGrowsAtEveryPlausibleDefaultAppDensity() {
        for (dpi in listOf(300.0, 320.0, 360.0, 400.0)) {
            assertEquals(PopupReadability(1.6, 1.4), popupReadability(1860, 2480, dpi / 160, 300.0, 300.0))
            assertEquals(PopupReadability(1.6, 1.4), popupReadability(2480, 1860, dpi / 160, 300.0, 300.0))
        }
    }

    @Test fun foldKeepsExistingTypographyEvenWithUnreliablePhysicalDpi() {
        assertEquals(PopupReadability(), popupReadability(1848, 2448, 2.625, 403.0, 403.0))
        for (dpi in listOf(0.0, 160.0, 300.0, 403.0, Double.NaN)) {
            assertEquals(PopupReadability(), popupReadability(1848, 2448, 2.625, dpi, dpi, "samsung", "SM-F971B"))
            assertEquals(PopupReadability(), popupReadability(2448, 1848, 2.625, dpi, dpi, "samsung", "SM-F971B"))
        }
    }

    @Test fun booxFallbackHandlesFirmwareDpiButExcludesSmallDevicesAndPanes() {
        assertEquals(PopupReadability(1.6, 1.4), popupReadability(1860, 2480, 2.5, 420.0, 420.0, "ONYX", "NoteAir6C"))
        assertEquals(PopupReadability(), popupReadability(930, 2480, 2.25, 300.0, 300.0, "ONYX", "NoteAir6C"))
        assertEquals(PopupReadability(), popupReadability(824, 1648, 1.0, 300.0, 300.0, "ONYX", "Palma"))
        assertEquals(PopupReadability(), popupReadability(0, 0, 2.25, 300.0, 300.0))
    }
}
