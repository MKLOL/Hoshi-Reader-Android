package moe.antimony.hoshi.features.reader

import moe.antimony.hoshi.features.sasayaki.SasayakiScreenAwake

object ReaderScreenAwake {
    fun shouldKeepScreenOn(
        keepScreenOnWhileReading: Boolean,
        sasayakiIsPlaying: Boolean,
        sasayakiAutoScroll: Boolean,
    ): Boolean =
        keepScreenOnWhileReading ||
            SasayakiScreenAwake.shouldKeepScreenOn(
                isPlaying = sasayakiIsPlaying,
                autoScroll = sasayakiAutoScroll,
            )
}
