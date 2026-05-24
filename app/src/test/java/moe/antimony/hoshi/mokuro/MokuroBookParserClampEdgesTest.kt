package moe.antimony.hoshi.mokuro

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Extra edge-case coverage for [clampMokuroFontSize] beyond what
 * [MokuroBookParserTest] exercises:
 *
 *  - the explicit small-font safety multiplier (1.5x) at fs <= 40
 *  - the explicit big-font safety multiplier (1.0x)   at fs >= 100
 *  - the linear interpolation between those two thresholds
 *
 * These three points are the contract the codebase reasons about: changing the multipliers
 * or the threshold band would silently regress OCR-plate sizing across all manga.
 */
class MokuroBookParserClampEdgesTest {
    @Test
    fun smallFontUsesPermissiveSafetyOfOnePointFive() {
        // mokuro fs=20 (well under the small-font threshold of 40). One horizontal line of
        // 10 chars in a 20px-wide box: pure-fit allows 2 px (20/10), safety multiplier 1.5
        // raises the cap to 3 px. mokuro's value 20 exceeds that, so the result is the cap,
        // which proves the 1.5x multiplier was used (a 1.0x would clamp to 2).
        assertEquals(
            3,
            clampMokuroFontSize(
                mokuroFontSize = 20.0,
                boxWidth = 20,
                boxHeight = 10_000,
                vertical = false,
                lines = listOf("0123456789"),
            ),
        )
    }

    @Test
    fun bigFontUsesStrictSafetyOfOne() {
        // mokuro fs=200, horizontal, 10 chars in 20px-wide box: fit=2, cap=2*1.0=2.
        // The 1.0x multiplier is what keeps overshot OCR boxes from blowing up the plate.
        assertEquals(
            2,
            clampMokuroFontSize(
                mokuroFontSize = 200.0,
                boxWidth = 20,
                boxHeight = 10_000,
                vertical = false,
                lines = listOf("0123456789"),
            ),
        )
    }

    @Test
    fun thresholdBoundariesPickTheBoundarySafety() {
        // fs=40 -> at the small threshold inclusive: safety should be 1.5 (cap=3).
        assertEquals(
            3,
            clampMokuroFontSize(
                mokuroFontSize = 40.0,
                boxWidth = 20,
                boxHeight = 10_000,
                vertical = false,
                lines = listOf("0123456789"),
            ),
        )
        // fs=100 -> at the big threshold inclusive: safety should be 1.0 (cap=2).
        assertEquals(
            2,
            clampMokuroFontSize(
                mokuroFontSize = 100.0,
                boxWidth = 20,
                boxHeight = 10_000,
                vertical = false,
                lines = listOf("0123456789"),
            ),
        )
    }

    @Test
    fun midRangeFontInterpolatesLinearlyBetweenThresholds() {
        // fs=70 sits halfway between 40 and 100, so safety = 1.5 + 0.5 * (1.0 - 1.5) = 1.25.
        // Horizontal, 10 chars in a 20px-wide box: fit=2, cap=2*1.25=2.5, floored to 2.
        assertEquals(
            2,
            clampMokuroFontSize(
                mokuroFontSize = 70.0,
                boxWidth = 20,
                boxHeight = 10_000,
                vertical = false,
                lines = listOf("0123456789"),
            ),
        )
    }

    @Test
    fun zeroBoxHeightFallsBackToMokuroValueWithoutDividingByZero() {
        // Separate from the existing width=0 case in MokuroBookParserTest — guards
        // the symmetric height=0 branch (and incidentally would catch a flipped
        // `<=` to `<` in the box-dims guard).
        assertEquals(
            42,
            clampMokuroFontSize(
                mokuroFontSize = 42.0,
                boxWidth = 100,
                boxHeight = 0,
                vertical = true,
                lines = listOf("a"),
            ),
        )
    }

    @Test
    fun verticalPathExercisesNumLinesAcrossWidth() {
        // Multi-line vertical with a *narrow* box: the binding constraint is boxWidth / numLines
        // (5 lines spanning a 50px wide box -> 10 px per line). Each line has 1 char so the
        // height-axis (50/(1*1.1) ~ 45) is the other candidate. Fit=10; mokuro fs=80 is in the
        // mid-range, safety ~= 1.5 + ((80-40)/60) * (-0.5) = 1.166...; cap = 10 * 1.166 ~= 11.66
        // -> floored to 11. This pins the vertical numLines-across-width branch behavior.
        assertEquals(
            11,
            clampMokuroFontSize(
                mokuroFontSize = 80.0,
                boxWidth = 50,
                boxHeight = 1_000,
                vertical = true,
                lines = listOf("a", "b", "c", "d", "e"),
            ),
        )
    }
}
