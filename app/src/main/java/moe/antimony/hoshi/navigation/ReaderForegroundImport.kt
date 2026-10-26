package moe.antimony.hoshi.navigation

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

/**
 * The ッツ import made when the app returns to the foreground after a while. As on iOS
 * (`ReaderViewModel.syncAfterForeground`), it runs with the reader still shown: only an import
 * that changed the book here (an imported position, or statistics merged in without moving it)
 * reloads the reader, which then starts from the imported state. Otherwise the reader just goes
 * on and keeps its popups, selection and link history.
 *
 * Exports wait for the import, so this device's state never replaces the external base before
 * the import has read it. The shown reader may keep writing meanwhile: imports touch only the
 * shared ッツ bucket and other devices' entries, which merge by their newer stamps, and its
 * position writes are new actions, which the position history orders after the import.
 */
internal class ReaderForegroundImport(
    private val scope: CoroutineScope,
    private val exports: ReaderAutoSyncExportController,
) {
    private var running: Job? = null

    /**
     * Starts [import], which returns whether it changed this book's position or statistics, unless
     * one is already running. A failed import changes nothing and exports resume.
     */
    fun start(
        import: suspend () -> Boolean,
        exportsEnabled: () -> Boolean,
        reload: () -> Unit,
    ): Job? {
        if (running?.isActive == true) return null
        exports.holdExports()
        return scope.launch {
            exports.pauseExportsForImport()
            val changed = try {
                import()
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                false
            }
            // A reload requested meanwhile superseded this import and resumes exports itself.
            coroutineContext.ensureActive()
            if (changed) reload() else exports.resumeExports(exportsEnabled())
        }.also { running = it }
    }

    /** A reload replaces the reader and makes its own import. */
    fun cancel() {
        running?.cancel()
        running = null
    }
}
