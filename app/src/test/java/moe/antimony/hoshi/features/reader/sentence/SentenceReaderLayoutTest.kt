package moe.antimony.hoshi.features.reader.sentence

import org.junit.Assert.assertEquals
import org.junit.Test

class SentenceReaderLayoutTest {
    @Test
    fun readsEachSavedPaneArrangement() {
        assertEquals(SentenceReaderLayout.Stacked, SentenceReaderLayout.fromPreferenceValue("stacked"))
        assertEquals(SentenceReaderLayout.SideBySide, SentenceReaderLayout.fromPreferenceValue("side-by-side"))
    }

    @Test
    fun absentOrUnsupportedPreferenceUsesStackedPanes() {
        listOf(null, "", "unknown", "SideBySide").forEach { value ->
            assertEquals(SentenceReaderLayout.Stacked, SentenceReaderLayout.fromPreferenceValue(value))
        }
    }

    @Test
    fun eachLayoutCanBeRestoredFromItsSavedValue() {
        SentenceReaderLayout.entries.forEach { layout ->
            assertEquals(layout, SentenceReaderLayout.fromPreferenceValue(layout.preferenceValue))
        }
    }
}
