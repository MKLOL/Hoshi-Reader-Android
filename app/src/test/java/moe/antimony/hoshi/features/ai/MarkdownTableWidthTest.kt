package moe.antimony.hoshi.features.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers how a tutor reply's breakdown table is fitted to the popup card: [shareColumnWidths]
 * hands out the card's width, and [minContentTokenRanges] is what tells it how narrow a column
 * may get before a word breaks in half.
 *
 * The regression: a flat 52dp floor let a column end up narrower than its own longest word, so
 * the card showed "sentenc/e" and "punctua/tion" split across two lines.
 */
class MarkdownTableWidthTest {

    private fun assertWidths(expected: List<Float>, actual: List<Float>) {
        assertEquals(expected.size, actual.size)
        expected.forEachIndexed { index, value ->
            assertEquals("column $index of $actual", value, actual[index], 0.01f)
        }
    }

    @Test
    fun columnsThatAlreadyFitAreLeftAlone() {
        val widths = shareColumnWidths(
            wanted = listOf(60f, 80f, 100f),
            floors = listOf(40f, 50f, 60f),
            available = 320f,
        )
        assertWidths(listOf(60f, 80f, 100f), widths)
    }

    @Test
    fun aLoneColumnGetsTheWholeCard() {
        val widths = shareColumnWidths(wanted = listOf(400f), floors = listOf(52f), available = 300f)
        assertWidths(listOf(300f), widths)
    }

    @Test
    fun sixColumnsFitInsideA320DpCard() {
        val wanted = listOf(60f, 55f, 70f, 90f, 80f, 260f)
        val floors = listOf(40f, 38f, 44f, 52f, 50f, 64f)
        val widths = shareColumnWidths(wanted = wanted, floors = floors, available = 320f)

        assertEquals("must fill exactly one card width: $widths", 320f, widths.sum(), 0.01f)
        widths.forEachIndexed { index, width ->
            assertTrue(
                "column $index fell below its longest word: $widths",
                width >= floors[index] - 0.01f,
            )
        }
        // The five short columns are starved by the proportional share, so they pin to their
        // floors and the wide column absorbs everything that is left.
        assertEquals(96f, widths.last(), 0.01f)
    }

    @Test
    fun theMeaningColumnGetsTheLeftoverWidth() {
        // A four-column breakdown: reading, part of speech, form — then the meaning, which is the
        // column the reader is actually here for.
        val widths = shareColumnWidths(
            wanted = listOf(40f, 40f, 40f, 400f),
            floors = listOf(40f, 40f, 40f, 60f),
            available = 320f,
        )
        assertWidths(listOf(40f, 40f, 40f, 200f), widths)
    }

    @Test
    fun aColumnNeverFallsBelowItsLongestWord() {
        // Its proportional share would be 100, which would break "punctuation" mid-word; it pins
        // to its min-content width instead and the greedy column pays for it.
        val widths = shareColumnWidths(
            wanted = listOf(200f, 400f),
            floors = listOf(200f, 60f),
            available = 300f,
        )
        assertWidths(listOf(200f, 100f), widths)
    }

    @Test
    fun everyColumnStarvedSharesWhatLittleThereIs() {
        // Floors that cannot all fit are scaled down together rather than overflowing the card.
        val widths = shareColumnWidths(
            wanted = listOf(100f, 100f, 100f),
            floors = listOf(100f, 100f, 100f),
            available = 60f,
        )
        assertWidths(listOf(20f, 20f, 20f), widths)
        assertEquals(60f, widths.sum(), 0.01f)
    }

    @Test
    fun anUnknownCardWidthLeavesTheWantedWidthsAlone() {
        // The first measure pass runs before `BoxWithConstraints` knows anything; the table is
        // re-measured as soon as it does.
        val wanted = listOf(120f, 300f)
        assertWidths(wanted, shareColumnWidths(wanted, listOf(60f, 60f), available = 0f))
        assertWidths(emptyList(), shareColumnWidths(emptyList(), emptyList(), available = 320f))
    }

    @Test
    fun aFloorWiderThanTheColumnWantsIsIgnored() {
        val widths = shareColumnWidths(
            wanted = listOf(30f, 400f),
            floors = listOf(90f, 60f),
            available = 300f,
        )
        assertEquals("a column never gets more than it wanted: $widths", 30f, widths[0], 0.01f)
        assertEquals(300f, widths.sum(), 0.01f)
    }

    @Test
    fun englishTokensAreWordsWithoutTheirTrailingSpace() {
        assertEquals(
            listOf("Nice", "to", "meet", "you."),
            "Nice to meet you.".let { text ->
                minContentTokenRanges(text).map { text.substring(it.first, it.last + 1) }
            },
        )
    }

    @Test
    fun japaneseBreaksBetweenCharactersSoAColumnNeedNotHoldTheWholeSentence() {
        val text = "元気ですか"
        val tokens = minContentTokenRanges(text).map { text.substring(it.first, it.last + 1) }
        assertEquals(listOf("元", "気", "で", "す", "か"), tokens)
    }

    @Test
    fun emptyAndBlankCellsHaveNoTokens() {
        assertEquals(emptyList<IntRange>(), minContentTokenRanges(""))
        assertEquals(emptyList<IntRange>(), minContentTokenRanges("   "))
    }
}
