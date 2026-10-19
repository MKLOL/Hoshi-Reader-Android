package moe.antimony.hoshi.features.sync.http

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/** [HttpSyncKvClient.getRange] against servers that misbehave in the ways the review asked about. */
class HttpSyncKvClientRangeTest {
    private val executor = Executors.newCachedThreadPool()
    private val server = HttpServer.create(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0).apply {
        this.executor = this@HttpSyncKvClientRangeTest.executor
        start()
    }
    private val client = HttpSyncKvClient("http://127.0.0.1:${server.address.port}", "t", Dispatchers.IO)
    private val requestRanges = mutableListOf<String?>()

    @After
    fun stop() {
        server.stop(0)
        executor.shutdownNow()
    }

    private fun route(path: String, handler: (HttpExchange) -> Unit) {
        server.createContext(path) { exchange ->
            synchronized(requestRanges) { requestRanges += exchange.requestHeaders.getFirst("Range") }
            try {
                handler(exchange)
            } catch (_: IOException) {
            } finally {
                exchange.close()
            }
        }
    }

    private fun HttpExchange.partial(body: ByteArray, contentRange: String?, contentLength: Long = body.size.toLong()) {
        contentRange?.let { responseHeaders.add("Content-Range", it) }
        sendResponseHeaders(206, contentLength)
        responseBody.write(body)
    }

    private suspend fun rangeError(start: Long?, length: Int): HttpSyncException? = try {
        client.getRange("k", start, length)
        null
    } catch (e: HttpSyncException) {
        e
    }

    @Test
    fun aServerThatIgnoresTheRangeIsNotDownloaded() = runBlocking {
        val total = 1L shl 30
        val written = AtomicLong()
        route("/v1/kv/k") { exchange ->
            exchange.sendResponseHeaders(200, total)
            val chunk = ByteArray(64 * 1024)
            while (written.get() < total) {
                exchange.responseBody.write(chunk)
                written.addAndGet(chunk.size.toLong())
            }
        }
        val started = System.nanoTime()

        assertNull(client.getRange("k", null, 65_577))

        val millis = (System.nanoTime() - started) / 1_000_000
        delay(500)
        assertTrue("took $millis ms", millis < 5_000)
        assertTrue("server wrote ${written.get()} bytes", written.get() < 64L * 1024 * 1024)
    }

    @Test
    fun aPartialResponseIsCheckedAgainstWhatWasAsked() = runBlocking {
        val body = ByteArray(100) { it.toByte() }
        route("/v1/kv/k") { exchange ->
            val range = exchange.requestHeaders.getFirst("Range")
            when (range) {
                "bytes=10-109" -> exchange.partial(body, "bytes 11-110/1000") // wrong start
                "bytes=20-119" -> exchange.partial(body.copyOf(50), "bytes 20-119/1000", 50) // short body
                "bytes=30-129" -> exchange.partial(body + body, "bytes 30-129/1000", 200) // long body
                "bytes=40-139" -> exchange.partial(body, null) // no Content-Range
                "bytes=50-149" -> exchange.partial(body, "bytes 50-149/*") // unknown total
                "bytes=950-1049" -> exchange.partial(body.copyOf(50), "bytes 950-999/1000", 50) // clamped at EOF
                else -> { exchange.sendResponseHeaders(416, -1) }
            }
        }

        assertNotNull("wrong start", rangeError(10, 100))
        assertNotNull("short body", rangeError(20, 100))
        assertNotNull("long body", rangeError(30, 100))
        assertNotNull("no Content-Range", rangeError(40, 100))
        assertNotNull("unknown total", rangeError(50, 100))
        val clamped = client.getRange("k", 950, 100)!!
        assertEquals(50, clamped.body.size)
        assertEquals(1000L, clamped.totalSize)
        assertNull("416", client.getRange("k", 5_000, 100))
        assertNull("404", client.getRange("missing", 0, 1))
        assertTrue(requestRanges.all { it == null || it.startsWith("bytes=") })
    }

    /**
     * RFC 9110 15.3.7 lets a server send "only a subset of the data requested for reasons of its
     * own, such as temporary unavailability". A short 206 that does not end at the end of the value
     * is accepted as if it were complete, so the reader takes it as a malformed archive and records
     * the cover as permanently unavailable instead of retrying or reading the rest.
     */
    @Test
    fun aShortRangeThatDoesNotReachTheEndOfTheValueIsNotTakenAsComplete() = runBlocking {
        route("/v1/kv/k") { exchange -> exchange.partial(ByteArray(50), "bytes 60-109/1000") }

        val result = try {
            client.getRange("k", 60, 100)
        } catch (_: HttpSyncException) {
            null
        }

        assertNull("accepted ${result?.body?.size} of 100 bytes mid-value", result)
    }

    /**
     * A suffix request (`bytes=-N`) must come back as the value's last bytes: the reader searches
     * the end record from the end of the body. Content-Range `bytes 0-99/1000` is not a tail.
     */
    @Test
    fun aSuffixRequestAnsweredWithBytesThatAreNotTheTailIsRejected() = runBlocking {
        val body = ByteArray(100) { it.toByte() }
        route("/v1/kv/k") { exchange -> exchange.partial(body, "bytes 0-99/1000") }

        val result = try {
            client.getRange("k", null, 100)
        } catch (_: HttpSyncException) {
            null
        }

        assertNull("accepted a non-tail range for a suffix request: start=${result?.start}", result)
    }

    /**
     * Cancelling while the server has not answered yet closes the socket at once. (A stall in the
     * middle of a small body is not covered: the JDK's HttpURLConnection.disconnect() then waits
     * for the blocked read, unlike Android's OkHttp-backed implementation, which closes the socket.)
     */
    @Test
    fun cancellingWhileTheServerStallsReturnsPromptly() = runBlocking {
        route("/v1/kv/k") { _ -> Thread.sleep(20_000) }
        val request = async(Dispatchers.IO) { client.getRange("k", 0, 100) }
        delay(500)
        val started = System.nanoTime()

        request.cancel()
        try {
            request.await()
            fail("not cancelled")
        } catch (_: CancellationException) {
        }

        val millis = (System.nanoTime() - started) / 1_000_000
        assertTrue("cancel took $millis ms", millis < 2_000)
    }

    @Test
    fun aRedirectKeepsTheRangeAndIdentityEncoding() = runBlocking {
        val body = ByteArray(10) { it.toByte() }
        var encoding: String? = null
        route("/v1/kv/k") { exchange ->
            exchange.responseHeaders.add("Location", "/v1/kv/moved")
            exchange.sendResponseHeaders(302, -1)
        }
        route("/v1/kv/moved") { exchange ->
            encoding = exchange.requestHeaders.getFirst("Accept-Encoding")
            val range = exchange.requestHeaders.getFirst("Range")
            if (range == "bytes=5-14") exchange.partial(body, "bytes 5-14/100") else exchange.sendResponseHeaders(200, -1)
        }

        val range = client.getRange("k", 5, 10)

        assertNotNull(range)
        assertArrayEquals(body, range!!.body)
        assertEquals("identity", encoding)
    }
}
