package moe.antimony.hoshi.mokuro

import org.junit.Assert.assertEquals
import org.junit.Test

class MangaTextEnlargementTest {
    @Test
    fun everySupportedSettingPreservesTextSizeOrder() {
        for (threshold in 10..60) for (amount in 0..100) {
            var previous = mangaRevealFontSize(1, true, amount, threshold)
            for (original in 2..120) {
                val result = mangaRevealFontSize(original, true, amount, threshold)
                check(result >= previous && result >= original && (original < threshold || result == original)) {
                    "Size order changed at source $original amount $amount cutoff $threshold"
                }
                previous = result
            }
        }
    }

    @Test
    fun defaultMatchesPreviousCurveAndLeavesLargeTextAlone() {
        assertEquals(listOf(21, 25, 30, 40), listOf(12, 20, 30, 40).map { mangaRevealFontSize(it, true) })
        assertEquals(27, mangaRevealFontSize(25, true))
    }

    @Test
    fun amountFillsTheGapToTheCutoffWithoutChangingLargerText() {
        assertEquals(30, mangaRevealFontSize(1, true, 58, 51))
        assertEquals(15, mangaRevealFontSize(12, true, 25, 24))
        assertEquals(33, mangaRevealFontSize(12, true, 75, 40))
        assertEquals(20, mangaRevealFontSize(20, true, 0, 60))
        assertEquals(60, mangaRevealFontSize(20, true, 100, 60))
        assertEquals(80, mangaRevealFontSize(80, true, 100, 60))
        assertEquals(12, mangaRevealFontSize(12, false, 100, 60))
    }

    @Test
    fun settingsAreAppliedToOriginalOcrSizeWithoutCompoundingThePreviousBoost() {
        val box = MokuroTextBox(0, 0, 100, 100, 21, false, listOf("文字"), originalFontSize = 12)
        assertEquals(12, box.revealFontSize(false))
        assertEquals(21, box.revealFontSize(true))
        assertEquals(33, box.revealFontSize(true, 75, 40))
        assertEquals(21, box.revealFontSize(true))
    }
}
