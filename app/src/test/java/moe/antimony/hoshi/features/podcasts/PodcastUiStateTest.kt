package moe.antimony.hoshi.features.podcasts

import org.junit.Assert.*
import org.junit.Test

class PodcastUiStateTest {
    private val episode = PodcastEpisode("a".repeat(64), "Lesson", "2026-09-24", 300, "preparing")
    private val catalogue = PodcastCatalogue(
        episodes = listOf(episode),
        worker = PodcastWorkerStatus(alive = true),
        generating = PodcastGeneration(episode.id, "speech", 3, 10, 48, ageSeconds = 25),
    )

    @Test fun progressExpiresUsingBothServerAgeAndMonotonicTimeSinceReceipt() {
        val state = PodcastUiState().withCatalogue(catalogue, emptySet(), receivedAtMs = 1_000)
        assertNotNull(state.expirePodcastProgress(65_999).generating)
        assertNull(state.expirePodcastProgress(66_000).generating)
        assertEquals(state.episodes, state.expirePodcastProgress(66_000).episodes)
        assertNull(state.expirePodcastProgress(66_000).generationReceivedAtMs)
    }

    @Test fun failedPollOrBackgroundingDropsLiveStatusButKeepsDownloadedLessons() {
        val state = PodcastUiState().withCatalogue(catalogue, setOf(episode.id), receivedAtMs = 1_000)
        val offline = state.withoutLivePodcastStatus()
        assertNull(offline.generating)
        assertNull(offline.worker)
        assertNull(offline.generationReceivedAtMs)
        assertEquals(state.episodes, offline.episodes)
        assertEquals(state.downloaded, offline.downloaded)
    }

    @Test fun returningToACachedCatalogueCannotReviveOldProgressOrWorkerHealth() {
        val live = PodcastUiState().withCatalogue(catalogue, emptySet(), receivedAtMs = 1_000)
        // This is also the migration case: older app versions persisted worker and generating.
        val restored = live.withCatalogue(catalogue, emptySet())
        assertNull(restored.generating)
        assertNull(restored.worker)
        assertNull(restored.generationReceivedAtMs)
        assertEquals(listOf(episode), restored.episodes)
    }

    @Test fun alreadyStaleResponsesStayIndeterminateButFreshResponsesResumeProgress() {
        val stale = catalogue.copy(generating = catalogue.generating!!.copy(ageSeconds = 90))
        val state = PodcastUiState().withCatalogue(stale, emptySet(), receivedAtMs = 1_000)
        assertNull(state.generating)
        val fresh = state.withCatalogue(catalogue, emptySet(), receivedAtMs = 100_000)
        assertNotNull(fresh.generating)
        assertEquals(100_000L, fresh.generationReceivedAtMs)
    }

    @Test fun lateRefreshKeepsAnArchivedDownloadCompletedAfterItsFileScan() {
        val beforeScan = PodcastUiState()
        val capturedCatalogue = PodcastCatalogue(emptyList())
        val capturedDownloads = emptySet<String>()
        val completed = episode.copy(status = "ready", show = "archived")
        val show = PodcastShow("archived", "Saved show")
        val afterCompletion = beforeScan.withCatalogue(
            PodcastCatalogue(listOf(completed), listOf(show)), setOf(completed.id),
        )

        val afterRefresh = afterCompletion.withCatalogue(
            capturedCatalogue, capturedDownloads, receivedAtMs = 1_000,
            downloadedBeforeScan = beforeScan.downloaded,
        )

        assertEquals(listOf(completed), afterRefresh.episodes)
        assertEquals(listOf(show), afterRefresh.shows)
        assertEquals(setOf(completed.id), afterRefresh.downloaded)
    }

    @Test fun aLateRefreshCannotRestoreADownloadAlreadyObservedAsRemoved() {
        val downloadedEpisode = episode.copy(status = "ready")
        val beforeScan = PodcastUiState().withCatalogue(PodcastCatalogue(listOf(downloadedEpisode)), setOf(episode.id))
        val afterRemoval = beforeScan.copy(downloaded = emptySet())

        val afterRefresh = afterRemoval.withCatalogue(
            PodcastCatalogue(listOf(downloadedEpisode)), setOf(episode.id), receivedAtMs = 1_000,
            downloadedBeforeScan = beforeScan.downloaded,
        )

        assertTrue(afterRefresh.downloaded.isEmpty())
        assertEquals(listOf(downloadedEpisode), afterRefresh.episodes)
    }

    @Test fun aLateDownloadObservationKeepsADownloadDiscoveredByANewerRefresh() {
        val beforeScan = PodcastUiState()
        val capturedLocal = PodcastCatalogue(emptyList())
        val capturedDownloads = emptySet<String>()
        val completed = episode.copy(status = "ready")
        val afterRefresh = beforeScan.withCatalogue(PodcastCatalogue(listOf(completed)), setOf(episode.id), receivedAtMs = 1_000)

        val afterObservation = afterRefresh.withDownloadObservation(capturedLocal, capturedDownloads, beforeScan.downloaded)

        assertEquals(listOf(completed), afterObservation.episodes)
        assertEquals(setOf(completed.id), afterObservation.downloaded)
    }

    @Test fun downloadObservationStillClearsAMissingFileWhenNoNewerCompletionWasPublished() {
        val completed = episode.copy(status = "ready")
        val beforeScan = PodcastUiState().withCatalogue(PodcastCatalogue(listOf(completed)), setOf(episode.id))

        val afterObservation = beforeScan.withDownloadObservation(PodcastCatalogue(listOf(completed)), emptySet(), beforeScan.downloaded)

        assertTrue(afterObservation.downloaded.isEmpty())
    }
}
