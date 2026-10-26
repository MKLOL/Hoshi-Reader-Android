package moe.antimony.hoshi.navigation

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderAutoSyncExportControllerTest {
    @Test
    fun anImportWaitsForTheRunningExportAndDefersTheNextExportUntilResume() = runBlocking {
        val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val controller = ReaderAutoSyncExportController(appScope)
        val releaseExport = CompletableDeferred<Unit>()
        val firstStarted = CompletableDeferred<Unit>()
        val nextExported = CompletableDeferred<Unit>()
        controller.scheduleExport(true) { firstStarted.complete(Unit); releaseExport.await() }
        controller.flushExport(true)
        firstStarted.await()
        val paused = CompletableDeferred<Unit>()
        val import = launch {
            controller.pauseExportsForImport()
            paused.complete(Unit)
        }
        kotlinx.coroutines.yield()
        assertFalse(paused.isCompleted)
        controller.scheduleExport(true) { nextExported.complete(Unit) }
        assertEquals(null, controller.flushExport(true))
        releaseExport.complete(Unit)
        import.join()
        assertTrue(paused.isCompleted)
        assertFalse(nextExported.isCompleted)
        controller.resumeExports(true)
        controller.flushExport(true)?.join()
        assertTrue(nextExported.isCompleted)
        appScope.cancel()
    }

    @Test
    fun aRequestedReloadKeepsTheRetiringReadersFlushFromExportingBeforeTheImport() = runBlocking {
        val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val controller = ReaderAutoSyncExportController(appScope)
        var exports = 0
        controller.holdExports()
        // Retirement saves statistics and disposal flushes, all before the reload coroutine runs.
        controller.scheduleExport(true) { exports++ }
        assertEquals(null, controller.flushExport(true))
        controller.pauseExportsForImport()
        assertEquals(0, exports)
        controller.resumeExports(true)
        controller.flushExport(true)?.join()
        assertEquals(1, exports)
        appScope.cancel()
    }

    @Test
    fun leavingDuringAReloadStillExportsTheRetiringReadersQueueOnlyAfterItsImport() = runBlocking {
        val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val routeJob = Job()
        val routeScope = CoroutineScope(routeJob + Dispatchers.Unconfined)
        val controller = ReaderAutoSyncExportController(appScope)
        val events = mutableListOf<String>()
        val finishRetiringSave = CompletableDeferred<Unit>()
        // reloadReader(): hold, then the retiring reader saves its final state and queues it.
        controller.holdExports()
        controller.launchSave { finishRetiringSave.await(); events += "save" }
        controller.scheduleExport(true) { events += "export" }
        assertEquals(null, controller.flushExport(true))
        // The reload's load starts, then the route is left while its spinner shows.
        routeScope.launch {
            controller.pauseExportsForImport()
            kotlinx.coroutines.awaitCancellation()
        }
        routeJob.cancel()
        val released = controller.releaseAbandonedReload(true) { events += "import" }
        kotlinx.coroutines.yield()
        assertEquals("the import reads the local base only after the retiring save", emptyList<String>(), events)
        finishRetiringSave.complete(Unit)
        withTimeout(1_000) { released!!.join() }
        assertEquals(listOf("save", "import", "export"), events)
        // The queue is drained: nothing is exported twice.
        assertEquals(null, controller.flushExport(true))
        appScope.cancel()
    }

    @Test
    fun anAbandonedReloadWhoseImportFailsExportsNothing() = runBlocking {
        val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val controller = ReaderAutoSyncExportController(appScope)
        var exports = 0
        controller.holdExports()
        controller.scheduleExport(true) { exports++ }
        val released = controller.releaseAbandonedReload(true) { throw java.io.IOException("offline") }
        withTimeout(1_000) { released!!.join() }
        // Exporting without having read the external base could replace a newer one.
        assertEquals(0, exports)
        appScope.cancel()
    }

    @Test
    fun leavingAShownReaderLeavesItsOwnFlushAloneAndImportsNothing() = runBlocking {
        val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val controller = ReaderAutoSyncExportController(appScope)
        var exports = 0
        var imports = 0
        controller.pauseExportsForImport()
        controller.resumeExports(true)
        controller.scheduleExport(true) { exports++ }
        // The shown reader's disposal flushes before the route's own disposal runs.
        controller.flushExport(true)?.join()
        assertEquals(null, controller.releaseAbandonedReload(true) { imports++ })
        assertEquals(1, exports)
        assertEquals(0, imports)
        appScope.cancel()
    }

    @Test
    fun flushExportSurvivesCallerScopeCancellationAndWaitsForLatestSave() = runBlocking {
        val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val callerJob = Job()
        val callerScope = CoroutineScope(callerJob + Dispatchers.Unconfined)
        val controller = ReaderAutoSyncExportController(appScope)
        val allowSave = CompletableDeferred<Unit>()
        val saved = CompletableDeferred<Unit>()
        val exportedAfterSave = CompletableDeferred<Boolean>()

        callerScope.launch {
            controller.launchSave {
                allowSave.await()
                saved.complete(Unit)
            }
            controller.scheduleExport(enabled = true) {
                exportedAfterSave.complete(saved.isCompleted)
            }
            controller.flushExport(enabled = true)
        }.join()

        callerJob.cancel()
        assertFalse(exportedAfterSave.isCompleted)

        allowSave.complete(Unit)

        assertTrue(withTimeout(1_000) { exportedAfterSave.await() })
        appScope.cancel()
    }
}
