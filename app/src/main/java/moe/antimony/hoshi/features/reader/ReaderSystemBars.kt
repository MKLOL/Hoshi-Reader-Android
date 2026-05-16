package moe.antimony.hoshi.features.reader

import android.os.Build
import java.util.Locale

internal data class ReaderDeviceProfile(
    val manufacturer: String?,
    val brand: String?,
    val model: String?,
    val device: String?,
)

internal fun currentReaderDeviceProfile(): ReaderDeviceProfile =
    ReaderDeviceProfile(
        manufacturer = Build.MANUFACTURER,
        brand = Build.BRAND,
        model = Build.MODEL,
        device = Build.DEVICE,
    )

internal fun readerShouldUseImmersiveSystemBars(
    focusMode: Boolean,
    deviceProfile: ReaderDeviceProfile = currentReaderDeviceProfile(),
): Boolean =
    focusMode || deviceProfile.isBooxReaderDevice()

private fun ReaderDeviceProfile.isBooxReaderDevice(): Boolean =
    listOfNotNull(manufacturer, brand, model, device)
        .any { field ->
            val normalized = field.lowercase(Locale.US)
            normalized.contains("onyx") || normalized.contains("boox")
        }
