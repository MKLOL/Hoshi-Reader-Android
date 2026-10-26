package moe.antimony.hoshi.features.anonymousstats

import android.app.LocaleManager
import android.content.Context
import android.content.res.Resources
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.os.Build
import android.view.Display
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import moe.antimony.hoshi.BuildConfig
import moe.antimony.hoshi.epub.BookRepository
import moe.antimony.hoshi.epub.ContentType
import moe.antimony.hoshi.epub.bookContentType
import moe.antimony.hoshi.features.reader.ReaderSettings
import moe.antimony.hoshi.features.settings.isAppLanguagePickerSupported
import moe.antimony.hoshi.features.sync.SyncSettings
import moe.antimony.hoshi.features.sync.http.HttpSyncCloudBooks
import moe.antimony.hoshi.features.sync.http.HttpSyncSettings
import moe.antimony.hoshi.features.sync.http.syncIdForMetadata
import java.io.File

/**
 * Reads one report from what the app already keeps: the library folder listing, the cloud
 * catalogue, the installed dictionaries and the sync and reader settings. Only counts and
 * on/off flags leave this class; a fact that cannot be read is left out of the report.
 */
internal class AnonymousStatsCollector(
    private val bookRepository: BookRepository,
    private val cloudBooks: HttpSyncCloudBooks,
    private val dictionaryCount: () -> Int,
    private val httpSyncSettings: Flow<HttpSyncSettings>,
    private val driveSyncSettings: Flow<SyncSettings>,
    private val readerSettings: Flow<ReaderSettings>,
    private val device: () -> AnonymousStatsDevice,
) {
    suspend fun collect(installId: String): AnonymousStatsPayload {
        val httpSync = optional { httpSyncSettings.first() }
        val reader = optional { readerSettings.first() }
        return anonymousStatsPayload(
            installId = installId,
            device = optional { device() } ?: AnonymousStatsDevice(),
            library = library(httpSync),
            flags = AnonymousStatsFlags(
                httpSyncEnabled = httpSync?.isConfigured,
                driveSyncEnabled = optional { driveSyncSettings.first().enabled },
                readerContinuous = reader?.continuousMode,
                readerVertical = reader?.verticalWriting,
            ),
        )
    }

    private suspend fun library(httpSync: HttpSyncSettings?): AnonymousStatsLibrary {
        val roots = optional { bookRepository.loadAllBooks() }
        val types = optional { roots?.map(::bookContentType) }
        return AnonymousStatsLibrary(
            epubCount = types?.count { it == ContentType.Epub },
            mangaCount = types?.count { it == ContentType.Mokuro },
            cloudOnlyCount = optional { cloudOnlyCount(roots, httpSync) },
            dictionaryCount = optional { dictionaryCount() },
        )
    }

    /**
     * The shelf's "Not downloaded" books: the catalogue is shown only with a configured v3 HTTP
     * sync, and never for a book that is already installed. Book metadata is read only when the
     * catalogue lists something.
     */
    private suspend fun cloudOnlyCount(roots: List<File>?, httpSync: HttpSyncSettings?): Int? {
        if (httpSync == null) return null
        if (!httpSync.isConfigured || !httpSync.useV3Sync) return 0
        val records = cloudBooks.records()
        if (records.isEmpty()) return 0
        val installed = (roots ?: return null).mapNotNullTo(mutableSetOf()) { root ->
            bookRepository.loadMetadata(root)?.let { syncIdForMetadata(it.copy(folder = it.folder ?: root.name)) }
        }
        return records.count { it.syncId !in installed }
    }

    private inline fun <T> optional(read: () -> T): T? =
        try {
            read()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
}

/** Reads the app, OS, screen and language facts. Call off the main thread. */
internal fun readAnonymousStatsDevice(context: Context): AnonymousStatsDevice {
    val configuration = context.resources.configuration
    val screen = runCatching { maximumScreenSize(context) }.getOrNull()
    return AnonymousStatsDevice(
        appVersion = BuildConfig.VERSION_NAME,
        versionCode = BuildConfig.VERSION_CODE,
        buildType = BuildConfig.BUILD_TYPE,
        osVersion = Build.VERSION.RELEASE,
        sdkInt = Build.VERSION.SDK_INT,
        manufacturer = Build.MANUFACTURER,
        model = Build.MODEL,
        emulator = looksLikeEmulator(
            fingerprint = Build.FINGERPRINT,
            hardware = Build.HARDWARE,
            product = Build.PRODUCT,
            model = Build.MODEL,
            manufacturer = Build.MANUFACTURER,
        ),
        screenWidthPx = screen?.x,
        screenHeightPx = screen?.y,
        densityDpi = configuration.densityDpi,
        smallestWidthDp = configuration.smallestScreenWidthDp,
        // The system's language, not the app's override (the process default follows the app's).
        locale = runCatching { languageTag(Resources.getSystem().configuration.locales[0]) }.getOrNull(),
        appLanguage = runCatching { applicationLanguage(context) }.getOrNull(),
    )
}

/**
 * The largest window the app can have on the default display: its full size, before any system
 * bars are taken off. Read through DisplayManager so no Activity (UI context) is needed.
 */
@Suppress("DEPRECATION")
private fun maximumScreenSize(context: Context): Point? {
    val display = context.getSystemService(DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY) ?: return null
    return Point().also(display::getRealSize)
}

/**
 * The per-app language chosen in Settings or the system's App languages page, or
 * [APP_LANGUAGE_SYSTEM]; null (left out of the report) when it cannot be read.
 */
private fun applicationLanguage(context: Context): String? {
    // Before Android 13 there is no per-app language: the app always follows the system.
    if (!isAppLanguagePickerSupported()) return APP_LANGUAGE_SYSTEM
    val locales = context.getSystemService(LocaleManager::class.java)?.applicationLocales ?: return null
    return if (locales.isEmpty) APP_LANGUAGE_SYSTEM else languageTag(locales[0])
}
