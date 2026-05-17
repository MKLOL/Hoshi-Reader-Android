package moe.antimony.hoshi.features.sync.http

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import moe.antimony.hoshi.epub.BookMetadata
import moe.antimony.hoshi.epub.BookRepository
import moe.antimony.hoshi.epub.BookShelf
import moe.antimony.hoshi.epub.Bookmark
import moe.antimony.hoshi.features.ai.AiChatEntry
import moe.antimony.hoshi.features.ai.AiChatHistoryStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.Instant
import java.util.UUID

/**
 * End-to-end tests for the v2 KV HTTP sync. Covers:
 *
 *  - [HttpSyncReconciler.syncOnce] inbound, outbound, cursor advancement, error collection.
 *  - [HttpSyncPusher.pushBookmark] / [pushChatEntry] single-shot hooks.
 *  - The blob schema helpers from [HttpSyncBlobs] — `deriveSyncId`, `chatEntryKeySuffix`,
 *    timestamp conversions, key builders.
 *
 * Uses a hand-rolled [FakeKvTransport] so we test against a real in-memory KV map without
 * spinning up an HTTP server or touching the live network. The reader-hooks tests live in
 * a sibling class to keep this file focused on the manager.
 */
class HttpSyncTest {

    @get:Rule val tempFolder = TemporaryFolder()

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val configured = HttpSyncSettings("https://x", "t", enabled = true)

    // ===== Blob helpers ======================================================================

    @Test
    fun deriveSyncIdHandlesAsciiAndUnicodeAndEdgeCases() {
        assertEquals("yotsubato_01", deriveSyncId("Yotsubato 01"))
        assertEquals("001_jp_yotsubato", deriveSyncId("001 [JP] Yotsubato"))
        assertEquals("kafka_on_the_shore", deriveSyncId("Kafka on the Shore"))
        // Japanese alone falls back to a deterministic hashed id instead of being skipped.
        assertTrue(deriveSyncId("よつばと")!!.matches(Regex("book_[0-9a-f]{16}")))
        // Mixed ASCII + Japanese keeps the ASCII parts; digits count as ASCII-alphanumeric.
        assertEquals("1_vol_1", deriveSyncId("第1巻 vol 1"))
        // Empty / whitespace / null all return null.
        assertNull(deriveSyncId(""))
        assertNull(deriveSyncId("   "))
        assertNull(deriveSyncId(null))
        // Runs of separators collapse and trim.
        assertEquals("a_b", deriveSyncId("a---b"))
        assertEquals("a", deriveSyncId("___a___"))
    }

    @Test
    fun chatEntryKeySuffixIsContentAddressableAndDeterministic() {
        val ts = 800_000_000.0
        val a = chatEntryKeySuffix(ts, "もうすぐだ", "Almost there!")
        val b = chatEntryKeySuffix(ts, "もうすぐだ", "Almost there!")
        assertEquals("same inputs → same suffix", a, b)

        val differentBubble = chatEntryKeySuffix(ts, "違うね", "Almost there!")
        assertFalse("different bubble → different suffix", a == differentBubble)

        val differentResponse = chatEntryKeySuffix(ts, "もうすぐだ", "Nearly!")
        assertFalse("different response → different suffix", a == differentResponse)

        val laterTime = chatEntryKeySuffix(ts + 1.0, "もうすぐだ", "Almost there!")
        assertTrue(
            "later timestamp sorts lexicographically after earlier (chronology preserved)",
            laterTime > a,
        )
        // Suffix shape: `{rfc3339 with - instead of :}-{8 hex chars}`.
        assertTrue("suffix ends with 8 hex chars", a.matches(Regex(".*-[0-9a-f]{8}$")))
    }

    @Test
    fun appleSecondsAndRfc3339RoundTrip() {
        val cases = listOf(0.0, 1.0, 800_000_000.0, 1.5e9, -1_000.0)
        for (appleSeconds in cases) {
            val rfc = appleSecondsToRfc3339(appleSeconds)
            val back = rfc3339ToAppleSeconds(rfc)
            assertEquals(
                "round-trip $appleSeconds via $rfc",
                appleSeconds,
                back,
                0.001,
            )
        }
        // Bad input parses as 0.
        assertEquals(0.0, rfc3339ToAppleSeconds("not-a-date"), 0.0)
    }

    @Test
    fun compareRfc3339IsChronologicalAndNullSafe() {
        assertTrue(compareRfc3339("2026-05-15T00:00:00Z", "2026-05-14T00:00:00Z") > 0)
        assertTrue(compareRfc3339("2026-05-14T00:00:00Z", "2026-05-15T00:00:00Z") < 0)
        assertEquals(0, compareRfc3339("2026-05-15T00:00:00Z", "2026-05-15T00:00:00Z"))
        assertTrue(compareRfc3339("2026-05-15T00:00:00.500Z", "2026-05-15T00:00:00Z") > 0)
        assertTrue(compareRfc3339("2026-05-15T00:00:00Z", "2026-05-15T00:00:00.500Z") < 0)
        // Null treated as "older than anything" (empty string sorts first).
        assertTrue(compareRfc3339(null, "2026-05-15T00:00:00Z") < 0)
        assertTrue(compareRfc3339("2026-05-15T00:00:00Z", null) > 0)
        assertEquals(0, compareRfc3339(null, null))
    }

    @Test
    fun keyBuildersProduceExpectedShapes() {
        assertEquals("books/yotsubato_01/bookmark", bookmarkKey("yotsubato_01"))
        assertEquals("books/yotsubato_01/metadata", metadataKey("yotsubato_01"))
        assertEquals("books/yotsubato_01/chat/abc", chatKey("yotsubato_01", "abc"))
        assertEquals("books/yotsubato_01/chat/", chatPrefixForBook("yotsubato_01"))
        assertEquals("books/", ALL_BOOKS_PREFIX)
    }

    @Test
    fun contentTypeSerializesUsingSerialNames() {
        assertEquals(
            "\"mokuro\"",
            json.encodeToString(HttpSyncContentType.serializer(), HttpSyncContentType.Mokuro),
        )
        assertEquals(
            "\"epub\"",
            json.encodeToString(HttpSyncContentType.serializer(), HttpSyncContentType.Epub),
        )
    }

    // ===== syncId key grammar regressions ====================================================

    @Test
    fun deriveSyncIdPreservesLegacyAsciiIdsThatFitServerSegment() {
        assertEquals("yotsubato_01", deriveSyncId("Yotsubato 01"))
        assertEquals("001_jp_yotsubato", deriveSyncId("001 [JP] Yotsubato"))
        assertEquals("kafka_on_the_shore", deriveSyncId("Kafka on the Shore"))

        val exactly64 = "A".repeat(64)
        assertEquals("a".repeat(64), deriveSyncId(exactly64))
    }

    @Test
    fun deriveSyncIdFallsBackForJapaneseOnlyTitle() {
        val syncId = deriveSyncId("よつばと")!!

        assertTrue("fallback id should be server-safe: $syncId", syncId.matches(Regex("[a-z0-9_]{1,64}")))
        assertTrue("fallback id should be recognizable: $syncId", syncId.startsWith("book_"))
        assertEquals("fallback id length", 21, syncId.length)
        assertEquals("fallback must be stable", syncId, deriveSyncId("よつばと"))
    }

    @Test
    fun deriveSyncIdKeepsEveryServerKeySegmentAtMost64Chars() {
        val overlongTitle = "A".repeat(90)
        val syncId = deriveSyncId(overlongTitle)!!
        val keys = listOf(
            bookmarkKey(syncId),
            metadataKey(syncId),
            chatPrefixForBook(syncId).trimEnd('/'),
        )

        assertTrue("syncId should fit one server segment: $syncId", syncId.length <= 64)
        assertTrue("syncId should be server-safe: $syncId", syncId.matches(Regex("[a-z0-9_]{1,64}")))
        assertTrue("long ascii prefix should remain recognizable: $syncId", syncId.startsWith("a".repeat(40)))
        keys.flatMap { it.split('/') }.forEach { segment ->
            assertTrue("segment '$segment' in $keys is too long", segment.length <= 64)
            assertTrue("segment '$segment' in $keys is not server-safe", segment.matches(Regex("[A-Za-z0-9_.-]{1,64}")))
        }
    }

    // ===== pushBookmark =======================================================================

    @Test
    fun pushBookmarkWritesBookmarkBlobAtBookmarkKey() = runBlocking {
        val repo = newBookRepository()
        val (root, _) = importMokuroBook(repo, "Test Volume")
        repo.saveBookmark(root, Bookmark(chapterIndex = 7, progress = 0.0, characterCount = 42, lastModified = 800_000_000.0))

        val transport = FakeKvTransport()
        val manager = managerFor(repo, transport)

        manager.pushBookmark(root, "Test Volume", configured)

        val key = bookmarkKey("test_volume")
        val stored = transport.kv[key] ?: error("expected bookmark to be PUT at $key")
        val blob = json.decodeFromString(HttpSyncBookmarkBlob.serializer(), stored.body.toString(Charsets.UTF_8))
        assertEquals(7, blob.chapterIndex)
        assertEquals(42, blob.characterCount)
        assertEquals("application/json; charset=utf-8", stored.contentType)
    }

    @Test
    fun pushBookmarkThrowsWhenNoLocalBookmark() = runBlocking {
        val repo = newBookRepository()
        val (_, _) = importMokuroBook(repo, "No Bookmark Yet")
        val manager = managerFor(repo, FakeKvTransport())
        val ex = assertThrows(HttpSyncException::class.java) {
            runBlocking { manager.pushBookmark(repo.loadBookEntries().first().root, "No Bookmark Yet", configured) }
        }
        assertTrue(ex.message!!.contains("No local bookmark"))
    }

    @Test
    fun pushBookmarkRequiresConfiguredSettings() {
        val repo = newBookRepository()
        val manager = managerFor(repo, FakeKvTransport())
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { manager.pushBookmark(File("/dev/null"), "X", HttpSyncSettings()) }
        }
    }

    // ===== pushChatEntry ======================================================================

    @Test
    fun pushChatEntryUsesContentAddressableKey() = runBlocking {
        val repo = newBookRepository()
        val transport = FakeKvTransport()
        val manager = managerFor(repo, transport)
        val entry = AiChatEntry(
            bubbleText = "もうすぐだ",
            prompt = "tutor",
            model = "gpt-5.5",
            response = "Almost there!",
            timestampSeconds = 800_000_000.0,
        )

        manager.pushChatEntry("Hello World", entry, configured)
        manager.pushChatEntry("Hello World", entry, configured) // idempotent — same key

        val expectedSuffix = chatEntryKeySuffix(entry.timestampSeconds, entry.bubbleText, entry.response)
        val expectedKey = chatKey("hello_world", expectedSuffix)
        assertNotNull(transport.kv[expectedKey])
        assertEquals(
            "double-push only writes one key (idempotent)",
            1,
            transport.kv.keys.count { it.startsWith("books/hello_world/chat/") },
        )
    }

    @Test
    fun pushChatEntryRequiresConfiguredSettings() {
        val repo = newBookRepository()
        val manager = managerFor(repo, FakeKvTransport())
        val entry = AiChatEntry("x", "p", "m", "r", 0.0)
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { manager.pushChatEntry("X", entry, HttpSyncSettings()) }
        }
    }

    // ===== syncOnce — outbound ================================================================

    @Test
    fun syncOnceOutboundPushesBookmarkAndMetadataForEveryLocalBook() = runBlocking {
        val repo = newBookRepository()
        val (mangaRoot, _) = importMokuroBook(repo, "First Manga")
        repo.saveBookmark(mangaRoot, Bookmark(1, 0.0, 1, 800_000_000.0))
        val (epubRoot, _) = importEpubBook(repo, "Second Book")
        repo.saveBookmark(epubRoot, Bookmark(2, 0.5, 2, 800_100_000.0))

        val transport = FakeKvTransport()
        val manager = managerFor(repo, transport)

        val result = manager.syncOnce(configured)

        assertEquals(2, result.uploadedBookmarks)
        assertEquals(2, result.uploadedMetadata)
        assertEquals(0, result.uploadedChatEntries)
        assertTrue(result.errors.isEmpty())
        assertNotNull(transport.kv[bookmarkKey("first_manga")])
        assertNotNull(transport.kv[bookmarkKey("second_book")])
        assertNotNull(transport.kv[metadataKey("first_manga")])
        assertNotNull(transport.kv[metadataKey("second_book")])
    }

    @Test
    fun syncOnceOutboundOnlyPushesMissingChatEntries() = runBlocking {
        val repo = newBookRepository()
        val (root, _) = importMokuroBook(repo, "Chat Heavy")
        repo.saveBookmark(root, Bookmark(3, 0.0, 3, 800_000_000.0))
        val historyStore = AiChatHistoryStore()
        val existing = AiChatEntry("a", "p", "m", "r1", 800_000.0)
        val newOne = AiChatEntry("b", "p", "m", "r2", 800_500.0)
        historyStore.append(root, existing)
        historyStore.append(root, newOne)

        // Pre-seed the server with one of the two chat entries.
        val transport = FakeKvTransport()
        val existingSuffix = chatEntryKeySuffix(existing.timestampSeconds, existing.bubbleText, existing.response)
        transport.kv[chatKey("chat_heavy", existingSuffix)] = FakeKvTransport.Stored(
            body = json.encodeToString(
                HttpSyncChatEntryBlob.serializer(),
                HttpSyncChatEntryBlob(existing.bubbleText, existing.prompt, existing.model, existing.response, existing.timestampSeconds),
            ).toByteArray(),
            contentType = "application/json; charset=utf-8",
            lastModified = "2026-05-10T00:00:00Z",
        )

        val manager = managerFor(repo, transport, historyStore)
        val result = manager.syncOnce(configured)

        assertEquals("only the missing entry was uploaded", 1, result.uploadedChatEntries)
        val newSuffix = chatEntryKeySuffix(newOne.timestampSeconds, newOne.bubbleText, newOne.response)
        assertNotNull(transport.kv[chatKey("chat_heavy", newSuffix)])
    }

    @Test
    fun syncOncePushesJapaneseOnlyTitleUsingFallbackSyncId() = runBlocking {
        val repo = newBookRepository()
        val title = "よつばと"
        val (root, _) = importEpubBook(repo, title)
        repo.saveBookmark(root, Bookmark(1, 0.0, 1, 800_000_000.0))

        val transport = FakeKvTransport()
        val manager = managerFor(repo, transport)
        val result = manager.syncOnce(configured)

        val syncId = deriveSyncId(title)!!
        assertEquals(1, result.uploadedBookmarks)
        assertNotNull(transport.kv[bookmarkKey(syncId)])
        assertNotNull(transport.kv[metadataKey(syncId)])
    }

    @Test
    fun syncOnceDoesNotAdvanceCursorFromOutboundWrites() = runBlocking {
        val repo = newBookRepository()
        val (root, _) = importMokuroBook(repo, "Outbound Cursor")
        repo.saveBookmark(root, Bookmark(1, 0.0, 1, 800_000_000.0))

        val transport = FakeKvTransport()
        val manager = managerFor(repo, transport)
        val result = manager.syncOnce(configured.copy(lastSyncedAt = "2030-01-01T00:00:00Z"))

        assertEquals(1, result.uploadedBookmarks)
        assertNull("locally written keys must not advance the inbound cursor", result.newLastSyncedAt)
    }

    // ===== syncOnce — inbound =================================================================

    @Test
    fun syncOnceInboundAppliesNewerBookmarkFromServer() = runBlocking {
        val repo = newBookRepository()
        val (root, _) = importMokuroBook(repo, "Downward Sync")
        repo.saveBookmark(root, Bookmark(chapterIndex = 1, progress = 0.0, characterCount = 1, lastModified = 800_000_000.0))

        val transport = FakeKvTransport()
        transport.putJson(bookmarkKey("downward_sync"), HttpSyncBookmarkBlob.serializer(), HttpSyncBookmarkBlob(
            chapterIndex = 99,
            progress = 0.0,
            characterCount = 100,
            lastModified = "2030-01-01T00:00:00Z", // strictly newer than 800_000_000 apple-sec
        ), json, lastModified = "2030-01-01T00:00:00Z")

        val manager = managerFor(repo, transport)
        val result = manager.syncOnce(configured)

        assertEquals(1, result.downloadedBookmarks)
        val updated = repo.loadBookmark(root)!!
        assertEquals(99, updated.chapterIndex)
    }

    @Test
    fun syncOnceInboundDoesNotDowngradeBookmarkWhenLocalIsNewer() = runBlocking {
        val repo = newBookRepository()
        val (root, _) = importMokuroBook(repo, "Stay Local")
        repo.saveBookmark(root, Bookmark(chapterIndex = 50, progress = 0.0, characterCount = 50, lastModified = 1_000_000_000.0))

        val transport = FakeKvTransport()
        transport.putJson(bookmarkKey("stay_local"), HttpSyncBookmarkBlob.serializer(), HttpSyncBookmarkBlob(
            chapterIndex = 1,
            progress = 0.0,
            characterCount = 1,
            lastModified = "2000-01-01T00:00:00Z",
        ), json, lastModified = "2000-01-01T00:00:00Z")

        val manager = managerFor(repo, transport)
        val result = manager.syncOnce(configured)

        assertEquals("server's older bookmark must not overwrite", 0, result.downloadedBookmarks)
        val unchanged = repo.loadBookmark(root)!!
        assertEquals(50, unchanged.chapterIndex)
    }

    @Test
    fun syncOnceInboundAppendsNewChatEntryAndDedupsExisting() = runBlocking {
        val repo = newBookRepository()
        val (root, _) = importMokuroBook(repo, "Chat Sync")
        repo.saveBookmark(root, Bookmark(1, 0.0, 1, 800_000_000.0))
        val historyStore = AiChatHistoryStore()
        val existing = AiChatEntry("local", "p", "m", "r", 800_000.0)
        historyStore.append(root, existing)

        val transport = FakeKvTransport()
        val incoming = AiChatEntry("remote", "p", "m", "r2", 900_000.0)
        val incomingSuffix = chatEntryKeySuffix(incoming.timestampSeconds, incoming.bubbleText, incoming.response)
        transport.putJson(
            chatKey("chat_sync", incomingSuffix),
            HttpSyncChatEntryBlob.serializer(),
            HttpSyncChatEntryBlob(incoming.bubbleText, incoming.prompt, incoming.model, incoming.response, incoming.timestampSeconds),
            json,
            lastModified = "2030-01-01T00:00:00Z",
        )
        // Also seed the server with the existing entry — manager should NOT re-append.
        val existingSuffix = chatEntryKeySuffix(existing.timestampSeconds, existing.bubbleText, existing.response)
        transport.putJson(
            chatKey("chat_sync", existingSuffix),
            HttpSyncChatEntryBlob.serializer(),
            HttpSyncChatEntryBlob(existing.bubbleText, existing.prompt, existing.model, existing.response, existing.timestampSeconds),
            json,
            lastModified = "2030-01-01T00:00:00Z",
        )

        val manager = managerFor(repo, transport, historyStore)
        val result = manager.syncOnce(configured)

        assertEquals(1, result.downloadedChatEntries)
        val log = historyStore.load(root)
        assertEquals(2, log.entries.size)
        assertTrue(log.entries.any { it.bubbleText == "local" })
        assertTrue(log.entries.any { it.bubbleText == "remote" })
    }

    @Test
    fun syncOnceInboundKeepsDistinctChatResponsesAtSameTimestamp() = runBlocking {
        val repo = newBookRepository()
        val (root, _) = importMokuroBook(repo, "Chat Fork")
        repo.saveBookmark(root, Bookmark(1, 0.0, 1, 800_000_000.0))
        val historyStore = AiChatHistoryStore()
        val local = AiChatEntry("bubble", "p", "m", "first response", 900_000.0)
        historyStore.append(root, local)

        val transport = FakeKvTransport()
        val incoming = AiChatEntry("bubble", "p", "m", "second response", 900_000.0)
        val incomingSuffix = chatEntryKeySuffix(incoming.timestampSeconds, incoming.bubbleText, incoming.response)
        transport.putJson(
            chatKey("chat_fork", incomingSuffix),
            HttpSyncChatEntryBlob.serializer(),
            HttpSyncChatEntryBlob(
                incoming.bubbleText,
                incoming.prompt,
                incoming.model,
                incoming.response,
                incoming.timestampSeconds,
            ),
            json,
            lastModified = "2030-01-01T00:00:00Z",
        )

        val manager = managerFor(repo, transport, historyStore)
        val result = manager.syncOnce(configured)

        assertEquals(1, result.downloadedChatEntries)
        val responses = historyStore.load(root).entries.map { it.response }.toSet()
        assertEquals(setOf("first response", "second response"), responses)
    }

    @Test
    fun syncOnceInboundIgnoresChatEntriesForEpubBooks() = runBlocking {
        val repo = newBookRepository()
        val (root, _) = importEpubBook(repo, "Not Manga")
        repo.saveBookmark(root, Bookmark(1, 0.0, 1, 800_000_000.0))

        val transport = FakeKvTransport()
        val entry = AiChatEntry("x", "p", "m", "r", 100.0)
        val suffix = chatEntryKeySuffix(entry.timestampSeconds, entry.bubbleText, entry.response)
        transport.putJson(
            chatKey("not_manga", suffix),
            HttpSyncChatEntryBlob.serializer(),
            HttpSyncChatEntryBlob(entry.bubbleText, entry.prompt, entry.model, entry.response, entry.timestampSeconds),
            json,
            lastModified = "2030-01-01T00:00:00Z",
        )

        val historyStore = AiChatHistoryStore()
        val manager = managerFor(repo, transport, historyStore)
        val result = manager.syncOnce(configured)

        assertEquals(0, result.downloadedChatEntries)
        assertTrue("no chat log should exist for an EPUB book", historyStore.load(root).entries.isEmpty())
    }

    @Test
    fun syncOnceBackfillsChatEntriesSkippedByAnOlderBadCursor() = runBlocking {
        val repo = newBookRepository()
        val (root, _) = importMokuroBook(repo, "Skipped Chat")
        repo.saveBookmark(root, Bookmark(1, 0.0, 1, 800_000_000.0))

        val transport = FakeKvTransport()
        val skipped = AiChatEntry("missed bubble", "p", "m", "missed reply", 900_000.0)
        transport.putJson(
            chatKey("skipped_chat", chatEntryKeySuffix(skipped.timestampSeconds, skipped.bubbleText, skipped.response)),
            HttpSyncChatEntryBlob.serializer(),
            HttpSyncChatEntryBlob(
                skipped.bubbleText,
                skipped.prompt,
                skipped.model,
                skipped.response,
                skipped.timestampSeconds,
            ),
            json,
            lastModified = "2030-01-01T00:00:00Z",
        )

        val historyStore = AiChatHistoryStore()
        val manager = managerFor(repo, transport, historyStore)
        val result = manager.syncOnce(configured.copy(lastSyncedAt = "2040-01-01T00:00:00Z"))

        assertEquals(1, result.downloadedChatEntries)
        assertEquals("missed bubble", historyStore.load(root).entries.single().bubbleText)
    }

    @Test
    fun syncOnceDoesNotAdvanceCursorFromChatBackfill() = runBlocking {
        val repo = newBookRepository()
        val (root, _) = importMokuroBook(repo, "Backfill Cursor")
        val historyStore = AiChatHistoryStore()
        val skipped = AiChatEntry("late backfill", "p", "m", "reply", 900_000.0)
        val skippedKey = chatKey(
            "backfill_cursor",
            chatEntryKeySuffix(skipped.timestampSeconds, skipped.bubbleText, skipped.response),
        )
        val transport = object : HttpSyncKvTransport by FakeKvTransport() {
            override suspend fun list(prefix: String?, since: String?, cursor: String?, limit: Int?): HttpSyncKvList =
                if (prefix == chatPrefixForBook("backfill_cursor")) {
                    HttpSyncKvList(
                        keys = listOf(
                            HttpSyncKvKeyMeta(
                                key = skippedKey,
                                lastModified = "2099-01-01T00:00:00Z",
                                etag = "etag",
                                size = 1,
                                contentType = "application/json; charset=utf-8",
                            ),
                        ),
                    )
                } else {
                    HttpSyncKvList()
                }

            override suspend fun get(key: String): HttpSyncKvFetched? =
                if (key == skippedKey) {
                    HttpSyncKvFetched(
                        body = json.encodeToString(
                            HttpSyncChatEntryBlob.serializer(),
                            skipped.toBlob(),
                        ).toByteArray(),
                        contentType = "application/json; charset=utf-8",
                        lastModified = "2099-01-01T00:00:00Z",
                        etag = "etag",
                    )
                } else {
                    null
                }

            override suspend fun put(key: String, contentType: String, body: ByteArray): HttpSyncKvWriteResponse =
                HttpSyncKvWriteResponse(key, "2027-01-01T00:00:00Z", "etag", body.size, contentType)
        }
        val manager = managerFor(repo, transport, historyStore)

        val result = manager.syncOnce(configured.copy(lastSyncedAt = "2040-01-01T00:00:00Z"))

        assertEquals(1, result.downloadedChatEntries)
        assertEquals("late backfill", historyStore.load(root).entries.single().bubbleText)
        assertNull("backfill recovery must not move the global incremental cursor", result.newLastSyncedAt)
    }

    @Test
    fun syncOnceReturnsCursorAdvancedToLatestSeenTimestamp() = runBlocking {
        val repo = newBookRepository()
        val (root, _) = importMokuroBook(repo, "Cursor Bump")
        repo.saveBookmark(root, Bookmark(1, 0.0, 1, 800_000_000.0))

        val transport = FakeKvTransport()
        transport.putJson(
            bookmarkKey("cursor_bump"),
            HttpSyncBookmarkBlob.serializer(),
            HttpSyncBookmarkBlob(5, 0.0, 5, "2030-06-01T00:00:00Z"),
            json,
            lastModified = "2030-06-01T12:00:00Z",
        )

        val manager = managerFor(repo, transport)
        val result = manager.syncOnce(configured.copy(lastSyncedAt = "2020-01-01T00:00:00Z"))

        assertNotNull(result.newLastSyncedAt)
        assertTrue(
            "cursor advances past the seen lastModified",
            compareRfc3339(result.newLastSyncedAt!!, "2020-01-01T00:00:00Z") > 0,
        )
    }

    @Test
    fun syncOnceCollectsPerBookErrorsAndContinues() = runBlocking {
        val repo = newBookRepository()
        val (good, _) = importMokuroBook(repo, "Good Book")
        repo.saveBookmark(good, Bookmark(1, 0.0, 1, 800_000_000.0))
        val (bad, _) = importMokuroBook(repo, "Bad Book")
        repo.saveBookmark(bad, Bookmark(2, 0.0, 2, 800_000_000.0))

        val transport = object : HttpSyncKvTransport by FakeKvTransport() {
            override suspend fun put(key: String, contentType: String, body: ByteArray): HttpSyncKvWriteResponse {
                if ("bad_book" in key) throw HttpSyncException("simulated server error")
                return HttpSyncKvWriteResponse(key, "2026-05-15T00:00:00Z", "sha256:00", body.size, contentType)
            }
        }
        val manager = managerFor(repo, transport)
        val result = manager.syncOnce(configured)

        assertTrue(
            "expected at least one bad_book error, got ${result.errors}",
            result.errors.any { it.contains("Bad Book") || it.contains("simulated") },
        )
        // The good book's bookmark must still have been pushed.
        assertEquals(1, result.uploadedBookmarks)
    }

    @Test
    fun syncOnceRequiresConfiguredSettings() {
        val repo = newBookRepository()
        val manager = managerFor(repo, FakeKvTransport())
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { manager.syncOnce(HttpSyncSettings()) }
        }
    }

    @Test
    fun isConfiguredRequiresBothUrlAndToken() {
        assertFalse(HttpSyncSettings(baseUrl = "", bearerToken = "t").isConfigured)
        assertFalse(HttpSyncSettings(baseUrl = "https://x", bearerToken = "").isConfigured)
        assertFalse(HttpSyncSettings(baseUrl = "", bearerToken = "").isConfigured)
        // `isNotBlank()` catches whitespace-only — repository trims on write anyway, but the
        // data class itself is defensive.
        assertFalse(HttpSyncSettings(baseUrl = "https://x", bearerToken = " ").isConfigured)
        assertFalse(HttpSyncSettings(baseUrl = "   ", bearerToken = "t").isConfigured)
        assertTrue(HttpSyncSettings(baseUrl = "https://x", bearerToken = "t").isConfigured)
    }

    @Test
    fun syncOnceDoesNotAdvanceCursorWhenNothingChanges() = runBlocking {
        // Empty local + empty server → no inbound, no outbound, cursor stays put.
        val repo = newBookRepository()
        val transport = FakeKvTransport()
        val manager = managerFor(repo, transport)
        val result = manager.syncOnce(configured.copy(lastSyncedAt = "2020-01-01T00:00:00Z"))
        assertEquals(0, result.uploadedBookmarks)
        assertEquals(0, result.downloadedBookmarks)
        assertNull("cursor must not advance when there is nothing to do", result.newLastSyncedAt)
    }

    @Test
    fun syncOncePaginatesAcrossTruncatedListResponses() = runBlocking {
        val repo = newBookRepository()
        val (root, _) = importMokuroBook(repo, "Paginated Book")
        repo.saveBookmark(root, Bookmark(1, 0.0, 1, 800_000_000.0))

        // Server has a bookmark + 3 chat entries split across 2 pages of 2 each.
        val transport = object : HttpSyncKvTransport {
            val data: MutableMap<String, FakeKvTransport.Stored> = linkedMapOf()
            override suspend fun put(key: String, contentType: String, body: ByteArray) = error("unused")
            override suspend fun get(key: String): HttpSyncKvFetched? {
                val s = data[key] ?: return null
                return HttpSyncKvFetched(s.body, s.contentType, s.lastModified, "etag")
            }
            override suspend fun list(prefix: String?, since: String?, cursor: String?, limit: Int?): HttpSyncKvList {
                val all = data.entries
                    .filter { prefix == null || it.key.startsWith(prefix) }
                    .sortedBy { it.key }
                val pageSize = 2
                val start = cursor?.let { c -> all.indexOfFirst { it.key > c }.coerceAtLeast(0) } ?: 0
                val end = (start + pageSize).coerceAtMost(all.size)
                val slice = all.subList(start, end)
                val truncated = end < all.size
                val nextCursor = if (truncated) slice.last().key else null
                val keys = slice.map { (k, s) -> HttpSyncKvKeyMeta(k, s.lastModified, "etag", s.body.size, s.contentType) }
                return HttpSyncKvList(keys = keys, truncated = truncated, nextCursor = nextCursor)
            }
            override suspend fun delete(key: String) { data.remove(key) }
        }
        // Seed the pages with a bookmark + chat entries.
        listOf(
            chatKey("paginated_book", "2030-01-01T00:00:00.000Z-aaaa") to
                HttpSyncChatEntryBlob("a", "p", "m", "r", 1.0),
            chatKey("paginated_book", "2030-01-01T00:00:01.000Z-bbbb") to
                HttpSyncChatEntryBlob("b", "p", "m", "r", 2.0),
            chatKey("paginated_book", "2030-01-01T00:00:02.000Z-cccc") to
                HttpSyncChatEntryBlob("c", "p", "m", "r", 3.0),
        ).forEach { (k, blob) ->
            transport.data[k] = FakeKvTransport.Stored(
                body = json.encodeToString(HttpSyncChatEntryBlob.serializer(), blob).toByteArray(),
                contentType = "application/json; charset=utf-8",
                lastModified = "2030-01-01T00:00:00Z",
            )
        }
        // Reconciler talks list/get only — push side is bypassed via FakeKvTransport.put = error.
        // We test inbound pagination by giving an empty local chat log; all 3 chat entries
        // should make it across despite the 2-per-page split.
        val historyStore = AiChatHistoryStore()
        val readOnly = HttpSyncReconciler(
            bookRepository = repo,
            aiHistoryStore = historyStore,
            transportFactory = {
                // Wrap to make PUTs no-op so the outbound push doesn't fail the test.
                object : HttpSyncKvTransport by transport {
                    override suspend fun put(key: String, contentType: String, body: ByteArray) =
                        HttpSyncKvWriteResponse(key, "2030-01-01T00:00:00Z", "etag", body.size, contentType)

                    override suspend fun putFile(key: String, contentType: String, file: File) =
                        HttpSyncKvWriteResponse(key, "2030-01-01T00:00:00Z", "etag", file.length().toInt(), contentType)
                }
            },
            ioDispatcher = kotlinx.coroutines.Dispatchers.Unconfined,
        )
        val result = readOnly.syncOnce(configured)
        assertEquals(3, result.downloadedChatEntries)
        assertEquals(3, historyStore.load(root).entries.size)
    }

    @Test
    fun pushBookmarkExceptionPropagatesToCaller() = runBlocking {
        val repo = newBookRepository()
        val (root, _) = importMokuroBook(repo, "Failing")
        repo.saveBookmark(root, Bookmark(1, 0.0, 1, 800_000_000.0))
        val explodingTransport = object : HttpSyncKvTransport by FakeKvTransport() {
            override suspend fun put(key: String, contentType: String, body: ByteArray): HttpSyncKvWriteResponse =
                throw HttpSyncException("simulated 5xx")
        }
        val manager = managerFor(repo, explodingTransport)
        val ex = assertThrows(HttpSyncException::class.java) {
            runBlocking { manager.pushBookmark(root, "Failing", configured) }
        }
        assertTrue("expected simulated message, got '${ex.message}'", "simulated" in ex.message!!)
    }

    @Test
    fun pushChatEntryExceptionPropagatesToCaller() = runBlocking {
        val repo = newBookRepository()
        val explodingTransport = object : HttpSyncKvTransport by FakeKvTransport() {
            override suspend fun put(key: String, contentType: String, body: ByteArray): HttpSyncKvWriteResponse =
                throw HttpSyncException("server burning")
        }
        val manager = managerFor(repo, explodingTransport)
        val ex = assertThrows(HttpSyncException::class.java) {
            runBlocking {
                manager.pushChatEntry("Title", AiChatEntry("x", "p", "m", "r", 0.0), configured)
            }
        }
        assertTrue("server burning" in ex.message!!)
    }

    @Test
    fun applyBookmarkSurvivesKeyGoingAwayBeforeFetch() = runBlocking {
        val repo = newBookRepository()
        val (root, _) = importMokuroBook(repo, "Vanishing")
        repo.saveBookmark(root, Bookmark(1, 0.0, 1, 800_000_000.0))
        val transport = object : HttpSyncKvTransport by FakeKvTransport() {
            override suspend fun list(prefix: String?, since: String?, cursor: String?, limit: Int?): HttpSyncKvList =
                HttpSyncKvList(
                    keys = listOf(
                        HttpSyncKvKeyMeta(
                            key = bookmarkKey("vanishing"),
                            lastModified = "2030-01-01T00:00:00Z",
                            etag = "etag",
                            size = 100,
                            contentType = "application/json; charset=utf-8",
                        ),
                    ),
                    truncated = false,
                    nextCursor = null,
                )
            override suspend fun get(key: String): HttpSyncKvFetched? = null // 404 — disappeared between list and get
            override suspend fun put(key: String, contentType: String, body: ByteArray) =
                HttpSyncKvWriteResponse(key, "2030-01-01T00:00:00Z", "etag", body.size, contentType)
        }
        val manager = managerFor(repo, transport)
        val result = manager.syncOnce(configured)
        assertEquals(0, result.downloadedBookmarks)
        // Outbound still ran — the absent key didn't poison the whole sync.
        assertTrue(result.errors.isEmpty())
    }

    @Test
    fun malformedBookmarkBlobIsReportedAsPerBookError() = runBlocking {
        val repo = newBookRepository()
        val (root, _) = importMokuroBook(repo, "Bad JSON")
        repo.saveBookmark(root, Bookmark(1, 0.0, 1, 800_000_000.0))
        val transport = FakeKvTransport()
        transport.kv[bookmarkKey("bad_json")] = FakeKvTransport.Stored(
            body = "this is not json {".toByteArray(),
            contentType = "application/json; charset=utf-8",
            lastModified = "2030-01-01T00:00:00Z",
        )
        val manager = managerFor(repo, transport)
        val result = manager.syncOnce(configured)
        assertEquals("malformed JSON should not crash; reported as error", 0, result.downloadedBookmarks)
        assertTrue(
            "expected the per-key error, got ${result.errors}",
            result.errors.any { "bad_json" in it && "malformed" in it.lowercase() },
        )
    }

    @Test
    fun encodeKeyHandlesTimestampWithColons() {
        // Reconstruct what HttpSyncKvClient.encodeKey does — colons must survive
        // (or be encoded consistently). The real chat key has the form
        // books/{syncId}/chat/2026-05-15T12:34:56.789Z-abcd.
        val client = HttpSyncKvClient(baseUrl = "https://x", bearerToken = "t")
        val encode = HttpSyncKvClient::class.java.getDeclaredMethod("encodeKey", String::class.java)
        encode.isAccessible = true
        val key = "books/yotsubato_01/chat/2026-05-15T12:34:56.789Z-abcdef"
        val encoded = encode.invoke(client, key) as String
        // Slashes preserved, segments percent-encoded only where needed.
        assertTrue("encoded path keeps slashes: $encoded", encoded.startsWith("books/"))
        assertEquals("dots and dashes don't get encoded", -1, encoded.indexOf("%2D"))
        assertEquals("dots don't get encoded", -1, encoded.indexOf("%2E"))
        // Colon is encoded by URLEncoder as %3A — that's fine, the server's path decoder
        // restores it. The key thing is no `+` slipped in (since URLEncoder maps spaces to +).
        assertEquals("no '+' in output (we mapped them to %20)", -1, encoded.indexOf('+'))
    }

    @Test
    fun manualSyncIgnoresStaleCursorAndStillDownloadsNewBooks() = runBlocking {
        // Regression for the user-reported bug: device A uploaded new books, but device B's
        // persisted lastSyncedAt cursor was somehow stamped at a later wallclock than the
        // new uploads (clock skew, an older build that markHandled keys it didn't apply,
        // partial sync, etc). With a `since=cursor` filter on the listing, device B's
        // server-side LIST returns zero keys and the books never download. The fix is to
        // always do a full pull on manual `Sync now`.

        val repoA = newBookRepository()
        val (rootA, _) = importMokuroBook(repoA, "Stale Cursor Book")
        rootA.resolve("pages").mkdirs()
        rootA.resolve("pages/p1.png").writeBytes(byteArrayOf(0x77))
        val transport = StaleCursorTransport()
        val managerA = managerFor(repoA, transport)
        managerA.syncOnce(configured)
        assertNotNull(transport.kv["books/stale_cursor_book/payload.manifest"])

        val repoB = newBookRepository()
        val managerB = managerFor(repoB, transport)
        // Device B's cursor is from THE FUTURE relative to device A's uploads.
        val futureCursor = "2099-12-31T23:59:59Z"
        val result = managerB.syncOnce(configured.copy(lastSyncedAt = futureCursor))

        assertTrue(
            "transport should have received list calls without a since= filter",
            transport.listsObserved.isNotEmpty(),
        )
        assertTrue(
            "every list call from a manual sync must omit since= (got: ${transport.listsObserved})",
            transport.listsObserved.all { it == null },
        )
        assertEquals("device B should have no errors", emptyList<String>(), result.errors)
        assertEquals(
            "device B should download the manga even though its cursor is in the future",
            1,
            result.downloadedPayloads,
        )
        val titles = repoB.loadBookEntries().map { it.metadata.title }
        assertTrue("device B should now have the book", titles.contains("Stale Cursor Book"))
    }

    /** FakeKvTransport plus a record of every `since` value the reconciler passed to `list`. */
    private class StaleCursorTransport : HttpSyncKvTransport {
        private val inner = FakeKvTransport()
        val kv: MutableMap<String, FakeKvTransport.Stored> get() = inner.kv
        val listsObserved: MutableList<String?> = mutableListOf()

        override suspend fun put(key: String, contentType: String, body: ByteArray): HttpSyncKvWriteResponse =
            inner.put(key, contentType, body)

        override suspend fun putFile(key: String, contentType: String, file: File): HttpSyncKvWriteResponse =
            inner.putFile(key, contentType, file)

        override suspend fun get(key: String): HttpSyncKvFetched? = inner.get(key)

        override suspend fun downloadToFile(key: String, targetFile: File): HttpSyncKvFileFetched? =
            inner.downloadToFile(key, targetFile)

        override suspend fun list(
            prefix: String?,
            since: String?,
            cursor: String?,
            limit: Int?,
        ): HttpSyncKvList {
            // Only record the top-level books/ listing; chat-backfill listings are a separate
            // concern and DO still use the cursor's prefix.
            if (prefix == ALL_BOOKS_PREFIX) listsObserved += since
            return inner.list(prefix, since, cursor, limit)
        }

        override suspend fun delete(key: String) = inner.delete(key)
    }

    @Test
    fun incrementalSyncDownloadsBookAddedOnDeviceASinceLastSync() = runBlocking {
        // Regression: device B already synced once (so its lastSyncedAt is non-null), then
        // device A imports a NEW book and syncs. Device B's next syncOnce must pick it up
        // even though `since=` filters out everything that existed at the previous sync.

        val repoA = newBookRepository()
        val (firstA, _) = importMokuroBook(repoA, "First Manga")
        firstA.resolve("pages").mkdirs()
        firstA.resolve("pages/p1.png").writeBytes(byteArrayOf(0x01))
        val transport = FakeKvTransport()
        val managerA = managerFor(repoA, transport)

        // Device A's initial push.
        managerA.syncOnce(configured)
        assertNotNull(transport.kv["books/first_manga/payload.manifest"])

        // Device B's initial sync — picks up "First Manga" and persists a cursor.
        val repoB = newBookRepository()
        val managerB = managerFor(repoB, transport)
        val initial = managerB.syncOnce(configured)
        assertEquals(1, initial.downloadedPayloads)
        val cursorAfterFirstSync = initial.newLastSyncedAt
        assertNotNull("first sync should produce a cursor", cursorAfterFirstSync)

        // Device A imports a second manga and pushes it.
        val (secondA, _) = importMokuroBook(repoA, "Second Manga")
        secondA.resolve("pages").mkdirs()
        secondA.resolve("pages/p1.png").writeBytes(byteArrayOf(0x02))
        managerA.syncOnce(configured)
        assertNotNull(
            "device A should have uploaded the second manga's manifest",
            transport.kv["books/second_manga/payload.manifest"],
        )

        // Device B's incremental sync — must pick up the new book.
        val incremental = managerB.syncOnce(configured.copy(lastSyncedAt = cursorAfterFirstSync))
        assertEquals("incremental sync should have no errors", emptyList<String>(), incremental.errors)
        assertEquals(
            "device B's incremental sync should download the new manga from device A",
            1,
            incremental.downloadedPayloads,
        )
        val titles = repoB.loadBookEntries().map { it.metadata.title }
        assertTrue("device B should now have First Manga", titles.contains("First Manga"))
        assertTrue("device B should now have Second Manga", titles.contains("Second Manga"))
    }

    @Test
    fun freshDeviceSyncDownloadsBookAfterDeviceAFullOutboundPush() = runBlocking {
        // Regression: device A imports a Mokuro book and runs a normal outbound sync — that
        // writes `books/{syncId}/metadata` and the `payload.*` keys. Device B (fresh, empty
        // BookRepository) then runs syncOnce against the same transport and must end up
        // with the imported book on its shelf.
        //
        // Reproduces the user-reported bug "I uploaded new books from one device and the
        // other device doesn't pick them up": the metadata key was being marked handled
        // before the payload import had a chance to materialize a local root.

        val repoA = newBookRepository()
        val (rootA, _) = importMokuroBook(repoA, "Cross Device Book")
        rootA.resolve("pages").mkdirs()
        rootA.resolve("pages/p1.png").writeBytes(byteArrayOf(0x11))
        val transport = FakeKvTransport()
        val managerA = managerFor(repoA, transport)

        // Device A pushes everything to the fake server.
        val resultA = managerA.syncOnce(configured)
        assertEquals(emptyList<String>(), resultA.errors)
        assertEquals("device A should have uploaded one metadata blob", 1, resultA.uploadedMetadata)
        assertEquals("device A should have uploaded one payload", 1, resultA.uploadedPayloads)
        assertNotNull(
            "device A should have left a metadata key on the server",
            transport.kv[metadataKey("cross_device_book")],
        )
        assertNotNull(
            "device A should have left a payload manifest on the server",
            transport.kv["books/cross_device_book/payload.manifest"],
        )

        // Device B is a brand-new install: empty repo, fresh history, no shelves.
        val repoB = newBookRepository()
        val managerB = managerFor(repoB, transport)

        val resultB = managerB.syncOnce(configured)

        assertEquals("device B should have no errors", emptyList<String>(), resultB.errors)
        assertEquals(
            "device B should have downloaded the payload from device A",
            1,
            resultB.downloadedPayloads,
        )
        val downloaded = repoB.loadBookEntries().single { deriveSyncId(it.metadata.title) == "cross_device_book" }
        assertEquals("Cross Device Book", downloaded.metadata.title)
    }

    @Test
    fun freshDeviceSyncPopulatesCoverPathSoBookshelfDoesNotShowBlankCover() = runBlocking {
        // Regression: when a book is materialized via the v2 inbound import path
        // (`importRemoteOnlyBook`), the resulting metadata sidecar must carry a `cover` field
        // pointing at a page in the freshly-unpacked book — the same shape user-side imports
        // produce. Before the fix, the sync path wrote `cover = null` and the bookshelf
        // showed a blank slot until the user opened the book, which kicked the parser via
        // `BookshelfRepository.openBook` and rewrote metadata.
        val syncId = "cover_book"
        val title = "Cover Book"
        val transport = FakeKvTransport()
        // Stage a valid Mokuro payload on the server so the receiving device parses it after
        // unpacking. `mokuro.json` references `pages/0001.jpg` as page 0; that's what the
        // parser surfaces as `coverImagePath`.
        run {
            val srcRoot = tempFolder.newFolder("cover-book-src")
            srcRoot.resolve("mokuro.json").writeText(
                """{"version":"1.0","title":"Cover Book","pages":[{"img_path":"pages/0001.jpg","img_width":100,"img_height":200,"blocks":[]}]}""",
            )
            srcRoot.resolve("pages").mkdirs()
            srcRoot.resolve("pages/0001.jpg").writeBytes(byteArrayOf(0x42, 0x43, 0x44))
            HttpSyncPayloadCodec(kotlinx.coroutines.Dispatchers.Unconfined)
                .uploadIfChanged(transport, syncId, srcRoot, title, HttpSyncContentType.Mokuro)
        }

        val repo = newBookRepository()
        val reconciler = HttpSyncReconciler(
            bookRepository = repo,
            transportFactory = { transport },
            ioDispatcher = kotlinx.coroutines.Dispatchers.Unconfined,
        )
        val result = reconciler.syncOnce(configured)

        assertEquals(emptyList<String>(), result.errors)
        assertEquals(1, result.downloadedPayloads)

        val imported = repo.loadBookEntries().single { deriveSyncId(it.metadata.title) == syncId }
        assertNotNull(
            "cover path must be populated after sync import so the bookshelf renders a thumbnail",
            imported.metadata.cover,
        )
        // `metadataCoverPath` copies the cover to the book root and returns an iOS-style
        // `Books/{folder}/{name}` path. The exact value here is the implementation's contract.
        assertEquals(
            "Books/${imported.root.name}/0001.jpg",
            imported.metadata.cover,
        )
        // The cover file must actually resolve to bytes on disk via the repository's
        // bookshelf-loader path; otherwise the cover slot would still render blank.
        val coverFile = repo.coverFile(imported)
        assertNotNull("repo must resolve metadata.cover to a real file", coverFile)
        assertTrue("cover file exists", coverFile!!.isFile)
    }

    @Test
    fun freshDeviceSyncDownloadsPayloadBookmarkAndChatInOnePass() = runBlocking {
        // Regression: on a fresh device with no local books, a single syncOnce must
        // download the payload AND the bookmark AND the chat entries — even though the
        // server returns them in lex order (`bookmark` before `payload.manifest`).
        //
        // Before the fix, the bookmark / chat keys were seen first, the local book didn't
        // exist yet, so they were silently skipped. Only the payload was applied.

        val filesDir = tempFolder.newFolder("files")
        val repo = BookRepository(filesDir)
        val transport = FakeKvTransport()
        val syncId = "fresh_device_book"
        val title = "Fresh Device Book"

        // Stage the "other device" state on the server: payload zip + manifest + bookmark + chat.
        run {
            val srcRoot = tempFolder.newFolder("device-a")
            srcRoot.resolve("mokuro.json").writeText("""{"v":1}""")
            srcRoot.resolve("pages").mkdirs()
            srcRoot.resolve("pages/p1.png").writeBytes(byteArrayOf(0x42))
            HttpSyncPayloadCodec(kotlinx.coroutines.Dispatchers.Unconfined)
                .uploadIfChanged(transport, syncId, srcRoot, title, HttpSyncContentType.Mokuro)
        }
        transport.kv[bookmarkKey(syncId)] = FakeKvTransport.Stored(
            body = json.encodeToString(
                HttpSyncBookmarkBlob.serializer(),
                HttpSyncBookmarkBlob(chapterIndex = 42, progress = 0.0, characterCount = 100, lastModified = "2030-01-01T00:00:00Z"),
            ).toByteArray(),
            contentType = "application/json; charset=utf-8",
            lastModified = "2030-01-01T00:00:00Z",
        )
        val chatBlob = HttpSyncChatEntryBlob("hello", "p", "m", "world", 12345.0)
        val chatSuffix = chatEntryKeySuffix(chatBlob.timestampSeconds, chatBlob.bubbleText, chatBlob.response)
        transport.kv[chatKey(syncId, chatSuffix)] = FakeKvTransport.Stored(
            body = json.encodeToString(HttpSyncChatEntryBlob.serializer(), chatBlob).toByteArray(),
            contentType = "application/json; charset=utf-8",
            lastModified = "2030-01-01T00:00:00Z",
        )

        // Fresh device: empty BookRepository, no AI history.
        val historyStore = AiChatHistoryStore()
        val reconciler = HttpSyncReconciler(
            bookRepository = repo,
            aiHistoryStore = historyStore,
            transportFactory = { transport },
            ioDispatcher = kotlinx.coroutines.Dispatchers.Unconfined,
        )

        val result = reconciler.syncOnce(configured)

        assertEquals("payload should have been downloaded", 1, result.downloadedPayloads)
        assertEquals("bookmark should have been downloaded in the SAME sync", 1, result.downloadedBookmarks)
        assertEquals("chat entry should have been downloaded in the SAME sync", 1, result.downloadedChatEntries)

        // The book is now on the shelf with bookmark + chat in place.
        val importedBook = repo.loadBookEntries().single { deriveSyncId(it.metadata.title) == syncId }
        assertEquals(42, repo.loadBookmark(importedBook.root)!!.chapterIndex)
        assertEquals(1, historyStore.load(importedBook.root).entries.size)
        assertEquals("hello", historyStore.load(importedBook.root).entries.single().bubbleText)
    }

    @Test
    fun missingRemoteOnlyBookDoesNotAdvanceCursorUntilPayloadArrives() = runBlocking {
        val repo = newBookRepository()
        val transport = FakeKvTransport()
        val historyStore = AiChatHistoryStore()
        val manager = managerFor(repo, transport, historyStore)
        val syncId = "late_payload_book"
        val originalCursor = "2020-01-01T00:00:00Z"

        transport.putJson(
            key = bookmarkKey(syncId),
            serializer = HttpSyncBookmarkBlob.serializer(),
            value = HttpSyncBookmarkBlob(9, 0.0, 9, "2030-01-01T00:00:00Z"),
            json = json,
            lastModified = "2030-01-01T00:00:00Z",
        )
        val chatBlob = HttpSyncChatEntryBlob("late", "p", "m", "payload", 123.0)
        transport.putJson(
            key = chatKey(syncId, chatEntryKeySuffix(chatBlob.timestampSeconds, chatBlob.bubbleText, chatBlob.response)),
            serializer = HttpSyncChatEntryBlob.serializer(),
            value = chatBlob,
            json = json,
            lastModified = "2030-01-01T00:00:01Z",
        )

        val first = manager.syncOnce(configured.copy(lastSyncedAt = originalCursor))

        assertEquals(0, first.downloadedBookmarks)
        assertEquals(0, first.downloadedChatEntries)
        assertEquals(1, first.remoteOnlyBooks)
        assertNull("unapplied bookmark/chat keys must keep the persisted cursor unchanged", first.newLastSyncedAt)

        uploadRemoteMokuroPayload(transport, syncId, "Late Payload Book", "late-payload-source")
        val second = manager.syncOnce(configured.copy(lastSyncedAt = first.newLastSyncedAt ?: originalCursor))

        assertEquals(1, second.downloadedPayloads)
        assertEquals(1, second.downloadedBookmarks)
        assertEquals(1, second.downloadedChatEntries)
        val imported = repo.loadBookEntries().single { deriveSyncId(it.metadata.title) == syncId }
        assertEquals(9, repo.loadBookmark(imported.root)!!.chapterIndex)
        assertEquals("late", historyStore.load(imported.root).entries.single().bubbleText)
    }

    @Test
    fun syncOnceAppliesNewerBookmarkDiscoveredDuringOutboundGuard() = runBlocking {
        val repo = newBookRepository()
        val (root, _) = importEpubBook(repo, "Concurrent Bookmark")
        repo.saveBookmark(root, Bookmark(1, 0.0, 1, 800_000_000.0))

        val base = FakeKvTransport()
        base.putJson(
            key = bookmarkKey("concurrent_bookmark"),
            serializer = HttpSyncBookmarkBlob.serializer(),
            value = HttpSyncBookmarkBlob(99, 0.0, 99, "2099-01-01T00:00:00Z"),
            json = json,
            lastModified = "2099-01-01T00:00:00Z",
        )
        val transport = object : HttpSyncKvTransport {
            override suspend fun put(key: String, contentType: String, body: ByteArray): HttpSyncKvWriteResponse =
                base.put(key, contentType, body)

            override suspend fun get(key: String): HttpSyncKvFetched? =
                base.get(key)

            override suspend fun list(prefix: String?, since: String?, cursor: String?, limit: Int?): HttpSyncKvList =
                if (prefix == ALL_BOOKS_PREFIX) {
                    HttpSyncKvList()
                } else {
                    base.list(prefix, since, cursor, limit)
                }

            override suspend fun delete(key: String) {
                base.delete(key)
            }
        }

        val manager = managerFor(repo, transport)
        val result = manager.syncOnce(configured)

        assertEquals(0, result.uploadedBookmarks)
        assertEquals(99, repo.loadBookmark(root)!!.chapterIndex)
    }

    @Test
    fun pushBookmarkDoesNotOverwriteNewerServerBookmark() = runBlocking {
        // Device A is stale on page 50 (T1); server has page 100 (T2 > T1) from device B.
        // The fix: pushBookmark must fetch + compare, refuse to clobber, pull instead.
        val repo = newBookRepository()
        val (root, _) = importMokuroBook(repo, "Stale Push")
        val staleApple = 800_000_000.0
        repo.saveBookmark(root, Bookmark(chapterIndex = 50, progress = 0.0, characterCount = 50, lastModified = staleApple))

        val transport = FakeKvTransport()
        // Server has a NEWER bookmark (lexicographically > the stale one's RFC).
        val serverStamp = "2099-01-01T00:00:00Z"
        transport.putJson(
            key = bookmarkKey("stale_push"),
            serializer = HttpSyncBookmarkBlob.serializer(),
            value = HttpSyncBookmarkBlob(chapterIndex = 100, progress = 0.0, characterCount = 100, lastModified = serverStamp),
            json = json,
            lastModified = serverStamp,
        )

        val pusher = HttpSyncPusher(
            bookRepository = repo,
            transportFactory = { transport },
            ioDispatcher = kotlinx.coroutines.Dispatchers.Unconfined,
        )
        pusher.pushBookmark(root, "Stale Push", configured)

        // Server's bookmark is unchanged — we did NOT overwrite it.
        val serverNow = transport.kv[bookmarkKey("stale_push")]!!
        val serverBlob = json.decodeFromString(HttpSyncBookmarkBlob.serializer(), serverNow.body.toString(Charsets.UTF_8))
        assertEquals("server bookmark must not be clobbered", 100, serverBlob.chapterIndex)
        // And local got pulled forward to match.
        assertEquals("local should have been bumped to server's value", 100, repo.loadBookmark(root)!!.chapterIndex)
    }

    @Test
    fun pushBookmarkPushesWhenLocalIsStrictlyNewer() = runBlocking {
        val repo = newBookRepository()
        val (root, _) = importMokuroBook(repo, "Fresh Push")
        // Local apple-seconds → RFC will be NEWER than the server's old stamp.
        repo.saveBookmark(root, Bookmark(chapterIndex = 100, progress = 0.0, characterCount = 100, lastModified = 1_000_000_000.0))

        val transport = FakeKvTransport()
        transport.putJson(
            key = bookmarkKey("fresh_push"),
            serializer = HttpSyncBookmarkBlob.serializer(),
            value = HttpSyncBookmarkBlob(chapterIndex = 1, progress = 0.0, characterCount = 1, lastModified = "2000-01-01T00:00:00Z"),
            json = json,
            lastModified = "2000-01-01T00:00:00Z",
        )

        val pusher = HttpSyncPusher(
            bookRepository = repo,
            transportFactory = { transport },
            ioDispatcher = kotlinx.coroutines.Dispatchers.Unconfined,
        )
        pusher.pushBookmark(root, "Fresh Push", configured)

        val serverNow = transport.kv[bookmarkKey("fresh_push")]!!
        val serverBlob = json.decodeFromString(HttpSyncBookmarkBlob.serializer(), serverNow.body.toString(Charsets.UTF_8))
        assertEquals("local is newer → push wins", 100, serverBlob.chapterIndex)
    }

    @Test
    fun reconcilerPreservesServerDeletionTombstoneOnMetadataPush() = runBlocking {
        // If another device set `deletedAt`, our metadata push must not erase it.
        val repo = newBookRepository()
        val (root, _) = importMokuroBook(repo, "Tombstone Book")
        repo.saveBookmark(root, Bookmark(1, 0.0, 1, 800_000_000.0))

        val transport = FakeKvTransport()
        transport.putJson(
            key = metadataKey("tombstone_book"),
            serializer = HttpSyncMetadataBlob.serializer(),
            value = HttpSyncMetadataBlob(
                title = "Tombstone Book",
                contentType = HttpSyncContentType.Mokuro,
                deletedAt = "2030-06-01T00:00:00Z",
            ),
            json = json,
            lastModified = "2030-06-01T00:00:00Z",
        )

        val manager = managerFor(repo, transport)
        manager.syncOnce(configured)

        // After the sync, the server's metadata still has the tombstone preserved.
        val finalMeta = json.decodeFromString(
            HttpSyncMetadataBlob.serializer(),
            transport.kv[metadataKey("tombstone_book")]!!.body.toString(Charsets.UTF_8),
        )
        assertEquals(
            "deletion tombstone must survive the outbound metadata push",
            "2030-06-01T00:00:00Z",
            finalMeta.deletedAt,
        )
    }

    @Test
    fun syncOnceAppliesRemoteDeletionTombstone() = runBlocking {
        val repo = newBookRepository()
        importMokuroBook(repo, "Deleted Remotely")
        val transport = FakeKvTransport()
        transport.putJson(
            key = metadataKey("deleted_remotely"),
            serializer = HttpSyncMetadataBlob.serializer(),
            value = HttpSyncMetadataBlob(
                title = "Deleted Remotely",
                contentType = HttpSyncContentType.Mokuro,
                deletedAt = "2030-06-01T00:00:00Z",
            ),
            json = json,
            lastModified = "2030-06-01T00:00:00Z",
        )
        val manager = managerFor(repo, transport)

        val result = manager.syncOnce(configured)

        assertEquals(emptyList<String>(), result.errors)
        assertTrue(repo.loadBookEntries().isEmpty())
        assertEquals(0, result.remoteOnlyBooks)
    }

    @Test
    fun syncOnceTreatsRemoteTombstoneForMissingLocalBookAsHandled() = runBlocking {
        val repo = newBookRepository()
        val transport = FakeKvTransport()
        transport.putJson(
            key = metadataKey("already_gone"),
            serializer = HttpSyncMetadataBlob.serializer(),
            value = HttpSyncMetadataBlob(
                title = "Already Gone",
                contentType = HttpSyncContentType.Mokuro,
                deletedAt = "2030-06-01T00:00:00Z",
            ),
            json = json,
            lastModified = "2030-06-01T00:00:00Z",
        )
        val manager = managerFor(repo, transport)

        val result = manager.syncOnce(configured.copy(lastSyncedAt = "2020-01-01T00:00:00Z"))

        assertEquals(emptyList<String>(), result.errors)
        assertEquals(0, result.remoteOnlyBooks)
        assertNotNull("rootless tombstone should not pin the cursor", result.newLastSyncedAt)
    }

    @Test
    fun syncOnceSkipsRemotePayloadWhenMetadataTombstoneExists() = runBlocking {
        val repo = newBookRepository()
        val transport = FakeKvTransport()
        transport.putJson(
            key = metadataKey("deleted_payload"),
            serializer = HttpSyncMetadataBlob.serializer(),
            value = HttpSyncMetadataBlob(
                title = "Deleted Payload",
                contentType = HttpSyncContentType.Mokuro,
                deletedAt = "2030-06-01T00:00:00Z",
            ),
            json = json,
            lastModified = "2030-06-01T00:00:00Z",
        )
        transport.putJson(
            key = payloadManifestKey("deleted_payload"),
            serializer = HttpSyncPayloadManifest.serializer(),
            value = HttpSyncPayloadManifest(
                sha256 = "sha256:missing",
                sizeBytes = 123,
                originalName = "Deleted Payload",
                format = HttpSyncContentType.Mokuro,
            ),
            json = json,
            lastModified = "2030-06-01T00:00:01Z",
        )
        val manager = managerFor(repo, transport)

        val result = manager.syncOnce(configured.copy(lastSyncedAt = "2020-01-01T00:00:00Z"))

        assertEquals(emptyList<String>(), result.errors)
        assertEquals(0, result.downloadedPayloads)
        assertEquals(0, result.remoteOnlyBooks)
        assertNotNull("tombstoned payload manifest should not pin the cursor", result.newLastSyncedAt)
    }

    @Test
    fun syncOnceUploadsPendingLocalDeletionTombstoneAndClearsIt() = runBlocking {
        val repo = newBookRepository()
        HttpSyncDeletedBookStateStore(json).recordDeletedBook(
            booksRoot = repo.booksDirectory,
            syncId = "locally_deleted",
            record = HttpSyncDeletedBookRecord(
                title = "Locally Deleted",
                contentType = HttpSyncContentType.Mokuro,
                deletedAt = "2030-06-01T00:00:00Z",
            ),
        )
        val transport = FakeKvTransport()
        val manager = managerFor(repo, transport)

        val result = manager.syncOnce(configured)

        assertEquals(emptyList<String>(), result.errors)
        assertEquals(1, result.uploadedMetadata)
        val uploaded = json.decodeFromString(
            HttpSyncMetadataBlob.serializer(),
            transport.kv[metadataKey("locally_deleted")]!!.body.toString(Charsets.UTF_8),
        )
        assertEquals("2030-06-01T00:00:00Z", uploaded.deletedAt)
        assertTrue(HttpSyncDeletedBookStateStore(json).load(repo.booksDirectory).isEmpty())
    }

    /**
     * Regression for the v2 "delete lost on the user's other devices" bug.
     *
     * Scenario: the user deletes a book locally (so the local book root is gone and a
     * tombstone is staged in `.http_sync_deleted_books.json`), but the server still has the
     * **live** metadata + payload manifest from a previous sync. They tap "Sync now".
     *
     * Pre-fix, `pullChangedKeys` ran first and Pass 3 re-imported the remote payload
     * because the metadata's `deletedAt` was still null. That re-created the local book,
     * pushed `liveLocalSyncIds` to include the syncId, made `tombstonesToPush` empty in
     * `pushAllLocal`, cleared the staged tombstone from disk, and finally PUT plain
     * metadata (`deletedAt = null`) — silently overwriting the server's live state with…
     * the same live state. Net: the user's deletion was lost on every other device.
     *
     * Post-fix, Pass 3 consults the pending-tombstone sidecar before importing and skips
     * the re-import when a tombstone is staged. `pushAllLocal` then sees the syncId only
     * in `pendingDeletedBooks` (not `liveLocalSyncIds`), pushes the tombstone, and clears
     * the sidecar atomically.
     */
    @Test
    fun syncOncePushesPendingTombstoneEvenWhenRemoteStillHasLivePayload() = runBlocking {
        val repo = newBookRepository()
        val deletedStore = HttpSyncDeletedBookStateStore(json)
        val syncId = "vanishing_volume"
        val title = "Vanishing Volume"

        // Stage the tombstone the same way `BookshelfRepository.recordHttpSyncTombstone`
        // does on a user-initiated delete. The local book root is intentionally absent —
        // the user already pressed delete.
        deletedStore.recordDeletedBook(
            booksRoot = repo.booksDirectory,
            syncId = syncId,
            record = HttpSyncDeletedBookRecord(
                title = title,
                contentType = HttpSyncContentType.Mokuro,
                deletedAt = "2030-06-01T00:00:00Z",
            ),
        )

        // Server has the live remote: metadata with deletedAt=null + a payload manifest.
        // This is exactly the state that triggered the re-import bug.
        val transport = FakeKvTransport()
        transport.putJson(
            key = metadataKey(syncId),
            serializer = HttpSyncMetadataBlob.serializer(),
            value = HttpSyncMetadataBlob(
                title = title,
                contentType = HttpSyncContentType.Mokuro,
                deletedAt = null,
            ),
            json = json,
            lastModified = "2030-05-01T00:00:00Z",
        )
        val src = tempFolder.newFolder("source-vanishing").apply {
            resolve("mokuro.json").writeText("""{"v":1}""")
        }
        HttpSyncPayloadCodec(kotlinx.coroutines.Dispatchers.Unconfined)
            .uploadIfChanged(transport, syncId, src, title, HttpSyncContentType.Mokuro)

        val manager = managerFor(repo, transport)
        val result = manager.syncOnce(configured)

        assertEquals(emptyList<String>(), result.errors)
        // Server metadata now carries a non-null deletedAt — the tombstone won.
        val finalMeta = json.decodeFromString(
            HttpSyncMetadataBlob.serializer(),
            transport.kv[metadataKey(syncId)]!!.body.toString(Charsets.UTF_8),
        )
        assertEquals(
            "v2 must push the tombstone even when the remote still has live metadata + manifest",
            "2030-06-01T00:00:00Z",
            finalMeta.deletedAt,
        )
        // Local book was NOT re-imported (the bug's smoking gun).
        assertTrue(
            "v2 should not re-import a remote-only book whose syncId has a staged tombstone",
            repo.loadBookEntries().none { deriveSyncId(it.metadata.title) == syncId },
        )
        // The pending-tombstone sidecar is cleared once the push succeeds.
        assertTrue(
            "pending tombstone must be cleared after a successful push",
            deletedStore.load(repo.booksDirectory).isEmpty(),
        )
    }

    // --- re-import-after-tombstone (v2 reconciler, mirrors V3PlannerTest) ---

    /**
     * v2 reconciler equivalent of `V3PlannerTest.reImportAfterTombstoneKeepsLocalAndPushesOverrideMetadata`.
     * Local book has `importedAt = T2`; server-side metadata has `deletedAt = T1 < T2`.
     * The reconciler must NOT delete the local book and the outbound metadata push must
     * carry `deletedAt = null` + the local `importedAt`.
     */
    @Test
    fun syncOnceReImportAfterRemoteTombstoneKeepsLocalAndOverwritesServer() = runBlocking {
        val repo = newBookRepository()
        val (root, _) = importMokuroBook(repo, "Re-Imported Tomb")
        // Stamp a local `importedAt` strictly newer than the server-side `deletedAt`.
        val localImported = "2030-06-02T00:00:00Z"
        val original = repo.loadMetadata(root)!!
        repo.saveMetadata(root, original.copy(importedAt = localImported))

        val transport = FakeKvTransport()
        transport.putJson(
            key = metadataKey("re_imported_tomb"),
            serializer = HttpSyncMetadataBlob.serializer(),
            value = HttpSyncMetadataBlob(
                title = "Re-Imported Tomb",
                contentType = HttpSyncContentType.Mokuro,
                deletedAt = "2030-06-01T00:00:00Z",
                importedAt = "2030-05-01T00:00:00Z",
            ),
            json = json,
            lastModified = "2030-06-01T00:00:00Z",
        )

        val manager = managerFor(repo, transport)
        val result = manager.syncOnce(configured)

        assertEquals(emptyList<String>(), result.errors)
        // Local book must survive — neither Pass 2 of pullChangedKeys nor the outbound
        // pass should have deleted it.
        assertTrue(
            "local book must survive the re-import-after-tombstone path",
            repo.loadBookEntries().any { it.metadata.title == "Re-Imported Tomb" },
        )
        // Server metadata must now have `deletedAt = null` and carry the local import stamp.
        val finalMeta = json.decodeFromString(
            HttpSyncMetadataBlob.serializer(),
            transport.kv[metadataKey("re_imported_tomb")]!!.body.toString(Charsets.UTF_8),
        )
        assertNull(
            "v2 outbound must overwrite the stale remote tombstone with deletedAt=null",
            finalMeta.deletedAt,
        )
        assertEquals(
            "outbound must publish the local importedAt verbatim so peers can compare",
            localImported,
            finalMeta.importedAt,
        )
    }

    /**
     * Conservative case: local `importedAt` is older than the remote `deletedAt`. The
     * tombstone wins — the v2 reconciler must delete the local book on the inbound pass
     * (just like the pre-fix behaviour for this scenario).
     */
    @Test
    fun syncOnceOlderLocalImportedAtThanRemoteDeletedAtStillDeletes() = runBlocking {
        val repo = newBookRepository()
        val (root, _) = importMokuroBook(repo, "Stale Local Tomb")
        val original = repo.loadMetadata(root)!!
        repo.saveMetadata(root, original.copy(importedAt = "2030-05-01T00:00:00Z"))

        val transport = FakeKvTransport()
        transport.putJson(
            key = metadataKey("stale_local_tomb"),
            serializer = HttpSyncMetadataBlob.serializer(),
            value = HttpSyncMetadataBlob(
                title = "Stale Local Tomb",
                contentType = HttpSyncContentType.Mokuro,
                deletedAt = "2030-06-01T00:00:00Z",
            ),
            json = json,
            lastModified = "2030-06-01T00:00:00Z",
        )

        val manager = managerFor(repo, transport)
        val result = manager.syncOnce(configured)

        assertEquals(emptyList<String>(), result.errors)
        assertTrue(
            "tombstone must still win when local was imported before the deletion",
            repo.loadBookEntries().none { it.metadata.title == "Stale Local Tomb" },
        )
        // Server metadata still carries the tombstone — no local push overwrote it.
        val finalMeta = json.decodeFromString(
            HttpSyncMetadataBlob.serializer(),
            transport.kv[metadataKey("stale_local_tomb")]!!.body.toString(Charsets.UTF_8),
        )
        assertEquals("2030-06-01T00:00:00Z", finalMeta.deletedAt)
    }

    /**
     * Legacy local book written before `BookMetadata.importedAt` existed (so the field is
     * null). The reconciler can't prove a post-tombstone import — conservative path: the
     * tombstone wins. This is what the importMokuroBook helper would have produced before
     * the importedAt feature landed; we simulate it here by clearing the field.
     */
    @Test
    fun syncOnceLegacyLocalBookWithoutImportedAtStillHonoursTombstone() = runBlocking {
        val repo = newBookRepository()
        val (root, _) = importMokuroBook(repo, "Legacy No Imported")
        val original = repo.loadMetadata(root)!!
        repo.saveMetadata(root, original.copy(importedAt = null))

        val transport = FakeKvTransport()
        transport.putJson(
            key = metadataKey("legacy_no_imported"),
            serializer = HttpSyncMetadataBlob.serializer(),
            value = HttpSyncMetadataBlob(
                title = "Legacy No Imported",
                contentType = HttpSyncContentType.Mokuro,
                deletedAt = "2030-06-01T00:00:00Z",
            ),
            json = json,
            lastModified = "2030-06-01T00:00:00Z",
        )

        val manager = managerFor(repo, transport)
        val result = manager.syncOnce(configured)

        assertEquals(emptyList<String>(), result.errors)
        assertTrue(
            "legacy book with null importedAt must still honour the remote tombstone",
            repo.loadBookEntries().none { it.metadata.title == "Legacy No Imported" },
        )
    }

    @Test
    fun metadataGetFailureDoesNotClearRemoteTombstone() = runBlocking {
        val repo = newBookRepository()
        importMokuroBook(repo, "Tombstone Get Failure")
        val base = FakeKvTransport()
        base.putJson(
            key = metadataKey("tombstone_get_failure"),
            serializer = HttpSyncMetadataBlob.serializer(),
            value = HttpSyncMetadataBlob(
                title = "Tombstone Get Failure",
                contentType = HttpSyncContentType.Mokuro,
                deletedAt = "2030-06-01T00:00:00Z",
            ),
            json = json,
            lastModified = "2030-06-01T00:00:00Z",
        )
        val transport = object : HttpSyncKvTransport by base {
            override suspend fun list(prefix: String?, since: String?, cursor: String?, limit: Int?): HttpSyncKvList =
                HttpSyncKvList()

            override suspend fun get(key: String): HttpSyncKvFetched? {
                if (key == metadataKey("tombstone_get_failure")) throw HttpSyncException("simulated metadata outage")
                return base.get(key)
            }
        }
        val manager = managerFor(repo, transport)

        val result = manager.syncOnce(configured)

        assertEquals(0, result.uploadedMetadata)
        assertTrue(result.errors.any { it.contains("metadata GET") })
        val finalMeta = json.decodeFromString(
            HttpSyncMetadataBlob.serializer(),
            base.kv[metadataKey("tombstone_get_failure")]!!.body.toString(Charsets.UTF_8),
        )
        assertEquals("2030-06-01T00:00:00Z", finalMeta.deletedAt)
    }

    @Test
    fun failedPayloadImportDoesNotPoisonOtherBooksInSameSync() = runBlocking {
        // Fresh device: server has TWO books. The first one's payload zip is corrupted
        // (sha256 won't match the manifest). The second one is fine. Both have bookmarks.
        // The corrupted one should produce a per-book error; the second should sync cleanly.
        val repo = newBookRepository()
        val transport = FakeKvTransport()

        // Book 1: corrupted payload (manifest claims a sha that the zip bytes don't match).
        run {
            val bogusZip = byteArrayOf(0x50, 0x4B, 0x05, 0x06).plus(ByteArray(20))  // "valid" but empty zip
            transport.kv[payloadZipKey("corrupted_book")] = FakeKvTransport.Stored(
                body = bogusZip, contentType = "application/zip", lastModified = "2030-01-01T00:00:00Z",
            )
            transport.putJson(
                key = payloadManifestKey("corrupted_book"),
                serializer = HttpSyncPayloadManifest.serializer(),
                value = HttpSyncPayloadManifest(
                    sha256 = "sha256:" + "ff".repeat(32),
                    sizeBytes = 9_999_999L,
                    originalName = "Corrupted Book",
                    format = HttpSyncContentType.Mokuro,
                ),
                json = json,
                lastModified = "2030-01-01T00:00:00Z",
            )
        }

        // Book 2: clean payload via the codec, plus a bookmark.
        run {
            val src = tempFolder.newFolder("source-book2").apply {
                resolve("mokuro.json").writeText("""{"good":true}""")
            }
            HttpSyncPayloadCodec(kotlinx.coroutines.Dispatchers.Unconfined)
                .uploadIfChanged(transport, "good_book", src, "Good Book", HttpSyncContentType.Mokuro)
            transport.putJson(
                key = bookmarkKey("good_book"),
                serializer = HttpSyncBookmarkBlob.serializer(),
                value = HttpSyncBookmarkBlob(7, 0.0, 7, "2030-02-01T00:00:00Z"),
                json = json,
                lastModified = "2030-02-01T00:00:00Z",
            )
        }

        val manager = managerFor(repo, transport)
        val result = manager.syncOnce(configured)

        // Good book made it through.
        val imported = repo.loadBookEntries().singleOrNull { deriveSyncId(it.metadata.title) == "good_book" }
        assertNotNull("good_book should have been imported", imported)
        assertEquals(7, repo.loadBookmark(imported!!.root)!!.chapterIndex)

        // Corrupted book did NOT crash the sync — it just surfaced as an error.
        assertTrue(
            "expected a corrupted_book error, got ${result.errors}",
            result.errors.any { "corrupted_book" in it.lowercase() || "corrupted_book" in it },
        )
    }

    @Test
    fun failedPayloadImportDoesNotAdvanceCursorAndCanRetrySameInboundKeys() = runBlocking {
        val repo = newBookRepository()
        val transport = FakeKvTransport()
        val syncId = "retry_book"
        val originalCursor = "2020-01-01T00:00:00Z"

        transport.kv[payloadZipKey(syncId)] = FakeKvTransport.Stored(
            body = byteArrayOf(0x50, 0x4B, 0x05, 0x06).plus(ByteArray(20)),
            contentType = "application/zip",
            lastModified = "2030-01-01T00:00:00Z",
        )
        transport.putJson(
            key = payloadManifestKey(syncId),
            serializer = HttpSyncPayloadManifest.serializer(),
            value = HttpSyncPayloadManifest(
                sha256 = "sha256:" + "ff".repeat(32),
                sizeBytes = 9_999_999L,
                originalName = "Retry Book",
                format = HttpSyncContentType.Mokuro,
            ),
            json = json,
            lastModified = "2030-01-01T00:00:00Z",
        )
        transport.putJson(
            key = bookmarkKey(syncId),
            serializer = HttpSyncBookmarkBlob.serializer(),
            value = HttpSyncBookmarkBlob(12, 0.0, 12, "2030-01-02T00:00:00Z"),
            json = json,
            lastModified = "2030-01-02T00:00:00Z",
        )

        val manager = managerFor(repo, transport)
        val first = manager.syncOnce(configured.copy(lastSyncedAt = originalCursor))

        assertEquals(0, first.downloadedPayloads)
        assertEquals(0, first.downloadedBookmarks)
        assertTrue("expected payload failure, got ${first.errors}", first.errors.any { "retry_book" in it })
        assertNull("failed payload import must not move the cursor past retryable inbound keys", first.newLastSyncedAt)

        transport.delete(payloadManifestKey(syncId))
        transport.delete(payloadZipKey(syncId))
        uploadRemoteMokuroPayload(transport, syncId, "Retry Book", "retry-source")
        val second = manager.syncOnce(configured.copy(lastSyncedAt = first.newLastSyncedAt ?: originalCursor))

        assertEquals(1, second.downloadedPayloads)
        assertEquals(1, second.downloadedBookmarks)
        val imported = repo.loadBookEntries().single { deriveSyncId(it.metadata.title) == syncId }
        assertEquals(12, repo.loadBookmark(imported.root)!!.chapterIndex)
    }

    @Test
    fun chatDedupListPaginatesAcrossTruncatedPages() = runBlocking {
        // Regression: `pushAllLocal` used to call list() once for chat-dedup, ignoring
        // `truncated`. On a chat-heavy book that means the second page of remote chat keys
        // is invisible — we'd re-upload entries we already have, or worse, miss new entries
        // because we thought they weren't there.
        val repo = newBookRepository()
        val (root, _) = importMokuroBook(repo, "Chat Heavy Book")
        repo.saveBookmark(root, Bookmark(1, 0.0, 1, 800_000_000.0))
        val historyStore = AiChatHistoryStore()
        // Push 3 chat entries locally, two of which will be "on the server" across two pages.
        val entries = listOf(
            AiChatEntry("a", "p", "m", "r1", 1.0),
            AiChatEntry("b", "p", "m", "r2", 2.0),
            AiChatEntry("c", "p", "m", "r3", 3.0),
        )
        for (e in entries) historyStore.append(root, e)

        // Server has entries a + b on different pages; c is missing.
        val pagedTransport = object : HttpSyncKvTransport {
            val seeded: MutableMap<String, FakeKvTransport.Stored> = mutableMapOf()
            override suspend fun put(key: String, contentType: String, body: ByteArray): HttpSyncKvWriteResponse {
                seeded[key] = FakeKvTransport.Stored(body, contentType, "2026-01-01T00:00:00Z")
                return HttpSyncKvWriteResponse(key, "2026-01-01T00:00:00Z", "etag", body.size, contentType)
            }
            override suspend fun get(key: String): HttpSyncKvFetched? = null
            override suspend fun list(prefix: String?, since: String?, cursor: String?, limit: Int?): HttpSyncKvList {
                if (prefix?.startsWith("books/chat_heavy_book/chat/") == true) {
                    return when (cursor) {
                        null -> HttpSyncKvList(
                            keys = listOf(
                                HttpSyncKvKeyMeta(
                                    key = chatKey("chat_heavy_book", chatEntryKeySuffix(entries[0].timestampSeconds, entries[0].bubbleText, entries[0].response)),
                                    lastModified = "2026-01-01T00:00:00Z",
                                    etag = "etag", size = 1, contentType = "application/json; charset=utf-8",
                                ),
                            ),
                            truncated = true,
                            nextCursor = "page2",
                        )
                        "page2" -> HttpSyncKvList(
                            keys = listOf(
                                HttpSyncKvKeyMeta(
                                    key = chatKey("chat_heavy_book", chatEntryKeySuffix(entries[1].timestampSeconds, entries[1].bubbleText, entries[1].response)),
                                    lastModified = "2026-01-01T00:00:00Z",
                                    etag = "etag", size = 1, contentType = "application/json; charset=utf-8",
                                ),
                            ),
                            truncated = false, nextCursor = null,
                        )
                        else -> HttpSyncKvList()
                    }
                }
                return HttpSyncKvList()
            }
            override suspend fun delete(key: String) { seeded.remove(key) }
        }
        val manager = managerFor(repo, pagedTransport, historyStore)
        val result = manager.syncOnce(configured)

        // Only entry `c` should have been uploaded — `a` and `b` were on the server across
        // two pages and dedup correctly identified both.
        assertEquals(1, result.uploadedChatEntries)
        val cSuffix = chatEntryKeySuffix(entries[2].timestampSeconds, entries[2].bubbleText, entries[2].response)
        assertTrue(
            "entry c should be the one that landed on the server",
            pagedTransport.seeded.keys.any { it == chatKey("chat_heavy_book", cSuffix) },
        )
    }

    @Test
    fun syncOncePushesShelfPlacementInBookMetadata() = runBlocking {
        val repo = newBookRepository()
        val (root, _) = importMokuroBook(repo, "Shelved Manga")
        val bookId = repo.loadMetadata(root)!!.id
        repo.saveShelves(listOf(BookShelf("Favorites", listOf(bookId))))
        val transport = FakeKvTransport()
        val manager = managerFor(repo, transport)

        val result = manager.syncOnce(configured)

        assertEquals(1, result.uploadedMetadata)
        val stored = transport.kv[metadataKey("shelved_manga")]
        assertNotNull("metadata should be uploaded", stored)
        val blob = json.decodeFromString(
            HttpSyncMetadataBlob.serializer(),
            stored!!.body.toString(Charsets.UTF_8),
        )
        assertEquals("Favorites", blob.shelfName)
        assertNotNull("shelf timestamp should be uploaded", blob.shelfUpdatedAt)
    }

    @Test
    fun syncOnceAppliesRemoteShelfPlacementToExistingLocalBook() = runBlocking {
        val repo = newBookRepository()
        val (root, _) = importMokuroBook(repo, "Remote Shelf Manga")
        val bookId = repo.loadMetadata(root)!!.id
        repo.saveShelves(
            listOf(
                BookShelf("Old Shelf", listOf(bookId)),
                BookShelf("Other Shelf", emptyList()),
            ),
        )
        val transport = FakeKvTransport().apply {
            putJson(
                key = metadataKey("remote_shelf_manga"),
                serializer = HttpSyncMetadataBlob.serializer(),
                value = HttpSyncMetadataBlob(
                    title = "Remote Shelf Manga",
                    contentType = HttpSyncContentType.Mokuro,
                    shelfName = "New Shelf",
                    shelfUpdatedAt = "2099-01-01T00:00:10Z",
                ),
                json = json,
                lastModified = "2027-01-01T00:00:20Z",
            )
        }
        val manager = managerFor(repo, transport)

        val result = manager.syncOnce(configured)

        assertEquals(emptyList<String>(), result.errors)
        assertTrue(repo.loadShelves().any { it.name == "New Shelf" && it.bookIds == listOf(bookId) })
        assertTrue(repo.loadShelves().single { it.name == "Old Shelf" }.bookIds.isEmpty())
    }

    @Test
    fun syncOnceAppliesRemoteUnshelvedPlacement() = runBlocking {
        val repo = newBookRepository()
        val (root, _) = importMokuroBook(repo, "Unshelved Manga")
        val bookId = repo.loadMetadata(root)!!.id
        repo.saveShelves(listOf(BookShelf("Favorites", listOf(bookId))))
        val transport = FakeKvTransport().apply {
            put(
                key = metadataKey("unshelved_manga"),
                contentType = HttpSyncPusher.JSON_CONTENT_TYPE,
                body = """
                    {
                      "title": "Unshelved Manga",
                      "contentType": "mokuro",
                      "shelfName": null,
                      "shelfUpdatedAt": "2099-01-01T00:00:10Z"
                    }
                """.trimIndent().toByteArray(),
            )
        }
        val manager = managerFor(repo, transport)

        manager.syncOnce(configured)

        assertTrue(repo.loadShelves().single { it.name == "Favorites" }.bookIds.isEmpty())
    }

    @Test
    fun syncOnceDoesNotUnshelveWhenRemoteMetadataIsLegacyWithoutShelfName() = runBlocking {
        val repo = newBookRepository()
        val (root, _) = importMokuroBook(repo, "Legacy Metadata Manga")
        val bookId = repo.loadMetadata(root)!!.id
        repo.saveShelves(listOf(BookShelf("Keep Me", listOf(bookId))))
        val transport = FakeKvTransport().apply {
            put(
                key = metadataKey("legacy_metadata_manga"),
                contentType = HttpSyncPusher.JSON_CONTENT_TYPE,
                body = """
                    {
                      "title": "Legacy Metadata Manga",
                      "contentType": "mokuro"
                    }
                """.trimIndent().toByteArray(),
            )
        }
        val manager = managerFor(repo, transport)

        manager.syncOnce(configured)

        assertEquals(listOf(bookId), repo.loadShelves().single { it.name == "Keep Me" }.bookIds)
    }

    @Test
    fun freshDeviceImportsRemoteBookThenAppliesShelfPlacement() = runBlocking {
        val sourceRepo = newBookRepository()
        val (sourceRoot, _) = importMokuroBook(sourceRepo, "Fresh Shelf Manga")
        val sourceTransport = FakeKvTransport()
        HttpSyncPayloadCodec(kotlinx.coroutines.Dispatchers.Unconfined)
            .uploadIfChanged(
                sourceTransport,
                "fresh_shelf_manga",
                sourceRoot,
                "Fresh Shelf Manga",
                HttpSyncContentType.Mokuro,
            )
        sourceTransport.putJson(
            key = metadataKey("fresh_shelf_manga"),
            serializer = HttpSyncMetadataBlob.serializer(),
            value = HttpSyncMetadataBlob(
                title = "Fresh Shelf Manga",
                contentType = HttpSyncContentType.Mokuro,
                shelfName = "Synced Shelf",
                shelfUpdatedAt = "2099-01-01T00:00:10Z",
            ),
            json = json,
            lastModified = "2027-01-01T00:00:20Z",
        )
        val receivingRepo = newBookRepository()
        val manager = managerFor(receivingRepo, sourceTransport)

        val result = manager.syncOnce(configured)

        assertEquals(1, result.downloadedPayloads)
        val imported = receivingRepo.loadBookEntries().single()
        assertEquals("Fresh Shelf Manga", imported.metadata.title)
        assertEquals(
            listOf(imported.metadata.id),
            receivingRepo.loadShelves().single { it.name == "Synced Shelf" }.bookIds,
        )
        val finalMetadata = json.decodeFromString(
            HttpSyncMetadataBlob.serializer(),
            sourceTransport.kv[metadataKey("fresh_shelf_manga")]!!.body.toString(Charsets.UTF_8),
        )
        assertEquals("2099-01-01T00:00:10Z", finalMetadata.shelfUpdatedAt)
    }

    @Test
    fun syncOnceDoesNotApplyStaleRemoteShelfPlacementOverNewerLocalShelves() = runBlocking {
        val repo = newBookRepository()
        val (root, _) = importMokuroBook(repo, "Local Shelf Wins")
        val bookId = repo.loadMetadata(root)!!.id
        repo.saveShelves(listOf(BookShelf("Local Shelf", listOf(bookId))))
        val transport = FakeKvTransport().apply {
            putJson(
                key = metadataKey("local_shelf_wins"),
                serializer = HttpSyncMetadataBlob.serializer(),
                value = HttpSyncMetadataBlob(
                    title = "Local Shelf Wins",
                    contentType = HttpSyncContentType.Mokuro,
                    shelfName = "Old Remote Shelf",
                    shelfUpdatedAt = "2000-01-01T00:00:00Z",
                ),
                json = json,
                lastModified = "2027-01-01T00:00:20Z",
            )
        }
        val manager = managerFor(repo, transport)

        val result = manager.syncOnce(configured)

        assertEquals(emptyList<String>(), result.errors)
        val shelves = repo.loadShelves()
        assertEquals(listOf(bookId), shelves.single { it.name == "Local Shelf" }.bookIds)
        assertTrue(shelves.none { it.name == "Old Remote Shelf" && it.bookIds.contains(bookId) })
    }

    @Test
    fun syncOnceAppliesMultipleRemoteShelfPlacementsUsingOneLocalTimestampSnapshot() = runBlocking {
        val repo = newBookRepository()
        val (firstRoot, _) = importMokuroBook(repo, "Snapshot Shelf One")
        val (secondRoot, _) = importMokuroBook(repo, "Snapshot Shelf Two")
        val firstId = repo.loadMetadata(firstRoot)!!.id
        val secondId = repo.loadMetadata(secondRoot)!!.id
        repo.saveShelves(listOf(BookShelf("Existing", emptyList())))
        val shelvesFile = firstRoot.parentFile!!.resolve("shelves.json")
        assertTrue(
            "test setup should be able to age the local shelves file",
            shelvesFile.setLastModified(Instant.parse("2024-01-01T00:00:00Z").toEpochMilli()),
        )
        val transport = FakeKvTransport().apply {
            putJson(
                key = metadataKey("snapshot_shelf_one"),
                serializer = HttpSyncMetadataBlob.serializer(),
                value = HttpSyncMetadataBlob(
                    title = "Snapshot Shelf One",
                    contentType = HttpSyncContentType.Mokuro,
                    shelfName = "Remote One",
                    shelfUpdatedAt = "2025-01-01T00:00:00Z",
                ),
                json = json,
                lastModified = "2099-01-01T00:00:10Z",
            )
            putJson(
                key = metadataKey("snapshot_shelf_two"),
                serializer = HttpSyncMetadataBlob.serializer(),
                value = HttpSyncMetadataBlob(
                    title = "Snapshot Shelf Two",
                    contentType = HttpSyncContentType.Mokuro,
                    shelfName = "Remote Two",
                    shelfUpdatedAt = "2025-01-01T00:00:00Z",
                ),
                json = json,
                lastModified = "2027-01-01T00:00:20Z",
            )
        }
        val manager = managerFor(repo, transport)

        val result = manager.syncOnce(configured)

        assertEquals(emptyList<String>(), result.errors)
        val shelves = repo.loadShelves()
        assertEquals(listOf(firstId), shelves.single { it.name == "Remote One" }.bookIds)
        assertEquals(listOf(secondId), shelves.single { it.name == "Remote Two" }.bookIds)
    }

    @Test
    fun syncOnceAppliesRemoteShelfForOneBookWhenAnotherBookMovedLocally() = runBlocking {
        val repo = newBookRepository()
        val (firstRoot, _) = importMokuroBook(repo, "Remote Shelf Beats Unrelated Local")
        val (secondRoot, _) = importMokuroBook(repo, "Locally Moved Other Book")
        val firstId = repo.loadMetadata(firstRoot)!!.id
        val secondId = repo.loadMetadata(secondRoot)!!.id
        repo.saveShelves(
            listOf(
                BookShelf("Old First Shelf", listOf(firstId)),
                BookShelf("Local Second Shelf", listOf(secondId)),
            ),
        )
        HttpSyncShelfStateStore(json).save(
            repo.booksDirectory,
            mapOf(
                "remote_shelf_beats_unrelated_local" to HttpSyncShelfPlacementRecord(
                    shelfName = "Old First Shelf",
                    updatedAt = "2024-01-01T00:00:00Z",
                ),
                "locally_moved_other_book" to HttpSyncShelfPlacementRecord(
                    shelfName = "Old Second Shelf",
                    updatedAt = "2024-01-01T00:00:00Z",
                ),
            ),
        )
        val transport = FakeKvTransport().apply {
            putJson(
                key = metadataKey("remote_shelf_beats_unrelated_local"),
                serializer = HttpSyncMetadataBlob.serializer(),
                value = HttpSyncMetadataBlob(
                    title = "Remote Shelf Beats Unrelated Local",
                    contentType = HttpSyncContentType.Mokuro,
                    shelfName = "Remote First Shelf",
                    shelfUpdatedAt = "2025-01-01T00:00:00Z",
                ),
                json = json,
                lastModified = "2027-01-01T00:00:20Z",
            )
        }
        val manager = managerFor(repo, transport)

        val result = manager.syncOnce(configured)

        assertEquals(emptyList<String>(), result.errors)
        val shelves = repo.loadShelves()
        assertEquals(listOf(firstId), shelves.single { it.name == "Remote First Shelf" }.bookIds)
        assertEquals(listOf(secondId), shelves.single { it.name == "Local Second Shelf" }.bookIds)
        val firstMetadata = json.decodeFromString(
            HttpSyncMetadataBlob.serializer(),
            transport.kv[metadataKey("remote_shelf_beats_unrelated_local")]!!.body.toString(Charsets.UTF_8),
        )
        assertEquals("Remote First Shelf", firstMetadata.shelfName)
        assertEquals("2025-01-01T00:00:00Z", firstMetadata.shelfUpdatedAt)
    }

    @Test
    fun summaryRendersHumanReadableCountsOrNothingToSync() {
        val empty = HttpSyncResult(
            uploadedBookmarks = 0,
            uploadedChatEntries = 0,
            uploadedMetadata = 0,
            downloadedBookmarks = 0,
            downloadedChatEntries = 0,
            remoteOnlyBooks = 0,
            errors = emptyList(),
        )
        assertEquals("nothing to sync", empty.summary())

        val mixed = HttpSyncResult(
            uploadedBookmarks = 3,
            uploadedChatEntries = 1,
            uploadedMetadata = 3,
            uploadedPayloads = 2,
            downloadedBookmarks = 0,
            downloadedChatEntries = 2,
            downloadedPayloads = 1,
            remoteOnlyBooks = 1,
            errors = emptyList(),
        )
        val summary = mixed.summary()
        assertTrue(summary.contains("3 bookmarks up"))
        assertTrue(summary.contains("1 chat up"))
        assertTrue(summary.contains("2 book payloads up"))
        assertTrue(summary.contains("2 chats down"))
        assertTrue(summary.contains("1 book payload down"))
        assertTrue(summary.contains("1 remote-only book"))
    }

    @Test
    fun syncOnceReportsUserVisibleProgress() = runBlocking {
        val repo = newBookRepository()
        val (root, _) = importMokuroBook(repo, "Progress Book")
        repo.saveBookmark(root, Bookmark(1, 0.0, 1, 800_000_000.0))
        val transport = FakeKvTransport()
        val progress = mutableListOf<HttpSyncProgress>()
        val reconciler = HttpSyncReconciler(
            bookRepository = repo,
            transportFactory = { transport },
            ioDispatcher = kotlinx.coroutines.Dispatchers.Unconfined,
        )

        reconciler.syncOnce(configured) { progress += it }

        assertTrue(progress.any { it.message == "Preparing sync" })
        assertTrue(progress.any { it.message == "Listing remote changes" })
        assertTrue(progress.any { it.message == "Uploading local book state" && it.total == 1 })
        assertTrue(progress.any { it.message == "Checking manga payload upload" && it.total == 1 })
        assertTrue(progress.any { it.message == "Finishing sync" })
        assertTrue(progress.any { it.fraction != null })
    }

    @Test
    fun syncOnceProgressCountsFilteredPayloadAndMissingChatWork() = runBlocking {
        val repo = newBookRepository()
        val (mangaRoot, _) = importMokuroBook(repo, "Progress Manga")
        importEpubBook(repo, "Progress Epub")
        val existingChat = AiChatEntry("already remote", "p", "m", "r1", 1.0)
        val missingChat = AiChatEntry("needs upload", "p", "m", "r2", 2.0)
        val historyStore = AiChatHistoryStore()
        historyStore.append(mangaRoot, existingChat)
        historyStore.append(mangaRoot, missingChat)
        val transport = FakeKvTransport().apply {
            putJson(
                key = chatKey(
                    "progress_manga",
                    chatEntryKeySuffix(
                        existingChat.timestampSeconds,
                        existingChat.bubbleText,
                        existingChat.response,
                    ),
                ),
                serializer = HttpSyncChatEntryBlob.serializer(),
                value = existingChat.toBlob(),
                json = json,
                lastModified = "2027-01-01T00:00:01Z",
            )
        }
        val progress = mutableListOf<HttpSyncProgress>()
        val reconciler = HttpSyncReconciler(
            bookRepository = repo,
            aiHistoryStore = historyStore,
            transportFactory = { transport },
            ioDispatcher = kotlinx.coroutines.Dispatchers.Unconfined,
        )

        reconciler.syncOnce(configured) { progress += it }

        val payloadProgress = progress.single { it.message == "Checking manga payload upload" }
        assertEquals("Book 1 of 1: Progress Manga", payloadProgress.detail)
        assertEquals(0, payloadProgress.completed)
        assertEquals(1, payloadProgress.total)

        val chatProgress = progress.single { it.message == "Uploading manga chat history" }
        assertEquals("Progress Manga: chat 1 of 1", chatProgress.detail)
        assertEquals(0, chatProgress.completed)
        assertEquals(1, chatProgress.total)
    }

    // ===== helpers ============================================================================

    private fun newBookRepository(): BookRepository =
        BookRepository(tempFolder.newFolder())

    private suspend fun importMokuroBook(repo: BookRepository, title: String): Pair<File, String> {
        val root = repo.createBookDirectoryForImportedTitle(title)
        repo.saveMetadata(
            root,
            BookMetadata(
                id = UUID.randomUUID().toString(),
                title = title,
                cover = null,
                folder = root.name,
                lastAccess = 0.0,
            ),
        )
        // Marker file that flips `bookContentType` over to Mokuro.
        root.resolve("mokuro.json").writeText("{}")
        return root to title
    }

    private suspend fun importEpubBook(repo: BookRepository, title: String): Pair<File, String> {
        val root = repo.createBookDirectoryForImportedTitle(title)
        repo.saveMetadata(
            root,
            BookMetadata(
                id = UUID.randomUUID().toString(),
                title = title,
                cover = null,
                folder = root.name,
                lastAccess = 0.0,
            ),
        )
        return root to title
    }

    private suspend fun uploadRemoteMokuroPayload(
        transport: FakeKvTransport,
        syncId: String,
        title: String,
        sourceFolder: String,
    ) {
        val srcRoot = tempFolder.newFolder(sourceFolder)
        srcRoot.resolve("mokuro.json").writeText("""{"v":1}""")
        HttpSyncPayloadCodec(kotlinx.coroutines.Dispatchers.Unconfined)
            .uploadIfChanged(transport, syncId, srcRoot, title, HttpSyncContentType.Mokuro)
    }

    /** Holder for the two halves of the sync code so tests can pick whichever they need. */
    private class SyncFixture(
        val pusher: HttpSyncPusher,
        val reconciler: HttpSyncReconciler,
    ) {
        suspend fun pushBookmark(bookRoot: File, title: String, settings: HttpSyncSettings) =
            pusher.pushBookmark(bookRoot, title, settings)
        suspend fun pushChatEntry(title: String, entry: AiChatEntry, settings: HttpSyncSettings) =
            pusher.pushChatEntry(title, entry, settings)
        suspend fun syncOnce(settings: HttpSyncSettings): HttpSyncResult =
            reconciler.syncOnce(settings)
    }

    private fun managerFor(
        repo: BookRepository,
        transport: HttpSyncKvTransport,
        historyStore: AiChatHistoryStore = AiChatHistoryStore(),
    ): SyncFixture = SyncFixture(
        pusher = HttpSyncPusher(
            bookRepository = repo,
            transportFactory = { transport },
            ioDispatcher = kotlinx.coroutines.Dispatchers.Unconfined,
        ),
        reconciler = HttpSyncReconciler(
            bookRepository = repo,
            aiHistoryStore = historyStore,
            transportFactory = { transport },
            ioDispatcher = kotlinx.coroutines.Dispatchers.Unconfined,
        ),
    )
}

/**
 * In-memory implementation of [HttpSyncKvTransport]. Stores blobs in a map keyed by KV key,
 * stamps every PUT with an incrementing wallclock to mimic the server's lastModified
 * monotonicity.
 *
 * Public so [HttpSyncReaderHooksTest] (and any future sync test) can share it.
 */
internal class FakeKvTransport : HttpSyncKvTransport {
    data class Stored(val body: ByteArray, val contentType: String, val lastModified: String)

    val kv: MutableMap<String, Stored> = linkedMapOf()
    private var clock: Long = 0L

    override suspend fun put(key: String, contentType: String, body: ByteArray): HttpSyncKvWriteResponse {
        val ts = nextTimestamp()
        kv[key] = Stored(body = body, contentType = contentType, lastModified = ts)
        return HttpSyncKvWriteResponse(
            key = key,
            lastModified = ts,
            etag = "sha256:fake",
            size = body.size,
            contentType = contentType,
        )
    }

    override suspend fun get(key: String): HttpSyncKvFetched? {
        val stored = kv[key] ?: return null
        return HttpSyncKvFetched(
            body = stored.body,
            contentType = stored.contentType,
            lastModified = stored.lastModified,
            etag = "sha256:fake",
        )
    }

    override suspend fun list(
        prefix: String?,
        since: String?,
        cursor: String?,
        limit: Int?,
    ): HttpSyncKvList {
        val filtered = kv.entries
            .filter { (key, _) -> prefix == null || key.startsWith(prefix) }
            .filter { (_, stored) -> since == null || stored.lastModified > since }
            .map { (key, stored) ->
                HttpSyncKvKeyMeta(
                    key = key,
                    lastModified = stored.lastModified,
                    etag = "sha256:fake",
                    size = stored.body.size,
                    contentType = stored.contentType,
                )
            }
            .sortedBy { it.key }
        return HttpSyncKvList(keys = filtered, truncated = false, nextCursor = null)
    }

    override suspend fun delete(key: String) {
        kv.remove(key)
    }

    /** Stash a JSON blob with a chosen `lastModified` (so inbound tests can shape the cursor). */
    fun <T> putJson(
        key: String,
        serializer: kotlinx.serialization.KSerializer<T>,
        value: T,
        json: Json,
        lastModified: String,
    ) {
        kv[key] = Stored(
            body = json.encodeToString(serializer, value).toByteArray(),
            contentType = "application/json; charset=utf-8",
            lastModified = lastModified,
        )
    }

    private fun nextTimestamp(): String {
        clock += 1
        // Use a fixed-prefix synthetic timestamp so tests don't depend on real wallclock.
        val seconds = clock
        return "2027-01-01T00:00:%02dZ".format(seconds % 60)
    }
}
