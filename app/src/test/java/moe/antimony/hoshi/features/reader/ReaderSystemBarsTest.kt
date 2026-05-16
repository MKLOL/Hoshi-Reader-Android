package moe.antimony.hoshi.features.reader

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderSystemBarsTest {
    @Test
    fun focusModeUsesImmersiveSystemBarsOnEveryDevice() {
        assertTrue(
            readerShouldUseImmersiveSystemBars(
                focusMode = true,
                deviceProfile = ReaderDeviceProfile(
                    manufacturer = "Google",
                    brand = "google",
                    model = "Pixel",
                    device = "pixel",
                ),
            ),
        )
    }

    @Test
    fun booxDevicesUseImmersiveSystemBarsWhileReading() {
        assertTrue(
            readerShouldUseImmersiveSystemBars(
                focusMode = false,
                deviceProfile = ReaderDeviceProfile(
                    manufacturer = "ONYX",
                    brand = "BOOX",
                    model = "Tab Ultra",
                    device = "tab_ultra",
                ),
            ),
        )
        assertTrue(
            readerShouldUseImmersiveSystemBars(
                focusMode = false,
                deviceProfile = ReaderDeviceProfile(
                    manufacturer = "onyx",
                    brand = null,
                    model = null,
                    device = null,
                ),
            ),
        )
    }

    @Test
    fun otherDevicesKeepNormalBarsUntilFocusMode() {
        assertFalse(
            readerShouldUseImmersiveSystemBars(
                focusMode = false,
                deviceProfile = ReaderDeviceProfile(
                    manufacturer = "Google",
                    brand = "google",
                    model = "Pixel",
                    device = "raven",
                ),
            ),
        )
    }
}
