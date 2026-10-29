package moe.antimony.hoshi.features.mangareader

import java.util.concurrent.Executors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MangaFuriganaTest {
    @Test
    fun knownCompoundsUseDictionaryReadingsInHiragana() {
        assertEquals(
            listOf(MangaFuriganaSpan(0, 3, "にほんご"), MangaFuriganaSpan(4, 2, "べんきょう")),
            MangaFurigana.annotations("日本語を勉強する。"),
        )
        // These cannot be read by assigning a fixed reading to each character.
        assertEquals(listOf(MangaFuriganaSpan(0, 2, "おとな")), MangaFurigana.annotations("大人"))
        assertEquals(listOf(MangaFuriganaSpan(0, 2, "きょう")), MangaFurigana.annotations("今日"))
    }

    @Test
    fun inflectionsLeaveOkuriganaInTheBaseText() {
        assertEquals(listOf(MangaFuriganaSpan(0, 1, "た")), MangaFurigana.annotations("食べました"))
        assertEquals(
            listOf(MangaFuriganaSpan(0, 1, "と"), MangaFuriganaSpan(2, 1, "もど")),
            MangaFurigana.annotations("取り戻す"),
        )
        assertEquals(
            listOf(MangaFuriganaSpan(0, 1, "もう"), MangaFuriganaSpan(2, 1, "こ")),
            MangaFurigana.annotations("申し込む"),
        )
        assertEquals(listOf(MangaFuriganaSpan(1, 1, "いわ")), MangaFurigana.annotations("お祝い"))
    }

    @Test
    fun whitespaceNewlinesRepeatedTokensAndEmojiRetainOriginalUtf16Offsets() {
        val text = "😀  日本語\n食べる\n食べる！"
        val spans = MangaFurigana.annotations(text)
        assertEquals(
            listOf(
                MangaFuriganaSpan(4, 3, "にほんご"),
                MangaFuriganaSpan(8, 1, "た"),
                MangaFuriganaSpan(12, 1, "た"),
            ),
            spans,
        )
        assertEquals(listOf("日本語", "食", "食"), spans.map { text.substring(it.start, it.start + it.length) })
        assertEquals(spans, MangaFurigana.annotations(text))
    }

    @Test
    fun kanaPunctuationLatinAndUnknownKanjiDoNotReceiveGuessedReadings() {
        for (text in listOf("", "かなカナです！", "<ruby>hello</ruby>", "𠮷𠮷𠮷")) {
            assertTrue(text, MangaFurigana.annotations(text).isEmpty())
        }
        val text = "𠮷𠮷𠮷\n学校"
        assertEquals(listOf(MangaFuriganaSpan(7, 2, "がっこう")), MangaFurigana.annotations(text))
    }

    @Test
    fun unusuallyLargeOcrInputFallsBackToPlainText() {
        assertTrue(MangaFurigana.annotations("漢".repeat(8_193)).isEmpty())
    }

    @Test
    fun concurrentPageRequestsReturnConsistentReadingsWithoutMutableSharedLists() {
        val executor = Executors.newFixedThreadPool(4)
        try {
            val futures = (0 until 24).map { index ->
                executor.submit<List<MangaFuriganaSpan>> {
                    MangaFurigana.annotations("${index}番。食べました。日本語。")
                }
            }
            futures.forEachIndexed { index, future ->
                val text = "${index}番。食べました。日本語。"
                val spans = future.get()
                assertFalse(spans.isEmpty())
                assertTrue(spans.any { text.substring(it.start, it.start + it.length) == "食" && it.reading == "た" })
                assertTrue(spans.any { text.substring(it.start, it.start + it.length) == "日本語" && it.reading == "にほんご" })
            }
        } finally {
            executor.shutdownNow()
        }
    }
}
