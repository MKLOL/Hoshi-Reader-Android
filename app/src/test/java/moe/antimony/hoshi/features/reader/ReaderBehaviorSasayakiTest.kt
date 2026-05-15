package moe.antimony.hoshi.features.reader

import org.junit.Assert.assertEquals
import org.junit.Test

class ReaderBehaviorSasayakiTest {
    @Test
    fun behaviorShowsKeepScreenOnBeforeSasayakiVolumeSeek() {
        assertEquals(
            listOf(
                "Volume Keys Turn Pages",
                "Volume Keys Seek Sasayaki",
                "Reverse Volume Key Direction",
                "Keep Screen On",
                "Automatically Check for Updates",
            ),
            readerBehaviorRows(),
        )
    }

    @Test
    fun behaviorAlwaysShowsSasayakiVolumeSeek() {
        assertEquals(
            listOf("Volume Keys Seek Sasayaki"),
            readerBehaviorSasayakiRows(),
        )
    }
}
