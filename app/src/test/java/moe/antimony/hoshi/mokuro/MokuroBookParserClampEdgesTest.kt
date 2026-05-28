package moe.antimony.hoshi.mokuro

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Edge-case coverage for [clampMokuroFontSize] beyond what [MokuroBookParserTest]
 * exercises.
 *
 * The result is `mokuroFs + max(0, READABLE_TARGET_PX - mokuroFs) × BOOST_STRENGTH`.
 * With the constants currently in [MokuroBookParser] (target 30 px, strength 0.5)
 * that means: tiny artwork glyphs get a big absolute bump up toward 18 px, glyphs
 * already at or above 30 px stay at their mokuro-reported size, and everything in
 * between gets a proportionally smaller bump. Continuous, no thresholds, no
 * character-count dependency: bubbles with equal mokuroFs reveal at equal OCR size.
 */
class MokuroBookParserClampEdgesTest {
    @Test
    fun tinyArtworkIsBumpedSubstantially() {
        // mokuroFs = 5 → 5 + 25 × 0.5 = 17.5 → 17.
        assertEquals(
            17,
            clampMokuroFontSize(
                mokuroFontSize = 5.0,
                boxWidth = 500,
                boxHeight = 500,
                vertical = false,
                lines = listOf("0"),
            ),
        )
    }

    @Test
    fun mediumArtworkGetsProportionalBump() {
        // mokuroFs = 18 → 18 + 12 × 0.5 = 24.
        assertEquals(
            24,
            clampMokuroFontSize(
                mokuroFontSize = 18.0,
                boxWidth = 200,
                boxHeight = 200,
                vertical = false,
                lines = listOf("ab"),
            ),
        )
    }

    @Test
    fun targetArtworkGetsNoBump() {
        // mokuroFs = 30 → no headroom, no bump. This is the "BIG TEXT keep it the
        // same" half of the user-facing contract.
        assertEquals(
            30,
            clampMokuroFontSize(
                mokuroFontSize = 30.0,
                boxWidth = 200,
                boxHeight = 200,
                vertical = false,
                lines = listOf("0"),
            ),
        )
    }

    @Test
    fun bigArtworkPassesThroughUnchanged() {
        // mokuroFs ≥ READABLE_TARGET_PX always reveals at mokuro's native size.
        assertEquals(
            45,
            clampMokuroFontSize(
                mokuroFontSize = 45.0,
                boxWidth = 200,
                boxHeight = 200,
                vertical = false,
                lines = listOf("失礼ね"),
            ),
        )
    }

    @Test
    fun curveIsContinuousNotStepped() {
        // Three closely-spaced mokuroFs values should produce closely-spaced results
        // — no step discontinuity at any threshold (the previous "small ≤ X, big ≥ Y,
        // lerp between" design had a hidden plateau below X that this curve removes).
        val a = clampMokuroFontSize(14.0, 200, 200, false, listOf("0"))
        val b = clampMokuroFontSize(15.0, 200, 200, false, listOf("0"))
        val c = clampMokuroFontSize(16.0, 200, 200, false, listOf("0"))
        // Differences between adjacent points stay within 1 px of each other.
        val ab = b - a
        val bc = c - b
        assert(Math.abs(ab - bc) <= 1) {
            "expected smooth curve but adjacent deltas were $ab and $bc (a=$a b=$b c=$c)"
        }
    }

    @Test
    fun consistencyAcrossBubblesAtSameMokuroFs() {
        // Two bubbles with the same mokuroFs always reveal at the same OCR size —
        // regardless of how many characters fit beside them in the bubble or how big
        // their OCR boxes are. This is the property the user asked for: "the scaling
        // shouldn't fucking depend on how many characters you have in the box."
        val a = clampMokuroFontSize(
            mokuroFontSize = 18.0,
            boxWidth = 500,
            boxHeight = 500,
            vertical = false,
            lines = listOf("Aa"),
        )
        val b = clampMokuroFontSize(
            mokuroFontSize = 18.0,
            boxWidth = 50,
            boxHeight = 200,
            vertical = true,
            lines = listOf("こんにちはお父さん"),
        )
        assertEquals(a, b)
    }

    @Test
    fun degenerateBoxStillProducesValidFontSize() {
        // Even when box dims are unknowable, mokuroFs alone drives the result.
        assertEquals(
            42,
            clampMokuroFontSize(42.0, boxWidth = 0, boxHeight = 100, vertical = true, lines = listOf("a")),
        )
        // mokuroFs=0 floors to 1, then the boost lifts that to ~15 px — still
        // readable, never invisible.
        assertEquals(
            15,
            clampMokuroFontSize(0.0, boxWidth = 100, boxHeight = 100, vertical = true, lines = listOf("a")),
        )
    }
}
