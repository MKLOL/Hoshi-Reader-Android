package moe.antimony.hoshi.features.reader

import org.junit.Assert.assertEquals
import org.junit.Test

class ReaderBehaviorSasayakiTest {
    @Test
    fun behaviorShowsKeepScreenOnBeforeSasayakiVolumeSeek() {
        // Disable Page-Turn Animation is this fork's manga-reader-specific addition.
        assertEquals(
            listOf(
                "Disable Page-Turn Animation",
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
