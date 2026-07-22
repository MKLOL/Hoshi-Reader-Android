package moe.antimony.hoshi.features.sync.http

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import moe.antimony.hoshi.features.ai.AiChatEntry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * Unit tests for the every-5/on-leave/on-chat counter logic in [HttpSyncReaderHooks].
 *
 * No real network, no real `HttpSyncPusher` — the hooks class takes `pushBookmark` and
 * `pushChatEntry` as function references so we can drop in a recording spy. The
 * persistence scope runs on `Dispatchers.Unconfined` so async pushes resolve immediately
 * and assertions can inspect the spy without sleeping.
 */
class HttpSyncReaderHooksTest {

    private lateinit var spy: RecordingPusher
    private lateinit var scope: CoroutineScope
    private val configured = HttpSyncSettings(baseUrl = "https://x", bearerToken = "t")
    private val unconfigured = HttpSyncSettings(baseUrl = "", bearerToken = "")
    private var fakeNowMs: Long = 1_700_000_000_000L

    @Before fun setUp() {
        spy = RecordingPusher()
        // Unconfined dispatcher → launched coroutines run inline, no need to advance time.
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    }

    @After fun tearDown() {
        scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
    }

    @Test
    fun onPageTurnBelowThresholdAccumulatesWithoutPushing() = runBlocking {
        val hooks = newHooks(settings = configured)
        repeat(HttpSyncReaderHooks.PAGE_TURN_PUSH_THRESHOLD - 1) { hooks.onPageTurnPersisted() }
        assertEquals(0, spy.bookmarkCount)
        assertEquals(HttpSyncReaderHooks.PAGE_TURN_PUSH_THRESHOLD - 1, hooks.pendingTurns)
    }

    @Test
    fun onPageTurnAtThresholdFiresPushAndResets() = runBlocking {
        val hooks = newHooks(settings = configured)
        repeat(HttpSyncReaderHooks.PAGE_TURN_PUSH_THRESHOLD) { hooks.onPageTurnPersisted() }
        assertEquals(1, spy.bookmarkCount)
        assertEquals("counter reset after push", 0, hooks.pendingTurns)
    }

    @Test
    fun onPageTurnAtTenFiresTwice() = runBlocking {
        val hooks = newHooks(settings = configured)
        repeat(HttpSyncReaderHooks.PAGE_TURN_PUSH_THRESHOLD * 2) { hooks.onPageTurnPersisted() }
        assertEquals(2, spy.bookmarkCount)
    }

    @Test
    fun onLeavePushesWhenThereAreUnpushedTurns() = runBlocking {
        val hooks = newHooks(settings = configured)
        hooks.onPageTurnPersisted()
        hooks.onPageTurnPersisted()
        assertEquals(0, spy.bookmarkCount)
        hooks.onLeave()
        assertEquals(1, spy.bookmarkCount)
        assertEquals(0, hooks.pendingTurns)
    }

    @Test
    fun onLeaveNoopWhenNothingTurnedSinceLastPush() = runBlocking {
        val hooks = newHooks(settings = configured)
        hooks.onLeave()
        assertEquals(0, spy.bookmarkCount)
    }

    @Test
    fun onChatEntryPersistedAlwaysFiresImmediately() = runBlocking {
        val hooks = newHooks(settings = configured)
        val entry = AiChatEntry("bubble", "p", "m", "r", 100.0)
        hooks.onChatEntryPersisted(entry)
        assertEquals(1, spy.chatCount)
        assertEquals(entry, spy.chatEntries.single())
    }

    @Test
    fun hooksNoOpWhenSettingsIsNull() = runBlocking {
        val hooks = newHooks(settings = null)
        repeat(HttpSyncReaderHooks.PAGE_TURN_PUSH_THRESHOLD) { hooks.onPageTurnPersisted() }
        hooks.onLeave()
        hooks.onChatEntryPersisted(AiChatEntry("x", "p", "m", "r", 0.0))
        assertEquals(0, spy.bookmarkCount)
        assertEquals(0, spy.chatCount)
    }

    @Test
    fun hooksNoOpWhenSettingsUnconfigured() = runBlocking {
        val hooks = newHooks(settings = unconfigured)
        repeat(HttpSyncReaderHooks.PAGE_TURN_PUSH_THRESHOLD) { hooks.onPageTurnPersisted() }
        hooks.onLeave()
        hooks.onChatEntryPersisted(AiChatEntry("x", "p", "m", "r", 0.0))
        assertEquals(0, spy.bookmarkCount)
        assertEquals(0, spy.chatCount)
    }

    @Test
    fun bookmarkPushCarriesProvidedBookRootTitleAndSettings() = runBlocking {
        val root = File("/tmp/test-book")
        val title = "Mock Title"
        val hooks = newHooks(settings = configured, bookRoot = root, title = title)
        repeat(HttpSyncReaderHooks.PAGE_TURN_PUSH_THRESHOLD) { hooks.onPageTurnPersisted() }
        val (gotRoot, gotTitle, gotSettings) = spy.bookmarkCalls.single()
        assertEquals(root, gotRoot)
        assertEquals(title, gotTitle)
        assertTrue(gotSettings.isConfigured)
    }

    @Test
    fun chatPushCarriesProvidedTitleAndEntry() = runBlocking {
        val title = "Chat Book"
        val hooks = newHooks(settings = configured, title = title)
        val entry = AiChatEntry("hi", "p", "m", "r", 100.0)
        hooks.onChatEntryPersisted(entry)
        val (gotTitle, gotEntry, _) = spy.chatCalls.single()
        assertEquals(title, gotTitle)
        assertEquals(entry, gotEntry)
    }

    @Test
    fun circuitBreakerTripsAfterConsecutiveFailures() = runBlocking {
        val failing: suspend (File, String, HttpSyncSettings) -> Unit = { _, _, _ ->
            throw HttpSyncException("network down")
        }
        val hooks = newHooks(settings = configured, pushBookmark = failing)
        // Each batch of THRESHOLD turns triggers one push attempt.
        repeat(HttpSyncReaderHooks.CONSECUTIVE_FAILURE_THRESHOLD) {
            repeat(HttpSyncReaderHooks.PAGE_TURN_PUSH_THRESHOLD) { hooks.onPageTurnPersisted() }
        }
        assertTrue("after N consecutive failures the breaker is open", hooks.isSuppressed)
    }

    @Test
    fun openBreakerSuppressesFurtherPushAttempts() = runBlocking {
        var attemptCount = 0
        val failing: suspend (File, String, HttpSyncSettings) -> Unit = { _, _, _ ->
            attemptCount += 1
            throw HttpSyncException("server is down")
        }
        val hooks = newHooks(settings = configured, pushBookmark = failing)
        // Trip the breaker.
        repeat(HttpSyncReaderHooks.CONSECUTIVE_FAILURE_THRESHOLD) {
            repeat(HttpSyncReaderHooks.PAGE_TURN_PUSH_THRESHOLD) { hooks.onPageTurnPersisted() }
        }
        val attemptsAtTrip = attemptCount
        // Many more page turns while the breaker is open should NOT trigger more PUTs.
        repeat(HttpSyncReaderHooks.PAGE_TURN_PUSH_THRESHOLD * 5) { hooks.onPageTurnPersisted() }
        hooks.onLeave()
        assertEquals(
            "no extra push attempts while breaker is open",
            attemptsAtTrip,
            attemptCount,
        )
    }

    @Test
    fun breakerResetsImmediatelyWhenManualSyncSignalArrivesAfterTrip() = runBlocking {
        var manualSyncStamp = 0L
        val failing: suspend (File, String, HttpSyncSettings) -> Unit = { _, _, _ ->
            throw HttpSyncException("offline")
        }
        val hooks = HttpSyncReaderHooks(
            bookRoot = File("/tmp/test-book"),
            title = "Some Title",
            pushBookmark = failing,
            pushChatEntry = { _, _, _ -> },
            currentSettings = { configured },
            persistenceScope = scope,
            clock = { fakeNowMs },
            breakerResetSignal = { manualSyncStamp },
        )
        // Trip the breaker.
        repeat(HttpSyncReaderHooks.CONSECUTIVE_FAILURE_THRESHOLD) {
            repeat(HttpSyncReaderHooks.PAGE_TURN_PUSH_THRESHOLD) { hooks.onPageTurnPersisted() }
        }
        assertTrue("breaker open after threshold failures", hooks.isSuppressed)

        // Now the user taps Sync now and it succeeds — settings view bumps the signal.
        manualSyncStamp = fakeNowMs + 1L
        // The very next page turn batch should see a fresh signal, reset the breaker,
        // and proceed.
        assertFalse("manual-sync signal must clear the breaker immediately", hooks.isSuppressed)
    }

    @Test
    fun breakerResetsAfterBackoffWindow() = runBlocking {
        var failNext = true
        val maybeFailing: suspend (File, String, HttpSyncSettings) -> Unit = { _, _, _ ->
            if (failNext) throw HttpSyncException("transient")
        }
        val hooks = newHooks(settings = configured, pushBookmark = maybeFailing)
        // Trip the breaker.
        repeat(HttpSyncReaderHooks.CONSECUTIVE_FAILURE_THRESHOLD) {
            repeat(HttpSyncReaderHooks.PAGE_TURN_PUSH_THRESHOLD) { hooks.onPageTurnPersisted() }
        }
        assertTrue(hooks.isSuppressed)
        // Advance fake clock past the backoff.
        fakeNowMs += HttpSyncReaderHooks.BACKOFF_MS + 1
        assertFalse("breaker closes once backoff elapses", hooks.isSuppressed)
        // A successful push after backoff should reset the failure counter.
        failNext = false
        repeat(HttpSyncReaderHooks.PAGE_TURN_PUSH_THRESHOLD) { hooks.onPageTurnPersisted() }
        // Trip would now take another N failures, so a single failure shouldn't suppress.
        failNext = true
        repeat(HttpSyncReaderHooks.PAGE_TURN_PUSH_THRESHOLD) { hooks.onPageTurnPersisted() }
        assertFalse("one failure post-recovery does not re-trip the breaker", hooks.isSuppressed)
    }

    @Test
    fun chatEntryFailureDoesNotPropagate() = runBlocking {
        val failing: suspend (String, AiChatEntry, HttpSyncSettings) -> Unit = { _, _, _ ->
            throw HttpSyncException("boom")
        }
        val hooks = newHooks(settings = configured, pushChatEntry = failing)
        // Must not throw.
        hooks.onChatEntryPersisted(AiChatEntry("x", "p", "m", "r", 0.0))
        // Survives — subsequent calls keep working.
        hooks.onChatEntryPersisted(AiChatEntry("y", "p", "m", "r", 1.0))
    }

    // --- helpers --------------------------------------------------------------------------

    private fun newHooks(
        settings: HttpSyncSettings?,
        bookRoot: File = File("/tmp/test-book"),
        title: String = "Some Title",
        pushBookmark: suspend (File, String, HttpSyncSettings) -> Unit = { root, t, s -> spy.recordBookmark(root, t, s) },
        pushChatEntry: suspend (String, AiChatEntry, HttpSyncSettings) -> Unit = { t, e, s -> spy.recordChat(t, e, s) },
    ): HttpSyncReaderHooks = HttpSyncReaderHooks(
        bookRoot = bookRoot,
        title = title,
        pushBookmark = pushBookmark,
        pushChatEntry = pushChatEntry,
        currentSettings = { settings },
        persistenceScope = scope,
        clock = { fakeNowMs },
    )

    private class RecordingPusher {
        val bookmarkCalls: MutableList<Triple<File, String, HttpSyncSettings>> = mutableListOf()
        val chatCalls: MutableList<Triple<String, AiChatEntry, HttpSyncSettings>> = mutableListOf()
        val bookmarkCount: Int get() = bookmarkCalls.size
        val chatCount: Int get() = chatCalls.size
        val chatEntries: List<AiChatEntry> get() = chatCalls.map { it.second }

        fun recordBookmark(root: File, title: String, settings: HttpSyncSettings) {
            bookmarkCalls += Triple(root, title, settings)
        }

        fun recordChat(title: String, entry: AiChatEntry, settings: HttpSyncSettings) {
            chatCalls += Triple(title, entry, settings)
        }
    }
}
