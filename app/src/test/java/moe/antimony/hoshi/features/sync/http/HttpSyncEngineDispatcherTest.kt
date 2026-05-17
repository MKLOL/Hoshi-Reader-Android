package moe.antimony.hoshi.features.sync.http

import kotlinx.coroutines.runBlocking
import moe.antimony.hoshi.features.sync.v3.V3AppliedCounts
import moe.antimony.hoshi.features.sync.v3.V3Phase
import moe.antimony.hoshi.features.sync.v3.V3Progress
import moe.antimony.hoshi.features.sync.v3.V3PushedCounts
import moe.antimony.hoshi.features.sync.v3.V3SyncResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [HttpSyncEngineDispatcher].
 *
 * Verifies the cutover safety net:
 *  - default flag value (v2) dispatches to the v2 engine,
 *  - flag = true dispatches to the v3 engine,
 *  - the v3 result/progress are correctly adapted to the v2 shape so the existing
 *    UI keeps working unchanged.
 *
 * Uses the lambda-keyed `syncOnce` overload so the test doesn't need to subclass the
 * final [HttpSyncReconciler] / [moe.antimony.hoshi.features.sync.v3.V3SyncEngine]
 * classes.
 */
class HttpSyncEngineDispatcherTest {

    private val configured = HttpSyncSettings(
        baseUrl = "https://x",
        bearerToken = "t",
        enabled = true,
    )

    @Test
    fun defaultFlagDispatchesToV3() = runBlocking {
        var v2Called = false
        var v3Called = false
        val v3Result = V3SyncResult(
            applied = V3AppliedCounts(bookmarks = 0, chatEntries = 0, payloads = 0, aiSettings = 0),
            pushed = V3PushedCounts(
                bookmarks = 1,
                chatEntries = 2,
                metadata = 0,
                payloads = 0,
                aiSettings = 0,
            ),
            remoteOnlyBooks = 0,
            errors = emptyList(),
        )

        // useV3Sync defaults to true — same value as the DataStore-backed default
        // (verified by HttpSyncSettings()'s constructor default).
        assertTrue("default useV3Sync must be true (v3)", configured.useV3Sync)

        val result = HttpSyncEngineDispatcher.syncOnce(
            settings = configured,
            v2 = { _, _ ->
                v2Called = true
                error("v2 must not be called when useV3Sync = true")
            },
            v3 = { _, _ ->
                v3Called = true
                v3Result
            },
        )

        assertFalse("v2 engine must NOT be invoked", v2Called)
        assertTrue("v3 engine must be invoked", v3Called)
        assertEquals(1, result.uploadedBookmarks)
        assertEquals(2, result.uploadedChatEntries)
        // v3 doesn't use the v2 ?since= cursor.
        assertNull(result.newLastSyncedAt)
    }

    @Test
    fun v2FlagAsRollbackDispatchesToV2() = runBlocking {
        // Rollback path: a device that explicitly pins useV3Sync=false (via dev tools)
        // must run the legacy reconciler. This guards the rollback contract documented
        // on HttpSyncSettings.useV3Sync.
        var v2Called = false
        var v3Called = false
        val v2Result = HttpSyncResult(
            uploadedBookmarks = 1,
            uploadedChatEntries = 0,
            uploadedMetadata = 0,
            uploadedPayloads = 0,
            downloadedBookmarks = 0,
            downloadedChatEntries = 0,
            remoteOnlyBooks = 0,
            errors = emptyList(),
            newLastSyncedAt = "2025-01-01T00:00:00Z",
        )

        val result = HttpSyncEngineDispatcher.syncOnce(
            settings = configured.copy(useV3Sync = false),
            v2 = { _, _ ->
                v2Called = true
                v2Result
            },
            v3 = { _, _ ->
                v3Called = true
                error("v3 must not be called when useV3Sync = false")
            },
        )

        assertTrue("v2 engine must be invoked on rollback", v2Called)
        assertFalse("v3 engine must NOT be invoked on rollback", v3Called)
        // v2 result passes through untouched, including the cursor.
        assertEquals(v2Result, result)
    }

    @Test
    fun v3FlagDispatchesToV3AndAdaptsResult() = runBlocking {
        var v2Called = false
        var v3Called = false
        val v3Result = V3SyncResult(
            applied = V3AppliedCounts(
                bookmarks = 3,
                chatEntries = 4,
                payloads = 5,
                aiSettings = 1,
            ),
            pushed = V3PushedCounts(
                bookmarks = 6,
                chatEntries = 7,
                metadata = 8,
                payloads = 9,
                aiSettings = 1,
            ),
            remoteOnlyBooks = 2,
            errors = emptyList(),
        )

        val result = HttpSyncEngineDispatcher.syncOnce(
            settings = configured.copy(useV3Sync = true),
            v2 = { _, _ ->
                v2Called = true
                error("v2 must not be called when useV3Sync = true")
            },
            v3 = { _, _ ->
                v3Called = true
                v3Result
            },
        )

        assertFalse("v2 engine must NOT be invoked", v2Called)
        assertTrue("v3 engine must be invoked", v3Called)

        // Field-by-field check that the v3 -> v2 adapter mapped counts correctly.
        assertEquals(6, result.uploadedBookmarks)
        assertEquals(7, result.uploadedChatEntries)
        assertEquals(8, result.uploadedMetadata)
        assertEquals(9, result.uploadedPayloads)
        assertTrue(result.uploadedAppSettings)
        assertEquals(3, result.downloadedBookmarks)
        assertEquals(4, result.downloadedChatEntries)
        assertEquals(5, result.downloadedPayloads)
        assertTrue(result.downloadedAppSettings)
        assertEquals(2, result.remoteOnlyBooks)
        // v3 doesn't use the v2 ?since= cursor — adapter must drop it so the UI never
        // persists a stale v3 cursor into the v2 cursor slot.
        assertNull("v3 sync must not advance the v2 cursor", result.newLastSyncedAt)
    }

    @Test
    fun v3ProgressIsAdaptedToHttpSyncProgress() = runBlocking {
        val seen = mutableListOf<HttpSyncProgress>()
        val v3Result = V3SyncResult(
            applied = V3AppliedCounts(),
            pushed = V3PushedCounts(),
            remoteOnlyBooks = 0,
            errors = emptyList(),
        )

        HttpSyncEngineDispatcher.syncOnce(
            settings = configured.copy(useV3Sync = true),
            v2 = { _, _ -> error("v2 must not be called") },
            v3 = { _, onV3Progress ->
                onV3Progress(
                    V3Progress(
                        phase = V3Phase.Planning,
                        message = "planning",
                        detail = "computing plan",
                        completed = 1,
                        total = 4,
                    ),
                )
                v3Result
            },
            onProgress = { p -> seen += p },
        )

        assertEquals(1, seen.size)
        val p = seen.single()
        assertEquals("planning", p.message)
        assertEquals("computing plan", p.detail)
        assertEquals(1, p.completed)
        assertEquals(4, p.total)
    }
}
