package moe.antimony.hoshi.features.reader

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
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
    immersiveReaderContent: Boolean = false,
    deviceProfile: ReaderDeviceProfile = currentReaderDeviceProfile(),
): Boolean =
    focusMode || immersiveReaderContent || deviceProfile.isBooxReaderDevice()

internal tailrec fun Context.findHoshiActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findHoshiActivity()
    else -> null
}

private fun ReaderDeviceProfile.isBooxReaderDevice(): Boolean =
    listOfNotNull(manufacturer, brand, model, device)
        .any { field ->
            val normalized = field.lowercase(Locale.US)
            normalized.contains("onyx") || normalized.contains("boox")
        }
