package moe.antimony.hoshi.navigation

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Returning to the app after a while imports from ッツ with the reader still shown, as on iOS.
 * The reader is rebuilt only when the import changed the book.
 */
class ReaderForegroundImportTest {
    private class Route {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val exports = ReaderAutoSyncExportController(scope)
        val foregroundImport = ReaderForegroundImport(scope, exports)
        val events = mutableListOf<String>()
        var reloads = 0

        /** A reader page turn while the import runs: it queues its export as usual. */
        fun readerQueuesExport() = exports.scheduleExport(true) { events += "export" }

        fun start(import: suspend () -> Boolean) = foregroundImport.start(
            import = import,
            exportsEnabled = { true },
            reload = { reloads++ },
        )
    }

    @Test
    fun anImportThatChangedNothingKeepsTheReaderAndThenSendsItsExport() = runBlocking {
        val route = Route()
        val finishImport = CompletableDeferred<Boolean>()
        val job = route.start { route.events += "import"; finishImport.await() }!!
        route.readerQueuesExport()
        // Nothing is exported over the external base while the import reads it.
        assertNull(route.exports.flushExport(true))
        finishImport.complete(false)
        withTimeout(1_000) { job.join() }
        assertEquals(0, route.reloads)
        route.exports.flushExport(true)?.join()
        assertEquals(listOf("import", "export"), route.events)
        route.scope.cancel()
    }

    @Test
    fun anImportedPositionOrMergedStatisticsReloadsTheReaderWithExportsStillHeld() = runBlocking {
        // The callback says "changed" both for a position and for statistics alone.
        val route = Route()
        val job = route.start { true }!!
        withTimeout(1_000) { job.join() }
        assertEquals(1, route.reloads)
        route.readerQueuesExport()
        // The reload's own load resumes exports once the new reader has its state.
        assertNull(route.exports.flushExport(true))
        route.exports.resumeExports(true)
        route.exports.flushExport(true)?.join()
        assertEquals(listOf("export"), route.events)
        route.scope.cancel()
    }

    @Test
    fun aFailedImportKeepsTheReaderAndResumesExports() = runBlocking {
        val route = Route()
        val job = route.start { throw java.io.IOException("offline") }!!
        withTimeout(1_000) { job.join() }
        assertEquals(0, route.reloads)
        route.readerQueuesExport()
        route.exports.flushExport(true)?.join()
        assertEquals(listOf("export"), route.events)
        route.scope.cancel()
    }

    @Test
    fun aReloadRequestedDuringTheImportSupersedesItAndAnotherReturnDoesNotStartASecond() = runBlocking {
        val route = Route()
        val finishImport = CompletableDeferred<Boolean>()
        var imports = 0
        val job = route.start { imports++; finishImport.await() }!!
        assertNull("one import at a time", route.start { imports++; false })
        // A remote position reloads the reader meanwhile; that reload makes its own import.
        route.foregroundImport.cancel()
        finishImport.complete(true)
        withTimeout(1_000) { job.join() }
        assertEquals(1, imports)
        assertEquals(0, route.reloads)
        // Exports stay held for the superseding reload to resume.
        route.readerQueuesExport()
        assertNull(route.exports.flushExport(true))
        route.scope.cancel()
    }
}
