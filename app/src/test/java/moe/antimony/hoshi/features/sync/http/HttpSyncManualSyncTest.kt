package moe.antimony.hoshi.features.sync.http

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HttpSyncManualSyncTest {
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
}
