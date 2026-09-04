package moe.antimony.hoshi.features.sync.integration

import kotlinx.coroutines.runBlocking
import moe.antimony.hoshi.features.sync.http.HttpSyncException
import moe.antimony.hoshi.features.sync.http.HttpSyncKvClient
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.ClassRule
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.security.MessageDigest
import kotlin.random.Random

/**
 * The production [HttpSyncKvClient] against a real server over real HTTP: every route, the
 * status codes the reconcilers rely on, pagination, and the multipart upload path.
 */
class RealServerKvClientTest {
    companion object {
        @JvmField @ClassRule val serverRule = SyncTestServerRule()
    }

    @get:Rule val temp = TemporaryFolder()

    private val server get() = serverRule.server

    @Before
    fun resetServer() {
        server.reset()
    }

    private fun sha256(bytes: ByteArray): String =
        "sha256:" + MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    @Test
    fun putGetListDeleteRoundTripOverHttp() = runBlocking {
        val client = server.client()
        val key = "books/zenitendou-01/epub.manifest"
        val body = """{"sha256":"sha256:abc","sizeBytes":3}""".toByteArray()

        val put = client.put(key, "application/json; charset=utf-8", body)
        assertEquals(key, put.key)
        assertEquals(sha256(body), put.etag)
        assertEquals(body.size, put.size)
        assertTrue(put.lastModified.endsWith("Z"))

        val fetched = client.get(key)
        assertNotNull(fetched)
        assertArrayEquals(body, fetched!!.body)
        assertTrue(fetched.contentType.startsWith("application/json"))
        assertEquals(put.etag, fetched.etag)
        assertEquals(put.lastModified, fetched.lastModified)

        val listed = client.list(prefix = "books/")
        assertEquals(listOf(key), listed.keys.map { it.key })
        assertEquals(put.etag, listed.keys.single().etag)
        assertEquals(body.size, listed.keys.single().size)
        assertFalse(listed.truncated)

        client.delete(key)
        assertNull(client.get(key))
        // Deleting again is a quiet success: the post-condition "gone" already holds.
        client.delete(key)
    }

    @Test
    fun missingKeyIsNullAndWrongTokenIs401() = runBlocking {
        assertNull(server.client().get("books/never/bookmark"))
        val wrong = HttpSyncKvClient(server.baseUrl, "wrong-token")
        val error = assertThrows(HttpSyncException::class.java) {
            runBlocking { wrong.get("books/never/bookmark") }
        }
        assertTrue(error.message!!, error.message!!.contains("401"))
    }

    @Test
    fun listingPaginatesAndFiltersBySinceLikeTheReconcilersExpect() = runBlocking {
        val client = server.client()
        val stamps = (1..5).map { i -> client.put("books/b$i/bookmark", "text/plain", byteArrayOf(i.toByte())).lastModified }
        assertEquals("stamps are strictly increasing", stamps, stamps.sorted())
        assertEquals(stamps.size, stamps.toSet().size)

        val all = mutableListOf<String>()
        var cursor: String? = null
        do {
            val page = client.list(prefix = "books/", cursor = cursor, limit = 2)
            all += page.keys.map { it.key }
            cursor = page.nextCursor
        } while (page.truncated && cursor != null)
        assertEquals((1..5).map { "books/b$it/bookmark" }, all)

        val since = client.list(since = stamps[2])
        assertEquals(listOf("books/b4/bookmark", "books/b5/bookmark"), since.keys.map { it.key })
    }

    @Test
    fun largeFileUploadsThroughMultipartAndDownloadsIdentically() = runBlocking {
        val client = server.client(multipartThresholdBytes = 4096, multipartPartSizeBytes = 4096)
        val bytes = Random(7).nextBytes(10_000)
        val file = temp.newFile("payload.zip").apply { writeBytes(bytes) }
        server.clearRequests()

        val response = client.putFile("books/big/payload.zip", "application/zip", file)

        assertEquals(bytes.size, response.size)
        assertEquals(sha256(bytes), response.etag)
        val paths = server.requests().map { "${it.method} ${it.path}" }
        assertTrue(paths.toString(), "POST /v1/kv-multipart/start" in paths)
        assertEquals(3, paths.count { it.startsWith("PUT /v1/kv-multipart/") })
        assertTrue(paths.toString(), paths.any { it.startsWith("POST /v1/kv-multipart/") && it.endsWith("/complete") })

        val target = temp.newFile("download.zip")
        val meta = client.downloadToFile("books/big/payload.zip", target)
        assertNotNull(meta)
        assertEquals(response.etag, meta!!.etag)
        assertArrayEquals(bytes, target.readBytes())
        assertArrayEquals(bytes, client.get("books/big/payload.zip")!!.body)
    }

    @Test
    fun smallFileUploadsWithASinglePut() = runBlocking {
        val client = server.client(multipartThresholdBytes = 4096, multipartPartSizeBytes = 4096)
        val file = temp.newFile("small.zip").apply { writeBytes(ByteArray(4096) { it.toByte() }) }
        server.clearRequests()
        client.putFile("books/small/payload.zip", "application/zip", file)
        val paths = server.requests().map { "${it.method} ${it.path}" }
        assertEquals(listOf("PUT /v1/kv/books/small/payload.zip"), paths)
    }
}
