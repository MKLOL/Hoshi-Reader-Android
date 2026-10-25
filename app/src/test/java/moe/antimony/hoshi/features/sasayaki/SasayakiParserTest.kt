package moe.antimony.hoshi.features.sasayaki

import org.junit.Assert.assertEquals
import org.junit.Test

class SasayakiParserTest {
    @Test
    fun parsesSubplzSrtBlocksLikeIos() {
        val srt = """
            1
            00:00:19,124 --> 00:00:22,016
            ＊シックスイヤーザー号、

            2
            00:00:24,148 --> 00:00:28,468
            渚　それはある日の、あたし達にとっては日常の光景だった。
        """.trimIndent().replace("\n", "\r\n")

        val cues = SasayakiParser.parseCues(srt.toByteArray())

        assertEquals(2, cues.size)
        assertEquals("0", cues[0].id)
        assertEquals(19.124, cues[0].startTime, 0.0001)
        assertEquals(22.016, cues[0].endTime, 0.0001)
        assertEquals("＊シックスイヤーザー号、", cues[0].text)
        assertEquals("1", cues[1].id)
        assertEquals(24.148, cues[1].startTime, 0.0001)
        assertEquals(28.468, cues[1].endTime, 0.0001)
        assertEquals("渚　それはある日の、あたし達にとっては日常の光景だった。", cues[1].text)
    }

    @Test
    fun ignoresMalformedBlocksLikeIos() {
        val cues = SasayakiParser.parseCues(
            """
                not-a-cue

                2
                00:00:01,000 --> 00:00:02,000
                text
            """.trimIndent().toByteArray(),
        )

        assertEquals(listOf("1"), cues.map { it.id })
    }

    @Test
    fun skipsBrokenTimestampsWithoutLosingValidSurroundingCues() {
        val timestamps = listOf(
            "00:00:01,000 --> 00:00:02,000",
            "missing --> 00:00:03,000",
            "00:00:04,000 --> 00:invalid:05,000",
            "00:00:NaN --> 00:00:06,000",
            "00:00:07,000 --> 00:00:08,000",
        )
        val source = timestamps.mapIndexed { index, timestamp ->
            "${index + 1}\n$timestamp\ntext $index"
        }.joinToString("\n\n")

        val cues = SasayakiParser.parseCues(source.toByteArray())

        assertEquals(listOf("0", "4"), cues.map { it.id })
        assertEquals(listOf("text 0", "text 4"), cues.map { it.text })
        assertEquals(7.0, cues.last().startTime, 0.0)
        assertEquals(8.0, cues.last().endTime, 0.0)
    }
}
