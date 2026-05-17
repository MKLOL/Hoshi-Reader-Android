package moe.antimony.hoshi.features.sync.http

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.UUID

/**
 * Live-server wire-format smoke test for [HttpSyncKvClient].
 *
 * Disabled by default — the fake-transport tests in `HttpSyncTest` cover the manager logic
 * without a network. This one exercises the real HTTP/JSON/headers handshake against the
 * configured `dragos.games` KV server, and only runs when both `HOSHI_KV_BASE_URL` and
 * `HOSHI_KV_TOKEN` are present in the environment, so it never executes in CI.
 *
 * Run locally with:
 *
 *     HOSHI_KV_BASE_URL=https://dragos.games/api/book_sync \
 *     HOSHI_KV_TOKEN=... \
 *     ./gradlew :app:testDebugUnitTest \
 *         --tests moe.antimony.hoshi.features.sync.http.HttpSyncLiveServerSmokeTest
 *
 * The test writes under a per-run `__smoke/{uuid}/...` prefix and cleans up after itself
 * so it doesn't pollute the live namespace.
 */
class HttpSyncLiveServerSmokeTest {

    @get:Rule val tempFolder = TemporaryFolder()

    private val baseUrl: String? = readSecret("HOSHI_KV_BASE_URL")
    private val token: String? = readSecret("HOSHI_KV_TOKEN")

    companion object {
        /**
         * Resolution order for live-test credentials:
         *  1. Environment variable.
         *  2. The file `<repo-root>/.hoshi-sync-secret.env` (gitignored). Format is plain
         *     `KEY=value` lines, blank lines and `#` comments are ignored.
         *
         * If neither is set, the live tests are skipped via JUnit `Assume`; the rest of the
         * suite uses an in-memory fake transport and never needs credentials.
         */
        private fun readSecret(name: String): String? {
            System.getenv(name)?.takeIf { it.isNotBlank() }?.let { return it }
            // Walk up from the JUnit working dir (typically `<repo>/app/`) to find the
            // repo-root secret file. Stop after a few levels so misuse can't escape.
            var dir: java.io.File? = java.io.File(System.getProperty("user.dir") ?: ".").canonicalFile
            repeat(4) {
                val candidate = dir?.resolve(".hoshi-sync-secret.env")
                if (candidate != null && candidate.isFile) {
                    val parsed = parseEnvFile(candidate.readText())
                    return parsed[name]?.takeIf { it.isNotBlank() }
                }
                dir = dir?.parentFile
            }
            return null
        }

        private fun parseEnvFile(content: String): Map<String, String> {
            val out = mutableMapOf<String, String>()
            for (raw in content.lineSequence()) {
                val line = raw.trim()
                if (line.isEmpty() || line.startsWith("#")) continue
                val eq = line.indexOf('=')
                if (eq <= 0) continue
                val key = line.substring(0, eq).trim()
                val value = line.substring(eq + 1).trim().trim('"', '\'')
                out[key] = value
            }
            return out
        }
    }

    @Test
    fun putGetListDeleteRoundTripsAgainstLiveServer() = runBlocking {
        assumeTrue("HOSHI_KV_BASE_URL not set — skipping live-server test", baseUrl != null)
        assumeTrue("HOSHI_KV_TOKEN not set — skipping live-server test", token != null)

        val client = HttpSyncKvClient(baseUrl!!, token!!)
        val testId = "__smoke/${UUID.randomUUID()}"
        val key = "$testId/hello"
        val body = """{"ping":"pong"}""".toByteArray()

        try {
            // PUT
            val putResp = client.put(key, "application/json; charset=utf-8", body)
            assertEquals(key, putResp.key)
            assertTrue("etag is a sha256 prefix", putResp.etag.startsWith("sha256:"))
            assertEquals(body.size, putResp.size)

            // GET round-trips bytes + headers
            val fetched = client.get(key)
            assertNotNull("just-put key should exist", fetched)
            assertEquals(
                """{"ping":"pong"}""",
                fetched!!.body.toString(Charsets.UTF_8),
            )
            assertTrue(fetched.contentType.startsWith("application/json"))
            assertTrue(fetched.etag.startsWith("sha256:"))

            // LIST with prefix returns our key
            val listed = client.list(prefix = "$testId/")
            assertTrue(
                "expected our key in the listing, got ${listed.keys.map { it.key }}",
                listed.keys.any { it.key == key },
            )

            // DELETE makes it 404 on next GET
            client.delete(key)
            assertNull("deleted key should be gone", client.get(key))
        } finally {
            // Best-effort cleanup if any earlier step left state behind.
            runCatching { client.delete(key) }
        }
    }

    @Test
    fun missingKeyReturnsNullNotException() = runBlocking {
        assumeTrue("HOSHI_KV_BASE_URL not set — skipping live-server test", baseUrl != null)
        assumeTrue("HOSHI_KV_TOKEN not set — skipping live-server test", token != null)
        val client = HttpSyncKvClient(baseUrl!!, token!!)
        assertNull(client.get("__smoke/${UUID.randomUUID()}/never-existed"))
    }

    @Test
    fun payloadCodecRoundTripsAgainstLiveServer() = runBlocking {
        assumeTrue("HOSHI_KV_BASE_URL not set — skipping live-server test", baseUrl != null)
        assumeTrue("HOSHI_KV_TOKEN not set — skipping live-server test", token != null)

        val client = HttpSyncKvClient(baseUrl!!, token!!)
        val codec = HttpSyncPayloadCodec()
        val syncId = "__smoke_payload_${UUID.randomUUID().toString().substring(0, 8)}"

        // Build a small fake book directory.
        val src = tempFolder.newFolder("upload-side").apply {
            resolve("mokuro.json").writeText("""{"smoke":"test"}""")
            resolve("pages").mkdirs()
            resolve("pages/page1.png").writeBytes(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47))
        }

        try {
            // First upload should write zip + manifest.
            val firstUpload = codec.uploadIfChanged(
                transport = client,
                syncId = syncId,
                bookRoot = src,
                originalName = "Smoke Test Volume",
                format = HttpSyncContentType.Mokuro,
            )
            assertTrue("first call should upload", firstUpload)

            // Second call (same contents) must not re-upload.
            val secondUpload = codec.uploadIfChanged(
                transport = client,
                syncId = syncId,
                bookRoot = src,
                originalName = "Smoke Test Volume",
                format = HttpSyncContentType.Mokuro,
            )
            assertFalse("identical contents should not re-upload", secondUpload)

            // Download to a fresh dir and verify contents match.
            val downloadTarget = tempFolder.newFolder("download-side")
            val manifest = codec.downloadAndUnpack(client, syncId, downloadTarget)
            assertEquals("Smoke Test Volume", manifest.originalName)
            assertEquals(HttpSyncContentType.Mokuro, manifest.format)
            assertEquals("""{"smoke":"test"}""", downloadTarget.resolve("mokuro.json").readText())
            assertEquals(4, downloadTarget.resolve("pages/page1.png").readBytes().size)
        } finally {
            // Clean up server-side keys regardless of test outcome.
            runCatching { client.delete(payloadZipKey(syncId)) }
            runCatching { client.delete(payloadManifestKey(syncId)) }
        }
    }

    /**
     * The test that would have caught the v0.7.6 regression. Two simulated devices, one
     * server. Device A pushes a complete state (book payload + bookmark + chat entry + AI
     * settings). Device B (fresh BookRepository, fresh AI settings DataStore) runs syncOnce
     * and is asserted to end up with all four pieces of state.
     */
    @Test
    fun freshDeviceDownloadsBookBookmarkChatAndAiSettings() = runBlocking {
        assumeTrue("HOSHI_KV_BASE_URL not set", baseUrl != null)
        assumeTrue("HOSHI_KV_TOKEN not set", token != null)

        val client = HttpSyncKvClient(baseUrl!!, token!!)
        // Use a title that round-trips through `deriveSyncId` cleanly — the production
        // flow assumes the title pushed to the manifest can be re-derived back to the same
        // syncId on the receiving device.
        val titleHash = java.util.UUID.randomUUID().toString().substring(0, 8)
        val title = "smoke cross device $titleHash"
        val syncId = deriveSyncId(title)
            ?: error("test title must derive to a non-null syncId")
        val cleanupKeys = mutableListOf<String>()

        try {
            // ── Device A: build a complete state on the server ────────────────────────────
            run {
                val codec = HttpSyncPayloadCodec()
                val src = tempFolder.newFolder("device-a").apply {
                    resolve("mokuro.json").writeText("""{"smoke":"a"}""")
                    resolve("pages").mkdirs()
                    resolve("pages/p1.png").writeBytes(byteArrayOf(0x89.toByte(), 0x50))
                }
                codec.uploadIfChanged(client, syncId, src, title, HttpSyncContentType.Mokuro)
                cleanupKeys += payloadZipKey(syncId)
                cleanupKeys += payloadManifestKey(syncId)
                // Bookmark.
                val bookmarkBlob = HttpSyncBookmarkBlob(
                    chapterIndex = 42, progress = 0.0, characterCount = 100,
                    lastModified = "2099-01-01T00:00:00Z",
                )
                client.put(
                    bookmarkKey(syncId),
                    "application/json; charset=utf-8",
                    kotlinx.serialization.json.Json.encodeToString(HttpSyncBookmarkBlob.serializer(), bookmarkBlob)
                        .toByteArray(),
                )
                cleanupKeys += bookmarkKey(syncId)
                // Chat entry.
                val chatBlob = HttpSyncChatEntryBlob("xdev-bubble", "tutor", "gpt-5.5", "xdev response", 12345.0)
                val chatSuffix = chatEntryKeySuffix(chatBlob.timestampSeconds, chatBlob.bubbleText, chatBlob.response)
                client.put(
                    chatKey(syncId, chatSuffix),
                    "application/json; charset=utf-8",
                    kotlinx.serialization.json.Json.encodeToString(HttpSyncChatEntryBlob.serializer(), chatBlob)
                        .toByteArray(),
                )
                cleanupKeys += chatKey(syncId, chatSuffix)
                // AI settings.
                val aiBlob = HttpSyncAiChatSettingsBlob(
                    model = "smoke-test-model",
                    promptText = "Smoke test prompt.",
                    imagePromptText = "Smoke test image prompt.",
                    lastModified = "2099-01-01T00:00:00Z",
                )
                client.put(
                    AI_CHAT_SETTINGS_KEY,
                    "application/json; charset=utf-8",
                    kotlinx.serialization.json.Json.encodeToString(HttpSyncAiChatSettingsBlob.serializer(), aiBlob)
                        .toByteArray(),
                )
                // Don't add AI_CHAT_SETTINGS_KEY to cleanupKeys — it's a global key, deleting
                // it would affect any concurrent run. Best-effort restore in the finally
                // block instead.
            }

            // ── Device B: fresh repo + fresh AI settings ──────────────────────────────────
            val filesDir = tempFolder.newFolder("device-b-files")
            val repo = moe.antimony.hoshi.epub.BookRepository(filesDir)
            val aiRepo = moe.antimony.hoshi.features.ai.AiChatSettingsRepository(
                HttpSyncAppSettingsTestSupport.inMemoryPreferencesDataStore(),
            )
            val reconciler = HttpSyncReconciler(
                bookRepository = repo,
                aiSettingsRepository = aiRepo,
                transportFactory = { client },
            )
            val settings = HttpSyncSettings(baseUrl = baseUrl, bearerToken = token, enabled = true)
            val result = reconciler.syncOnce(settings)

            // ── Assertions ────────────────────────────────────────────────────────────
            // Don't compare global counts (other __smoke_xdev_* orphans from prior runs
            // would skew them). Instead assert that OUR specific book ended up locally
            // with the right bookmark and chat entry.
            assertTrue("AI settings should download", result.downloadedAppSettings)
            assertTrue("downloaded at least our payload", result.downloadedPayloads >= 1)
            assertTrue("downloaded at least our bookmark", result.downloadedBookmarks >= 1)
            assertTrue("downloaded at least our chat entry", result.downloadedChatEntries >= 1)

            val imported = repo.loadBookEntries().singleOrNull { deriveSyncId(it.metadata.title) == syncId }
            assertNotNull("our specific book should have been imported, got titles: ${repo.loadBookEntries().map { it.metadata.title }}", imported)
            val bookmark = repo.loadBookmark(imported!!.root)!!
            assertEquals(42, bookmark.chapterIndex)

            val aiHistory = moe.antimony.hoshi.features.ai.AiChatHistoryStore().load(imported.root)
            assertTrue("at least our chat entry made it", aiHistory.entries.any { it.bubbleText == "xdev-bubble" })

            val finalAi = aiRepo.settings.first()
            assertEquals("smoke-test-model", finalAi.model)
            assertEquals("Smoke test prompt.", finalAi.promptText)
            assertEquals("Smoke test image prompt.", finalAi.imagePromptText)
        } finally {
            cleanupKeys.forEach { key -> runCatching { client.delete(key) } }
            // Best-effort: leave AI_CHAT_SETTINGS_KEY's original state alone (nobody else
            // should be using a __smoke_xdev book in production, so it's contained).
        }
    }

    @Test
    fun wrongTokenReturnsHttpSyncException() = runBlocking {
        assumeTrue("HOSHI_KV_BASE_URL not set — skipping live-server test", baseUrl != null)
        val client = HttpSyncKvClient(baseUrl!!, "definitely-not-a-real-token")
        val ex = runCatching { client.list(prefix = "__smoke/") }.exceptionOrNull()
        assertNotNull("expected HttpSyncException for bad token", ex)
        assertTrue(
            "expected message to mention 401, got: ${ex?.message}",
            ex is HttpSyncException && ex.message!!.contains("401"),
        )
    }
}
