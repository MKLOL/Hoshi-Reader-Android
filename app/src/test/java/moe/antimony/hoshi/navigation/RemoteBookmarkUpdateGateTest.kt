package moe.antimony.hoshi.navigation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The reader routes watch `remoteBookmarkUpdates` for their whole lifetime now that the
 * pre-open sync refresh is time-bounded; [RemoteBookmarkUpdateGate] keeps that from reloading
 * a route for a remote bookmark the in-progress load is about to read anyway.
 */
class RemoteBookmarkUpdateGateTest {
    @Test
    fun ignoresEverythingUntilTheLoadIsAboutToReadTheBookmark() {
        val gate = RemoteBookmarkUpdateGate()
        gate.expect("book-a")

        assertFalse("remote winner before the read is what the load opens on", gate.shouldReload("book-a"))
    }

    @Test
    fun reloadsOnlyTheExpectedBookOnceOpen() {
        val gate = RemoteBookmarkUpdateGate()
        gate.expect("book-a")
        gate.open()

        assertTrue(gate.shouldReload("book-a"))
        assertFalse("another book's bookmark never reloads this route", gate.shouldReload("book-b"))
    }

    @Test
    fun aRestartedLoadClosesTheGateAgain() {
        val gate = RemoteBookmarkUpdateGate()
        gate.expect("book-a")
        gate.open()
        gate.close()

        assertFalse(gate.shouldReload("book-a"))
        gate.open()
        assertTrue(gate.shouldReload("book-a"))
    }

    @Test
    fun aBookWithoutASyncIdNeverReloads() {
        val gate = RemoteBookmarkUpdateGate()
        gate.expect(null)
        gate.open()

        assertFalse(gate.shouldReload("book-a"))
    }
}
