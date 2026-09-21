package moe.antimony.hoshi.features.podcasts

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.mockwebserver.SocketPolicy
import moe.antimony.hoshi.features.sync.http.HttpSyncSettings
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class PodcastApiTest {
    @Test fun accessUsesBearerHeaderAndCorrectBasePath() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("{\"enabled\":true}"))
            val settings = HttpSyncSettings(server.url("/api/book_sync/").toString(), "fixture-token")
            assertTrue(PodcastApi().access(settings))
            val request = server.takeRequest()
            assertEquals("/api/book_sync/v1/podcasts/access", request.path)
            assertEquals("Bearer fixture-token", request.getHeader("Authorization"))
            assertFalse(request.path!!.contains("fixture-token"))
        }
    }

    @Test fun redirectNeverForwardsTokenToAnotherServer() = runBlocking {
        MockWebServer().use { server -> MockWebServer().use { other ->
            server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", other.url("/stolen")))
            val settings = HttpSyncSettings(server.url("/api/book_sync").toString(), "fixture-token")
            try { PodcastApi().access(settings); fail("redirect accepted") } catch (error: PodcastHttpException) { assertEquals(302, error.status) }
            assertEquals(0, other.requestCount)
        } }
    }

    @Test fun revokedTokenRemainsAnAuthFailure() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(401))
            try {
                PodcastApi().access(HttpSyncSettings(server.url("/api/book_sync").toString(), "revoked"))
                fail("unauthorized accepted")
            } catch (error: PodcastHttpException) { assertEquals(401, error.status) }
        }
    }

    @Test fun cancelStopsAStalledAuthRequestPromptly() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val api = PodcastApi()
            val job = launch { api.access(HttpSyncSettings(server.url("/api/book_sync").toString(), "fixture")) }
            withContext(Dispatchers.IO) { server.takeRequest() }
            withTimeout(5_000) { job.cancelAndJoin() }
            assertTrue(job.isCancelled)
        }
    }

    @Test fun publicCleartextAndMalformedEpisodeIdsAreRejected() = runBlocking {
        try {
            PodcastApi().request(HttpSyncSettings("http://example.com/api/book_sync", "secret"), "/access")
            fail("cleartext accepted")
        } catch (_: IllegalArgumentException) { }
        try {
            PodcastApi().prepare(HttpSyncSettings("https://example.com/api/book_sync", "secret"), "../../outside")
            fail("unsafe id accepted")
        } catch (_: IllegalArgumentException) { }
    }
}
