package moe.antimony.hoshi.features.ai

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class AiChatRequestsTest {
    @get:Rule val tempFolder = TemporaryFolder()

    private fun entry(text: String, response: String = "reply to $text") =
        AiChatEntry(bubbleText = text, prompt = "p", model = "m", response = response, timestampSeconds = 1.0)

    private fun TestScope.requests(): Pair<AiChatRequests, AiChatHistoryStore> {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val history = AiChatHistoryStore(dispatcher)
        // Not backgroundScope: advanceUntilIdle skips background work, and these must run.
        return AiChatRequests(CoroutineScope(dispatcher + SupervisorJob()), history) to history
    }

    private fun AiChatRequests.request(id: Long) = requests.value.single { it.id == id }

    private fun AiChatRequests.ask(
        book: File,
        text: String,
        key: String? = text,
        onSaved: (AiChatEntry) -> Unit = {},
        run: suspend () -> AiChatEntry,
    ) = start(book, key, text, onDevice = false, failureMessage = { it.message ?: "failed" }, onSaved = onSaved, run = run)

    @Test
    fun aClosedPopupLeavesItsRequestRunningAndTheReplyLandsInTheHistory() = runTest {
        val (requests, history) = requests()
        val book = tempFolder.newFolder("book")
        val reply = CompletableDeferred<AiChatEntry>()
        val saved = mutableListOf<AiChatEntry>()

        val id = requests.ask(book, "もうすぐ", onSaved = { saved += it }) { reply.await() }
        advanceUntilIdle()
        requests.hide(id)
        assertEquals(AiChatRequest.Status.Running, requests.request(id).status)
        assertFalse(requests.request(id).visible)

        reply.complete(entry("もうすぐ"))
        advanceUntilIdle()

        assertEquals(listOf("もうすぐ"), history.load(book).entries.map { it.bubbleText })
        assertEquals(listOf("もうすぐ"), saved.map { it.bubbleText })
        assertEquals(1, requests.historyRevisions.value[book.path])
        // Kept out of sight, so asking again shows it instead of paying for a new reply.
        assertEquals(entry("もうすぐ"), (requests.request(id).status as AiChatRequest.Status.Done).entry)
    }

    @Test
    fun askingAboutTheSameBubbleJoinsTheRequestOrShowsItsUnseenReply() = runTest {
        val (requests, _) = requests()
        val book = tempFolder.newFolder("book")
        val reply = CompletableDeferred<AiChatEntry>()
        var runs = 0

        val first = requests.ask(book, "a") { runs++; reply.await() }
        advanceUntilIdle()
        requests.hide(first)
        val joined = requests.ask(book, "a") { runs++; entry("a", "second") }
        advanceUntilIdle()
        assertEquals(first, joined)
        assertTrue("re-asking shows the request again", requests.request(first).visible)

        requests.hide(first)
        reply.complete(entry("a"))
        advanceUntilIdle()
        val shown = requests.ask(book, "a") { runs++; entry("a", "third") }
        advanceUntilIdle()

        assertEquals(first, shown)
        assertEquals(1, runs)
        assertEquals("reply to a", (requests.request(first).status as AiChatRequest.Status.Done).entry?.response)
        requests.consume(first)
        assertTrue(requests.requests.value.isEmpty())
    }

    @Test
    fun anotherBubbleOrAnotherBookIsItsOwnRequest() = runTest {
        val (requests, history) = requests()
        val bookA = tempFolder.newFolder("a")
        val bookB = tempFolder.newFolder("b")

        val one = requests.ask(bookA, "x") { entry("x") }
        val two = requests.ask(bookA, "y") { entry("y") }
        val three = requests.ask(bookB, "x") { entry("x") }
        advanceUntilIdle()

        assertEquals(3, setOf(one, two, three).size)
        assertEquals(listOf("x", "y"), history.load(bookA).entries.map { it.bubbleText })
        assertEquals(listOf("x"), history.load(bookB).entries.map { it.bubbleText })
    }

    @Test
    fun aFailureOutOfSightStaysListedUntilRetriedOrDismissed() = runTest {
        val (requests, history) = requests()
        val book = tempFolder.newFolder("book")
        var attempts = 0

        val id = requests.ask(book, "a") {
            attempts++
            if (attempts == 1) throw IOException("offline") else entry("a")
        }
        requests.hide(id)
        advanceUntilIdle()
        assertEquals(AiChatRequest.Status.Failed("offline"), requests.request(id).status)

        requests.retry(id)
        assertEquals(AiChatRequest.Status.Running, requests.request(id).status)
        advanceUntilIdle()
        assertEquals(2, attempts)
        assertEquals(listOf("a"), history.load(book).entries.map { it.bubbleText })

        val failing = requests.ask(book, "b") { throw IOException("offline") }
        requests.hide(failing)
        advanceUntilIdle()
        requests.dismiss(failing)
        assertTrue(requests.requests.value.none { it.id == failing })
    }

    @Test
    fun askingAgainAfterAFailureSendsANewRequest() = runTest {
        val (requests, _) = requests()
        val book = tempFolder.newFolder("book")

        val failed = requests.ask(book, "a") { throw IOException("offline") }
        requests.hide(failed)
        advanceUntilIdle()
        val again = requests.ask(book, "a") { entry("a") }
        advanceUntilIdle()

        assertTrue(again != failed)
        assertEquals(listOf(again), requests.requests.value.map { it.id })
    }

    @Test
    fun aTimeoutIsAFailureNotASilentlyDroppedRequest() = runTest {
        val (requests, _) = requests()
        val book = tempFolder.newFolder("book")

        val id = requests.ask(book, "a") {
            withTimeout(10) { CompletableDeferred<AiChatEntry>().await() }
        }
        requests.hide(id)
        advanceUntilIdle()

        val status = requests.request(id).status
        assertTrue(status is AiChatRequest.Status.Failed)
        assertTrue((status as AiChatRequest.Status.Failed).message.isNotBlank())
    }

    @Test
    fun aClosedFailedPopupForgetsItsRequest() = runTest {
        val (requests, _) = requests()
        val book = tempFolder.newFolder("book")

        val id = requests.ask(book, "a") { throw IOException("offline") }
        advanceUntilIdle()
        // Shown in the popup, then closed.
        requests.hide(id)

        assertTrue(requests.requests.value.isEmpty())
    }

    @Test
    fun aScreenshotReplyOutOfSightKeepsOnlyItsUnseenMark() = runTest {
        val (requests, history) = requests()
        val book = tempFolder.newFolder("book")
        val image = AiChatImage("image/png", "AAAA".repeat(1000))

        val id = requests.ask(book, "Screenshot translation", key = null) {
            entry("Screenshot translation").copy(screenshotImage = image)
        }
        requests.hide(id)
        advanceUntilIdle()

        assertNull((requests.request(id).status as AiChatRequest.Status.Done).entry)
        assertEquals(image, history.load(book).entries.single().screenshotImage)
        // Two screenshots never join each other.
        val other = requests.ask(book, "Screenshot translation", key = null) { entry("Screenshot translation") }
        assertTrue(other != id)
    }

    @Test
    fun openingTheHistoryMarksItsRepliesSeen() = runTest {
        val (requests, _) = requests()
        val book = tempFolder.newFolder("book")
        val other = tempFolder.newFolder("other")
        val running = CompletableDeferred<AiChatEntry>()

        val done = requests.ask(book, "a") { entry("a") }
        val pending = requests.ask(book, "b") { running.await() }
        val elsewhere = requests.ask(other, "c") { entry("c") }
        listOf(done, pending, elsewhere).forEach(requests::hide)
        advanceUntilIdle()

        requests.markHistorySeen(book)

        assertEquals(setOf(pending, elsewhere), requests.requests.value.map { it.id }.toSet())
    }

    @Test
    fun aBookDeletedMeanwhileIsNotRecreatedForItsReply() = runTest {
        val (requests, _) = requests()
        val book = tempFolder.newFolder("book")
        val reply = CompletableDeferred<AiChatEntry>()
        var saved = false

        val id = requests.ask(book, "a", onSaved = { saved = true }) { reply.await() }
        requests.hide(id)
        advanceUntilIdle()
        book.deleteRecursively()
        reply.complete(entry("a"))
        advanceUntilIdle()

        assertFalse(book.exists())
        assertFalse(saved)
        assertTrue(requests.request(id).status is AiChatRequest.Status.Done)
    }

    @Test
    fun onlyTheMostRecentUnseenRepliesAreKeptInMemory() = runTest {
        val (requests, history) = requests()
        val book = tempFolder.newFolder("book")

        val ids = (1..25).map { n ->
            requests.ask(book, "bubble $n") { entry("bubble $n") }.also(requests::hide)
        }
        advanceUntilIdle()

        assertEquals(ids.takeLast(20), requests.requests.value.map { it.id })
        assertEquals(25, history.load(book).entries.size)
    }

    @Test
    fun startingOrRetryingARequestKeepsTheAppAliveButJoiningOneDoesNot() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        var keepAlive = 0
        val requests = AiChatRequests(
            CoroutineScope(dispatcher + SupervisorJob()),
            AiChatHistoryStore(dispatcher),
            onRunning = { keepAlive++ },
        )
        val book = tempFolder.newFolder("book")
        val reply = CompletableDeferred<AiChatEntry>()
        var attempts = 0

        val id = requests.ask(book, "a") { reply.await() }
        requests.ask(book, "a") { entry("a") }
        assertEquals("joining a running request starts nothing", 1, keepAlive)

        val failing = requests.ask(book, "b") { attempts++; throw IOException("offline") }
        requests.hide(failing)
        advanceUntilIdle()
        requests.retry(failing)
        assertEquals(3, keepAlive)
        reply.complete(entry("a"))
        advanceUntilIdle()
        assertEquals(2, attempts)
        assertTrue(requests.requests.value.none { it.status == AiChatRequest.Status.Running && it.id == id })
    }
}
