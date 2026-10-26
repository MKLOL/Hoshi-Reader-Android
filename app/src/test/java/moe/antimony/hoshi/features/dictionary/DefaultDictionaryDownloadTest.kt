package moe.antimony.hoshi.features.dictionary

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import moe.antimony.hoshi.dictionary.DictionaryUpdateProgress
import moe.antimony.hoshi.dictionary.DictionaryUpdateStage
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class DefaultDictionaryDownloadTest {
    private val downloading = DictionaryUpdateProgress(DictionaryUpdateStage.Downloading, "JMdict")

    @Test
    fun onlyOneDownloadRunsAndItsProgressAndResultAreShared() = runTest {
        val gate = CompletableDeferred<Unit>()
        var installs = 0
        var recorded = 0
        var keptAlive = 0
        val download = download(
            install = { progress ->
                installs++
                progress(downloading)
                gate.await()
            },
            onInstalled = { recorded++ },
            onRunning = { keptAlive++ },
        )

        download.start()
        download.start()
        advanceUntilIdle()
        assertEquals(DefaultDictionaryDownload.Status.Running(downloading), download.status.value)
        download.start()
        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(DefaultDictionaryDownload.Status.Installed, download.status.value)
        assertEquals(1, installs)
        assertEquals(1, recorded)
        assertEquals(1, keptAlive)
    }

    @Test
    fun aFailedDownloadIsReportedAndCanBeRetried() = runTest {
        var installs = 0
        var recorded = 0
        val download = download(
            install = { if (++installs == 1) throw IOException("Offline") },
            onInstalled = { recorded++ },
        )

        download.start()
        advanceUntilIdle()
        assertEquals(DefaultDictionaryDownload.Status.Failed, download.status.value)
        assertEquals(0, recorded)

        download.start()
        advanceUntilIdle()
        assertEquals(DefaultDictionaryDownload.Status.Installed, download.status.value)
        assertEquals(2, installs)
        assertEquals(1, recorded)
    }

    @Test
    fun aResultThatCannotBeRecordedIsAFailureUntilARetryRecordsIt() = runTest {
        var records = 0
        val download = download(
            install = {},
            onInstalled = { if (++records == 1) throw IOException("Disk full") },
        )

        download.start()
        advanceUntilIdle()
        assertEquals(DefaultDictionaryDownload.Status.Failed, download.status.value)

        download.start()
        advanceUntilIdle()
        assertEquals(DefaultDictionaryDownload.Status.Installed, download.status.value)
        assertEquals(2, records)
    }

    private fun TestScope.download(
        install: suspend ((DictionaryUpdateProgress) -> Unit) -> Unit,
        onInstalled: suspend () -> Unit = {},
        onRunning: () -> Unit = {},
    ) = DefaultDictionaryDownload(
        // Every download in these tests ends, so the test's own scope stands in for the app's.
        scope = this,
        install = install,
        onInstalled = onInstalled,
        onRunning = onRunning,
        ioDispatcher = StandardTestDispatcher(testScheduler),
    )
}
