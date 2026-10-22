package moe.antimony.hoshi.epub

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ReadingPositionRepositoryTest {
    @get:Rule val temporary = TemporaryFolder()

    private suspend fun fixture(name: String): Pair<BookRepository, File> {
        val repository = BookRepository(temporary.newFolder(name), deviceIdentity = DeviceIdentity(name, name))
        val root = repository.createBookDirectoryForImportedTitle("Example")
        repository.saveMetadata(root, BookMetadata(name, "Example", null, root.name, 0.0, "example"))
        repository.saveBookmark(root, bookmark(100, 100.0))
        return repository to root
    }

    private fun bookmark(page: Int, time: Double) = Bookmark(page, 0.0, page + 1, time)

    @Test fun reopenAndRestoreDoNotCreateNavigation() = runBlocking {
        val (repository, root) = fixture("phone")
        val session = repository.openReadingPosition(root)
        assertNull(session.navigate(bookmark(100, 900.0)))
        assertEquals(bookmark(100, 100.0), repository.loadBookmark(root))
        assertTrue(repository.readingPositions.snapshot(root).pending.isEmpty())
        assertNull(repository.readingPositions.export("example"))
    }

    @Test fun staleReopenAdoptsOtherDeviceWithoutReauthoringIt() = runBlocking {
        val (phone, phoneRoot) = fixture("phone")
        val (tablet, tabletRoot) = fixture("tablet")
        val stale = phone.openReadingPosition(phoneRoot)
        val session = tablet.openReadingPosition(tabletRoot)
        tablet.recordReadingPosition(tabletRoot, session.navigate(bookmark(120, 200.0))!!)
        assertTrue(phone.readingPositions.merge(tablet.readingPositions.export("example")!!, phoneRoot))
        assertNull(stale.navigate(bookmark(100, 999.0)))
        assertEquals(120, phone.loadBookmark(phoneRoot)!!.chapterIndex)
        assertNull(phone.readingPositions.export("example"))
    }

    @Test fun delayedOldSaveCannotUndoARemoteWinner() = runBlocking {
        val (phone, phoneRoot) = fixture("phone")
        val (tablet, tabletRoot) = fixture("tablet")
        val pending = phone.openReadingPosition(phoneRoot).navigate(bookmark(101, 150.0))!!
        tablet.recordReadingPosition(tabletRoot, tablet.openReadingPosition(tabletRoot).navigate(bookmark(120, 200.0))!!)
        phone.readingPositions.merge(tablet.readingPositions.export("example")!!, phoneRoot)
        // This is the outgoing reader's already-captured action, persisted after remote reload.
        phone.recordReadingPosition(phoneRoot, pending)
        phone.saveBookmark(phoneRoot, bookmark(101, 150.0))
        assertEquals(120, phone.loadBookmark(phoneRoot)!!.chapterIndex)
        assertEquals(150.0, phone.readingPositions.snapshot(phoneRoot).events.single { it.id == pending.id }.occurredAt, 0.0)
        assertTrue(pending.id in phone.readingPositions.snapshot(phoneRoot).pending)
    }

    @Test fun deliberateRewindWinsEvenWithBackwardClockAndKeepsEveryAction() = runBlocking {
        val (repository, root) = fixture("phone")
        val session = repository.openReadingPosition(root)
        repository.recordReadingPosition(root, session.navigate(bookmark(120, 200.0))!!)
        val rewind = repository.openReadingPosition(root)
        for (page in 119 downTo 100) repository.recordReadingPosition(root, rewind.navigate(bookmark(page, 190.0))!!)
        val state = repository.readingPositions.snapshot(root)
        assertEquals(100, repository.loadBookmark(root)!!.chapterIndex)
        assertEquals(21, state.pending.size)
        assertEquals(190.0, state.winner()!!.occurredAt, 0.0)
        assertTrue(state.winner()!!.compatibilityAt > 200.0)
        assertEquals((119 downTo 100).toList(), state.events.filter { it.sessionId == state.winner()!!.sessionId }.map { it.position.chapterIndex })
    }

    @Test fun concurrentOfflineMergeIsCommutativeAndRetainsBothHistories() = runBlocking {
        val (phone, phoneRoot) = fixture("phone")
        val (tablet, tabletRoot) = fixture("tablet")
        phone.recordReadingPosition(phoneRoot, phone.openReadingPosition(phoneRoot).navigate(bookmark(101, 200.0))!!)
        tablet.recordReadingPosition(tabletRoot, tablet.openReadingPosition(tabletRoot).navigate(bookmark(120, 200.0))!!)
        val p = phone.readingPositions.export("example")!!
        val t = tablet.readingPositions.export("example")!!
        phone.readingPositions.merge(t, phoneRoot)
        tablet.readingPositions.merge(p, tabletRoot)
        assertEquals(phone.loadBookmark(phoneRoot), tablet.loadBookmark(tabletRoot))
        assertEquals(2, phone.readingPositions.snapshot(phoneRoot).events.count { it.action != "legacy" })
    }

    @Test fun processRestartPreservesPendingSequenceAndHistoryTrimsOnlyAcknowledgedEvents() = runBlocking {
        val files = temporary.newFolder("persistent")
        val repository = BookRepository(files, deviceIdentity = DeviceIdentity("phone", "Phone"))
        val root = repository.createBookDirectoryForImportedTitle("Example")
        repository.saveMetadata(root, BookMetadata("book", "Example", null, root.name, 0.0, "example"))
        val session = repository.openReadingPosition(root)
        for (page in 1..75) repository.recordReadingPosition(root, session.navigate(bookmark(page, 100.0 + page))!!)
        val before = repository.readingPositions.snapshot(root)
        assertEquals(75, before.pending.size)
        val reopened = BookRepository(files, deviceIdentity = DeviceIdentity("phone", "Phone"))
        val next = reopened.openReadingPosition(root).navigate(bookmark(76, 176.0))!!
        assertTrue(next.sequence > before.winner()!!.sequence)
        reopened.recordReadingPosition(root, next)
        reopened.readingPositions.acknowledge("example", before.pending)
        val after = reopened.readingPositions.snapshot(root)
        assertEquals(setOf(next.id), after.pending)
        assertEquals(50, after.events.size)
        assertEquals(76, after.winner()!!.position.chapterIndex)
    }

    @Test fun deletedBookIsNotResurrectedByAnAlreadyCapturedAction() = runBlocking {
        val (repository, root) = fixture("phone")
        val event = repository.openReadingPosition(root).navigate(bookmark(101, 150.0))!!
        root.deleteRecursively()
        repository.recordReadingPosition(root, event)
        assertFalse(root.exists())
        assertEquals(event.id, repository.readingPositions.export("example")!!.heads.single().id)
    }

    @Test fun acceptedLegacyTiePreservesCallerWinnerAndNegativeEpochDates() = runBlocking {
        val (repository, root) = fixture("phone")
        repository.saveBookmark(root, bookmark(120, 200.0))
        repository.saveBookmark(root, bookmark(90, 200.0))
        assertEquals(bookmark(90, 200.0), repository.loadBookmark(root))
        val negativeRoot = repository.createBookDirectoryForImportedTitle("Old book")
        repository.saveBookmark(negativeRoot, bookmark(10, -978307199.0))
        repository.saveBookmark(negativeRoot, bookmark(20, -978307198.0))
        assertEquals(20, repository.loadBookmark(negativeRoot)!!.chapterIndex)
    }

    @Test fun journalRecoversEqualTimeLegacyWinnerAfterInterruptedProjectionWrite() = runBlocking {
        val (repository, root) = fixture("phone")
        val old = bookmark(120, 200.0)
        repository.saveBookmark(root, old)
        repository.saveBookmark(root, bookmark(90, 200.0))
        // Simulate the journal having committed while bookmark.json still holds the old value.
        root.resolve("bookmark.json").writeText(kotlinx.serialization.json.Json.encodeToString(Bookmark.serializer(), old))
        assertEquals(90, repository.openReadingPosition(root).initialBookmark!!.chapterIndex)
        assertEquals(90, repository.loadBookmark(root)!!.chapterIndex)
    }

    @Test fun legacyMigrationFrontierRemainsBoundedAcrossManyUpdates() = runBlocking {
        val (repository, root) = fixture("phone")
        for (page in 101..400) repository.saveBookmark(root, bookmark(page, page.toDouble()))
        val state = repository.readingPositions.snapshot(root)
        assertEquals(1, state.heads.size)
        assertTrue(state.frontier().size < 5)
        assertNotNull(repository.openReadingPosition(root).navigate(bookmark(401, 401.0)))
    }

    @Test fun explicitCompletionObservesFutureClockPosition() = runBlocking {
        val (repository, root) = fixture("phone")
        repository.saveBookmark(root, bookmark(120, 900.0))
        repository.navigateReadingPosition(root, Bookmark(200, 1.0, 201, 800.0), "markRead")
        val winner = repository.readingPositions.snapshot(root).winner()!!
        assertEquals("markRead", winner.action)
        assertEquals(200, repository.loadBookmark(root)!!.chapterIndex)
        assertEquals(800.0, winner.occurredAt, 0.0)
        assertTrue(winner.compatibilityAt > 900.0)
    }

    @Test fun legacyProjectionIsStillExactlyTheReleasedBookmarkShape() = runBlocking {
        val (repository, root) = fixture("phone")
        val session = repository.openReadingPosition(root)
        repository.recordReadingPosition(root, session.navigate(bookmark(120, 200.0))!!)
        val objectValue = kotlinx.serialization.json.Json.parseToJsonElement(root.resolve("bookmark.json").readText())
            as kotlinx.serialization.json.JsonObject
        assertEquals(setOf("chapterIndex", "progress", "characterCount", "lastModified"), objectValue.keys)
        assertEquals(bookmark(120, 200.0), repository.loadBookmark(root))
    }
}
