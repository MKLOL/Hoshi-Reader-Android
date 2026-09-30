package moe.antimony.hoshi.features.sync.http

import android.app.Notification
import android.content.Context
import android.content.res.Configuration
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import moe.antimony.hoshi.features.sync.v3.BehaviorAction
import moe.antimony.hoshi.features.sync.v3.StubKvBehavior
import moe.antimony.hoshi.features.sync.v3.StubKvServer
import moe.antimony.hoshi.features.sync.v3.freshDevice
import moe.antimony.hoshi.ui.resolve
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.Locale
import kotlin.random.Random

class HttpSyncTransferProgressInstrumentedTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val temporary = TemporaryFolder()

    private fun context(locale: Locale): Context {
        val original = ApplicationProvider.getApplicationContext<Context>()
        return original.createConfigurationContext(Configuration(original.resources.configuration).apply { setLocale(locale) })
    }

    @Test fun progressShowsSpeedAndEtaThenClearsThemOutsideTransfers() {
        val english = context(Locale.US)
        val transfer = HttpSyncTransferProgress(2_000_000, 10_000_000, 1_250_000.0, 7)
        val state = mutableStateOf<SyncStatus>(SyncStatus.Running(HttpSyncProgress(transfer = transfer)))
        compose.setContent {
            CompositionLocalProvider(LocalContext provides english) {
                MaterialTheme { HttpSyncStatusLine(state.value) }
            }
        }
        compose.onNodeWithText("2.0 MB / 10.0 MB").assertIsDisplayed()
        compose.onNodeWithText("1.25 MB/s · ETA 0:07 for this file").assertIsDisplayed()
        compose.runOnIdle {
            state.value = SyncStatus.Running(HttpSyncProgress(transfer = transfer.copy(totalBytes = null, remainingSeconds = null)))
        }
        compose.onNodeWithText("1.25 MB/s · ETA unavailable").assertIsDisplayed()
        compose.runOnIdle { state.value = SyncStatus.Running(HttpSyncProgress(message = "Finishing")) }
        compose.onNodeWithText("MB/s", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Finishing").assertIsDisplayed()
    }

    @Test fun chineseAndNotificationUseTheSameLocalizedMetrics() {
        val chinese = context(Locale.SIMPLIFIED_CHINESE)
        val transfer = HttpSyncTransferProgress(2_000_000, 10_000_000, 1_250_000.0, 3661)
        val text = transfer.speedText().resolve(chinese)
        assertEquals("1.25 MB/s · 此文件预计剩余 1:01:01", text)
        val notification = HttpSyncBackgroundSync.notification(chinese, 20, text)
        assertEquals(text, notification.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString())
    }

    @Test fun bothEnginesReportMeasuredSpeedAndEtaFromRealDownloads() = runBlocking {
        StubKvServer().use { server ->
            server.start()
            val phone = freshDevice(server, "speed-phone", temporary.newFolder())
            phone.importMokuro("Measured transfer", Random(98).nextBytes(512 * 1024))
            assertTrue(phone.sync().errors.isEmpty())
            server.setBehavior(StubKvBehavior { method, path ->
                if (method == "GET" && path.endsWith("payload.zip")) BehaviorAction.Throttle(8 * 1024, 40)
                else BehaviorAction.Passthrough
            })
            for (v3 in listOf(false, true)) {
                val tablet = freshDevice(server, "speed-tablet-$v3", temporary.newFolder())
                val progress = mutableListOf<HttpSyncProgress>()
                val result = HttpSyncEngineDispatcher.syncOnce(
                    reconciler = HttpSyncReconciler(tablet.repo), v3Engine = tablet.engine,
                    settings = tablet.settings.copy(useV3Sync = v3), onProgress = { progress.add(it) },
                )
                assertTrue("Engine $v3: ${result.errors}", result.errors.isEmpty())
                val transfers = progress.mapNotNull { it.transfer }
                assertTrue("Engine $v3 never reported measured speed and ETA", transfers.any {
                    (it.bytesPerSecond ?: 0.0) > 0 && (it.remainingSeconds ?: 0) > 0
                })
                assertEquals(0L, transfers.last().remainingSeconds)
                assertEquals(transfers.last().totalBytes, transfers.last().transferredBytes)
                assertNull("Finished phase kept old transfer metrics", progress.last().transfer)
            }
        }
    }
}
