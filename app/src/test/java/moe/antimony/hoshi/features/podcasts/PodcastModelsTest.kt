package moe.antimony.hoshi.features.podcasts

import kotlinx.serialization.json.Json
import moe.antimony.hoshi.features.bookshelf.MainTab
import moe.antimony.hoshi.features.bookshelf.visibleMainTabs
import moe.antimony.hoshi.features.sync.http.HttpSyncSettings
import org.junit.Assert.*
import org.junit.Test

class PodcastModelsTest {
    @Test fun durationFiltersUseOriginalAndHaveNoGapsOrOverlap() {
        for (seconds in 1..3600) {
            val episode = PodcastEpisode("a".repeat(64), "news", "today", seconds, "ready", 9999.0)
            assertTrue(PodcastLength.All.includes(episode))
            assertEquals(1, PodcastLength.entries.drop(1).count { it.includes(episode) })
        }
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
