package moe.antimony.hoshi.features.sync.http

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

    private val baseUrl: String? = System.getenv("HOSHI_KV_BASE_URL")
    private val token: String? = System.getenv("HOSHI_KV_TOKEN")

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
