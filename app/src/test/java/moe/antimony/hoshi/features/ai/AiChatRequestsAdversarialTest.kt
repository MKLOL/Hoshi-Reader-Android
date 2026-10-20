package moe.antimony.hoshi.features.ai

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

/** Adversarial review tests for [AiChatRequests]. */
@OptIn(ExperimentalCoroutinesApi::class)
class AiChatRequestsAdversarialTest {
    @get:Rule val tempFolder = TemporaryFolder()

    private val uncaught = mutableListOf<Throwable>()

    private fun entry(text: String, response: String = "reply to $text") =
        AiChatEntry(bubbleText = text, prompt = "p", model = "m", response = response, timestampSeconds = 1.0)

    private class Harness(val requests: AiChatRequests, val history: AiChatHistoryStore, val scope: CoroutineScope)

    private fun TestScope.harness(): Harness {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val history = AiChatHistoryStore(dispatcher)
        // Production appScope has no handler: an uncaught exception there crashes the app.
        val handler = CoroutineExceptionHandler { _, error -> uncaught += error }
        val scope = CoroutineScope(dispatcher + SupervisorJob() + handler)
        return Harness(AiChatRequests(scope, history), history, scope)
    }

    private fun AiChatRequests.request(id: Long) = requests.value.single { it.id == id }
    private fun AiChatRequests.find(id: Long) = requests.value.firstOrNull { it.id == id }

    @Suppress("UNCHECKED_CAST")
    private fun AiChatRequests.workIds(): Set<Long> {
        val field = AiChatRequests::class.java.getDeclaredField("work").apply { isAccessible = true }
        return (field.get(this) as Map<Long, *>).keys.toSet()
    }

    private fun AiChatRequests.ask(
        book: File?,
        text: String,
        key: String? = text,
        onSaved: (AiChatEntry) -> Unit = {},
        failureMessage: (Throwable) -> String = { it.message ?: "failed" },
        run: suspend () -> AiChatEntry,
    ) = start(book, key, text, onDevice = false, failureMessage = failureMessage, onSaved = onSaved, run = run)

    // ---- Exception isolation -------------------------------------------------------------

    @Test
    fun aThrowingOnSavedStillFinishesTheRequest() = runTest {
        val h = harness()
        val book = tempFolder.newFolder("book")
        val id = h.requests.ask(book, "a", onSaved = { error("sync push blew up") }) { entry("a") }
        h.requests.hide(id)
        advanceUntilIdle()

        assertTrue(
            "status is ${h.requests.request(id).status}: the spinner never stops; uncaught=$uncaught",
            h.requests.request(id).status is AiChatRequest.Status.Done,
        )
        assertEquals("the exception escapes to the app scope (a crash in production)", emptyList<Throwable>(), uncaught)
    }

    @Test
    fun aThrowingFailureMessageStillFailsTheRequest() = runTest {
        val h = harness()
        val book = tempFolder.newFolder("book")
        val id = h.requests.ask(book, "a", failureMessage = { error("format failed") }) { throw IOException("offline") }
        h.requests.hide(id)
        advanceUntilIdle()

        assertEquals(emptyList<Throwable>(), uncaught)
        assertTrue(h.requests.request(id).status is AiChatRequest.Status.Failed)
    }

    // ---- Cancellation ---------------------------------------------------------------------

    @Test
    fun aCancellationExceptionFromRunWhileTheScopeLivesIsAFailure() = runTest {
        val h = harness()
        val book = tempFolder.newFolder("book")
        val id = h.requests.ask(book, "a") { throw CancellationException("socket cancelled") }
        h.requests.hide(id)
        advanceUntilIdle()
        assertEquals(AiChatRequest.Status.Failed("socket cancelled"), h.requests.request(id).status)
        assertEquals("kept for Retry", setOf(id), h.requests.workIds())
    }

    @Test
    fun aCancelledScopeLeavesTheRequestRunning() = runTest {
        val h = harness()
        val book = tempFolder.newFolder("book")
        val reply = CompletableDeferred<AiChatEntry>()
        val id = h.requests.ask(book, "a") { reply.await() }
        h.requests.hide(id)
        advanceUntilIdle()
        h.scope.cancel()
        advanceUntilIdle()
        // Documented behavior: only on app death, so a stuck Running row is acceptable.
        assertEquals(AiChatRequest.Status.Running, h.requests.request(id).status)
        // Retry is a no-op on Running, and start() would join this dead request forever.
        assertEquals(id, h.requests.ask(book, "a") { entry("a") })
    }

    // ---- Transitions ----------------------------------------------------------------------

    @Test
    fun hideAfterCompletionBeforeTheCollectorConsumesKeepsTheReplyUnseen() = runTest {
        val h = harness()
        val book = tempFolder.newFolder("book")
        val keyed = h.requests.ask(book, "a") { entry("a") }
        val shot = h.requests.ask(book, "shot", key = null) { entry("shot") }
        advanceUntilIdle()
        // Both completed while visible; the popup closes in the same frame.
        assertEquals(entry("a"), (h.requests.request(keyed).status as AiChatRequest.Status.Done).entry)
        assertEquals(entry("shot"), (h.requests.request(shot).status as AiChatRequest.Status.Done).entry)
        h.requests.hide(keyed)
        h.requests.hide(shot)

        assertFalse(h.requests.request(keyed).visible)
        assertEquals(entry("a"), (h.requests.request(keyed).status as AiChatRequest.Status.Done).entry)
        assertNull((h.requests.request(shot).status as AiChatRequest.Status.Done).entry)
        assertEquals(2, h.history.load(book).entries.size)
        assertTrue(h.requests.workIds().isEmpty())
    }

    @Test
    fun hideAfterAFailureTheCollectorHasNotShownYetDropsTheErrorUnseen() = runTest {
        val h = harness()
        val book = tempFolder.newFolder("book")
        val id = h.requests.ask(book, "a") { throw IOException("offline") }
        advanceUntilIdle()
        // Failed while visible, but the popup was closed before its LaunchedEffect ran.
        h.requests.hide(id)
        // Documented trade-off: a never-shown failure disappears without a history row.
        assertNull(h.requests.find(id))
    }

    @Test
    fun startWhileTheSameKeyIsFailedAndVisibleReplacesIt() = runTest {
        val h = harness()
        val book = tempFolder.newFolder("book")
        val failed = h.requests.ask(book, "a") { throw IOException("offline") }
        advanceUntilIdle()
        assertTrue(h.requests.request(failed).visible)

        val again = h.requests.ask(book, "a") { entry("a") }

        assertNotEquals(failed, again)
        assertNull(h.requests.find(failed))
        assertFalse(failed in h.requests.workIds())
        h.requests.retry(failed) // the old row is gone: no-op
        advanceUntilIdle()
        assertEquals(1, h.history.load(book).entries.size)
    }

    @Test
    fun retryTwiceRunsOnce() = runTest {
        val h = harness()
        val book = tempFolder.newFolder("book")
        var runs = 0
        val id = h.requests.ask(book, "a") {
            runs++
            if (runs == 1) throw IOException("offline") else entry("a")
        }
        h.requests.hide(id)
        advanceUntilIdle()
        h.requests.retry(id)
        h.requests.retry(id)
        advanceUntilIdle()
        assertEquals(2, runs)
        assertEquals(1, h.history.load(book).entries.size)
    }

    @Test
    fun retryOfARequestWhoseKeyNowHasANewerRequestIsANoOp() = runTest {
        val h = harness()
        val book = tempFolder.newFolder("book")
        var oldRuns = 0
        val old = h.requests.ask(book, "a") { oldRuns++; throw IOException("offline") }
        h.requests.hide(old)
        advanceUntilIdle()
        val pending = CompletableDeferred<AiChatEntry>()
        val newer = h.requests.ask(book, "a") { pending.await() }
        h.requests.retry(old)
        advanceUntilIdle()
        assertEquals(1, oldRuns)
        assertEquals(listOf(newer), h.requests.requests.value.map { it.id })
    }

    @Test
    fun markHistorySeenLeavesAVisibleReplyForItsPopup() = runTest {
        val h = harness()
        val book = tempFolder.newFolder("book")
        val visible = h.requests.ask(book, "a") { entry("a") }
        advanceUntilIdle()
        h.requests.markHistorySeen(book)
        // The popup has not taken it yet; forgetting it would leave the popup blank.
        assertEquals(entry("a"), (h.requests.request(visible).status as AiChatRequest.Status.Done).entry)
    }

    @Test
    fun consumeWhileRunningDropsTheReplyFromTheHistory() = runTest {
        val h = harness()
        val book = tempFolder.newFolder("book")
        val reply = CompletableDeferred<AiChatEntry>()
        var saved = 0
        val id = h.requests.ask(book, "a", onSaved = { saved++ }) { reply.await() }
        advanceUntilIdle()
        h.requests.consume(id)
        reply.complete(entry("a"))
        advanceUntilIdle()
        // The book is captured at start: a request forgotten meanwhile is still saved.
        assertEquals("a paid reply is lost when its row is gone", 1, h.history.load(book).entries.size)
        assertEquals(1, saved)
    }

    // ---- Memory ---------------------------------------------------------------------------

    @Test
    fun noPathLeavesAClosureBehindExceptAHiddenFailure() = runTest {
        val h = harness()
        val book = tempFolder.newFolder("book")
        val doneVisible = h.requests.ask(book, "1") { entry("1") }
        val doneHidden = h.requests.ask(book, "2") { entry("2") }.also(h.requests::hide)
        val shotHidden = h.requests.ask(book, "3", key = null) { entry("3") }.also(h.requests::hide)
        val failedVisible = h.requests.ask(book, "4") { throw IOException("x") }
        val failedHidden = h.requests.ask(book, "5") { throw IOException("x") }.also(h.requests::hide)
        val failedDismissed = h.requests.ask(book, "6") { throw IOException("x") }.also(h.requests::hide)
        val noRoot = h.requests.ask(null, "7") { entry("7") }.also(h.requests::hide)
        advanceUntilIdle()
        h.requests.consume(doneVisible)
        h.requests.consume(failedVisible)
        h.requests.dismiss(failedDismissed)
        h.requests.markHistorySeen(book)

        assertEquals(setOf(failedHidden), h.requests.workIds())
        assertEquals(setOf(failedHidden, noRoot), h.requests.requests.value.map { it.id }.toSet())
        assertTrue(doneHidden !in h.requests.workIds() && shotHidden !in h.requests.workIds())
    }

    @Test
    fun hiddenFailuresAreTrimmedWithTheirClosures() = runTest {
        val h = harness()
        val book = tempFolder.newFolder("book")
        val image = "A".repeat(1_000_000)
        val ids = (1..30).map { n ->
            h.requests.ask(book, "Screenshot translation", key = null) {
                if (image.isNotEmpty()) throw IOException("offline $n") else entry("x")
            }.also(h.requests::hide)
        }
        advanceUntilIdle()
        // Each closure holds a screenshot: only the book's 20 most recent failures are kept.
        assertEquals(ids.takeLast(20).toSet(), h.requests.workIds())
        assertEquals(ids.takeLast(20), h.requests.requests.value.map { it.id })
    }

    @Test
    fun repliesFromAnotherBookOrAnEpubKeepAMangaUnseenMark() = runTest {
        val h = harness()
        val manga = tempFolder.newFolder("manga")
        val epub = tempFolder.newFolder("epub")
        val mangaReply = h.requests.ask(manga, "m") { entry("m") }.also(h.requests::hide)
        advanceUntilIdle()
        (1..20).forEach { n -> h.requests.ask(epub, "e$n") { entry("e$n") }.also(h.requests::hide) }
        advanceUntilIdle()
        // The cap is per book, and the EPUB reader never calls markHistorySeen.
        assertTrue("the manga's unseen dot disappeared", h.requests.find(mangaReply) != null)
    }

    // ---- History bookkeeping -------------------------------------------------------------

    @Test
    fun historyRevisionsCountOnlySavedRepliesPerBook() = runTest {
        val h = harness()
        val a = tempFolder.newFolder("a")
        val b = tempFolder.newFolder("b")
        h.requests.ask(a, "1") { entry("1") }
        h.requests.ask(a, "2") { entry("2") }
        h.requests.ask(b, "3") { entry("3") }
        h.requests.ask(a, "4") { throw IOException("x") }
        h.requests.ask(null, "5") { entry("5") }
        advanceUntilIdle()
        assertEquals(mapOf(a.path to 2, b.path to 1), h.requests.historyRevisions.value)
    }

    @Test
    fun aHiddenScreenshotWhoseSaveFailsShowsNoNewReplyDot() = runTest {
        val h = harness()
        val book = tempFolder.newFolder("book")
        var saved = 0
        val id = h.requests.ask(book, "shot", key = null, onSaved = { saved++ }) { entry("shot") }
        h.requests.hide(id)
        // The history file cannot be written (disk full, permission): simulate with a directory.
        File(book, "ai_chat_log.json.tmp").mkdirs()
        File(book, "ai_chat_log.json").mkdirs()
        advanceUntilIdle()

        assertEquals(0, saved)
        assertTrue(h.history.load(book).entries.isEmpty())
        // Nothing could show it (the history has no copy, a screenshot cannot be asked for
        // again), so no "New translation" dot points at an empty history.
        assertNull(h.requests.find(id))
    }

    @Test
    fun aVisibleReplyIsShownOnlyAfterTheWholeHistoryFileIsRewritten() = runTest {
        // A separate scheduler for disk IO, advanced by hand: a large ai_chat_log.json (base64
        // screenshots) takes seconds to load + rewrite.
        val io = kotlinx.coroutines.test.TestCoroutineScheduler()
        val main = StandardTestDispatcher(testScheduler)
        val requests = AiChatRequests(
            CoroutineScope(main + SupervisorJob()),
            AiChatHistoryStore(StandardTestDispatcher(io)),
        )
        val book = tempFolder.newFolder("book")
        val id = requests.ask(book, "a") { entry("a") }
        advanceUntilIdle()
        // The reply has arrived; the old code showed it in the popup before appending.
        val statusDuringAppend = requests.request(id).status
        // Release AiChatHistoryStore's static appendMutex for the other tests in this JVM.
        repeat(5) { io.advanceUntilIdle(); advanceUntilIdle() }
        assertTrue(requests.request(id).status is AiChatRequest.Status.Done)
        assertTrue(
            "status is $statusDuringAppend while the history append is still running",
            statusDuringAppend is AiChatRequest.Status.Done,
        )
    }

    // ---- Re-entrancy with an immediate dispatcher ----------------------------------------

    @Test
    fun anImmediateDispatcherWithReentrantCallsLosesNoUpdate() {
        val book = tempFolder.newFolder("book")
        // Unconfined runs the launch body inside start(), like Main.immediate on the main thread.
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        val requests = AiChatRequests(scope, AiChatHistoryStore(Dispatchers.Unconfined))
        var nested = -1L
        val failedSync = requests.start(book, "x", "x", false, { it.message ?: "?" }) { throw IOException("no key") }
        assertEquals(AiChatRequest.Status.Failed("no key"), requests.request(failedSync).status)

        val outer = requests.start(
            book, "a", "a", false, { "?" },
            onSaved = {
                // A completion that triggers another start() and a hide() re-entrantly.
                nested = requests.start(book, "b", "b", false, { "?" }) { entry("b") }
                requests.hide(nested)
            },
        ) { entry("a") }

        assertTrue(requests.request(outer).status is AiChatRequest.Status.Done)
        assertTrue(requests.request(nested).status is AiChatRequest.Status.Done)
        assertFalse(requests.request(nested).visible)
        scope.cancel()
    }
}
