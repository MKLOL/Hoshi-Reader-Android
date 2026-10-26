package moe.antimony.hoshi.features.anonymousstats

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import java.io.File

/** Every key the server accepts. `audiobookCount` is left out: counting it means reading every book's playback file. */
internal val CONTRACT_KEYS = setOf(
    "installId", "platform",
    "appVersion", "versionCode", "buildType",
    "osVersion", "sdkInt", "manufacturer", "model", "emulator",
    "screenWidthPx", "screenHeightPx", "densityDpi", "smallestWidthDp",
    "locale", "appLanguage",
    "epubCount", "mangaCount", "cloudOnlyCount", "dictionaryCount",
    "httpSyncEnabled", "driveSyncEnabled", "readerContinuous", "readerVertical",
)

internal val UUID_V4 = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")

internal fun fullDevice() = AnonymousStatsDevice(
    appVersion = "0.12.5",
    versionCode = 1205,
    buildType = "release",
    osVersion = "14",
    sdkInt = 34,
    manufacturer = "Google",
    model = "Pixel 7",
    emulator = false,
    screenWidthPx = 2400,
    screenHeightPx = 1080,
    densityDpi = 420,
    smallestWidthDp = 411,
    locale = "en-US",
    appLanguage = "zh-CN",
)

internal fun fullLibrary() = AnonymousStatsLibrary(epubCount = 4, mangaCount = 12, cloudOnlyCount = 3, dictionaryCount = 5)

internal fun fullFlags() = AnonymousStatsFlags(httpSyncEnabled = true, driveSyncEnabled = false, readerContinuous = false, readerVertical = true)

/**
 * One installation's private storage as the reporter sees it: `files/` (backed up, holds the
 * on/off setting) and `no_backup/` (never backed up, holds the ID and times), with its own clock
 * and package install time.
 */
internal class StatsInstall(val dataDir: File, var installedAt: Long? = INSTALLED_AT) : AutoCloseable {
    val filesDir: File = dataDir.resolve("files").apply { mkdirs() }
    val noBackup: File = dataDir.resolve("no_backup").apply { mkdirs() }
    val install = AnonymousStatsInstall.inNoBackupDirectory(noBackup)
    var clock = START_MILLIS
    var collected = 0
        private set
    private val dataStoreScope = CoroutineScope(Dispatchers.IO + Job())
    val settings: AnonymousStatsSettingsRepository by lazy {
        AnonymousStatsSettingsRepository(
            PreferenceDataStoreFactory.create(
                scope = dataStoreScope,
                produceFile = { filesDir.resolve("datastore/anonymous-stats-settings.preferences_pb") },
            ),
        )
    }

    fun reporter(
        transport: AnonymousStatsTransport,
        scope: CoroutineScope = dataStoreScope,
        waitBeforeReport: suspend () -> Unit = {},
        automated: Boolean = false,
        collect: suspend (String) -> AnonymousStatsPayload = { id -> anonymousStatsPayload(id, fullDevice(), fullLibrary(), fullFlags()) },
    ) = AnonymousStatsReporter(
        settingsRepository = settings,
        install = install,
        collect = { id -> collected += 1; collect(id) },
        transport = transport,
        isAutomatedRun = { automated },
        installedAt = { installedAt },
        scope = scope,
        waitBeforeReport = waitBeforeReport,
        now = { clock },
        ioDispatcher = Dispatchers.Unconfined,
    )

    /** The feature's first check only starts the wait for the first report; this lets that day pass. */
    suspend fun passFirstDay(reporter: AnonymousStatsReporter) {
        assertEquals(AnonymousStatsOutcome.NotDue, reporter.reportIfDue())
        clock += AnonymousStatsReporter.FIRST_REPORT_DELAY_MILLIS
    }

    /** What a backup or device transfer carries to another device: everything but `no_backup/`. */
    fun restoreOnto(target: File, installedAt: Long): StatsInstall {
        filesDir.copyRecursively(target.resolve("files"), overwrite = true)
        return StatsInstall(target, installedAt).also { it.clock = clock }
    }

    fun storedFiles(): List<File> = noBackup.walk().filter { it.isFile }.toList()

    override fun close() {
        dataStoreScope.cancel()
    }

    companion object {
        const val INSTALLED_AT = 1_700_000_000_000L
        const val START_MILLIS = 1_800_000_000_000L
    }
}

/** Answers each report with the next status (or throws it), then with the last one again. */
internal class ScriptedTransport(private vararg val responses: () -> Int = arrayOf({ 204 })) : AnonymousStatsTransport {
    val urls = mutableListOf<String>()
    val bodies = mutableListOf<String>()

    override suspend fun post(url: String, json: String): Int {
        urls += url
        bodies += json
        return responses[minOf(bodies.size, responses.size) - 1]()
    }
}
