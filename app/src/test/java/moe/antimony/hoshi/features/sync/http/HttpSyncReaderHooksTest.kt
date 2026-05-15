package moe.antimony.hoshi.features.sync.http

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import moe.antimony.hoshi.features.ai.AiChatEntry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * Unit tests for the every-5/on-leave/on-chat counter logic in [HttpSyncReaderHooks].
 *
 * No real network, no real `HttpSyncManager` — the hooks class takes `pushBookmark` and
 * `pushChatEntry` as function references so we can drop in a recording spy. The
 * persistence scope runs on `Dispatchers.Unconfined` so async pushes resolve immediately
 * and assertions can inspect the spy without sleeping.
 */
class HttpSyncReaderHooksTest {

    private lateinit var spy: RecordingPusher
    private lateinit var scope: CoroutineScope
    private val configured = HttpSyncSettings(baseUrl = "https://x", bearerToken = "t", enabled = true)
    private val unconfigured = HttpSyncSettings(baseUrl = "", bearerToken = "")

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

    // --- helpers --------------------------------------------------------------------------

    private fun newHooks(
        settings: HttpSyncSettings?,
        bookRoot: File = File("/tmp/test-book"),
        title: String = "Some Title",
    ): HttpSyncReaderHooks = HttpSyncReaderHooks(
        bookRoot = bookRoot,
        title = title,
        pushBookmark = { root, t, s -> spy.recordBookmark(root, t, s) },
        pushChatEntry = { t, e, s -> spy.recordChat(t, e, s) },
        currentSettings = { settings },
        persistenceScope = scope,
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
