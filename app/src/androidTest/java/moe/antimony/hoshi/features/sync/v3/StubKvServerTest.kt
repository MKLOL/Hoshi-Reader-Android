package moe.antimony.hoshi.features.sync.v3

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import moe.antimony.hoshi.features.sync.http.HttpSyncException
import moe.antimony.hoshi.features.sync.http.HttpSyncKvClient
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Protocol-level sanity tests for [StubKvServer]. These tests prove the server
 * implements the v2 KV protocol correctly enough for the v3 engine tests above
 * to be trustworthy. If any of these fail, all other v3 integration tests are
 * suspect.
 *
 * Required coverage:
 *  - PUT then GET round-trips bytes + headers,
 *  - GET on missing key returns 404,
 *  - DELETE then GET returns 404,
 *  - LIST with prefix filters correctly,
 *  - LIST pagination: insert > limit keys, walk via cursor, every key seen exactly once,
 *  - LIST since: filter by lastModified > since,
 *  - Multipart upload: start, parts, complete; final body bytes match a single PUT
 *    of the same content,
 *  - Multipart delete cancels in-flight upload,
 *  - Auth: missing token → 401; wrong token → 401; correct token → 200,
 *  - Bytes for [bytesAt] match what GET returns.
 */
@RunWith(AndroidJUnit4::class)
class StubKvServerTest {

    private lateinit var server: StubKvServer
    private lateinit var client: HttpSyncKvClient

    @Before
    fun setUp() {
        server = StubKvServer()
        server.start()
        client = HttpSyncKvClient(
            baseUrl = server.baseUrl,
            bearerToken = server.token,
            // Force tiny multipart parts so the multipart round-trip test exercises
            // real multi-part splitting without needing a huge payload.
            multipartPartSizeBytes = 64,
            multipartThresholdBytes = 64,
        )
    }

    @After
    fun tearDown() {
        server.close()
    }

    @Test
    fun putThenGet_roundTripsBytesAndHeaders() = runBlocking {
        val body = "hello world".toByteArray()
        val write = client.put("books/x/bookmark", "application/json", body)

        assertEquals("books/x/bookmark", write.key)
        assertEquals(body.size, write.size)
        assertEquals("application/json", write.contentType)
        assertEquals("sha256:" + sha256Hex(body), write.etag)
        assertTrue(write.lastModified.isNotBlank())

        val fetched = client.get("books/x/bookmark")
        assertNotNull(fetched)
        assertArrayEquals(body, fetched!!.body)
        assertTrue(fetched.contentType.startsWith("application/json"))
        assertEquals(write.etag, fetched.etag)
        assertEquals(write.lastModified, fetched.lastModified)
    }

    @Test
    fun get_missingKey_returnsNull() = runBlocking {
        assertNull(client.get("books/missing/bookmark"))
    }

    @Test
    fun deleteThenGet_returnsNull() = runBlocking {
        client.put("books/x/bookmark", "application/json", "v1".toByteArray())
        client.delete("books/x/bookmark")
        assertNull(client.get("books/x/bookmark"))
    }

    @Test
    fun delete_onAbsentKey_isNoOpSuccess() = runBlocking {
        // Per HTTP_SYNC_KV.md: 404 on DELETE is a no-op success from the client's view.
        // The transport translates that to a silent return, so this must not throw.
        client.delete("books/never/existed")
    }

    @Test
    fun list_withPrefix_filtersCorrectly() = runBlocking {
        client.put("books/a/bookmark", "application/json", "a".toByteArray())
        client.put("books/b/bookmark", "application/json", "b".toByteArray())
        client.put("app/ai_chat_settings", "application/json", "s".toByteArray())

        val listed = client.list(prefix = "books/")
        val keys = listed.keys.map { it.key }.sorted()
        assertEquals(listOf("books/a/bookmark", "books/b/bookmark"), keys)
        assertFalse(listed.truncated)
        assertNull(listed.nextCursor)
    }

    @Test
    fun list_paginationWalksEveryKeyExactlyOnce() = runBlocking {
        // Insert 7 keys; page size 3 → 3 pages: 3, 3, 1.
        repeat(7) { i -> client.put("books/p$i/bookmark", "application/json", "$i".toByteArray()) }

        val seen = mutableListOf<String>()
        var cursor: String? = null
        var pages = 0
        while (true) {
            val page = client.list(prefix = "books/", cursor = cursor, limit = 3)
            seen += page.keys.map { it.key }
            pages++
            if (!page.truncated) break
            cursor = page.nextCursor
            assertNotNull("truncated page must include nextCursor", cursor)
            if (pages > 100) fail("pagination did not terminate")
        }
        val expected = (0 until 7).map { "books/p$it/bookmark" }.sorted()
        assertEquals(expected, seen)
        // Exactly once: dedupe size matches.
        assertEquals(seen.size, seen.toSet().size)
    }

    @Test
    fun list_respectsLimit() = runBlocking {
        repeat(5) { i -> client.put("books/k$i/bookmark", "application/json", "$i".toByteArray()) }
        val page = client.list(prefix = "books/", limit = 2)
        assertEquals(2, page.keys.size)
        assertTrue(page.truncated)
        assertEquals(page.keys.last().key, page.nextCursor)
    }

    @Test
    fun list_sinceFiltersByLastModified() = runBlocking {
        val first = client.put("books/a/bookmark", "application/json", "a".toByteArray())
        // Ensure the second write is strictly later (StubKvServer guarantees monotonic).
        val second = client.put("books/b/bookmark", "application/json", "b".toByteArray())
        assertTrue("second write must be strictly later", second.lastModified > first.lastModified)

        val sinceFirst = client.list(since = first.lastModified).keys.map { it.key }.sorted()
        assertEquals(listOf("books/b/bookmark"), sinceFirst)

        val sinceSecond = client.list(since = second.lastModified).keys
        assertTrue(sinceSecond.isEmpty())
    }

    @Test
    fun multipart_uploadConcatenatesPartsInOrder() = runBlocking {
        // Build a payload bigger than multipartThresholdBytes (64) so the client routes
        // through start/parts/complete. We assert the stored bytes match a single PUT
        // of the same content.
        val payload = ByteArray(200) { i -> (i % 251).toByte() }

        // multipart route via the client's putFile API (only path that fans out to multipart).
        val tempFile = kotlin.io.path.createTempFile(prefix = "stub-multipart", suffix = ".bin").toFile()
        try {
            tempFile.writeBytes(payload)
            val response = client.putFile("books/big/payload.zip", "application/zip", tempFile)
            assertEquals(payload.size, response.size)
            assertEquals("sha256:" + sha256Hex(payload), response.etag)
        } finally {
            tempFile.delete()
        }

        val fetched = client.get("books/big/payload.zip")
        assertNotNull(fetched)
        assertArrayEquals(payload, fetched!!.body)
        assertTrue(fetched.contentType.startsWith("application/zip"))
        // And bytesAt should match the wire bytes.
        assertArrayEquals(payload, server.bytesAt("books/big/payload.zip"))
    }

    @Test
    fun multipart_completeMatchesSinglePut() = runBlocking {
        // Same content via two paths → identical stored bytes + identical etag.
        val payload = ByteArray(150) { (it * 7 % 251).toByte() }

        client.put("single/payload", "application/octet-stream", payload)
        val singleEtag = client.get("single/payload")!!.etag

        val tempFile = kotlin.io.path.createTempFile(prefix = "stub-multipart-match", suffix = ".bin").toFile()
        try {
            tempFile.writeBytes(payload)
            client.putFile("multi/payload", "application/octet-stream", tempFile)
        } finally {
            tempFile.delete()
        }
        val multiEtag = client.get("multi/payload")!!.etag

        assertEquals(singleEtag, multiEtag)
        assertArrayEquals(payload, server.bytesAt("multi/payload"))
    }

    @Test
    fun multipart_deleteCancelsInflightUpload() = runBlocking {
        // We hand-roll the multipart dance because the high-level putFile API completes
        // automatically. This test proves DELETE /v1/kv-multipart/{id} drops upload state
        // before complete is ever called.
        val startBody = """{"key":"books/x/payload.zip","contentType":"application/zip"}"""
        val (startCode, startResp) = rawJsonRequest("POST", "/v1/kv-multipart/start", startBody)
        assertEquals(200, startCode)
        val uploadId = Regex("\"uploadId\":\"([^\"]+)\"").find(startResp)!!.groupValues[1]

        // Upload one part
        val partBody = "abc".toByteArray()
        val (partCode, _) = rawRequest("PUT", "/v1/kv-multipart/$uploadId/1", partBody, "application/octet-stream")
        assertEquals(200, partCode)

        // Cancel
        val (cancelCode, _) = rawRequest("DELETE", "/v1/kv-multipart/$uploadId", ByteArray(0), null)
        assertEquals(204, cancelCode)

        // Completing now must 404 — upload state is gone.
        val (completeCode, _) = rawJsonRequest(
            "POST",
            "/v1/kv-multipart/$uploadId/complete",
            """{"parts":[1]}""",
        )
        assertEquals(404, completeCode)

        // And no key was ever created in the main store.
        assertNull(server.bytesAt("books/x/payload.zip"))
    }

    @Test
    fun auth_missingToken_returns401() {
        val (code, body) = rawRequestWithToken("GET", "/v1/kv?prefix=books/", null, null, null)
        assertEquals(401, code)
        assertTrue("error body should mention auth: $body", body.contains("error"))
    }

    @Test
    fun auth_wrongToken_returns401() {
        val (code, _) = rawRequestWithToken("GET", "/v1/kv?prefix=books/", null, null, "wrong-token")
        assertEquals(401, code)
    }

    @Test
    fun auth_correctToken_returns200() = runBlocking {
        // The client uses the correct token; if auth weren't allowing it through, every
        // other test would already be failing. Assert explicitly here for the coverage.
        val list = client.list()
        assertEquals(0, list.keys.size)
    }

    @Test
    fun bytesAt_matchesGetBytes() = runBlocking {
        val payload = byteArrayOf(0x01, 0x02, 0x03, 0x7f, 0x00, 0x55.toByte())
        client.put("books/x/metadata", "application/json", payload)

        val viaGet = client.get("books/x/metadata")!!.body
        val viaInspector = server.bytesAt("books/x/metadata")!!
        assertArrayEquals(payload, viaGet)
        assertArrayEquals(payload, viaInspector)
    }

    @Test
    fun keys_returnsAllInsertedKeys() = runBlocking {
        client.put("a", "application/json", "1".toByteArray())
        client.put("b", "application/json", "2".toByteArray())
        client.put("c/d/e", "application/json", "3".toByteArray())
        val keys = server.keys().toSet()
        assertEquals(setOf("a", "b", "c/d/e"), keys)
    }

    @Test
    fun reset_clearsState() = runBlocking {
        client.put("books/x/bookmark", "application/json", "v".toByteArray())
        assertTrue(server.keys().isNotEmpty())
        server.reset()
        assertTrue(server.keys().isEmpty())
        assertNull(server.bytesAt("books/x/bookmark"))
    }

    @Test
    fun setBehavior_failWith_overridesResponse() = runBlocking {
        server.setBehavior { _, _ -> BehaviorAction.FailWith(503, """{"error":"injected"}""") }
        try {
            client.list()
            fail("expected HttpSyncException for injected 503")
        } catch (e: HttpSyncException) {
            assertTrue(
                "exception message should mention server trouble or 503, got: ${e.message}",
                e.message?.contains("503") == true || e.message?.lowercase()?.contains("server") == true,
            )
        }
    }

    @Test
    fun setBehavior_delay_doesNotCorruptResponse() = runBlocking {
        // 50ms is short enough not to slow the test suite, long enough to prove the
        // sleep happens before the response is generated. The body still arrives intact.
        server.setBehavior { _, _ -> BehaviorAction.Delay(50) }
        val start = System.nanoTime()
        val list = client.list()
        val elapsedMs = (System.nanoTime() - start) / 1_000_000
        assertTrue("expected at least 50ms delay, got $elapsedMs ms", elapsedMs >= 40)
        assertEquals(0, list.keys.size)
    }

    @Test
    fun setBehavior_dropConnection_surfacesAsError() {
        server.setBehavior { _, _ -> BehaviorAction.DropConnection }
        runBlocking {
            try {
                client.list()
                fail("expected HttpSyncException when stub drops the connection")
            } catch (e: HttpSyncException) {
                // The exact wording depends on which I/O step JVM trips on — we just
                // require some error was surfaced, not silently swallowed.
                assertNotNull(e.message)
            }
        }
    }

    // ─── Raw HTTP helpers ─────────────────────────────────────────────────────

    private fun rawJsonRequest(method: String, path: String, jsonBody: String): Pair<Int, String> =
        rawRequest(method, path, jsonBody.toByteArray(), "application/json; charset=utf-8")

    private fun rawRequest(
        method: String,
        path: String,
        body: ByteArray,
        contentType: String?,
    ): Pair<Int, String> = rawRequestWithToken(method, path, body, contentType, server.token)

    private fun rawRequestWithToken(
        method: String,
        path: String,
        body: ByteArray?,
        contentType: String?,
        token: String?,
    ): Pair<Int, String> {
        val url = URL(server.baseUrl.trimEnd('/') + path)
        val connection = url.openConnection() as HttpURLConnection
        connection.requestMethod = method
        connection.connectTimeout = 5_000
        connection.readTimeout = 10_000
        token?.let { connection.setRequestProperty("Authorization", "Bearer $it") }
        contentType?.let { connection.setRequestProperty("Content-Type", it) }
        if (body != null && body.isNotEmpty() && method != "GET") {
            connection.doOutput = true
            connection.setFixedLengthStreamingMode(body.size)
            connection.outputStream.use { it.write(body) }
        }
        return try {
            val code = connection.responseCode
            val text = (if (code in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()?.use { it.readText() }
                .orEmpty()
            code to text
        } finally {
            connection.disconnect()
        }
    }

    private fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        val sb = StringBuilder(digest.size * 2)
        for (b in digest) sb.append("%02x".format(b))
        return sb.toString()
    }
}
