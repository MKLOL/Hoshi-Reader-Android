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

    @Test fun processRestartPreservesPendingSequenceAndTheOutboxStaysBounded() = runBlocking {
        val files = temporary.newFolder("persistent")
        val repository = BookRepository(files, deviceIdentity = DeviceIdentity("phone", "Phone"))
        val root = repository.createBookDirectoryForImportedTitle("Example")
        repository.saveMetadata(root, BookMetadata("book", "Example", null, root.name, 0.0, "example"))
        val session = repository.openReadingPosition(root)
        for (page in 1..75) repository.recordReadingPosition(root, session.navigate(bookmark(page, 100.0 + page))!!)
        val before = repository.readingPositions.snapshot(root)
        // Receivers keep 50 events per device; without sync the outbox must not grow past them.
        assertEquals(50, before.pending.size)
        assertEquals(before.pending, repository.readingPositions.ownHistories()["example"])
        val reopened = BookRepository(files, deviceIdentity = DeviceIdentity("phone", "Phone"))
        // A new process rebuilds the outbox from disk before any book is opened.
        assertEquals(before.pending, reopened.readingPositions.ownHistories()["example"])
        val next = reopened.openReadingPosition(root).navigate(bookmark(76, 176.0))!!
        assertTrue(next.sequence > before.winner()!!.sequence)
        reopened.recordReadingPosition(root, next)
        reopened.readingPositions.acknowledge("example", before.pending)
        val after = reopened.readingPositions.snapshot(root)
        assertEquals(setOf(next.id), after.pending)
        assertEquals(50, after.events.size)
        assertEquals(76, after.winner()!!.position.chapterIndex)
    }

    @Test fun anUnreadableJournalNeverMakesTheBookUnopenable() = runBlocking {
        val files = temporary.newFolder("damaged")
        val repository = BookRepository(files, deviceIdentity = DeviceIdentity("phone", "Phone"))
        val root = repository.createBookDirectoryForImportedTitle("Example")
        repository.saveMetadata(root, BookMetadata("book", "Example", null, root.name, 0.0, "example"))
        repository.saveBookmark(root, bookmark(100, 100.0))
        val lost = repository.openReadingPosition(root).navigate(bookmark(120, 200.0))!!
        repository.recordReadingPosition(root, lost)
        val journals = files.resolve("ReadingPositions")
        journals.listFiles()!!.filter { it.name.startsWith("book-") }.forEach { it.writeText("{\"truncated") }
        val reopened = BookRepository(files, deviceIdentity = DeviceIdentity("phone", "Phone"))
        assertEquals(120, reopened.openReadingPosition(root).initialBookmark!!.chapterIndex)
        val next = reopened.openReadingPosition(root).navigate(bookmark(121, 210.0))!!
        // The lost journal hid how far the old counter got: new events use a new one.
        assertNotEquals(lost.originId, next.originId)
        reopened.recordReadingPosition(root, next)
        assertEquals(121, reopened.loadBookmark(root)!!.chapterIndex)
        assertEquals(mapOf("example" to setOf(next.id)), reopened.readingPositions.ownHistories())
    }

    @Test fun anUnreadableOriginStartsANewCounter() = runBlocking {
        val files = temporary.newFolder("origin")
        val repository = BookRepository(files, deviceIdentity = DeviceIdentity("phone", "Phone"))
        val root = repository.createBookDirectoryForImportedTitle("Example")
        repository.saveMetadata(root, BookMetadata("book", "Example", null, root.name, 0.0, "example"))
        val first = repository.openReadingPosition(root).navigate(bookmark(120, 200.0))!!
        repository.recordReadingPosition(root, first)
        files.resolve("ReadingPositions/origin").writeText("")
        val reopened = BookRepository(files, deviceIdentity = DeviceIdentity("phone", "Phone"))
        val next = reopened.openReadingPosition(root).navigate(bookmark(121, 210.0))!!
        assertNotEquals(first.originId, next.originId)
        reopened.recordReadingPosition(root, next)
        assertEquals(121, reopened.loadBookmark(root)!!.chapterIndex)
    }

    @Test fun anAbsurdStoredTimestampCannotBreakLaterNavigation() = runBlocking {
        val (repository, root) = fixture("phone")
        root.resolve("bookmark.json").writeText(
            """{"chapterIndex":3,"progress":0.0,"characterCount":4,"lastModified":1.0E16}""",
        )
        val session = repository.openReadingPosition(root)
        repository.recordReadingPosition(root, session.navigate(bookmark(4, 500.0))!!)
        assertEquals(4, repository.loadBookmark(root)!!.chapterIndex)
    }

    @Test fun outOfRangeLegacyPositionsAreClampedInsteadOfRejected() = runBlocking {
        val (repository, root) = fixture("phone")
        root.resolve("bookmark.json").writeText(
            """{"chapterIndex":-3,"progress":1.0000001,"characterCount":-1,"lastModified":300.0}""",
        )
        assertEquals(Bookmark(0, 1.0, 0, 300.0), repository.openReadingPosition(root).initialBookmark)
        // Another device's map entry arrives through the same legacy path.
        repository.saveBookmark(root, Bookmark(4, -0.5, 5, 400.0))
        assertEquals(Bookmark(4, 0.0, 5, 400.0), repository.loadBookmark(root))
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
    @Test fun aSamePageResaveFromAClockAheadDoesNotSwallowTheNextPageTurns() = runBlocking {
        val (repository, root) = fixture("phone")
        val session = repository.openReadingPosition(root)
        repository.recordReadingPosition(root, session.navigate(bookmark(120, 200.0))!!)
        // An older build whose clock runs ahead re-saves the page this reader is showing.
        repository.saveBookmark(root, bookmark(120, 500.0))
        // This device's clock is behind that stamp, yet the reader moved on from the page.
        repository.recordReadingPosition(root, session.navigate(bookmark(121, 300.0))!!)
        repository.recordReadingPosition(root, session.navigate(bookmark(122, 310.0))!!)
        val saved = repository.loadBookmark(root)!!
        assertEquals(122, saved.chapterIndex)
        // The stamp older clients order by comes after the re-save, so they take the move too.
        assertTrue(saved.lastModified!! > 500.0)
        assertEquals(310.0, repository.readingPositions.snapshot(root).winner()!!.occurredAt, 0.0)
    }

    @Test fun anotherDeviceStoppingOnTheShownPageIsContinuedFromToo() = runBlocking {
        val (phone, phoneRoot) = fixture("phone")
        val (tablet, tabletRoot) = fixture("tablet")
        val session = phone.openReadingPosition(phoneRoot)
        phone.recordReadingPosition(phoneRoot, session.navigate(bookmark(120, 200.0))!!)
        // The tablet, its clock ahead, reads on and comes back to the page the phone shows.
        val tabletSession = tablet.openReadingPosition(tabletRoot)
        tablet.recordReadingPosition(tabletRoot, tabletSession.navigate(bookmark(125, 400.0))!!)
        tablet.recordReadingPosition(tabletRoot, tabletSession.navigate(bookmark(120, 500.0))!!)
        phone.readingPositions.merge(tablet.readingPositions.export("example")!!, phoneRoot)
        phone.recordReadingPosition(phoneRoot, session.navigate(bookmark(121, 300.0))!!)
        assertEquals(121, phone.loadBookmark(phoneRoot)!!.chapterIndex)
    }

    @Test fun aNewerMoveToAnotherPageIsNotClaimedByAReaderThatNeverShowedIt() = runBlocking {
        val (repository, root) = fixture("phone")
        val session = repository.openReadingPosition(root)
        repository.recordReadingPosition(root, session.navigate(bookmark(120, 200.0))!!)
        // Another device moved elsewhere later; the open reader is reloaded to it, as before.
        repository.saveBookmark(root, bookmark(130, 500.0))
        repository.recordReadingPosition(root, session.navigate(bookmark(121, 300.0))!!)
        assertEquals(130, repository.loadBookmark(root)!!.chapterIndex)
    }

    @Test fun aLongSessionDoesNotCollectEveryResavedPage() = runBlocking {
        val (repository, root) = fixture("phone")
        val session = repository.openReadingPosition(root)
        repository.recordReadingPosition(root, session.navigate(bookmark(120, 200.0))!!)
        // An older build re-saves the shown page on every close, each time with a later stamp.
        for (step in 1..300) repository.saveBookmark(root, bookmark(120, 200.0 + step))
        val next = session.navigate(bookmark(121, 600.0))!!
        assertTrue(next.isValid())
        assertTrue(next.seen.size < 5)
        repository.recordReadingPosition(root, next)
        assertEquals(121, repository.loadBookmark(root)!!.chapterIndex)
    }
    @Test fun theBookmarkProjectionNeverCreatesABookFolder() = runBlocking {
        val missing = temporary.root.resolve("Books/Deleted book")
        try {
            BookSidecarDataSource().saveBookmark(missing, bookmark(3, 100.0))
            fail("a projection for a folder that is gone must fail")
        } catch (_: java.io.FileNotFoundException) {
        }
        assertFalse(missing.exists())
    }

    @Test fun aBookRetiredWhileItsLastMoveIsSavedIsNotBroughtBack() = runBlocking {
        val (repository, root) = fixture("phone")
        val event = repository.openReadingPosition(root).navigate(bookmark(101, 150.0))!!
        // Deleting a book renames its folder away in one step, as retireBook does.
        assertTrue(root.renameTo(root.resolveSibling(".deleted-${root.name}")))
        repository.recordReadingPosition(root, event)
        assertFalse("no folder holding only a bookmark reappears", root.exists())
        assertEquals(event.id, repository.readingPositions.export("example")!!.heads.single().id)
    }
}
