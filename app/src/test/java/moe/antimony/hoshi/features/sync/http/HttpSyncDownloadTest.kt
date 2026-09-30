package moe.antimony.hoshi.features.sync.http

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import moe.antimony.hoshi.features.sync.integration.SyncTestServerRule
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.ClassRule
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.random.Random

/** Production client, real sockets, persisted partial files and deliberately interrupted responses. */
class HttpSyncDownloadTest {
    companion object { @JvmField @ClassRule val serverRule = SyncTestServerRule() }
    @get:Rule val temp = TemporaryFolder()
    private val server get() = serverRule.server
    private val key = "books/resume/payload.zip"
    private val bytes = Random(72).nextBytes(512 * 1024)
    @Before fun reset() = server.reset()

    private suspend fun seed() = server.client().put(key, "application/zip", bytes)
    private fun downloads() = server.requests().filter { it.method == "GET" && it.path == "/v1/kv/$key" }
    private fun checkpoint(file: File) = File(file.path + ".resume")

    private suspend fun leavePartial(file: File) {
        try {
            server.client().downloadToFile(key, file) { transferred, _ ->
                if (transferred >= 64 * 1024) throw CancellationException("test interruption")
            }
            fail("Expected a canceled download")
        } catch (_: CancellationException) {
            assertTrue(file.length() in 1 until bytes.size.toLong())
            assertTrue(checkpoint(file).isFile)
        }
    }

    @Test(timeout = 10_000) fun transientDisconnectResumesAutomaticallyWithoutDownloadingThePrefixAgain() = runBlocking {
        val meta = seed()
        server.clearRequests()
        server.downloadFault(key, disconnectAfter = 24 * 1024)
        val target = temp.newFile()

        val result = server.client().downloadToFile(key, target)

        assertEquals(meta.etag, result?.etag)
        assertArrayEquals(bytes, target.readBytes())
        val requests = downloads()
        assertEquals(listOf(200, 206), requests.map { it.status })
        assertEquals(listOf(null, "bytes=24576-"), requests.map { it.range })
        assertEquals(meta.etag, requests.last().ifRange)
        assertEquals("Every archive byte should cross HTTP only once", bytes.size, requests.sumOf { it.responseBytes })
    }

    @Test(timeout = 15_000) fun exhaustedRetriesKeepTheirBytesAndANewClientContinuesFromTheCheckpoint() = runBlocking {
        seed()
        server.clearRequests()
        server.downloadFault(key, count = 3, disconnectAfter = 24 * 1024)
        val target = temp.newFile()
        val failure = runCatching { server.client().downloadToFile(key, target) }.exceptionOrNull()
        assertTrue(failure.toString(), failure is HttpSyncException)
        assertEquals(72 * 1024L, target.length())
        assertTrue(checkpoint(target).isFile)

        assertNotNull(server.client().downloadToFile(key, target))

        assertArrayEquals(bytes, target.readBytes())
        val requests = downloads()
        assertEquals(listOf(200, 206, 206, 206), requests.map { it.status })
        assertEquals(listOf(null, "bytes=24576-", "bytes=49152-", "bytes=73728-"), requests.map { it.range })
        assertEquals(bytes.size, requests.sumOf { it.responseBytes })
    }

    @Test(timeout = 10_000) fun coroutineCancellationKeepsPartialBytesForTheNextClient() = runBlocking {
        seed()
        val target = temp.newFile()
        val download = launch(Dispatchers.IO) {
            val ownJob = coroutineContext.job
            server.client().downloadToFile(key, target) { transferred, _ ->
                if (transferred > 0) ownJob.cancel()
            }
        }
        download.join()
        assertTrue(download.isCancelled)
        val offset = target.length()
        assertTrue(offset in 1 until bytes.size.toLong())
        assertTrue(checkpoint(target).isFile)

        server.client().downloadToFile(key, target)

        assertArrayEquals(bytes, target.readBytes())
        assertTrue(downloads().any { it.status == 206 && it.range == "bytes=$offset-" })
    }

    @Test(timeout = 10_000) fun ignoredRangeAndChangedObjectsReplaceThePartialFileInsteadOfAppending() = runBlocking {
        seed()
        val target = temp.newFile()
        leavePartial(target)
        server.downloadFault(key, ignoreRange = true)
        server.client().downloadToFile(key, target)
        assertArrayEquals(bytes, target.readBytes())
        assertTrue(downloads().any { it.status == 200 && it.range != null })

        target.delete()
        checkpoint(target).delete()
        leavePartial(target)
        val replacement = Random(73).nextBytes(bytes.size)
        val meta = server.client().put(key, "application/zip", replacement)
        val fetched = server.client().downloadToFile(key, target)
        assertEquals(meta.etag, fetched?.etag)
        assertArrayEquals(replacement, target.readBytes())
        assertEquals(200, downloads().last().status)
    }

    @Test(timeout = 15_000) fun invalidPartialValidatorsAndRangesAreDiscardedBeforeAFreshRetry() = runBlocking {
        for (fault in listOf("etag", "syntax", "start", "total")) {
            server.reset()
            seed()
            val target = temp.newFile()
            leavePartial(target)
            when (fault) {
                "etag" -> server.downloadFault(key, etag = "sha256:wrong-object")
                "syntax" -> server.downloadFault(key, contentRange = "not a range")
                "start" -> server.downloadFault(key, contentRange = "bytes 0-${bytes.size - 1}/${bytes.size}")
                "total" -> server.downloadFault(key, contentRange = "bytes ${target.length()}-${bytes.size - 1}/${bytes.size + 1}")
            }
            server.client().downloadToFile(key, target)
            assertArrayEquals(fault, bytes, target.readBytes())
            val last = downloads().takeLast(2)
            assertEquals(fault, listOf(206, 200), last.map { it.status })
            assertNull("Invalid append must discard the checkpoint before retry", last.last().range)
        }
    }

    @Test(timeout = 10_000) fun changingTheRemoteKeyCannotReuseAnotherFilesCheckpointAndMissingKeysCleanIt() = runBlocking {
        seed()
        val target = temp.newFile()
        leavePartial(target)
        val otherKey = "books/other/payload.zip"
        val other = Random(74).nextBytes(bytes.size)
        server.client().put(otherKey, "application/zip", other)
        server.client().downloadToFile(otherKey, target)
        assertArrayEquals(other, target.readBytes())
        val request = server.requests().single { it.method == "GET" && it.path == "/v1/kv/$otherKey" }
        assertNull(request.range)
        server.client().delete(otherKey)
        assertNull(server.client().downloadToFile(otherKey, target))
        assertFalse(target.exists())
        assertFalse(checkpoint(target).exists())
    }
}
