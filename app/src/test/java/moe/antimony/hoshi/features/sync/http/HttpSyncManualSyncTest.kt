package moe.antimony.hoshi.features.sync.http

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.awaitCancellation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class HttpSyncManualSyncTest {
    @Test
    fun stoppingBeforeForegroundPromotionAllowsAnotherTap() = runBlocking {
        var schedules = 0
        val sync = HttpSyncManualSync(this, schedule = { schedules++; HttpSyncStartRoute.AndroidTransfer }) { emptyResult() }
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
        val sync = HttpSyncManualSync(this, schedule = { schedules++; HttpSyncStartRoute.AndroidTransfer }) {
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

    @Test
    fun anOfflineTapFailsAtOnceInsteadOfWaitingForAndroid() = runBlocking {
        var executions = 0
        var network = HttpSyncNetwork.None
        val sync = HttpSyncManualSync(this, schedule = { httpSyncStartRoute(network) }) {
            executions++
            emptyResult()
        }
        sync.start()
        yield()
        assertEquals(moe.antimony.hoshi.R.string.http_sync_no_network,
            (sync.status.value as SyncStatus.Failed).messageResource)
        assertEquals(0, executions)
        // Back online, the same button works again.
        network = HttpSyncNetwork.Unvalidated
        sync.start()
        yield()
        assertTrue(sync.status.value is SyncStatus.Done)
        assertEquals(1, executions)
    }

    @Test
    fun onlyAValidatedNetworkIsHandedToAndroidTransfers() = runBlocking {
        assertEquals(HttpSyncStartRoute.AndroidTransfer, httpSyncStartRoute(HttpSyncNetwork.Validated))
        // A LAN-only server or captive portal never satisfies Android's network constraint.
        assertEquals(HttpSyncStartRoute.InProcess, httpSyncStartRoute(HttpSyncNetwork.Unvalidated))
        var executions = 0
        val sync = HttpSyncManualSync(this, schedule = { httpSyncStartRoute(HttpSyncNetwork.Validated) }) {
            executions++
            emptyResult()
        }
        sync.start()
        yield()
        assertEquals(0, executions)
        assertEquals(SyncStatus.Running(), sync.status.value)
    }

    @Test
    fun stoppingAPassThatAndroidHasNotStartedWithdrawsItAndAllowsAnotherTap() = runBlocking {
        var schedules = 0
        var withdrawals = 0
        var cancellations = 0
        val sync = HttpSyncManualSync(
            this,
            schedule = { schedules++; HttpSyncStartRoute.AndroidTransfer },
            unschedule = { withdrawals++ },
            onCancelled = { cancellations++ },
        ) { error("Android never started the pass") }
        sync.start()
        yield()
        assertTrue(sync.status.value is SyncStatus.Running)
        sync.cancel()
        assertEquals(SyncStatus.Idle, sync.status.value)
        assertEquals(1, withdrawals)
        // Nothing was transferring, so no shared sync work is cancelled.
        assertEquals(0, cancellations)
        sync.start()
        yield()
        assertEquals(2, schedules)
    }

    @Test
    fun stoppingWhileTheSchedulerIsStillBusyCancelsTheHandOff() = runBlocking<Unit> { withTimeout(5_000) {
        val scheduling = CompletableDeferred<Unit>()
        var withdrawals = 0
        val sync = HttpSyncManualSync(
            this,
            schedule = {
                scheduling.complete(Unit)
                awaitCancellation()
            },
            unschedule = { withdrawals++ },
        ) { error("Unexpected pass") }
        sync.start()
        scheduling.await()
        sync.cancel()
        yield()
        assertEquals(SyncStatus.Idle, sync.status.value)
        assertEquals(1, withdrawals)
    } }

    @Test
    fun stoppingAnInAppPassCancelsItsTransfer() = runBlocking<Unit> { withTimeout(5_000) {
        val entered = CompletableDeferred<Unit>()
        val stopped = CompletableDeferred<Unit>()
        var cancellations = 0
        val sync = HttpSyncManualSync(
            this,
            schedule = { HttpSyncStartRoute.InProcess },
            onCancelled = { cancellations++ },
        ) {
            entered.complete(Unit)
            try {
                awaitCancellation()
            } finally {
                stopped.complete(Unit)
            }
        }
        sync.start()
        entered.await()
        sync.cancel()
        stopped.await()
        yield()
        assertEquals(SyncStatus.Idle, sync.status.value)
        assertTrue(cancellations >= 1)
    } }

    @Test
    fun stoppingAnAndroidOwnedTransferCancelsSharedWorkAndIgnoresItsLateProgress() = runBlocking {
        val entered = CompletableDeferred<suspend (HttpSyncProgress) -> Unit>()
        var withdrawals = 0
        var cancellations = 0
        val sync = HttpSyncManualSync(
            this,
            unschedule = { withdrawals++ },
            onCancelled = { cancellations++ },
        ) { report ->
            entered.complete(report)
            awaitCancellation()
        }
        // Android's job calls execute() itself, so this process holds no start job to cancel.
        val androidJob = launch { sync.execute() }
        val report = entered.await()
        sync.cancel()
        assertEquals(SyncStatus.Idle, sync.status.value)
        assertEquals(1, withdrawals)
        assertEquals(1, cancellations)
        report(HttpSyncProgress(message = "Downloading", completed = 2, total = 3))
        assertEquals(SyncStatus.Idle, sync.status.value)
        // Android then stops its job.
        androidJob.cancelAndJoin()
        assertEquals(SyncStatus.Idle, sync.status.value)
    }

    @Test
    fun theProductionSchedulerAnswersOfflineAndUnvalidatedTapsWithoutAndroid() = runBlocking {
        // Neither route may touch JobScheduler or WorkManager; an empty context proves it.
        val context = android.content.ContextWrapper(null)
        val offline = assertThrows(HttpSyncException::class.java) {
            runBlocking { HttpSyncBackgroundSync.schedule(context, network = { HttpSyncNetwork.None }) }
        }
        assertEquals(moe.antimony.hoshi.R.string.http_sync_no_network, offline.messageResource)
        assertEquals(HttpSyncStartRoute.InProcess,
            HttpSyncBackgroundSync.schedule(context, network = { HttpSyncNetwork.Unvalidated }))
    }

    @Test
    fun onlyAnInternetNetworkAndroidHasValidatedCountsAsValidated() {
        assertEquals(HttpSyncNetwork.None, httpSyncNetwork(connected = false, internet = false, validated = false))
        assertEquals(HttpSyncNetwork.Validated, httpSyncNetwork(connected = true, internet = true, validated = true))
        // Captive portal or LAN-only Wi-Fi.
        assertEquals(HttpSyncNetwork.Unvalidated, httpSyncNetwork(connected = true, internet = true, validated = false))
        assertEquals(HttpSyncNetwork.Unvalidated, httpSyncNetwork(connected = true, internet = false, validated = true))
    }

    @Test
    fun aTapAndroidCannotRunWithdrawsATransferQueuedBeforeTheProcessDied() = runBlocking {
        var network = HttpSyncNetwork.Unvalidated
        var withdrawals = 0
        var executions = 0
        val sync = HttpSyncManualSync(
            this,
            schedule = { httpSyncStartRoute(network) },
            unschedule = { withdrawals++ },
        ) {
            executions++
            emptyResult()
        }
        sync.start()
        yield()
        assertEquals(1, withdrawals)
        assertEquals(1, executions)
        network = HttpSyncNetwork.None
        sync.start()
        yield()
        assertTrue(sync.status.value is SyncStatus.Failed)
        assertEquals(2, withdrawals)
        network = HttpSyncNetwork.Validated
        sync.start()
        yield()
        // Android owns this one; nothing is withdrawn.
        assertEquals(2, withdrawals)
    }

    @Test
    fun stoppingWhenNothingRunsDoesNothing() = runBlocking {
        var withdrawals = 0
        val sync = HttpSyncManualSync(this, unschedule = { withdrawals++ }) { emptyResult() }
        sync.cancel()
        sync.start()
        yield()
        assertTrue(sync.status.value is SyncStatus.Done)
        sync.cancel()
        assertTrue(sync.status.value is SyncStatus.Done)
        assertEquals(0, withdrawals)
    }
}
