package moe.antimony.hoshi.features.sync.http

import android.app.job.JobScheduler
import android.os.Build
import android.os.PowerManager
import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import moe.antimony.hoshi.HoshiApplication
import moe.antimony.hoshi.features.sync.v3.BehaviorAction
import moe.antimony.hoshi.features.sync.v3.StubKvBehavior
import moe.antimony.hoshi.features.sync.v3.StubKvServer
import moe.antimony.hoshi.features.sync.v3.freshDevice
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.TimeUnit
import java.util.UUID
import kotlin.random.Random

/** Run only on the release gate's newly owned emulator: changes screen power during a real transfer. */
class HttpSyncSleepTransferTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun manualBookDownloadContinuesAndFinishesWithScreenOff() = runBlocking {
        assertDownloadContinuesWithScreenOff(useWorker = false)
    }

    @Test
    fun foregroundWorkerDownloadContinuesAndFinishesWithScreenOff() = runBlocking {
        assertDownloadContinuesWithScreenOff(useWorker = true)
    }

    private suspend fun assertDownloadContinuesWithScreenOff(useWorker: Boolean) {
        val application = ApplicationProvider.getApplicationContext<HoshiApplication>()
        val container = application.appContainer
        val power = application.getSystemService(PowerManager::class.java)
        val workManager = WorkManager.getInstance(application)
        var workId: UUID? = null
        val bytes = Random(712).nextBytes(2 * 1024 * 1024)
        val title = "Screen off ${if (useWorker) "worker" else "manual"} transfer regression"
        StubKvServer().use { server ->
            server.start()
            val phone = freshDevice(server, "phone", temporary.newFolder())
            phone.importMokuro(title, bytes)
            assertTrue(phone.sync().errors.isEmpty())
            val delivered = AtomicLong()
            server.setBehavior(StubKvBehavior { method, path ->
                if (method == "GET" && path.endsWith("payload.zip")) {
                    BehaviorAction.Throttle(8 * 1024, 40) { delivered.addAndGet(it.toLong()) }
                } else BehaviorAction.Passthrough
            })
            try {
                shell("input keyevent 224")
                compose.setContent { Text("Transfer test") }
                container.httpSyncSettingsRepository.update {
                    it.copy(baseUrl = server.baseUrl, bearerToken = server.token, lastSyncedAt = null)
                }
                if (useWorker) {
                    val work = OneTimeWorkRequestBuilder<HttpSyncTransferWorker>().build()
                    workId = work.id
                    workManager.enqueue(work).result.get(10, TimeUnit.SECONDS)
                } else {
                    compose.runOnIdle { container.httpSyncManualSync.start() }
                }
                withTimeout(30_000) { while (delivered.get() < 64 * 1024) delay(50) }
                withTimeout(10_000) {
                    while (((container.httpSyncManualSync.status.value as? SyncStatus.Running)
                            ?.progress?.transfer?.bytesPerSecond ?: 0.0) <= 0.0) delay(50)
                }
                if (!useWorker && Build.VERSION.SDK_INT >= 34) {
                    val job = application.getSystemService(JobScheduler::class.java)
                        .getPendingJob(HttpSyncBackgroundSync.JOB_ID)
                    assertNotNull("Manual sync has no Android job", job)
                    assertTrue("Manual sync is not a user-initiated transfer", job!!.isUserInitiated)
                }
                shell("input keyevent 223")
                withTimeout(5_000) { while (power.isInteractive) delay(50) }
                val before = delivered.get()
                withTimeout(20_000) { while (delivered.get() <= before + 128 * 1024) delay(50) }
                assertFalse("Screen unexpectedly woke during transfer", power.isInteractive)
                withTimeout(90_000) {
                    while (container.httpSyncManualSync.status.value is SyncStatus.Running) delay(100)
                }
                val status = container.httpSyncManualSync.status.value
                assertTrue("Sync did not complete: $status", status is SyncStatus.Done)
                assertTrue((status as SyncStatus.Done).result.errors.toString(), status.result.errors.isEmpty())
                workId?.let { id ->
                    val work = withTimeout(10_000) {
                        workManager.getWorkInfoByIdFlow(id).filterNotNull().first { it.state.isFinished }
                    }
                    assertEquals("Foreground worker did not complete successfully", WorkInfo.State.SUCCEEDED, work.state)
                }
                assertFalse("Completion required waking the screen", power.isInteractive)
                val book = container.bookRepository.loadBookEntries().single { it.metadata.title == title }
                assertArrayEquals(bytes, book.root.resolve("pages/p1.png").readBytes())
            } finally {
                shell("input keyevent 224")
                workId?.let { workManager.cancelWorkById(it).result.get(10, TimeUnit.SECONDS) }
                if (!useWorker && Build.VERSION.SDK_INT >= 34) {
                    application.getSystemService(JobScheduler::class.java).cancel(HttpSyncBackgroundSync.JOB_ID)
                }
                // A failed assertion must not leave a throttled transfer writing into the next case.
                container.httpSyncFullCycleRunner.cancelActive()
                withTimeout(10_000) {
                    while (container.httpSyncManualSync.status.value is SyncStatus.Running) delay(50)
                }
                container.httpSyncSettingsRepository.update { it.copy(bearerToken = "") }
                container.bookRepository.loadBookEntries().filter { it.metadata.title == title }.forEach {
                    container.bookRepository.deleteBook(it.root)
                }
            }
        }
    }

    private fun shell(command: String) {
        android.os.ParcelFileDescriptor.AutoCloseInputStream(
            InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command),
        ).use { it.readBytes() }
    }
}
