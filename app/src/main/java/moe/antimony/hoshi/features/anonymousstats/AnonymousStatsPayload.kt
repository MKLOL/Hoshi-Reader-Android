package moe.antimony.hoshi.features.anonymousstats

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.Locale

/**
 * The whole report, one flat JSON object (`POST /api/book_sync/v1/stats`). Only [installId] and
 * [platform] are required; a fact that could not be read is left out. Nothing else is ever sent:
 * no device name, sync identity, token, account, server URL, book title or file name.
 */
@Serializable
internal data class AnonymousStatsPayload(
    val installId: String,
    val platform: String = PLATFORM,
    val appVersion: String? = null,
    val versionCode: Int? = null,
    val buildType: String? = null,
    val osVersion: String? = null,
    val sdkInt: Int? = null,
    val manufacturer: String? = null,
    val model: String? = null,
    val emulator: Boolean? = null,
    val screenWidthPx: Int? = null,
    val screenHeightPx: Int? = null,
    val densityDpi: Int? = null,
    val smallestWidthDp: Int? = null,
    val locale: String? = null,
    val appLanguage: String? = null,
    val epubCount: Int? = null,
    val mangaCount: Int? = null,
    val cloudOnlyCount: Int? = null,
    val dictionaryCount: Int? = null,
    val httpSyncEnabled: Boolean? = null,
    val driveSyncEnabled: Boolean? = null,
    val readerContinuous: Boolean? = null,
    val readerVertical: Boolean? = null,
) {
    fun toJson(): String = PayloadJson.encodeToString(serializer(), this)

    companion object {
        const val PLATFORM = "android"
        private val PayloadJson = Json {
            encodeDefaults = true
            explicitNulls = false
        }
    }
}

/** App, OS, screen and language facts, as Android reports them. */
internal data class AnonymousStatsDevice(
    val appVersion: String? = null,
    val versionCode: Int? = null,
    val buildType: String? = null,
    val osVersion: String? = null,
    val sdkInt: Int? = null,
    val manufacturer: String? = null,
    val model: String? = null,
    val emulator: Boolean? = null,
    /** The full display's maximum window bounds, in whatever orientation they were read. */
    val screenWidthPx: Int? = null,
    val screenHeightPx: Int? = null,
    val densityDpi: Int? = null,
    val smallestWidthDp: Int? = null,
    val locale: String? = null,
    /** The app's own UI language tag, [APP_LANGUAGE_SYSTEM] while it follows the system, null if unreadable. */
    val appLanguage: String? = null,
)

internal data class AnonymousStatsLibrary(
    val epubCount: Int? = null,
    val mangaCount: Int? = null,
    val cloudOnlyCount: Int? = null,
    val dictionaryCount: Int? = null,
)

internal data class AnonymousStatsFlags(
    val httpSyncEnabled: Boolean? = null,
    val driveSyncEnabled: Boolean? = null,
    val readerContinuous: Boolean? = null,
    val readerVertical: Boolean? = null,
)

internal fun anonymousStatsPayload(
    installId: String,
    device: AnonymousStatsDevice,
    library: AnonymousStatsLibrary,
    flags: AnonymousStatsFlags,
): AnonymousStatsPayload {
    // Orientation-independent: the width is always the shorter side.
    val width = device.screenWidthPx?.takeIf { it > 0 }
    val height = device.screenHeightPx?.takeIf { it > 0 }
    val screen = if (width != null && height != null) minOf(width, height) to maxOf(width, height) else null
    return AnonymousStatsPayload(
        installId = installId.lowercase(),
        appVersion = device.appVersion,
        versionCode = device.versionCode,
        buildType = device.buildType,
        osVersion = device.osVersion,
        sdkInt = device.sdkInt,
        manufacturer = device.manufacturer,
        model = device.model,
        emulator = device.emulator,
        screenWidthPx = screen?.first,
        screenHeightPx = screen?.second,
        densityDpi = device.densityDpi?.takeIf { it > 0 },
        smallestWidthDp = device.smallestWidthDp?.takeIf { it > 0 },
        locale = device.locale?.takeIf { it.isNotBlank() },
        appLanguage = device.appLanguage?.takeIf { it.isNotBlank() },
        epubCount = library.epubCount,
        mangaCount = library.mangaCount,
        cloudOnlyCount = library.cloudOnlyCount,
        dictionaryCount = library.dictionaryCount,
        httpSyncEnabled = flags.httpSyncEnabled,
        driveSyncEnabled = flags.driveSyncEnabled,
        readerContinuous = flags.readerContinuous,
        readerVertical = flags.readerVertical,
    )
}

internal const val APP_LANGUAGE_SYSTEM = "system"

/**
 * A plain BCP 47 tag. Unicode extensions are dropped: Android 14's regional preferences add
 * ones such as `-u-mu-celsius` or `-u-fw-mon` that say nothing about the language.
 */
internal fun languageTag(locale: Locale?): String? =
    locale?.stripExtensions()?.toLanguageTag()?.takeIf { it.isNotBlank() && it != "und" }

/**
 * Conservative: only the hardware names, fingerprints and products the Android SDK and
 * Genymotion emulators report, so a real device is never counted as one.
 */
internal fun looksLikeEmulator(
    fingerprint: String?,
    hardware: String?,
    product: String?,
    model: String?,
    manufacturer: String?,
): Boolean {
    val hardwareName = hardware.orEmpty().lowercase()
    val fingerprintText = fingerprint.orEmpty().lowercase()
    val productName = product.orEmpty().lowercase()
    return hardwareName in EMULATOR_HARDWARE ||
        fingerprintText.startsWith("generic") ||
        fingerprintText.contains("/sdk_gphone") ||
        productName in EMULATOR_PRODUCTS ||
        productName.startsWith("sdk_gphone") ||
        model.orEmpty().startsWith("Android SDK built for") ||
        manufacturer.orEmpty().equals("Genymotion", ignoreCase = true)
}

private val EMULATOR_HARDWARE = setOf("goldfish", "ranchu", "vbox86")
private val EMULATOR_PRODUCTS = setOf("sdk", "google_sdk", "sdk_x86", "sdk_x86_64", "sdk_google_phone_x86")
