package moe.antimony.hoshi.features.sasayaki

import moe.antimony.hoshi.epub.SasayakiPlaybackData
import moe.antimony.hoshi.epub.SasayakiMatchData

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import moe.antimony.hoshi.epub.SasayakiSidecarRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Test
import java.io.File
import java.nio.file.Files

class SasayakiPlaybackRepositoryTest {
    @Test
    fun reopeningWaitsForDurableOldReaderWritesBeforeLoadingAndClearingAudio() = runBlocking {
        val root = Files.createTempDirectory("hoshi-playback-reopen").toFile()
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val initial = SasayakiPlaybackData(lastPosition = 0.0, audioFileName = "audio.m4b")
        var stored = initial
        val sidecar = object : SasayakiSidecarRepository {
            override suspend fun loadSasayakiMatch(bookRoot: File): SasayakiMatchData? = null
            override suspend fun saveSasayakiMatch(bookRoot: File, match: SasayakiMatchData) = Unit
            override suspend fun loadSasayakiPlayback(bookRoot: File) = stored
            override suspend fun saveSasayakiPlayback(bookRoot: File, playback: SasayakiPlaybackData) {
                if (!started.isCompleted) {
                    started.complete(Unit)
                    release.await()
                }
                stored = playback
            }
        }
        val oldScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val old = SasayakiPlaybackPersistenceState(
                BookSasayakiPlaybackRepository(root, sidecar), SasayakiAudioRepository(root), initial, oldScope,
            )
            old.savePosition(13.0)
            started.await()
            oldScope.cancel()
            val reopenedRepository = BookSasayakiPlaybackRepository(root, sidecar)
            val reopenedLoad = async(start = CoroutineStart.UNDISPATCHED) { reopenedRepository.load() }
            assertFalse(reopenedLoad.isCompleted)
            release.complete(Unit)
            val loaded = withTimeout(5_000) { reopenedLoad.await() }
            assertEquals(13.0, requireNotNull(loaded).lastPosition, 0.0)
            val reopened = SasayakiPlaybackPersistenceState(
                reopenedRepository, SasayakiAudioRepository(root), loaded, this,
            )
            reopened.clearAudioMetadata()
            reopened.setRate(1.4f)
            reopened.setDelay(0.5)
            assertEquals(reopened.playback, reopenedRepository.load())
            yield()
            assertEquals(null, stored.audioFileName)
            assertEquals(0.0, stored.lastPosition, 0.0)
            assertEquals(1.4f, stored.rate)
            assertEquals(0.5, stored.delay, 0.0)
        } finally {
            release.complete(Unit)
            oldScope.cancel()
            root.deleteRecursively()
        }
    }

    @Test
    fun bookPlaybackRepositoryDelegatesLoadAndSaveToBookSidecar() = runBlocking {
        val root = File("book-root")
        val saved = SasayakiPlaybackData(
            lastPosition = 24.5,
            delay = -0.1,
            rate = 1.2f,
            audioUri = "content://audio/book.m4b",
        )
        val sidecar = FakeSasayakiSidecarRepository(saved)
        val repository = BookSasayakiPlaybackRepository(root, sidecar)

        assertEquals(saved, repository.load())

        val next = saved.copy(
            lastPosition = 42.0,
            audioUri = null,
            audioFileName = "sasayaki_audio.m4b",
        )
        repository.save(next)

        assertSame(root, sidecar.loadedRoot)
        assertSame(root, sidecar.savedRoot)
        assertEquals(next, sidecar.savedPlayback)
    }

    private class FakeSasayakiSidecarRepository(
        private val playback: SasayakiPlaybackData?,
    ) : SasayakiSidecarRepository {
        var loadedRoot: File? = null
            private set
        var savedRoot: File? = null
            private set
        var savedPlayback: SasayakiPlaybackData? = null
            private set

        override suspend fun loadSasayakiMatch(bookRoot: File): SasayakiMatchData? = null

        override suspend fun saveSasayakiMatch(bookRoot: File, match: SasayakiMatchData) = Unit

        override suspend fun loadSasayakiPlayback(bookRoot: File): SasayakiPlaybackData? {
            loadedRoot = bookRoot
            return playback
        }

        override suspend fun saveSasayakiPlayback(bookRoot: File, playback: SasayakiPlaybackData) {
            savedRoot = bookRoot
            savedPlayback = playback
        }
    }
}
