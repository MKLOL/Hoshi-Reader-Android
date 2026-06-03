package moe.antimony.hoshi.features.reader

import android.os.Build
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderImageActionsTest {
    @Test
    fun copyToastIsOnlyShownBeforeSystemClipboardFeedbackExists() {
        assertTrue(shouldShowReaderImageCopyToast(Build.VERSION_CODES.S_V2))
        assertFalse(shouldShowReaderImageCopyToast(Build.VERSION_CODES.TIRAMISU))
        assertFalse(shouldShowReaderImageCopyToast(Build.VERSION_CODES.UPSIDE_DOWN_CAKE))
    }
}
