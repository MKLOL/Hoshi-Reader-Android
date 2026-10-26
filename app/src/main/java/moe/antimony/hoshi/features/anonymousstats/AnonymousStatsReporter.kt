package moe.antimony.hoshi.features.anonymousstats

import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.random.Random

internal enum class AnonymousStatsOutcome {
    /** The server accepted the report (2xx). */
    Sent,
    /** The server refused it in a way a retry cannot fix (3xx, or 4xx other than 408/429); counted as sent. */
    Rejected,
    /** Not sent; tried again after [AnonymousStatsReporter.RETRY_INTERVAL_MILLIS]. */
    Failed,
    NotDue,
    Disabled,
    AutomatedRun,
}

/**
 * The anonymous usage report. Each time the app comes to the foreground, [onForeground] waits a
 * random 60–300 s (so the report cannot be matched to the sync pull that starts at the same
 * moment) and then sends at most one report per [SEND_INTERVAL_MILLIS]. An install's first report
 * waits [FIRST_REPORT_DELAY_MILLIS], so people who update can read the release notes and opt out
 * first. Nothing is recorded until a report is handled, so a process that dies first tries again
 * on the next launch. Nothing here throws.
 */
internal class AnonymousStatsReporter(
    private val settingsRepository: AnonymousStatsSettingsRepository,
    private val install: AnonymousStatsInstall,
    private val collect: suspend (installId: String) -> AnonymousStatsPayload,
    private val transport: AnonymousStatsTransport,
    private val isAutomatedRun: () -> Boolean,
    /** The package's `firstInstallTime`, or null when it cannot be read. */
    private val installedAt: () -> Long?,
    private val scope: CoroutineScope,
    private val waitBeforeReport: suspend () -> Unit = { delay(randomStartDelayMillis()) },
    private val now: () -> Long = System::currentTimeMillis,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    // Guards the setting check, the stored state and the in-flight job, never the request
    // itself, so turning statistics off is not held up by the network.
    private val lock = Mutex()
    private var pending: Job? = null

    /** Called on every background → foreground transition; one report waits at a time. */
    fun onForeground(): Job = synchronized(this) {
        pending?.takeIf { it.isActive }?.let { return it }
        scope.launch(ioDispatcher) {
            waitBeforeReport()
            reportIfDue()
        }.also { pending = it }
    }

    suspend fun reportIfDue(): AnonymousStatsOutcome = withContext(ioDispatcher) {
        try {
            if (isAutomatedRun()) return@withContext AnonymousStatsOutcome.AutomatedRun
            val installId = when (val gate = lock.withLock { admit() }) {
                is Gate.Closed -> return@withContext gate.outcome
                is Gate.Open -> gate.installId
            }
            send(installId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Log.w(TAG, "Anonymous usage statistics were not sent", error)
            AnonymousStatsOutcome.Failed
        }
    }

    /**
     * The Settings switch. The stored setting decides inside the lock, so a quick on/off/on
     * ends in the state that was saved last: off cancels a waiting or running report and
     * deletes this install's ID, on starts a new one.
     */
    suspend fun setEnabled(enabled: Boolean) {
        try {
            settingsRepository.update { it.copy(enabled = enabled) }
            withContext(ioDispatcher) {
                lock.withLock {
                    if (isEnabled()) {
                        val state = currentState()
                        if (state?.installId == null) {
                            install.write(
                                (state ?: AnonymousStatsState(installedAt = installedAt())).copy(installId = install.newInstallId()),
                            )
                        }
                    } else {
                        synchronized(this@AnonymousStatsReporter) { pending?.cancel() }
                        install.clear()
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            // The next report creates a missing ID, or deletes one left behind while off.
            Log.w(TAG, "Could not change anonymous usage statistics", error)
        }
    }

    /** Under [lock]: the ID to report under now, or why not. */
    private suspend fun admit(): Gate {
        if (!isEnabled()) {
            // Off means no ID on this device, even one an interrupted opt-out left behind.
            install.clear()
            return Gate.Closed(AnonymousStatsOutcome.Disabled)
        }
        val now = now()
        val state = currentState()
        if (state == null) {
            // The feature's first run on this install (new, updated, restored or cloned).
            install.write(AnonymousStatsState(installedAt = installedAt(), notBefore = now + FIRST_REPORT_DELAY_MILLIS))
            return Gate.Closed(AnonymousStatsOutcome.NotDue)
        }
        // A clock set back past these times does not hold reports back until it catches up.
        val waiting = state.notBefore?.let { (it - now) in 1..FIRST_REPORT_DELAY_MILLIS } == true ||
            state.lastSentAt?.let { (now - it) in 0 until SEND_INTERVAL_MILLIS } == true ||
            state.lastAttemptAt?.let { (now - it) in 0 until RETRY_INTERVAL_MILLIS } == true
        if (waiting) return Gate.Closed(AnonymousStatsOutcome.NotDue)
        val installId = state.installId ?: install.newInstallId().also { install.write(state.copy(installId = it)) }
        return Gate.Open(installId)
    }

    private suspend fun send(installId: String): AnonymousStatsOutcome {
        val status = try {
            val body = collect(installId).toJson()
            // Turning statistics off while the report was built withdraws it.
            if (!lock.withLock { isEnabled() && install.installId() == installId }) return AnonymousStatsOutcome.Disabled
            transport.post(ENDPOINT, body)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Log.w(TAG, "Anonymous usage statistics were not sent", error)
            null
        }
        // 2xx, and 3xx/4xx a retry would only repeat, count as handled; the rest backs off.
        val handled = status != null && status in 200..499 && status != 408 && status != 429
        val at = now()
        lock.withLock {
            val state = install.state()
            // Only for the ID that sent it: an opt-out meanwhile left nothing to record.
            if (state?.installId == installId) {
                install.write(
                    if (handled) state.copy(notBefore = null, lastSentAt = at, lastAttemptAt = null) else state.copy(lastAttemptAt = at),
                )
            }
        }
        return when {
            status != null && status in 200..299 -> AnonymousStatsOutcome.Sent
            handled -> AnonymousStatsOutcome.Rejected
            else -> AnonymousStatsOutcome.Failed
        }
    }

    private suspend fun isEnabled(): Boolean = settingsRepository.settings.first().enabled

    /** The stored state, unless a phone-clone tool copied it from another install. */
    private fun currentState(): AnonymousStatsState? {
        val state = install.state() ?: return null
        val installedAt = installedAt()
        if (installedAt != null && state.installedAt != installedAt) {
            install.clear()
            return null
        }
        return state
    }

    private sealed interface Gate {
        data class Open(val installId: String) : Gate
        data class Closed(val outcome: AnonymousStatsOutcome) : Gate
    }

    companion object {
        /** Fixed: reports never follow the HTTP sync server the user configured. */
        const val ENDPOINT = "https://dragos.games/api/book_sync/v1/stats"
        const val SEND_INTERVAL_MILLIS = 20L * 60 * 60 * 1000
        const val FIRST_REPORT_DELAY_MILLIS = 20L * 60 * 60 * 1000
        const val RETRY_INTERVAL_MILLIS = 60L * 60 * 1000
        private const val TAG = "AnonymousStats"
    }
}

/** 60–300 s after the app comes to the foreground. */
internal fun randomStartDelayMillis(random: Random = Random.Default): Long = random.nextLong(60_000, 300_001)

internal fun interface AnonymousStatsTransport {
    /** POSTs [json] to [url] and returns the HTTP status. Throws on a network failure. */
    suspend fun post(url: String, json: String): Int
}

/**
 * Plain JSON POST: no Authorization header, no sync token and no cookies (OkHttp keeps none by
 * default). Redirects are not followed, so the body never reaches another server. Cancelling the
 * calling coroutine cancels the request.
 */
internal class OkHttpAnonymousStatsTransport(
    clientFactory: () -> OkHttpClient = ::anonymousStatsHttpClient,
) : AnonymousStatsTransport {
    // Built on the first report, on the IO thread, not while the app starts.
    private val client by lazy(clientFactory)

    override suspend fun post(url: String, json: String): Int {
        val request = Request.Builder()
            .url(url)
            // A byte body keeps the content type exactly `application/json` (no charset suffix).
            .post(json.toByteArray(Charsets.UTF_8).toRequestBody(JSON_MEDIA_TYPE))
            .build()
        return suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    continuation.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use { continuation.resume(it.code) }
                }
            })
        }
    }

    private companion object {
        val JSON_MEDIA_TYPE = "application/json".toMediaType()
    }
}

internal fun anonymousStatsHttpClient(): OkHttpClient =
    OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

/**
 * Test runs never report: instrumentation (androidTest registers with AndroidX Test), a device in
 * test-harness mode, the monkey, and Firebase Test Lab (which also runs Play pre-launch reports).
 * JVM unit tests never build the app's reporter at all.
 */
internal fun isTestAutomationRun(context: Context): Boolean =
    isInTestHarness() ||
        ActivityManager.isUserAMonkey() ||
        runCatching { Settings.System.getString(context.contentResolver, "firebase.test.lab") == "true" }.getOrDefault(false) ||
        isInstrumented()

/** When this package was first installed on the device; app updates keep it, a reinstall or clone changes it. */
internal fun packageFirstInstallTime(context: Context): Long? =
    runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(context.packageName, 0)
        }.firstInstallTime
    }.getOrNull()

// Android 10 renamed the user-facing mode; the older check still reports `ro.test_harness` devices.
@Suppress("DEPRECATION")
private fun isInTestHarness(): Boolean =
    ActivityManager.isRunningInTestHarness() ||
        (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && ActivityManager.isRunningInUserTestHarness())

private fun isInstrumented(): Boolean =
    runCatching {
        // Absent from the app's own build; present and registered only under AndroidJUnitRunner.
        Class.forName("androidx.test.platform.app.InstrumentationRegistry")
            .getMethod("getInstrumentation")
            .invoke(null) != null
    }.getOrDefault(false)
