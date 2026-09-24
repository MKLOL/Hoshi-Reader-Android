package moe.antimony.hoshi.features.podcasts

import androidx.core.util.AtomicFile
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PodcastFilesTest {
    @get:Rule val temp = TemporaryFolder()
    private val account = "a".repeat(64)
    private val saved = PodcastEpisode("b".repeat(64), "Saved lesson", "2026-09-24", 300, "ready", show = "archived")
    private val unsaved = saved.copy(id = "c".repeat(64), title = "Not downloaded")
    private val show = PodcastShow("archived", "Archived show")

    @Test fun omittedDownloadedEpisodesAndTheirShowSurviveRefreshAndOfflineReopen() {
        val root = temp.newFolder()
        val files = PodcastFiles(root)
        files.saveCatalogue(account, PodcastCatalogue(listOf(saved, unsaved), listOf(show)))
        files.audio(account, saved.id).writeText("downloaded MP3")

        files.saveCatalogue(account, PodcastCatalogue(emptyList()))

        val restored = PodcastFiles(root).loadCatalogue(account)!!
        assertEquals(listOf(saved), restored.episodes)
        assertEquals(listOf(show), restored.shows)
        assertTrue(files.audio(account, saved.id).isFile)
    }

    @Test fun catalogueOnDiskNeverRestoresWorkerHealthOrGenerationAsLive() {
        val files = PodcastFiles(temp.newFolder())
        files.saveCatalogue(account, PodcastCatalogue(
            episodes = listOf(saved.copy(status = "preparing")),
            worker = PodcastWorkerStatus(alive = true),
            generating = PodcastGeneration(saved.id, "speech", 1, 10, 50, 0),
        ))

        val cached = files.loadCatalogue(account)!!
        assertNull(cached.generating)
        assertNull(cached.worker)
    }

    @Test fun archivingDuringADownloadDoesNotLoseTheCompletedLessonsMetadata() {
        val root = temp.newFolder()
        val files = PodcastFiles(root)
        files.saveCatalogue(account, PodcastCatalogue(listOf(saved), listOf(show)))
        files.rememberDownload(account, saved)
        files.saveCatalogue(account, PodcastCatalogue(emptyList()))
        assertTrue(files.loadCatalogue(account)!!.episodes.isEmpty())

        // WorkManager publishes the MP3 after the archived show has disappeared from the poll.
        files.audio(account, saved.id).writeText("download completed")

        val reopened = PodcastFiles(root).loadCatalogue(account)!!
        assertEquals(listOf(saved), reopened.episodes)
        assertEquals(listOf(show), reopened.shows)
    }

    @Test fun currentServerMetadataWinsWithoutDuplicatingALocalEpisodeOrShow() {
        val files = PodcastFiles(temp.newFolder())
        files.saveCatalogue(account, PodcastCatalogue(listOf(saved), listOf(show)))
        files.audio(account, saved.id).writeText("MP3")
        val renamed = saved.copy(title = "Corrected title")
        val renamedShow = show.copy(name = "Corrected show name")
        val latest = PodcastCatalogue(listOf(renamed), listOf(renamedShow))

        assertEquals(latest, files.saveCatalogue(account, latest))
        files.saveCatalogue(account, PodcastCatalogue(emptyList()))
        assertEquals(latest, files.loadCatalogue(account))
    }

    @Test fun downloadedMetadataIsAccountScopedAndSurvivesACorruptRemoteCache() {
        val root = temp.newFolder()
        val files = PodcastFiles(root)
        files.saveCatalogue(account, PodcastCatalogue(listOf(saved), listOf(show)))
        files.rememberDownload(account, saved)
        files.audio(account, saved.id).writeText("MP3")
        File(File(root, account), "catalogue.json").writeText("corrupt old cache")

        assertEquals(listOf(saved), files.loadCatalogue(account)!!.episodes)
        assertNull(files.loadCatalogue("d".repeat(64)))
        files.saveCatalogue("d".repeat(64), PodcastCatalogue(emptyList()))
        assertTrue(files.loadCatalogue("d".repeat(64))!!.episodes.isEmpty())
        assertTrue(files.audio(account, saved.id).isFile)
    }

    @Test fun removedAudioAndUndownloadedEpisodesAreNotRetainedWhenTheServerOmitsThem() {
        val files = PodcastFiles(temp.newFolder())
        files.saveCatalogue(account, PodcastCatalogue(listOf(saved, unsaved), listOf(show)))
        files.rememberDownload(account, saved)
        files.audio(account, saved.id).writeText("MP3")
        files.saveCatalogue(account, PodcastCatalogue(listOf(saved, unsaved), listOf(show)))
        assertTrue(files.audio(account, saved.id).delete())

        assertEquals(PodcastCatalogue(emptyList()), files.saveCatalogue(account, PodcastCatalogue(emptyList())))
        assertEquals(PodcastCatalogue(emptyList()), files.loadCatalogue(account))
    }

    @Test fun refreshingAfterAnActualFileRemovalClearsTheOldDownloadAndArchivedRow() {
        val files = PodcastFiles(temp.newFolder())
        files.saveCatalogue(account, PodcastCatalogue(listOf(saved), listOf(show)))
        files.rememberDownload(account, saved)
        files.audio(account, saved.id).writeText("MP3")
        val beforeScan = PodcastUiState().withCatalogue(files.loadCatalogue(account)!!, setOf(saved.id))
        assertTrue(files.audio(account, saved.id).delete())

        val refreshed = files.saveCatalogue(account, PodcastCatalogue(emptyList()))
        val onDisk = refreshed.episodes.filter { files.audio(account, it.id).isFile }.map { it.id }.toSet()
        val afterRefresh = beforeScan.withCatalogue(refreshed, onDisk, receivedAtMs = 1_000,
            downloadedBeforeScan = beforeScan.downloaded)

        assertTrue(afterRefresh.downloaded.isEmpty())
        assertTrue(afterRefresh.episodes.isEmpty())
        assertTrue(afterRefresh.shows.isEmpty())
    }

    @Test fun lateRefreshKeepsANewlyCompletedFileWithoutRevivingAnOlderRemovedFile() {
        val files = PodcastFiles(temp.newFolder())
        val newlyCompleted = unsaved.copy(title = "Completes during refresh")
        val original = PodcastCatalogue(listOf(saved, newlyCompleted), listOf(show))
        files.saveCatalogue(account, original)
        files.rememberDownload(account, newlyCompleted)
        files.audio(account, saved.id).writeText("Old MP3")
        val beforeScan = PodcastUiState().withCatalogue(original, setOf(saved.id))
        assertTrue(files.audio(account, saved.id).delete())

        // Refresh has captured an empty archive listing, but has not published it on Main.
        val capturedCatalogue = files.saveCatalogue(account, PodcastCatalogue(emptyList()))
        val capturedDownloads = capturedCatalogue.episodes.filter { files.audio(account, it.id).isFile }.map { it.id }.toSet()
        files.audio(account, newlyCompleted.id).writeText("New MP3")
        val local = files.loadCatalogue(account)!!
        val afterCompletion = beforeScan.withDownloadObservation(local, setOf(newlyCompleted.id), beforeScan.downloaded)

        val afterRefresh = afterCompletion.withCatalogue(capturedCatalogue, capturedDownloads,
            receivedAtMs = 1_000, downloadedBeforeScan = beforeScan.downloaded)

        assertEquals(listOf(newlyCompleted), afterRefresh.episodes)
        assertEquals(setOf(newlyCompleted.id), afterRefresh.downloaded)
        assertEquals(listOf(show), afterRefresh.shows)
        assertFalse(files.audio(account, saved.id).exists())
        assertTrue(files.audio(account, newlyCompleted.id).isFile)
    }

    @Test fun overlappingWritesFromSeparateInstancesAlwaysLeaveACompleteCatalogue() {
        val root = temp.newFolder()
        val firstOpened = CountDownLatch(1)
        val secondOpened = CountDownLatch(1)
        val firstFinished = CountDownLatch(1)
        val opened = AtomicInteger()
        // Hold the first stream open while a second caller tries to save a shorter response.
        // With mutual exclusion the second cannot open until the first finishes; without it,
        // both descriptors target the same .new file and the short JSON leaves a corrupt tail.
        val factory: (File) -> AtomicFile = { path ->
            object : AtomicFile(path) {
                override fun startWrite(): FileOutputStream {
                    val stream = super.startWrite()
                    if (opened.incrementAndGet() == 1) {
                        firstOpened.countDown()
                        secondOpened.await(5, TimeUnit.SECONDS)
                    } else {
                        secondOpened.countDown()
                        check(firstFinished.await(10, TimeUnit.SECONDS))
                    }
                    return stream
                }

                override fun finishWrite(stream: FileOutputStream?) {
                    super.finishWrite(stream)
                    firstFinished.countDown()
                }
            }
        }
        val first = PodcastFiles(root, factory)
        val second = PodcastFiles(root, factory)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val longWrite = executor.submit { first.saveCatalogue(account, PodcastCatalogue(listOf(saved.copy(title = "x".repeat(1000))))) }
            assertTrue(firstOpened.await(10, TimeUnit.SECONDS))
            val shortWrite = executor.submit { second.saveCatalogue(account, PodcastCatalogue(emptyList())) }
            longWrite.get(15, TimeUnit.SECONDS)
            shortWrite.get(15, TimeUnit.SECONDS)
            assertEquals(PodcastCatalogue(emptyList()), PodcastFiles(root).loadCatalogue(account))
        } finally {
            executor.shutdownNow()
        }
    }
}
