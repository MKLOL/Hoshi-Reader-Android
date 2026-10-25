package moe.antimony.hoshi.features.sasayaki

import moe.antimony.hoshi.epub.SasayakiPlaybackData

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class SasayakiPlaybackPersistenceStateTest {
    @Test
    fun delayedPositionWriteCannotRestoreClearedAudioOrOverwriteNewSettings() = runBlocking {
        val firstWrite = CompletableDeferred<Unit>()
        val releaseFirstWrite = CompletableDeferred<Unit>()
        val saved = mutableListOf<SasayakiPlaybackData>()
        val initial = SasayakiPlaybackData(lastPosition = 0.0, audioFileName = "sasayaki_audio.m4b")
        val repository = object : SasayakiPlaybackRepository {
            override suspend fun load() = initial
            override suspend fun save(playback: SasayakiPlaybackData) {
                if (!firstWrite.isCompleted) {
                    firstWrite.complete(Unit)
                    releaseFirstWrite.await()
                }
                saved += playback
            }
        }
        val state = SasayakiPlaybackPersistenceState(repository, SasayakiAudioRepository(File("book-root")), initial, this)
        state.savePosition(13.0)
        firstWrite.await()
        state.clearAudioMetadata()
        state.setRate(1.4f)
        state.setDelay(0.5)
        yield()
        assertTrue(saved.isEmpty())
        releaseFirstWrite.complete(Unit)
        withTimeout(5_000) { while (saved.size < 4) yield() }
        assertEquals(listOf(13.0, 0.0, 0.0, 0.0), saved.map { it.lastPosition })
        assertEquals(null, saved.last().audioFileName)
        assertEquals(1.4f, saved.last().rate)
        assertEquals(0.5, saved.last().delay, 0.0)
        assertEquals(state.playback, saved.last())
    }

    @Test
    fun loadsPlaybackAndSavesDelayRateAndPositionChanges() {
        val initial = SasayakiPlaybackData(
            lastPosition = 12.5,
            delay = 0.25,
            rate = 1.1f,
            audioFileName = "sasayaki_audio.m4b",
        )
        val repository = FakePlaybackRepository(initial)
        val state = SasayakiPlaybackPersistenceState(
            playbackRepository = repository,
            audioSourceRepository = SasayakiAudioRepository(File("book-root")),
            initialPlayback = initial,
            persistenceScope = CoroutineScope(Dispatchers.Unconfined),
        )

        assertEquals(initial, state.playback)
        assertEquals(0.25, state.delay, 0.0)
        assertEquals(1.1f, state.rate)

        state.setDelay(-0.15)
        assertEquals(-0.15, repository.saved.last().delay, 0.0)
        assertEquals(1.1f, repository.saved.last().rate)

        state.setRate(1.35f)
        assertEquals(-0.15, repository.saved.last().delay, 0.0)
        assertEquals(1.35f, repository.saved.last().rate)

        state.savePosition(33.0)
        assertEquals(33.0, repository.saved.last().lastPosition, 0.0)
        assertEquals(1.35f, repository.saved.last().rate)
    }

    @Test
    fun clearsAudioMetadataAndPreservesStorageSummaryBehavior() {
        val bookRoot = Files.createTempDirectory("hoshi-sasayaki-persistence").toFile()
        val copiedAudio = File(bookRoot, "Audio/sasayaki_audio.mp3").also { file ->
            file.parentFile?.mkdirs()
            file.writeText("audio")
        }
        val repository = FakePlaybackRepository(
            SasayakiPlaybackData(
                lastPosition = 42.0,
                delay = 0.1,
                rate = 0.9f,
                audioFileName = "sasayaki_audio.mp3",
            ),
        )
        val state = SasayakiPlaybackPersistenceState(
            playbackRepository = repository,
            audioSourceRepository = SasayakiAudioRepository(bookRoot),
            initialPlayback = repository.initial,
            persistenceScope = CoroutineScope(Dispatchers.Unconfined),
        )

        assertEquals("Copied to app storage. The original audiobook file can be deleted.", state.audioStorageSummary)

        state.clearAudioMetadata()

        assertEquals(0.0, state.playback.lastPosition, 0.0)
        assertEquals(0.1, state.playback.delay, 0.0)
        assertEquals(0.9f, state.playback.rate)
        assertEquals(null, state.playback.audioUri)
        assertEquals(null, state.playback.audioFileName)
        assertEquals("Select an .mp3 or .m4b audiobook", state.audioStorageSummary)
        assertEquals(state.playback, repository.saved.last())
        assertTrue(copiedAudio.exists())
    }

    private class FakePlaybackRepository(
        val initial: SasayakiPlaybackData?,
    ) : SasayakiPlaybackRepository {
        val saved = mutableListOf<SasayakiPlaybackData>()

        override suspend fun load(): SasayakiPlaybackData? = initial

        override suspend fun save(playback: SasayakiPlaybackData) {
            saved += playback
        }
    }
}
