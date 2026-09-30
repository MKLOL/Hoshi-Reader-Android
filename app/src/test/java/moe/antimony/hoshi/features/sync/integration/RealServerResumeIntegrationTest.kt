package moe.antimony.hoshi.features.sync.integration

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import moe.antimony.hoshi.epub.BookRepository
import moe.antimony.hoshi.features.sync.http.HttpSyncContentType
import moe.antimony.hoshi.features.sync.http.HttpSyncException
import moe.antimony.hoshi.features.sync.http.HttpSyncPayloadCodec
import moe.antimony.hoshi.features.sync.http.payloadZipKey
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.ClassRule
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.RandomAccessFile

/** The persistent archive cache must survive discarded import staging, but never bypass integrity checks. */
class RealServerResumeIntegrationTest {
    companion object { @JvmField @ClassRule val serverRule = SyncTestServerRule() }
    @get:Rule val temp = TemporaryFolder()
    private val server get() = serverRule.server
    private val syncId = "resume-book"
    private val zipKey get() = payloadZipKey(syncId)
    @Before fun reset() = server.reset()

    private suspend fun publish(): File {
        val repo = BookRepository(temp.newFolder())
        val root = SyncCorpus.importManga(repo, "Resume book", pageCount = 1, extraBytesPerPage = 256 * 1024)
        assertTrue(HttpSyncPayloadCodec().uploadIfChanged(server.client(), syncId, root, "Resume book", HttpSyncContentType.Mokuro))
        return root
    }

    private fun requests() = server.requests().filter { it.method == "GET" && it.path == "/v1/kv/$zipKey" }
    private fun cachedArchives(parent: File) = parent.resolve(".http-sync-downloads").walkTopDown()
        .filter { it.isFile && it.extension == "zip" }.toList()

    @Test(timeout = 15_000) fun failedDownloadResumesAcrossFreshCodecClientAndImportStagingDirectory() = runBlocking {
        val origin = publish()
        val parent = temp.newFolder()
        val firstStage = parent.resolve("old-stage").apply { mkdir() }
        server.clearRequests()
        server.downloadFault(zipKey, count = 3, disconnectAfter = 16 * 1024)
        val failure = runCatching {
            HttpSyncPayloadCodec().downloadAndUnpack(server.client(), syncId, firstStage)
        }.exceptionOrNull()
        assertTrue(failure.toString(), failure is HttpSyncException)
        assertEquals(48 * 1024L, cachedArchives(parent).single().length())
        assertTrue(firstStage.listFiles().orEmpty().isEmpty())
        firstStage.deleteRecursively()
        val nextStage = parent.resolve("new-stage").apply { mkdir() }

        val manifest = HttpSyncPayloadCodec().downloadAndUnpack(server.client(), syncId, nextStage)

        assertArrayEquals(origin.resolve("images/0001.jpg").readBytes(), nextStage.resolve("images/0001.jpg").readBytes())
        assertEquals(listOf(200, 206, 206, 206), requests().map { it.status })
        assertEquals("Every archive byte is transferred only once despite three failed requests", manifest.sizeBytes,
            requests().sumOf { it.responseBytes.toLong() })
        assertEquals("bytes=49152-", requests().last().range)
        assertTrue("Completed imports remove archive/checkpoint bytes", cachedArchives(parent).isEmpty())
    }

    @Test(timeout = 15_000) fun aNewManifestDiscardsThePriorVersionsPartialBeforeDownloading() = runBlocking {
        val origin = publish()
        val parent = temp.newFolder()
        val firstStage = parent.resolve("old-stage").apply { mkdir() }
        server.downloadFault(zipKey, count = 3, disconnectAfter = 16 * 1024)
        assertTrue(runCatching {
            HttpSyncPayloadCodec().downloadAndUnpack(server.client(), syncId, firstStage)
        }.isFailure)
        val oldArchive = cachedArchives(parent).single()
        val codec = HttpSyncPayloadCodec()
        // Model a re-import: replace static content and its verified hash together, then
        // mark the replacement for upload, as BookRepository does.
        val replacement = temp.newFolder("replacement")
        origin.copyRecursively(replacement, overwrite = true)
        replacement.resolve("images/0001.jpg").appendBytes(byteArrayOf(1, 2, 3, 4, 5))
        codec.installReplacement(origin, replacement, codec.computePayloadContentSha(replacement))
        codec.markPayloadContentDirty(origin)
        assertTrue(codec.uploadIfChanged(server.client(), syncId, origin, "Resume book", HttpSyncContentType.Mokuro))
        server.clearRequests()
        val newStage = parent.resolve("new-stage").apply { mkdir() }

        codec.downloadAndUnpack(server.client(), syncId, newStage)

        assertFalse(oldArchive.exists())
        assertEquals(listOf(200), requests().map { it.status })
        assertEquals(listOf(null), requests().map { it.range })
        assertArrayEquals(origin.resolve("images/0001.jpg").readBytes(), newStage.resolve("images/0001.jpg").readBytes())
    }

    @Test(timeout = 15_000) fun completedArchivesAreVerifiedBeforeReuseAndCorruptionForcesAFreshDownload() = runBlocking {
        for (corrupt in listOf(false, true)) {
            server.reset()
            val origin = publish()
            val parent = temp.newFolder()
            val firstStage = parent.resolve("old-stage").apply { mkdir() }
            try {
                HttpSyncPayloadCodec().downloadAndUnpack(server.client(), syncId, firstStage, onByteProgress = { done, total ->
                    if (total > 0 && done == total) throw CancellationException("Stopped before unpack")
                })
                fail("Expected interruption before unpack")
            } catch (_: CancellationException) {
                assertTrue(firstStage.listFiles().orEmpty().isEmpty())
            }
            val archive = cachedArchives(parent).single()
            if (corrupt) RandomAccessFile(archive, "rw").use { it.writeByte(0) }
            firstStage.deleteRecursively()
            server.clearRequests()
            val nextStage = parent.resolve("next-stage").apply { mkdir() }

            HttpSyncPayloadCodec().downloadAndUnpack(server.client(), syncId, nextStage)

            assertEquals("Only a verified complete archive may avoid HTTP", if (corrupt) 1 else 0, requests().size)
            assertTrue(requests().all { it.status == 200 && it.range == null })
            assertArrayEquals(origin.resolve("images/0001.jpg").readBytes(), nextStage.resolve("images/0001.jpg").readBytes())
        }
    }

    @Test(timeout = 10_000) fun archiveBytesThatDisagreeWithTheManifestNeverReachTheBook() = runBlocking {
        publish()
        val client = server.client()
        val original = client.get(zipKey)!!.body
        val corrupted = original.copyOf().apply { this[0] = 0 }
        client.put(zipKey, "application/zip", corrupted)
        val parent = temp.newFolder()
        val target = parent.resolve("staging").apply { mkdir() }

        val error = runCatching { HttpSyncPayloadCodec().downloadAndUnpack(client, syncId, target) }.exceptionOrNull()

        assertTrue(error.toString(), error is HttpSyncException)
        assertTrue("No archive entry may be extracted before SHA validation", target.listFiles().orEmpty().isEmpty())
        assertTrue("A corrupt completed archive cannot survive for reuse", cachedArchives(parent).isEmpty())
        client.put(zipKey, "application/zip", original)
        HttpSyncPayloadCodec().downloadAndUnpack(server.client(), syncId, target)
        assertTrue(target.resolve("mokuro.json").isFile)
    }

    @Test(timeout = 15_000) fun aWeekOldPartialStillResumesWhenItsBookIsRetried() = runBlocking {
        val origin = publish()
        val parent = temp.newFolder()
        val firstStage = parent.resolve("old-stage").apply { mkdir() }
        server.clearRequests()
        server.downloadFault(zipKey, count = 3, disconnectAfter = 16 * 1024)
        assertTrue(runCatching {
            HttpSyncPayloadCodec().downloadAndUnpack(server.client(), syncId, firstStage)
        }.isFailure)
        val partial = cachedArchives(parent).single()
        val saved = partial.length()
        // The user comes back after more than a week.
        val weekAgo = System.currentTimeMillis() - java.util.concurrent.TimeUnit.DAYS.toMillis(8)
        parent.resolve(".http-sync-downloads").walkTopDown().forEach { it.setLastModified(weekAgo) }
        firstStage.deleteRecursively()
        val nextStage = parent.resolve("new-stage").apply { mkdir() }

        val manifest = HttpSyncPayloadCodec().downloadAndUnpack(server.client(), syncId, nextStage)

        assertArrayEquals(origin.resolve("images/0001.jpg").readBytes(), nextStage.resolve("images/0001.jpg").readBytes())
        assertEquals("bytes=$saved-", requests().last().range)
        assertEquals(manifest.sizeBytes, requests().sumOf { it.responseBytes.toLong() })
    }
}
