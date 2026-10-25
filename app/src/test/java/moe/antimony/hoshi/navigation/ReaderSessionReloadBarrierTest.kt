package moe.antimony.hoshi.navigation

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import moe.antimony.hoshi.epub.BookRepository
import moe.antimony.hoshi.epub.DeviceIdentity
import moe.antimony.hoshi.epub.ReadingStatistics
import moe.antimony.hoshi.epub.readingTotals
import moe.antimony.hoshi.features.reader.ReaderStatisticsClock
import moe.antimony.hoshi.features.reader.ReaderStatisticsTracker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.LocalDate

class ReaderSessionReloadBarrierTest {
    @get:Rule val temp = TemporaryFolder()

    @Test
    fun delayedRetiringSaveFinishesBeforeImportAndTheNewTrackerUsesImportedHistory() = runBlocking {
        val device = DeviceIdentity("phone", "Phone")
        val repository = BookRepository(temp.root, deviceIdentity = device)
        val root = repository.createBookDirectory("book")
        val date = LocalDate.of(2026, 10, 24)
        val initial = ReadingStatistics("Book", date.toString(), 100, 100.0, lastStatisticModified = 1, deviceId = device.id)
        repository.saveStatistics(root, listOf(initial))
        val clock = object : ReaderStatisticsClock {
            var millis = 100_000L
            override fun currentTimeMillis() = millis
            override fun currentDate() = date
        }
        val oldTracker = ReaderStatisticsTracker("Book", repository.loadStatistics(root), true, clock, device)
        oldTracker.start(100)
        clock.millis += 1_000
        val releaseSave = CompletableDeferred<Unit>()
        val oldSaveStarted = CompletableDeferred<Unit>()
        var retires = 0
        var finalSave: kotlinx.coroutines.Job? = null
        val barrier = ReaderSessionReloadBarrier()
        barrier.attach(
            retire = {
                retires += 1
                oldTracker.pause(100)
                finalSave = launch {
                    oldSaveStarted.complete(Unit)
                    releaseSave.await()
                    repository.saveStatistics(root, oldTracker.statisticsForPersistence())
                }
            },
            awaitWrites = { finalSave?.join() },
        )
        barrier.retire()
        barrier.retire() // HTTP and foreground updates can arrive together.
        assertEquals(1, retires)
        oldSaveStarted.await()
        var imported = false
        val reload = async {
            barrier.awaitWrites()
            repository.applyDayTotals(root, listOf(initial.copy(
                charactersRead = 200, readingTime = 200.0, lastStatisticModified = clock.millis + 1,
                deviceId = null,
            )), false)
            imported = true
            ReaderStatisticsTracker("Book", repository.loadStatistics(root), true, clock, device)
        }
        yield()
        assertFalse(imported)
        releaseSave.complete(Unit)
        val freshTracker = reload.await()
        assertTrue(imported)
        assertEquals(200, freshTracker.state.allTime.charactersRead)
        assertEquals(200.0, freshTracker.state.allTime.readingTime, 0.0)
        freshTracker.start(500) // Restoring a remote bookmark is a baseline, not reading.
        clock.millis += 10_000
        freshTracker.pause(510)
        repository.saveStatistics(root, freshTracker.statisticsForPersistence())
        assertEquals(210, repository.loadStatistics(root).readingTotals().charactersRead)
        assertEquals(210.0, repository.loadStatistics(root).readingTotals().readingTime, 0.0)
    }
}
