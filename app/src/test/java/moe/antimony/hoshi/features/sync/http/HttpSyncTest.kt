package moe.antimony.hoshi.features.sync.http

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import moe.antimony.hoshi.epub.BookRepository
import moe.antimony.hoshi.epub.BookMetadata
import moe.antimony.hoshi.epub.Bookmark
import moe.antimony.hoshi.features.ai.AiChatEntry
import moe.antimony.hoshi.features.ai.AiChatHistoryStore
import moe.antimony.hoshi.features.ai.AiChatLog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.UUID

class HttpSyncTest {

    @get:Rule val tempFolder = TemporaryFolder()

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test
    fun bookRecordRoundTripsThroughJson() {
        val record = HttpSyncBookRecord(
            syncId = "001_jp_yotsubato",
            title = "001 [JP] Yotsubato",
            contentType = HttpSyncContentType.Mokuro,
            bookmark = HttpSyncBookmark(
                chapterIndex = 17,
                progress = 0.0,
                characterCount = 18,
                lastModified = "2026-05-15T01:18:00Z",
            ),
            aiChatLog = HttpSyncAiChatLog(
                entries = listOf(
                    HttpSyncAiChatEntry(
                        bubbleText = "もうすぐだ",
                        prompt = "tutor",
                        model = "gpt-5.5",
                        response = "Almost there!",
                        timestampSeconds = 800_000.0,
                    ),
                ),
            ),
            clientModified = "2026-05-15T01:18:00Z",
        )

        val encoded = json.encodeToString(HttpSyncBookRecord.serializer(), record)
        val decoded = json.decodeFromString(HttpSyncBookRecord.serializer(), encoded)

        assertEquals(record, decoded)
        // Spot-check that contentType serializes lowercase (matches docs/HTTP_SYNC.md).
        assertTrue("expected \"mokuro\" in $encoded", encoded.contains("\"contentType\":\"mokuro\""))
    }

    @Test
    fun deriveSyncIdSanitizesNonAlphanumerics() {
        assertEquals("001_jp_yotsubato", deriveSyncId("001 [JP] Yotsubato"))
        assertEquals("kafka_on_the_shore", deriveSyncId("Kafka on the Shore"))
        // CJK is entirely non-ASCII-alphanumeric -> would collapse to "_" then trim to empty.
        assertNull(deriveSyncId("よつばと!"))
        assertNull(deriveSyncId(""))
        assertNull(deriveSyncId(null))
    }

    @Test
    fun rfc3339AppleSecondsRoundTrip() {
        // The exact Apple-seconds value isn't what matters — that the conversion is lossless
        // for a typical ChatGPT/bookmark timestamp is.
        val rfc = "2026-05-15T01:18:00Z"
        val apple = rfc3339ToAppleSeconds(rfc)
        val roundTrip = appleSecondsToRfc3339(apple)
        // Java's Instant.toString() may omit zero milliseconds or render them; accept both.
        val normalized = roundTrip.removeSuffix(".000Z").let {
            if (it.endsWith("Z")) it else "${it}Z"
        }
        assertEquals(rfc, normalized)
    }

    @Test
    fun compareModifiedReturnsZeroForEqualAndChronologicalOtherwise() {
        assertTrue(compareModified("2026-05-15T01:00:00Z", "2026-05-15T01:00:00Z") == 0)
        assertTrue(compareModified("2026-05-15T01:00:00Z", "2026-05-15T01:00:01Z") < 0)
        assertTrue(compareModified("2026-05-15T01:00:01Z", "2026-05-15T01:00:00Z") > 0)
        assertTrue(compareModified(null, "2026-05-15T01:00:00Z") < 0)
        assertTrue(compareModified("2026-05-15T01:00:00Z", null) > 0)
    }

    @Test
    fun syncManagerUploadsLocalNewerAndDownloadsRemoteNewer() = runBlocking {
        val filesDir = tempFolder.newFolder("files")
        val repo = BookRepository(filesDir)

        // Two local books: "Up" is locally newer; "Down" is remotely newer.
        val upRoot = repo.createBookDirectoryForImportedTitle("Up Volume One")
        repo.saveMetadata(upRoot, BookMetadata(
            id = UUID.randomUUID().toString(),
            title = "Up Volume One",
            cover = null,
            folder = upRoot.name,
            lastAccess = 0.0,
        ))
        // Make this a manga so the manager also reads ai_chat_log.
        upRoot.resolve("mokuro.json").writeText("{}")
        repo.saveBookmark(upRoot, Bookmark(
            chapterIndex = 5,
            progress = 0.0,
            characterCount = 6,
            lastModified = 800_500_000.0, // newer
        ))

        val downRoot = repo.createBookDirectoryForImportedTitle("Down Volume One")
        repo.saveMetadata(downRoot, BookMetadata(
            id = UUID.randomUUID().toString(),
            title = "Down Volume One",
            cover = null,
            folder = downRoot.name,
            lastAccess = 0.0,
        ))
        repo.saveBookmark(downRoot, Bookmark(
            chapterIndex = 1,
            progress = 0.0,
            characterCount = 2,
            lastModified = 800_500_000.0, // older than remote
        ))

        val historyStore = AiChatHistoryStore()
        val transport = FakeTransport(
            // server's view: "up" is older than local, "down" is newer than local
            initialRecords = mapOf(
                "up_volume_one" to fakeRecord(
                    syncId = "up_volume_one",
                    title = "Up Volume One",
                    contentType = HttpSyncContentType.Mokuro,
                    chapterIndex = 1,
                    clientModified = "2026-05-13T00:00:00Z",
                ),
                "down_volume_one" to fakeRecord(
                    syncId = "down_volume_one",
                    title = "Down Volume One",
                    contentType = HttpSyncContentType.Epub,
                    chapterIndex = 9,
                    clientModified = "2026-05-20T00:00:00Z",
                ),
                "ghost_volume" to fakeRecord(
                    syncId = "ghost_volume",
                    title = "Ghost Volume",
                    contentType = HttpSyncContentType.Mokuro,
                    chapterIndex = 0,
                    clientModified = "2026-05-01T00:00:00Z",
                ),
            ),
        )
        val manager = HttpSyncManager(
            bookRepository = repo,
            aiHistoryStore = historyStore,
            transportFactory = { transport },
        )

        val settings = HttpSyncSettings(baseUrl = "https://x", bearerToken = "t", enabled = true)
        val result = manager.syncOnce(settings)

        assertEquals(1, result.uploaded)
        assertEquals(1, result.downloaded)
        assertEquals(0, result.upToDate)
        assertEquals(1, result.remoteOnly)
        assertTrue("expected no errors but got ${result.errors}", result.errors.isEmpty())

        // "Up" was uploaded -> server's record for it now reflects local chapter 5.
        assertEquals(5, transport.records["up_volume_one"]?.bookmark?.chapterIndex)
        // "Down" was downloaded -> local bookmark.json now shows the server's chapter 9.
        val downBookmark = repo.loadBookmark(downRoot)
        assertNotNull(downBookmark)
        assertEquals(9, downBookmark!!.chapterIndex)
    }

    @Test
    fun syncManagerCountsEqualClientModifiedAsUpToDate() = runBlocking {
        val filesDir = tempFolder.newFolder("files")
        val repo = BookRepository(filesDir)
        val bookRoot = repo.createBookDirectoryForImportedTitle("Steady Book")
        repo.saveMetadata(bookRoot, BookMetadata(
            id = UUID.randomUUID().toString(),
            title = "Steady Book",
            cover = null,
            folder = bookRoot.name,
            lastAccess = 0.0,
        ))
        val bookmarkAppleSeconds = 800_400_000.0
        repo.saveBookmark(bookRoot, Bookmark(
            chapterIndex = 3,
            progress = 0.5,
            characterCount = 100,
            lastModified = bookmarkAppleSeconds,
        ))

        val transport = FakeTransport(
            initialRecords = mapOf(
                "steady_book" to fakeRecord(
                    syncId = "steady_book",
                    title = "Steady Book",
                    contentType = HttpSyncContentType.Epub,
                    chapterIndex = 3,
                    clientModified = appleSecondsToRfc3339(bookmarkAppleSeconds),
                ),
            ),
        )
        val manager = HttpSyncManager(
            bookRepository = repo,
            transportFactory = { transport },
        )

        val result = manager.syncOnce(HttpSyncSettings("https://x", "t", enabled = true))

        assertEquals(0, result.uploaded)
        assertEquals(0, result.downloaded)
        assertEquals(1, result.upToDate)
        assertEquals(0, result.remoteOnly)
        assertTrue(result.errors.isEmpty())
    }

    @Test
    fun syncManagerPersistsAiChatLogOnDownload() = runBlocking {
        val filesDir = tempFolder.newFolder("files")
        val repo = BookRepository(filesDir)
        val bookRoot = repo.createBookDirectoryForImportedTitle("Manga With History")
        repo.saveMetadata(bookRoot, BookMetadata(
            id = UUID.randomUUID().toString(),
            title = "Manga With History",
            cover = null,
            folder = bookRoot.name,
            lastAccess = 0.0,
        ))
        bookRoot.resolve("mokuro.json").writeText("{}")
        // Local has no chat log yet.

        val remoteEntry = HttpSyncAiChatEntry(
            bubbleText = "おーっ?",
            prompt = "tutor",
            model = "gpt-5.5",
            response = "Whoa!",
            timestampSeconds = 800_999_000.0,
        )
        val transport = FakeTransport(
            initialRecords = mapOf(
                "manga_with_history" to fakeRecord(
                    syncId = "manga_with_history",
                    title = "Manga With History",
                    contentType = HttpSyncContentType.Mokuro,
                    chapterIndex = 0,
                    clientModified = "2099-01-01T00:00:00Z",
                    aiChatLog = HttpSyncAiChatLog(entries = listOf(remoteEntry)),
                ),
            ),
        )
        val historyStore = AiChatHistoryStore()
        val manager = HttpSyncManager(
            bookRepository = repo,
            aiHistoryStore = historyStore,
            transportFactory = { transport },
        )

        val result = manager.syncOnce(HttpSyncSettings("https://x", "t", enabled = true))
        assertEquals(1, result.downloaded)

        val loaded = historyStore.load(bookRoot)
        assertEquals(1, loaded.entries.size)
        assertEquals(
            AiChatEntry(
                bubbleText = "おーっ?",
                prompt = "tutor",
                model = "gpt-5.5",
                response = "Whoa!",
                timestampSeconds = 800_999_000.0,
            ),
            loaded.entries.single(),
        )
    }

    // --- helpers -------------------------------------------------------------------------

    private fun fakeRecord(
        syncId: String,
        title: String,
        contentType: HttpSyncContentType,
        chapterIndex: Int,
        clientModified: String,
        aiChatLog: HttpSyncAiChatLog? = null,
    ): HttpSyncBookRecord = HttpSyncBookRecord(
        syncId = syncId,
        title = title,
        contentType = contentType,
        bookmark = HttpSyncBookmark(
            chapterIndex = chapterIndex,
            progress = 0.0,
            characterCount = chapterIndex + 1,
            lastModified = clientModified,
        ),
        aiChatLog = aiChatLog,
        clientModified = clientModified,
        serverModified = clientModified,
    )

    /** In-memory transport that just keeps a map and returns server-modified timestamps. */
    private class FakeTransport(
        initialRecords: Map<String, HttpSyncBookRecord> = emptyMap(),
    ) : HttpSyncTransport {
        val records: MutableMap<String, HttpSyncBookRecord> = initialRecords.toMutableMap()

        override suspend fun listBooks(): HttpSyncBooksList = HttpSyncBooksList(
            books = records.values.map {
                HttpSyncBookSummary(
                    syncId = it.syncId,
                    clientModified = it.clientModified,
                    serverModified = it.serverModified,
                )
            },
        )

        override suspend fun getBook(syncId: String): HttpSyncBookRecord? = records[syncId]

        override suspend fun putBook(record: HttpSyncBookRecord): HttpSyncPutResponse {
            val stamped = record.copy(serverModified = "test-server-modified")
            records[record.syncId] = stamped
            return HttpSyncPutResponse(serverModified = stamped.serverModified)
        }

        override suspend fun deleteBook(syncId: String) {
            records.remove(syncId)
        }
    }
}
