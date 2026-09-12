package moe.antimony.hoshi.epub

/**
 * The device reading statistics are attributed to. [id] is the stable per-installation id
 * HTTP sync already uses for its bookmark shards; [name] is what the Statistics screens show
 * for it (the Android device name, falling back to the model).
 */
data class DeviceIdentity(
    val id: String,
    val name: String,
)

/** Key that tells one device's entry for a day from another's; entries without a device share one bucket. */
internal fun dayDeviceKey(dateKey: String, deviceId: String?): String = dateKey + "\u0000" + deviceId.orEmpty()
