package moe.antimony.hoshi.features.sync.http

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.TimeUnit

class HttpSyncDownloadSpoolTest {
    @get:Rule val temporary = TemporaryFolder()

    private val codec = HttpSyncPayloadCodec(ioDispatcher = Dispatchers.Unconfined)
    private val day = TimeUnit.DAYS.toMillis(1)

    @Test
    fun aCleanSyncRemovesArchivesNobodyRetriedForAWeek() = runBlocking {
        val spool = temporary.newFolder("files")
        val now = System.currentTimeMillis()
        // A book deleted on another device, or an account replaced mid-download.
        val abandoned = partialArchive(spool, "abandoned", ageMillis = 8 * day, now = now)
        val recent = partialArchive(spool, "recent", ageMillis = 2 * day, now = now)
        val transport = FakeKvTransport()
        codec.uploadIfChanged(transport, "next", book("next-source"), "Next", HttpSyncContentType.Mokuro)

        codec.downloadAndUnpack(transport, "next", spool.resolve("${HttpSyncDownloadSpool.IMPORT_STAGING_PREFIX}next").apply { mkdirs() })
        // A download only cleans up after itself; it must not decide what other books still need.
        assertEquals(setOf("abandoned", "recent"), spool.resolve(HttpSyncDownloadSpool.DIRECTORY).list()!!.toSet())

        HttpSyncDownloadSpool.pruneAfterSync(spool.resolve("Books"))

        assertFalse(abandoned.exists())
        assertTrue("A partial archive retried recently must stay resumable", recent.exists())
    }

    @Test
    fun aFailedAttemptRestartsTheAbandonmentClock() = runBlocking {
        val spool = temporary.newFolder("files")
        val transport = FakeKvTransport()
        codec.uploadIfChanged(transport, "flaky", book("flaky-source"), "Flaky", HttpSyncContentType.Mokuro)
        transport.kv.remove(payloadZipKey("flaky"))
        val target = spool.resolve("${HttpSyncDownloadSpool.IMPORT_STAGING_PREFIX}flaky").apply { mkdirs() }

        assertThrows(HttpSyncException::class.java) {
            runBlocking { codec.downloadAndUnpack(transport, "flaky", target) }
        }
        val attempt = spool.resolve(HttpSyncDownloadSpool.DIRECTORY).listFiles()!!.single()
        age(attempt, 6 * day, System.currentTimeMillis())
        assertThrows(HttpSyncException::class.java) {
            runBlocking { codec.downloadAndUnpack(transport, "flaky", target) }
        }

        HttpSyncDownloadSpool.pruneAbandoned(spool, System.currentTimeMillis() + 2 * day)
        assertTrue("The retry a moment ago keeps the archive for another week", attempt.exists())
    }

    @Test
    fun anArchiveADownloadIsUsingIsNeverRemoved() = runBlocking {
        val spool = temporary.newFolder("files")
        val now = System.currentTimeMillis()
        val held = partialArchive(spool, "held", ageMillis = 30 * day, now = now)
        val lock = HttpSyncDownloadSpool.lockFor("held")

        lock.lock()
        try {
            HttpSyncDownloadSpool.pruneAbandoned(spool, now)
            assertTrue(held.exists())
        } finally {
            lock.unlock()
        }
        HttpSyncDownloadSpool.pruneAbandoned(spool, now)
        assertFalse(held.exists())
    }

    @Test
    fun strandedImportStagingIsRemovedAfterADayWhileFreshStagingStays() {
        val spool = temporary.newFolder("files")
        val now = System.currentTimeMillis()
        val stranded = staging(spool, "stranded", ageMillis = day + 1, now = now)
        val unpacking = staging(spool, "unpacking", ageMillis = TimeUnit.MINUTES.toMillis(5), now = now)
        val unrelated = spool.resolve(".books-restore-keep").apply { mkdirs() }
        age(unrelated, 30 * day, now)

        HttpSyncDownloadSpool.pruneAbandoned(spool, now)

        assertFalse(stranded.exists())
        assertTrue(unpacking.exists())
        assertTrue(unrelated.exists())
    }

    @Test
    fun activityCountsTheNewestFileAnywhereInsideADirectory() {
        val now = System.currentTimeMillis()
        val directory = temporary.newFolder("activity")
        val pages = directory.resolve("pages").apply { mkdirs() }
        val file = pages.resolve("p1.png").apply { writeBytes(byteArrayOf(1)) }
        file.setLastModified(now - 1_000)
        pages.setLastModified(now - 10 * day)
        directory.setLastModified(now - 10 * day)
        assertEquals(now - 1_000, directory.lastActivityMillis())
    }

    @Test
    fun stagingStillUnpackingDeepFilesIsKept() {
        val spool = temporary.newFolder("files")
        val now = System.currentTimeMillis()
        val staging = staging(spool, "deep", ageMillis = 2 * day, now = now)
        staging.resolve("pages").apply { mkdirs() }.resolve("p9.png").writeBytes(byteArrayOf(1))
        staging.resolve("pages").setLastModified(now - 2 * day)
        staging.setLastModified(now - 2 * day)

        HttpSyncDownloadSpool.pruneAbandoned(spool, now)

        assertTrue(staging.exists())
    }

    private fun partialArchive(spool: File, identity: String, ageMillis: Long, now: Long): File {
        val directory = spool.resolve(HttpSyncDownloadSpool.DIRECTORY).resolve(identity).apply { mkdirs() }
        directory.resolve("0123-42.zip").writeBytes(ByteArray(42))
        directory.resolve("0123-42.zip.resume").writeText("{}")
        return age(directory, ageMillis, now)
    }

    private fun staging(spool: File, name: String, ageMillis: Long, now: Long): File {
        val directory = spool.resolve("${HttpSyncDownloadSpool.IMPORT_STAGING_PREFIX}$name").apply { mkdirs() }
        directory.resolve("mokuro.json").writeText("{}")
        return age(directory, ageMillis, now)
    }

    private fun age(directory: File, ageMillis: Long, now: Long): File {
        directory.listFiles()?.forEach { it.setLastModified(now - ageMillis) }
        directory.setLastModified(now - ageMillis)
        return directory
    }

    private fun book(name: String): File = temporary.newFolder(name).apply {
        resolve("mokuro.json").writeText("""{"book":"$name"}""")
    }
}
