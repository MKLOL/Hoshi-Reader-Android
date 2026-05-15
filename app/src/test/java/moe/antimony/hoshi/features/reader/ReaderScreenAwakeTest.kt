package moe.antimony.hoshi.features.reader

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderScreenAwakeTest {
    @Test
    fun keepsScreenAwakeWhenReaderSettingIsEnabled() {
        assertTrue(
            ReaderScreenAwake.shouldKeepScreenOn(
                keepScreenOnWhileReading = true,
                sasayakiIsPlaying = false,
                sasayakiAutoScroll = false,
            ),
        )
    }

    @Test
    fun keepsSasayakiPlaybackAutoScrollBehaviorWhenReaderSettingIsDisabled() {
        assertTrue(
            ReaderScreenAwake.shouldKeepScreenOn(
                keepScreenOnWhileReading = false,
                sasayakiIsPlaying = true,
                sasayakiAutoScroll = true,
            ),
        )
        assertFalse(
            ReaderScreenAwake.shouldKeepScreenOn(
                keepScreenOnWhileReading = false,
                sasayakiIsPlaying = true,
                sasayakiAutoScroll = false,
            ),
        )
    }
}
