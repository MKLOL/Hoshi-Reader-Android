package moe.antimony.hoshi.features.podcasts

import kotlinx.serialization.json.Json
import moe.antimony.hoshi.features.bookshelf.MainTab
import moe.antimony.hoshi.features.bookshelf.visibleMainTabs
import moe.antimony.hoshi.features.sync.http.HttpSyncSettings
import org.junit.Assert.*
import org.junit.Test

class PodcastModelsTest {
    @Test fun durationFiltersUseOriginalAndHaveNoGapsOrOverlapOrCeiling() {
        for (seconds in listOf(1, 599, 600, 601, 1200, 1201, 3600, 3601, 7200, Int.MAX_VALUE)) {
            val episode = PodcastEpisode("a".repeat(64), "news", "today", seconds, "ready", 9999.0)
            assertTrue("$seconds", PodcastLength.All.includes(episode))
            assertEquals("$seconds", 1, PodcastLength.entries.drop(1).count { it.includes(episode) })
        }
        assertTrue(PodcastLength.Long.includes(PodcastEpisode("a".repeat(64), "news", "today", 3601, "ready")))
        // An unknown length is still listed, but only under "All lengths".
        for (unknown in listOf(0, -5)) {
            val episode = PodcastEpisode("a".repeat(64), "news", "today", unknown, "ready")
            assertTrue(PodcastLength.All.includes(episode))
            assertEquals(0, PodcastLength.entries.drop(1).count { it.includes(episode) })
        }
        val missing = Json { ignoreUnknownKeys = true }.decodeFromString<PodcastCatalogue>("""{"episodes":[{"id":"${"b".repeat(64)}","title":"t","published_at":"p","status":"available"}]}""")
        assertEquals(0, missing.episodes.single().durationSeconds)
        assertTrue(PodcastLength.All.includes(missing.episodes.single()))
    }

    @Test fun completedLessonRestartsButPartialPositionResumes() {
        assertEquals(0L, podcastResumePosition(60_000, 60.0))
        assertEquals(0L, podcastResumePosition(59_500, 60.0))
        assertEquals(10_000L, podcastResumePosition(10_000, 60.0))
        assertEquals(0L, podcastResumePosition(-1, null))
    }

    @Test fun tabRequiresConfirmedAccess() {
        assertFalse(MainTab.Podcasts in visibleMainTabs(false))
        assertTrue(MainTab.Podcasts in visibleMainTabs(true))
    }

    @Test fun cacheIdentitySeparatesAccountsAndServers() {
        val first = HttpSyncSettings("https://one.test/api/book_sync", "one")
        assertEquals(podcastAccount(first), podcastAccount(first.copy(lastSyncedAt = "later")))
        assertNotEquals(podcastAccount(first), podcastAccount(first.copy(bearerToken = "two")))
        assertNotEquals(podcastAccount(first), podcastAccount(first.copy(baseUrl = "https://two.test/api/book_sync")))
        assertTrue(validPodcastId(podcastAccount(first)))
        assertFalse(validPodcastId("../episode"))
    }

    @Test fun failureDetailsAndWorkerHealthDecodeWithSafeDefaults() {
        val json = Json { ignoreUnknownKeys = true }
        val rich = json.decodeFromString<PodcastCatalogue>("""{
            "episodes":[{"id":"${"c".repeat(64)}","title":"t","published_at":"p","duration_seconds":60,"status":"failed",
            "error_code":"translation_provider_unreachable","error_message":"OpenAI could not be reached during translation.","failure_count":2}],
            "feed_stale":false,"worker":{"alive":false,"last_seen_seconds":720,"problems":["ffmpeg is not on PATH"]},"max_failures":3
        }""")
        val episode = rich.episodes.single()
        assertEquals("OpenAI could not be reached during translation.", episode.errorMessage)
        assertEquals(2, episode.failureCount)
        assertEquals(3, rich.maxFailures)
        assertEquals(PodcastWorkerStatus(alive = false, lastSeenSeconds = 720, problems = listOf("ffmpeg is not on PATH")), rich.worker)
        // An older server without these fields still decodes; nothing is reported as broken.
        val plain = json.decodeFromString<PodcastCatalogue>("""{"episodes":[{"id":"${"c".repeat(64)}","title":"t","published_at":"p","duration_seconds":60,"status":"available"}]}""")
        assertNull(plain.worker); assertEquals(3, plain.maxFailures)
        assertNull(plain.episodes.single().errorMessage); assertEquals(0, plain.episodes.single().failureCount)
    }

    @Test fun serverCatalogueDecodesAndFiltersReadyEpisodeByOriginalLength() {
        val decoded = Json { ignoreUnknownKeys = true }.decodeFromString<PodcastCatalogue>("""{
            "episodes":[{"id":"${"a".repeat(64)}","title":"ニュース","published_at":"2026-09-21T04:10:00+00:00",
            "duration_seconds":597,"status":"ready","lesson_duration_seconds":1800.5,"error_code":null}],"feed_stale":true
        }""")
        assertTrue(decoded.feedStale)
        assertTrue(PodcastLength.Short.includes(decoded.episodes.single()))
        assertFalse(PodcastLength.Long.includes(decoded.episodes.single()))
    }
}
