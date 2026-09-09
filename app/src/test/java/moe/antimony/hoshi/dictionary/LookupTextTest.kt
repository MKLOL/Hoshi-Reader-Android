package moe.antimony.hoshi.dictionary

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class LookupTextTest {
    private val high = '\uD842'
    private val low = '\uDFB7'
    private val replacement = '�'

    @Test
    fun textWithoutSurrogatesIsReturnedAsIs() {
        val text = "吉野家で食べる abc 123"
        assertSame(text, text.withUnpairedSurrogatesReplaced())
    }

    @Test
    fun completeSurrogatePairsAreKept() {
        val text = "𠮷野家"
        assertEquals(text, text.withUnpairedSurrogatesReplaced())
    }

    @Test
    fun loneHighSurrogateAtEndIsReplaced() {
        assertEquals("野家${replacement}", "野家${high}".withUnpairedSurrogatesReplaced())
    }

    @Test
    fun loneLowSurrogateAtStartIsReplaced() {
        assertEquals("${replacement}野家", "${low}野家".withUnpairedSurrogatesReplaced())
    }

    @Test
    fun highSurrogateFollowedByNonLowIsReplacedAndTheFollowingCharacterKept() {
        assertEquals("${replacement}野", "${high}野".withUnpairedSurrogatesReplaced())
    }

    @Test
    fun loneLowSurrogateBeforeACompletePairIsReplacedAndThePairKept() {
        assertEquals("${replacement}𠮷", "${low}𠮷".withUnpairedSurrogatesReplaced())
        assertEquals("𠮷${replacement}𠮷", "𠮷${low}𠮷".withUnpairedSurrogatesReplaced())
    }

    @Test
    fun consecutiveLoneSurrogatesAreEachReplaced() {
        assertEquals("$replacement${replacement}", "$high${high}".withUnpairedSurrogatesReplaced())
        assertEquals("$replacement${replacement}", "$low${low}".withUnpairedSurrogatesReplaced())
        assertEquals("$replacement${replacement}", "$low${high}".withUnpairedSurrogatesReplaced())
    }

    @Test
    fun mixedTextKeepsPairsAndReplacesOnlyTheBrokenUnits() {
        val text = "a${high}b𠮷${low}"
        assertEquals("a${replacement}b𠮷${replacement}", text.withUnpairedSurrogatesReplaced())
    }

    @Test
    fun utf16LengthAndCodePointCountArePreserved() {
        for (text in listOf("野家${high}", "${low}野家", "a${high}b𠮷${low}", "$high${high}", "𠮷${high}𠮷")) {
            val sanitized = text.withUnpairedSurrogatesReplaced()
            assertEquals(text, text.length, sanitized.length)
            assertEquals(text, text.codePointCount(0, text.length), sanitized.codePointCount(0, sanitized.length))
        }
    }
}
