package moe.antimony.hoshi.epub

import java.nio.file.Files
import java.util.ArrayDeque
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import moe.antimony.hoshi.features.sasayaki.BookSasayakiPlaybackRepository
import moe.antimony.hoshi.features.sasayaki.SasayakiAudioRepository
import moe.antimony.hoshi.features.sasayaki.SasayakiPlaybackPersistenceState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class SasayakiPlaybackOrderingTest {
    @Test
    fun directRepositoryLoadWaitsForRegisteredWritesBeforeTheirIoHasStarted() = runBlocking {
        val files = Files.createTempDirectory("hoshi-playback-order").toFile()
        val dispatcher = PausedDispatcher()
        val oldScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val repository = BookRepository(files, ioDispatcher = dispatcher)
            val bookRoot = files.resolve("book")
            val initial = SasayakiPlaybackData(lastPosition = 0.0, audioFileName = "audio.m4b")
            val old = SasayakiPlaybackPersistenceState(
                BookSasayakiPlaybackRepository(bookRoot, repository),
                SasayakiAudioRepository(bookRoot), initial, oldScope,
            )
            old.savePosition(13.0)
            assertFalse(bookRoot.resolve("sasayaki_playback.json").exists())
            oldScope.cancel()
            val read = async(start = CoroutineStart.UNDISPATCHED) {
                // The reader calls BookRepository directly, without the playback wrapper.
                BookRepository(files, ioDispatcher = dispatcher).loadSasayakiPlayback(bookRoot)
            }
            assertFalse(read.isCompleted)
            withTimeout(5_000) {
                while (!read.isCompleted) {
                    dispatcher.runAll()
                    yield()
                }
            }
            assertEquals(initial.copy(lastPosition = 13.0), read.await())
        } finally {
            dispatcher.runAll()
            oldScope.cancel()
            files.deleteRecursively()
        }
    }

    @Test
    fun directSaveFromAnotherRepositoryCannotBeOverwrittenByOldReaderSave() = runBlocking {
        val files = Files.createTempDirectory("hoshi-playback-save-order").toFile()
        val dispatcher = PausedDispatcher()
        val oldScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val repository = BookRepository(files, ioDispatcher = dispatcher)
            val bookRoot = files.resolve("book")
            val initial = SasayakiPlaybackData(lastPosition = 0.0, audioFileName = "audio.m4b")
            val old = SasayakiPlaybackPersistenceState(
                BookSasayakiPlaybackRepository(bookRoot, repository),
                SasayakiAudioRepository(bookRoot), initial, oldScope,
            )
            old.savePosition(13.0)
            oldScope.cancel()
            val reopenedRepository = BookRepository(files, ioDispatcher = dispatcher)
            val cleared = SasayakiPlaybackData(lastPosition = 0.0, rate = 1.4f)
            val newSave = launch(start = CoroutineStart.UNDISPATCHED) {
                reopenedRepository.saveSasayakiPlayback(bookRoot, cleared)
            }
            val read = async(start = CoroutineStart.UNDISPATCHED) {
                reopenedRepository.loadSasayakiPlayback(bookRoot)
            }
            assertFalse(newSave.isCompleted)
            assertFalse(read.isCompleted)
            withTimeout(5_000) {
                while (!read.isCompleted || !newSave.isCompleted) {
                    dispatcher.runAll()
                    yield()
                }
            }
            assertEquals(cleared, read.await())
        } finally {
            dispatcher.runAll()
            oldScope.cancel()
            files.deleteRecursively()
        }
    }

    private class PausedDispatcher : CoroutineDispatcher() {
        private val work = ArrayDeque<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) {
            work.addLast(block)
        }
        fun runAll() {
            while (work.isNotEmpty()) work.removeFirst().run()
        }
    }
}
