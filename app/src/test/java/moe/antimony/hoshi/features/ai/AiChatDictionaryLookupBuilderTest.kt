package moe.antimony.hoshi.features.ai

import de.manhhao.hoshi.Frequency
import de.manhhao.hoshi.FrequencyEntry
import de.manhhao.hoshi.GlossaryEntry
import de.manhhao.hoshi.LookupResult
import de.manhhao.hoshi.PitchEntry
import de.manhhao.hoshi.TermResult
import de.manhhao.hoshi.TransformGroup
import moe.antimony.hoshi.features.dictionary.DictionarySettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AiChatDictionaryLookupBuilderTest {
    @Test
    fun mapsLookupResultsIntoCompactChatHistorySnapshot() {
        val lookup = listOf(
            lookupResult(
                expression = "食べる",
                reading = "たべる",
                matched = "食べた",
                glossaries = arrayOf(
                    glossary("JMdict", "to eat", "v1", "common"),
                    glossary("Jitendex", "consume food"),
                    glossary("Extra", "third"),
                    glossary("Extra", "fourth"),
                    glossary("Extra", "fifth should be dropped"),
                ),
            ),
            lookupResult(expression = "食う", reading = "くう"),
            lookupResult(expression = "喰う", reading = "くう"),
            lookupResult(expression = "飯", reading = "めし"),
        ).toAiChatDictionaryLookup("食べた")

        requireNotNull(lookup)
        assertEquals("食べた", lookup.query)
        assertEquals("stored results are capped", 3, lookup.results.size)

        val first = lookup.results.first()
        assertEquals("食べる", first.expression)
        assertEquals("たべる", first.reading)
        assertEquals("食べた", first.matched)
        assertEquals(listOf("ichidan", "past"), first.deinflectionTrace.map { it.name })
        assertEquals("past tense", first.deinflectionTrace.last().description)
        assertEquals("rules are split into individual tags", listOf("v1", "vt"), first.rules)

        assertEquals("glossaries are capped", 4, first.glossaries.size)
        assertEquals("JMdict", first.glossaries.first().dictionary)
        assertEquals("to eat", first.glossaries.first().content)
        assertEquals("v1", first.glossaries.first().definitionTags)
        assertEquals("common", first.glossaries.first().termTags)

        assertEquals("frequency", first.frequencies.single().dictionary)
        assertEquals(100, first.frequencies.single().frequencies.single().value)
        assertEquals("rank 100", first.frequencies.single().frequencies.single().displayValue)
        assertEquals("pitch", first.pitches.single().dictionary)
        assertEquals(listOf(2, 0), first.pitches.single().pitchPositions)
    }

    @Test
    fun emptyLookupResultsDoNotCreateSnapshot() {
        assertNull(emptyList<LookupResult>().toAiChatDictionaryLookup("食べた"))
    }

    @Test
    fun extractsJapaneseFallbackCandidatesFromSpeechBubbleText() {
        val candidates = candidateLookupQueries("「……食べた！」\nでも OK")

        assertEquals("「……食べた！」\nでも OK", candidates.first())
        assertTrue(candidates.contains("食べた"))
        assertTrue(candidates.contains("でも"))
    }

    @Test
    fun buildLookupFallsBackToExtractedJapaneseRun() {
        val calls = mutableListOf<Triple<String, Int, Int>>()
        val lookup = buildAiChatDictionaryLookup(
            query = "「食べた！」",
            settings = DictionarySettings(maxResults = 50, scanLength = 99),
        ) { query, maxResults, scanLength ->
            calls += Triple(query, maxResults, scanLength)
            if (query == "食べた") listOf(lookupResult()) else emptyList()
        }

        requireNotNull(lookup)
        assertEquals("食べた", lookup.query)
        assertEquals(
            listOf(
                Triple("「食べた！」", 3, 64),
                Triple("食べた", 3, 64),
            ),
            calls,
        )
    }

    private fun lookupResult(
        expression: String = "食べる",
        reading: String = "たべる",
        matched: String = "食べた",
        glossaries: Array<GlossaryEntry> = arrayOf(glossary("JMdict", "to eat")),
    ): LookupResult = LookupResult(
        matched,
        "食べる",
        arrayOf(
            TransformGroup("past", "past tense"),
            TransformGroup("ichidan", "ichidan verb"),
        ),
        TermResult(
            expression,
            reading,
            "v1 vt",
            glossaries,
            arrayOf(
                FrequencyEntry(
                    "frequency",
                    arrayOf(Frequency(100, "rank 100")),
                ),
            ),
            arrayOf(PitchEntry("pitch", intArrayOf(2, 0))),
        ),
        0,
    )
}

private fun glossary(
    dictionary: String,
    content: String,
    definitionTags: String = "",
    termTags: String = "",
): GlossaryEntry = GlossaryEntry(
    dictionary,
    content,
    definitionTags,
    termTags,
)
