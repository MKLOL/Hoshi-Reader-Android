package moe.antimony.hoshi.features.reader.sentence

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SentenceLookupQueryTest {
    @Test
    fun scanLimitKeepsSupplementaryCharacterWhole() {
        val prefix = "あ".repeat(31)

        val query = requireNotNull(sentenceLookupQuery(prefix + "𠮷野", tapOffset = 0))

        assertEquals(prefix + "𠮷", query.text)
        assertEquals(32, query.text.codePointCount(0, query.text.length))
        assertEquals(0, query.startOffset)
    }

    @Test
    fun tappingEitherSurrogateSelectsTheSameCharacter() {
        val text = "あ𠮷野"

        val firstHalf = sentenceLookupQuery(text, tapOffset = 1)
        val secondHalf = sentenceLookupQuery(text, tapOffset = 2)

        assertEquals(SentenceLookupQuery(startOffset = 1, text = "𠮷野"), secondHalf)
        assertEquals(firstHalf, secondHalf)
    }

    @Test
    fun bmpTextKeepsExistingQueryLimitAndUtf16Start() {
        val query = sentenceLookupQuery("𠮷" + "あ".repeat(33), tapOffset = 2)

        assertEquals(SentenceLookupQuery(startOffset = 2, text = "あ".repeat(32)), query)
    }

    @Test
    fun trailingCaretKeepsFinalSupplementaryCharacterWhole() {
        assertEquals(
            SentenceLookupQuery(startOffset = 1, text = "𠮷"),
            sentenceLookupQuery("あ𠮷", tapOffset = 3),
        )
    }

    @Test
    fun emptySentenceHasNoQuery() {
        assertNull(sentenceLookupQuery("", tapOffset = 0))
    }
}
