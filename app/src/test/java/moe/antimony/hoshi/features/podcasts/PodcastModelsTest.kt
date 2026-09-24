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
        assertEquals(1, podcastAttemptsLeft(rich.maxFailures, episode.failureCount))
        assertEquals(PodcastWorkerStatus(alive = false, lastSeenSeconds = 720, problems = listOf("ffmpeg is not on PATH")), rich.worker)
        // An older server without these fields still decodes; nothing is reported as broken.
        val plain = json.decodeFromString<PodcastCatalogue>("""{"episodes":[{"id":"${"c".repeat(64)}","title":"t","published_at":"p","duration_seconds":60,"status":"available"}]}""")
        assertNull(plain.worker); assertNull(plain.maxFailures)
        assertNull(plain.episodes.single().errorMessage); assertEquals(0, plain.episodes.single().failureCount)
        assertNull(podcastAttemptsLeft(plain.maxFailures, 5))
    }

    @Test fun failureTextIsBoundedAttemptsClampAndReasonCodesMapToResources() {
        assertEquals(0, podcastAttemptsLeft(3, 7))
        assertEquals(3, podcastAttemptsLeft(3, 0))
        assertNull(podcastDisplayText(null)); assertNull(podcastDisplayText("  \n "))
        assertEquals("OpenAI could not be reached during translation.", podcastDisplayText(" OpenAI could not be\nreached during   translation. "))
        assertEquals(300, podcastDisplayText("x".repeat(1000))!!.length)
        assertEquals("a b c", podcastDisplayText("a\u3000\u00a0b\u202e\u200d\u0007c"))
        assertFalse(podcastWorkerNeedsAttention(null))
        assertFalse(podcastWorkerNeedsAttention(PodcastWorkerStatus(alive = true)))
        assertTrue(podcastWorkerNeedsAttention(PodcastWorkerStatus(alive = false)))
        assertTrue(podcastWorkerNeedsAttention(PodcastWorkerStatus(alive = true, problems = listOf("ffmpeg is not on PATH"))))
        for (code in listOf("account_changed", "content_type", "empty", "too_large", "incomplete", "save_failed")) {
            assertNotNull(code, podcastDownloadReasonRes(code))
        }
        assertNull(podcastDownloadReasonRes("http:503")); assertNull(podcastDownloadReasonRes("exception:SocketTimeoutException"))
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

    @Test fun showsDecodeAndAServerWithoutThemListsEveryEpisodeTogether() {
        val json = Json { ignoreUnknownKeys = true }
        val decoded = json.decodeFromString<PodcastCatalogue>("""{
            "episodes":[{"id":"${"a".repeat(64)}","title":"t","published_at":"p","duration_seconds":0,
            "status":"available","show":"teppei-z"}],
            "shows":[{"id":"nhk-news","name":"NHK News"},{"id":"teppei-z","name":"Nihongo con Teppei Z","feed_stale":true}]
        }""")
        assertEquals("teppei-z", decoded.episodes.single().show)
        assertEquals(listOf("nhk-news", "teppei-z"), decoded.shows.map { it.id })
        assertEquals(listOf("NHK News", "Nihongo con Teppei Z"), decoded.shows.map { it.name })
        assertTrue(decoded.shows.last().feedStale)
        assertFalse(decoded.shows.first().feedStale)
        // A server from before shows sends neither field; every episode still lists.
        val older = json.decodeFromString<PodcastCatalogue>("""{"episodes":[{"id":"${"b".repeat(64)}",
            "title":"t","published_at":"p","duration_seconds":60,"status":"available"}]}""")
        assertEquals("", older.episodes.single().show)
        assertTrue(older.shows.isEmpty())
    }

    @Test fun onlyNamedShowsAreOfferedAndTheirNamesAreBounded() {
        val shows = podcastVisibleShows(listOf(
            PodcastShow("nhk-news", "NHK News"),
            PodcastShow("", "No id"),
            PodcastShow("blank", "   "),
            PodcastShow("long", "x".repeat(400)),
            PodcastShow("spoofed", "Safe\u202Ekcatta"),
        ))
        assertEquals(listOf("nhk-news", "long", "spoofed"), shows.map { it.id })
        // A chip label, unlike an error sentence, has to stay narrow enough to leave room
        // for the other chips in the scrolling row.
        assertEquals(60, shows.first { it.id == "long" }.name.length)
        // A bidi override in a server-supplied name cannot reorder the chip's text.
        assertEquals("Safe kcatta", shows.first { it.id == "spoofed" }.name)
    }

    private fun episode(id: String, show: String, seconds: Int = 300) =
        PodcastEpisode(id.repeat(64).take(64), "t", "p", seconds, "available", show = show)

    @Test fun showFilterKeepsOnlyThatShowAndTheLengthFilterStillApplies() {
        val state = PodcastUiState(
            episodes = listOf(episode("a", "nhk-news", 300), episode("b", "teppei-z", 1500)),
            shows = listOf(PodcastShow("nhk-news", "NHK"), PodcastShow("teppei-z", "Z")),
        )
        assertEquals(2, state.filtered.size)
        assertEquals(listOf("teppei-z"), state.copy(showId = "teppei-z").filtered.map { it.show })
        assertEquals(0, state.copy(showId = "teppei-z", length = PodcastLength.Short).filtered.size)
    }

    @Test fun aShowWhoseFeedStatesNoLengthsReportsWhatTheLengthFilterHides() {
        val shows = listOf(PodcastShow("nhk-news", "NHK"), PodcastShow("teppei-beginners", "Beginners"))
        val state = PodcastUiState(
            episodes = listOf(episode("a", "nhk-news", 300), episode("b", "teppei-beginners", 0),
                episode("c", "teppei-beginners", 0)),
            shows = shows, showId = "teppei-beginners",
        )
        // Every unprepared episode of that show is unknown-length, so a length filter empties
        // the list; the screen has to be able to say so instead of looking like there is nothing.
        assertEquals(2, state.filtered.size)
        assertEquals(0, state.hiddenByLength)
        val filtered = state.copy(length = PodcastLength.Short)
        assertTrue(filtered.filtered.isEmpty())
        assertEquals(2, filtered.hiddenByLength)
    }

    @Test fun aChosenShowTheServerNoLongerOffersFallsBackToEveryShow() {
        val state = PodcastUiState(
            episodes = listOf(episode("a", "nhk-news"), episode("b", "teppei-z")),
            shows = listOf(PodcastShow("nhk-news", "NHK"), PodcastShow("teppei-z", "Z")),
            showId = "teppei-z",
        )
        // Otherwise the dangling id filters everything away while the chip that would clear
        // it is gone from the row.
        val narrowed = state.withShows(listOf(PodcastShow("nhk-news", "NHK")))
        assertNull(narrowed.showId)
        assertEquals(2, narrowed.filtered.size)
        // A server that predates shows sends none, and must not leave a filter applied either.
        val older = state.withShows(emptyList())
        assertNull(older.showId)
        assertEquals(2, older.filtered.size)
        assertEquals("teppei-z", state.withShows(state.shows).showId)
    }

    @Test fun progressIsDrawnOnlyWhenItHonestlyDescribesThisEpisode() {
        val alive = PodcastWorkerStatus(alive = true)
        val progress = PodcastGeneration(episode = "a".repeat(64), stage = "speech", done = 3, total = 10, percent = 48)
        assertEquals(progress, podcastProgressFor(progress, "a".repeat(64), alive))
        // A record for a different episode, a dead worker, or no record at all: show a plain
        // wait rather than a bar that means nothing.
        assertNull(podcastProgressFor(progress, "b".repeat(64), alive))
        assertNull(podcastProgressFor(progress, "a".repeat(64), PodcastWorkerStatus(alive = false)))
        assertNull(podcastProgressFor(null, "a".repeat(64), alive))
        assertNull(podcastProgressFor(progress.copy(total = 0), "a".repeat(64), alive))
        // An older server sends no worker block; that alone is not a reason to hide progress.
        assertEquals(progress, podcastProgressFor(progress, "a".repeat(64), null))
    }

    @Test fun nonsensicalProgressValuesAreClampedRatherThanDrawnAsIs() {
        val alive = PodcastWorkerStatus(alive = true)
        val wild = PodcastGeneration(episode = "c".repeat(64), stage = "speech", done = 99, total = 10, percent = 400)
        val shown = podcastProgressFor(wild, "c".repeat(64), alive)!!
        assertEquals(100, shown.percent)
        assertEquals(10, shown.done)
    }

    @Test fun oldProgressIsNotPresentedAsTheWorkersCurrentStep() {
        val progress = PodcastGeneration("a".repeat(64), "speech", 3, 10, 48, ageSeconds = 90)
        assertNull(podcastProgressFor(progress, progress.episode, PodcastWorkerStatus(alive = true)))
    }

    @Test fun deterministicFailuresNeedAnAdministratorEvenWhenAttemptsRemain() {
        val json = Json { ignoreUnknownKeys = true }
        val payload = """{"id":"${"e".repeat(64)}","title":"Lesson","published_at":"p","status":"failed","failure_count":1,"retryable":false}"""
        val episode = json.decodeFromString<PodcastEpisode>(payload)
        assertFalse(episode.retryable)
        assertTrue(podcastNeedsAdministrator(episode, maxFailures = 3))
        assertTrue(podcastNeedsAdministrator(episode, maxFailures = null))
        assertFalse(podcastNeedsAdministrator(episode.copy(status = "ready"), maxFailures = 3))
    }

    @Test fun olderServersAllowRetriesUntilTheirReportedAttemptLimitIsReached() {
        val episode = Json.decodeFromString<PodcastEpisode>("""{"id":"${"e".repeat(64)}","title":"Lesson","published_at":"p","status":"failed"}""")
        assertTrue(episode.retryable)
        assertFalse(podcastNeedsAdministrator(episode, maxFailures = null))
        assertFalse(podcastNeedsAdministrator(episode.copy(failureCount = 2), maxFailures = 3))
        assertTrue(podcastNeedsAdministrator(episode.copy(failureCount = 3), maxFailures = 3))
    }

    @Test fun aServerWithoutProgressReportingDecodesToNoProgress() {
        val json = Json { ignoreUnknownKeys = true }
        val older = json.decodeFromString<PodcastCatalogue>("""{"episodes":[]}""")
        assertNull(older.generating)
        val newer = json.decodeFromString<PodcastCatalogue>("""{"episodes":[],
            "generating":{"episode":"${"d".repeat(64)}","stage":"render","done":0,"total":1,
            "percent":90,"age_seconds":4}}""")
        assertEquals("render", newer.generating?.stage)
        assertEquals(4, newer.generating?.ageSeconds)
    }
}
