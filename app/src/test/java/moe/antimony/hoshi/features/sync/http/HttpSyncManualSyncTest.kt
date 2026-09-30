package moe.antimony.hoshi.features.sync.http

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.awaitCancellation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HttpSyncManualSyncTest {
    @Test
    fun stoppingBeforeForegroundPromotionAllowsAnotherTap() = runBlocking {
        var schedules = 0
        val sync = HttpSyncManualSync(this, schedule = { schedules++ }) { emptyResult() }
        sync.start()
        yield()
        sync.cancelledBeforeExecution()
        assertEquals(SyncStatus.Idle, sync.status.value)
        sync.start()
        yield()
        assertEquals(2, schedules)
        sync.backgroundStartFailed()
        assertEquals(moe.antimony.hoshi.R.string.http_sync_background_start_failed,
            (sync.status.value as SyncStatus.Failed).messageResource)
    }

    @Test
    fun aCancelledQueuedOwnerCannotResetAnActiveTransfer() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val sync = HttpSyncManualSync(this) {
            entered.complete(Unit)
            finish.await()
            emptyResult()
        }
        val active = launch { sync.execute() }
        entered.await()
        sync.cancelledBeforeExecution()
        assertTrue(sync.status.value is SyncStatus.Running)
        finish.complete(Unit)
        active.join()
        assertTrue(sync.status.value is SyncStatus.Done)
    }

    @Test
    fun systemScheduledSyncRunsOnceAndRestartsWithoutAnActivity() = runBlocking {
        var schedules = 0
        var executions = 0
        val sync = HttpSyncManualSync(this, schedule = { schedules++ }) {
            executions++
            emptyResult()
        }
        sync.start()
        sync.start()
        yield()
        assertEquals(1, schedules)
        assertEquals(0, executions)
        sync.execute()
        assertTrue(sync.status.value is SyncStatus.Done)
        // A recreated Android job invokes execute directly, without another tap.
        val recreated = HttpSyncManualSync(this, schedule = { error("Unexpected scheduling") }) {
            executions++
            emptyResult()
        }
        recreated.execute()
        assertEquals(2, executions)
        assertTrue(recreated.status.value is SyncStatus.Done)
    }

    @Test
    fun systemStoppingTheTransferCancelsSharedWorkAndAllowsRetry() = runBlocking {
        var cancellations = 0
        var wait = true
        val entered = CompletableDeferred<Unit>()
        val sync = HttpSyncManualSync(this, onCancelled = { cancellations++ }) {
            entered.complete(Unit)
            if (wait) awaitCancellation()
            emptyResult()
        }
        val execution = launch { sync.execute() }
        entered.await()
        execution.cancelAndJoin()
        assertEquals(1, cancellations)
        assertEquals(SyncStatus.Idle, sync.status.value)
        wait = false
        sync.execute()
        assertTrue(sync.status.value is SyncStatus.Done)
    }

    @Test
    fun schedulingFailureDoesNotLeaveAnEndlessSpinner() = runBlocking {
        val resource = moe.antimony.hoshi.R.string.http_sync_background_start_failed
        val sync = HttpSyncManualSync(this, schedule = { throw HttpSyncException(resource) }) {
            error("Cannot run without platform permission")
        }
        sync.start()
        yield()
        assertEquals(resource, (sync.status.value as SyncStatus.Failed).messageResource)
    }

    private fun emptyResult() = HttpSyncResult(
        uploadedBookmarks = 0, uploadedChatEntries = 0, uploadedMetadata = 0,
        downloadedBookmarks = 0, downloadedChatEntries = 0, remoteOnlyBooks = 0, errors = emptyList(),
    )

    @Test
    fun repeatedTapsShareProgressAndOneResultThenAllowAnotherSync() = runBlocking {
        val finish = CompletableDeferred<Unit>()
        var calls = 0
        val progress = HttpSyncProgress(message = "Downloading", completed = 1, total = 3)
        val result = emptyResult()
        val sync = HttpSyncManualSync(this) { report ->
            calls++
            report(progress)
            finish.await()
            result
        }
        sync.start()
        sync.start()
        assertTrue(sync.status.value is SyncStatus.Running)
        yield()
        assertEquals(SyncStatus.Running(progress), sync.status.value)
        sync.start()
        finish.complete(Unit)
        yield()
        assertEquals(1, calls)
        assertEquals(SyncStatus.Done(result), sync.status.value)
        sync.start()
        yield()
        assertEquals(2, calls)
    }

    @Test
    fun failureIsVisibleAndCanBeRetried() = runBlocking {
        var fail = true
        val sync = HttpSyncManualSync(this) {
            if (fail) error("Offline")
            emptyResult()
        }
        sync.start()
        yield()
        assertEquals(SyncStatus.Failed("Offline"), sync.status.value)
        fail = false
        sync.start()
        yield()
        assertTrue(sync.status.value is SyncStatus.Done)
    }

    @Test
    fun protocolFailuresKeepTheLocalizedMessageForTheUi() = runBlocking {
        val resource = moe.antimony.hoshi.R.string.http_sync_invalid_pagination
        val sync = HttpSyncManualSync(this) { throw HttpSyncException(resource) }
        sync.start()
        yield()
        assertEquals(resource, (sync.status.value as SyncStatus.Failed).messageResource)
    }
}
