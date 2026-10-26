package moe.antimony.hoshi.features.anonymousstats

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Runs against a local MockWebServer only; no test reaches the real statistics endpoint. */
class AnonymousStatsTransportTest {
    @Test
    fun postsPlainJsonWithoutCredentials() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(204))
            val body = anonymousStatsPayload(INSTALL_ID, fullDevice(), fullLibrary(), fullFlags()).toJson()

            assertEquals(204, OkHttpAnonymousStatsTransport().post(server.url("/api/book_sync/v1/stats").toString(), body))

            val request = server.takeRequest()
            assertEquals("POST", request.method)
            assertEquals("/api/book_sync/v1/stats", request.path)
            assertEquals("application/json", request.getHeader("Content-Type"))
            assertNull(request.getHeader("Authorization"))
            assertNull(request.getHeader("Cookie"))
            assertEquals(body, request.body.readUtf8())
        }
    }

    @Test
    fun returnsTheStatusAndNeverFollowsARedirect() = runBlocking {
        MockWebServer().use { server ->
            MockWebServer().use { other ->
                listOf(500, 429, 400).forEach { server.enqueue(MockResponse().setResponseCode(it)) }
                server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", other.url("/elsewhere")))
                val transport = OkHttpAnonymousStatsTransport()
                val url = server.url("/v1/stats").toString()

                assertEquals(listOf(500, 429, 400, 302), List(4) { transport.post(url, "{}") })
                // The report never follows a redirect to another server.
                assertEquals(0, other.requestCount)
            }
        }
    }

    @Test
    fun cancellingTheReportCancelsTheRequest() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val transport = OkHttpAnonymousStatsTransport()
            val job = launch(Dispatchers.IO) { transport.post(server.url("/v1/stats").toString(), "{}") }
            withContext(Dispatchers.IO) { server.takeRequest() }

            withTimeout(5_000) { job.cancelAndJoin() }

            assertTrue(job.isCancelled)
        }
    }

    @Test
    fun connectAndReadGiveUpAfterTenSeconds() {
        val client = anonymousStatsHttpClient()

        assertEquals(10_000, client.connectTimeoutMillis)
        assertEquals(10_000, client.readTimeoutMillis)
        assertFalse(client.followRedirects)
        assertFalse(client.followSslRedirects)
    }

    private companion object {
        const val INSTALL_ID = "0b6a8a43-3c1e-4c55-9d7e-5f3b2f1a9c11"
    }
}
