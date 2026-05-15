package moe.antimony.hoshi.features.sync.http

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import moe.antimony.hoshi.epub.BookMetadata
import moe.antimony.hoshi.epub.BookRepository
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
        // Japanese alone → all non-alphanumerics → empty → null.
        assertNull(deriveSyncId("よつばと"))
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
    fun syncOnceSkipsBooksWithoutDerivableSyncId() = runBlocking {
        val repo = newBookRepository()
        // Title that's all non-ASCII-alphanumeric → null syncId.
        val (root, _) = importEpubBook(repo, "よつばと")
        repo.saveBookmark(root, Bookmark(1, 0.0, 1, 800_000_000.0))

        val transport = FakeKvTransport()
        val manager = managerFor(repo, transport)
        val result = manager.syncOnce(configured)

        assertEquals(0, result.uploadedBookmarks)
        assertTrue("nothing should have been written", transport.kv.isEmpty())
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

    // ===== helpers ============================================================================

    private fun newBookRepository(): BookRepository =
        BookRepository(tempFolder.newFolder("files"))

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
