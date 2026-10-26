package moe.antimony.hoshi.features.reader.sentence

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SentenceRubyLayoutTest {
    @Test
    fun completeRubyWordsKeepTheirOriginalTextAndOffsets() {
        val text = "😀もう大丈夫だよ。"
        val groups = sentenceRubyGroups(text, listOf(RubyAnnotation(4, 3, "だいじょうぶ")))

        assertEquals(listOf(SentenceRubyGroup(4, 7, "大丈夫", "だいじょうぶ")), groups)
        assertEquals("大丈夫だよ。", sentenceLookupQuery(text, sentenceRubyHitOffset(groups.single(), 0))?.text)
        assertEquals(6, sentenceRubyHitOffset(groups.single(), 2))
    }

    @Test
    fun unannotatedCompoundsAndIterationMarksStayWholeWithoutGroupingKana() {
        val groups = sentenceRubyGroups("大丈夫だから今日も人々と𠮷野へ行く。", emptyList())

        assertEquals(listOf("大丈夫", "今日", "人々", "𠮷野"), groups.map { it.base })
        assertTrue(groups.all { it.reading.isEmpty() })
    }

    @Test
    fun perKanjiPublisherReadingsKeepTheCompleteCompoundTogether() {
        val groups = sentenceRubyGroups("大丈夫", listOf(
            RubyAnnotation(0, 1, "だい"),
            RubyAnnotation(1, 1, "じょう"),
            RubyAnnotation(2, 1, "ぶ"),
        ))

        assertEquals(listOf("大丈夫"), groups.map { it.base })
        assertEquals(listOf(RubyAnnotation(0, 1, "だい"), RubyAnnotation(1, 1, "じょう"), RubyAnnotation(2, 1, "ぶ")), groups.single().annotations)
        assertEquals(2, sentenceRubyHitOffset(groups.single(), 2))
    }

    @Test
    fun perKanjiCoalescingPreservesNeighboringPublisherWordBoundaries() {
        val groups = sentenceRubyGroups("東京大丈夫大阪", listOf(
            RubyAnnotation(0, 2, "とうきょう"),
            RubyAnnotation(2, 1, "だい"),
            RubyAnnotation(3, 1, "じょう"),
            RubyAnnotation(4, 1, "ぶ"),
            RubyAnnotation(5, 2, "おおさか"),
        ))

        assertEquals(listOf("東京", "大丈夫", "大阪"), groups.map { it.base })
        assertEquals(listOf("とうきょう", "だいじょうぶ", "おおさか"), groups.map { it.reading })
        assertEquals(listOf(0, 2, 5), groups.map { it.start })
    }

    @Test
    fun partialReadingsKeepTheCompoundWholeAndStayOnTheirOwnBase() {
        for (offset in 0..2) {
            val groups = sentenceRubyGroups("大丈夫ですよ。", listOf(RubyAnnotation(offset, 1, "よみ")))
            assertEquals(listOf("大丈夫"), groups.map { it.base })
            assertEquals(listOf(RubyAnnotation(offset, 1, "よみ")), groups.single().annotations)
            assertEquals(listOf(0, 1, 2), (0..2).map { sentenceRubyHitOffset(groups.single(), it) })
        }
    }

    @Test
    fun partialReadingsNeverJoinAcrossExplicitPublisherWords() {
        val groups = sentenceRubyGroups("東京大丈夫大阪", listOf(
            RubyAnnotation(0, 2, "とうきょう"), RubyAnnotation(2, 1, "だい"), RubyAnnotation(5, 2, "おおさか"),
        ))
        assertEquals(listOf("東京", "大丈夫", "大阪"), groups.map { it.base })
        assertEquals(listOf(RubyAnnotation(0, 1, "だい")), groups[1].annotations)
    }

    @Test
    fun malformedAndOverlappingAnnotationsCannotConsumeOtherCharacters() {
        val text = "𠮷野は大丈夫。"
        val groups = sentenceRubyGroups(text, listOf(
            RubyAnnotation(1, 1, "よし"), // Half of 𠮷.
            RubyAnnotation(0, 3, "よしの"),
            RubyAnnotation(2, 4, "overlap"),
            RubyAnnotation(4, Int.MAX_VALUE, "overflow"),
            RubyAnnotation(-1, 2, "invalid"),
            RubyAnnotation(4, 3, "だいじょうぶ"),
        ))

        assertEquals(listOf("𠮷野", "大丈夫"), groups.map { it.base })
        assertEquals(listOf(0, 4), groups.map { it.start })
    }

    @Test
    fun onlyKanjiRunsAreWrappingCompoundsNotPublisherWordsOrSingleReadings() {
        val groups = sentenceRubyGroups("大丈夫は東京で字だ", listOf(
            RubyAnnotation(4, 2, "とうきょう"),
            RubyAnnotation(7, 1, "じ"),
        ))

        assertEquals(listOf("大丈夫", "東京", "字"), groups.map { it.base })
        assertEquals(listOf(true, false, false), groups.map { it.compound })
    }

    @Test
    fun aCompoundThatFitsThePaneStaysOneAtom() {
        val groups = sentenceRubyGroups("原子力発電所の話。", emptyList())

        assertEquals(groups, fitSentenceRubyGroups(groups, availableWidth = 120f) { it.base.length * 20f })
        assertEquals(groups, fitSentenceRubyGroups(groups, availableWidth = 119f) { 119f })
    }

    @Test
    fun aLongUnannotatedKanjiRunWiderThanThePaneWrapsAsTextInsteadOfShrinking() {
        val text = "東京電力福島第一原子力発電所の話。"
        val groups = sentenceRubyGroups(text, emptyList())
        assertEquals(listOf("東京電力福島第一原子力発電所"), groups.map { it.base })

        // 14 characters at 20 px cannot fit 100 px: no placeholder remains, so nothing shrinks.
        assertTrue(fitSentenceRubyGroups(groups, availableWidth = 100f) { it.base.length * 20f }.isEmpty())
    }

    @Test
    fun aWrappingRunKeepsEachSingleKanjiReadingOverItsOwnCharacter() {
        val text = "は東京電力福島第一原子力発電所の"
        val groups = sentenceRubyGroups(text, listOf(
            RubyAnnotation(1, 1, "とう"),
            RubyAnnotation(11, 1, "りょく"),
            RubyAnnotation(15, 1, "の"), // Kana: a separate one-character publisher word.
        ))

        val fitted = fitSentenceRubyGroups(groups, availableWidth = 100f) { it.base.length * 20f }

        assertEquals(
            listOf(
                SentenceRubyGroup(1, 2, "東", "とう"),
                SentenceRubyGroup(11, 12, "力", "りょく"),
                SentenceRubyGroup(15, 16, "の", "の"),
            ),
            fitted,
        )
        fitted.forEach { group ->
            assertEquals(text.substring(group.start, group.end), group.base)
            assertEquals(group.start, sentenceRubyHitOffset(group, 0))
        }
    }

    @Test
    fun publisherWordsAndWordSizedCompoundsKeepShrinkToFitInsteadOfWrapping() {
        val word = "大丈夫大丈夫大丈夫"
        val publisher = sentenceRubyGroups(word, listOf(RubyAnnotation(0, word.length, "だいじょうぶ")))
        assertEquals(publisher, fitSentenceRubyGroups(publisher, availableWidth = 10f) { 1_000f })

        val shortCompound = sentenceRubyGroups("大丈夫ですよ。", listOf(RubyAnnotation(0, 1, "だい")))
        assertEquals(shortCompound, fitSentenceRubyGroups(shortCompound, availableWidth = 10f) { 1_000f })
        assertTrue(sentenceRubyPlacement(1_000f, 32f, 0f, 16f, 10f).scale < 1f)

        val fourKanji = sentenceRubyGroups("四字熟語", emptyList())
        assertEquals(fourKanji, fitSentenceRubyGroups(fourKanji, availableWidth = 10f) { 1_000f })
        val fiveKanji = sentenceRubyGroups("四字熟語集", emptyList())
        assertTrue(fitSentenceRubyGroups(fiveKanji, availableWidth = 10f) { 1_000f }.isEmpty())
    }

    @Test
    fun eachGroupIsFittedIndependentlyInSentenceOrder() {
        val text = "東京へ原子力発電所から大丈夫。"
        val groups = sentenceRubyGroups(text, listOf(RubyAnnotation(0, 2, "とうきょう")))
        assertEquals(listOf("東京", "原子力発電所", "大丈夫"), groups.map { it.base })

        val fitted = fitSentenceRubyGroups(groups, availableWidth = 80f) { it.base.length * 20f }

        assertEquals(listOf("東京", "大丈夫"), fitted.map { it.base })
        assertEquals(listOf(0, 11), fitted.map { it.start })
    }

    @Test
    fun readingWidthReservesSpaceAndNarrowPaneShrinksTheWholeGroup() {
        val full = sentenceRubyPlacement(90f, 32f, 110f, 16f, 300f)
        assertEquals(110f, full.width)
        assertEquals(10f, full.baseLeft)
        assertEquals(1f, full.scale)

        val narrow = sentenceRubyPlacement(90f, 32f, 110f, 16f, 55f)
        assertEquals(55f, narrow.width)
        assertEquals(0.5f, narrow.scale)
        assertEquals(24f, narrow.height)
        assertTrue(narrow.baseLeft >= 0)
        assertTrue(narrow.baseLeft + 90f * narrow.scale <= narrow.width)
        assertTrue(narrow.readingLeft + 110f * narrow.scale <= narrow.width)
    }

    @Test
    fun hidingFuriganaKeepsWholeBaseButRemovesReadingSpace() {
        val hidden = sentenceRubyPlacement(90f, 32f, 0f, 0f, 55f)
        assertEquals(55f, hidden.width)
        assertEquals(0f, hidden.baseTop)
        assertEquals(55f / 90f, hidden.scale)
    }

    @Test
    fun leftAndRightHalfTapsResolveThePressedCharacterInsteadOfTheNextCaret() {
        val text = "大丈夫"
        for (character in text.indices) {
            for (fraction in listOf(0.25f, 0.75f)) {
                val x = character * 20f + 20f * fraction
                val nearestCaret = character + if (fraction > 0.5f) 1 else 0
                val hit = sentenceCharacterHit(text, nearestCaret, x, 10f) { start, _ ->
                    SentenceGlyphBounds(start * 20f, 0f, (start + 1) * 20f, 30f)
                }
                assertEquals("character $character at $fraction", character, hit)
                val group = SentenceRubyGroup(5, 8, text, "だいじょうぶ")
                assertEquals(5 + character, sentenceRubyHitOffset(group, hit))
            }
        }
    }

    @Test
    fun hitTestingKeepsSupplementaryAndCombiningCharactersWhole() {
        val text = "𠮷か\u3099な"
        val starts = listOf(0, 2, 4)
        val ends = listOf(2, 4, 5)
        for (character in starts.indices) {
            val hit = sentenceCharacterHit(text, ends[character], character * 20f + 15f, 10f) { start, end ->
                val index = starts.indexOf(start)
                assertEquals(ends[index], end)
                SentenceGlyphBounds(index * 20f, 0f, (index + 1) * 20f, 30f)
            }
            assertEquals(starts[character], hit)
        }
        assertEquals(0, sentenceCharacterStart(text, 1))
        assertEquals(2, sentenceCharacterStart(text, 3))
    }
}
