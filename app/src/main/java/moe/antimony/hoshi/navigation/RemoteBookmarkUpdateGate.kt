package moe.antimony.hoshi.navigation

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Decides whether a `remoteBookmarkUpdates` event must reload an open reader route.
 *
 * A route load calls [expect] as soon as it knows the book's sync id, then [open] immediately
 * before it reads the bookmark file. Until then a remote winner written by the sync layer is
 * simply what the load reads, so reloading would only flash the spinner and redo the pre-open
 * refresh; from the read onwards a remote winner can no longer reach the load and must reload
 * the route. [close] is called when a (re)load starts so events during it are judged again.
 *
 * Plain atomics rather than snapshot state: the loader touches this from an I/O dispatcher and
 * the collector from the main thread, and nothing is read during composition.
 */
internal class RemoteBookmarkUpdateGate {
    private val expectedSyncId = AtomicReference<String?>(null)
    private val open = AtomicBoolean(false)

    fun expect(syncId: String?) {
        expectedSyncId.set(syncId)
    }

    fun close() {
        open.set(false)
    }

    fun open() {
        open.set(true)
    }

    fun shouldReload(changedSyncId: String): Boolean =
        open.get() && changedSyncId == expectedSyncId.get()
}
