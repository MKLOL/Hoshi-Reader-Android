package moe.antimony.hoshi.features.reader

import moe.antimony.hoshi.R
import org.junit.Assert.assertEquals
import org.junit.Test

class ReaderBehaviorSasayakiTest {
    @Test
    fun behaviorShowsKeepScreenOnBeforeSasayakiVolumeSeek() {
        // Disable Page-Turn Animation is this fork's manga-reader-specific addition.
        assertEquals(
            listOf(
                R.string.reader_behavior_disable_page_turn_animation,
                R.string.reader_behavior_volume_keys_turn_pages,
                R.string.reader_behavior_volume_keys_seek_sasayaki,
                R.string.reader_behavior_reverse_volume_key_direction,
                R.string.reader_behavior_keep_screen_on,
                // Manga-specific toggles, moved here from the reader's overflow (⋯)
                // menu so they live with the other durable reader preferences.
                R.string.reader_behavior_manga_single_tap_lookup,
                R.string.reader_behavior_manga_use_noto_sans_jp,
                R.string.reader_behavior_auto_check_updates,
            ),
            readerBehaviorRows(),
        )
    }

    @Test
    fun behaviorAlwaysShowsSasayakiVolumeSeek() {
        assertEquals(
            listOf(R.string.reader_behavior_volume_keys_seek_sasayaki),
            readerBehaviorSasayakiRows(),
        )
    }
}
