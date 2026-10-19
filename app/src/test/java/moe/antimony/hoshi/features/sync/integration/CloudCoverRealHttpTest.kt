package moe.antimony.hoshi.features.sync.integration

import kotlinx.coroutines.runBlocking
import moe.antimony.hoshi.features.sync.http.HttpSyncException
import moe.antimony.hoshi.features.sync.http.RawZip
import moe.antimony.hoshi.features.sync.http.fetchRemoteMangaCover
import moe.antimony.hoshi.features.sync.http.firstMokuroPagePath
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.ClassRule
import org.junit.Test
import java.io.File
import java.util.zip.ZipFile
import kotlin.random.Random

/** The remote cover reader with the production client against the Python sync test server. */
class CloudCoverRealHttpTest {
    companion object {
        @JvmField @ClassRule val serverRule = SyncTestServerRule()
        private const val KEY = "books/m/payload.zip"
    }

    private val server get() = serverRule.server
    private val page = Random(5).nextBytes(20_000)

    @Before
    fun reset() = server.reset()

    private suspend fun publish(archive: ByteArray) {
        server.client().put(KEY, "application/zip", archive)
    }

    /**
     * Bytes allocated to read one large deflated cover (Android uploads deflate every image). The
     * client grows a ByteArrayOutputStream to the body size and copies it, and the reader copies
     * the compressed bytes again (`compressed + 0`) before inflating into a third buffer.
     */
    @Test
    fun memoryNeededToReadALargeDeflatedCover() = runBlocking {
        val image = Random(9).nextBytes(10 * 1024 * 1024) // incompressible, like a big PNG or JPEG
        val archive = RawZip().apply { add("images/0001.jpg", image, deflate = true) }.finish()
        publish(archive)
        val client = moe.antimony.hoshi.features.sync.http.HttpSyncKvClient(server.baseUrl, server.token, kotlinx.coroutines.Dispatchers.Unconfined)
        val threads = java.lang.management.ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        val before = threads.getThreadAllocatedBytes(Thread.currentThread().id)

        val cover = fetchRemoteMangaCover(client, KEY, archive.size.toLong())

        val allocated = threads.getThreadAllocatedBytes(Thread.currentThread().id) - before
        assertArrayEquals(image, cover)
        println("allocated ${allocated / (1024 * 1024)} MiB to read a ${image.size / (1024 * 1024)} MiB cover")
        assertTrue("allocated ${allocated / (1024 * 1024)} MiB", allocated < 3L * image.size)
    }

    /** A dropped connection mid-range is a network error (retried), not "no cover". */
    @Test
    fun aDisconnectMidRangeIsANetworkError() = runBlocking {
        val archive = RawZip().apply { add("images/0001.jpg", page) }.finish()
        publish(archive)
        server.downloadFault(KEY, count = 3, disconnectAfter = 10)

        val thrown = runCatching { fetchRemoteMangaCover(server.client(), KEY, archive.size.toLong()) }.exceptionOrNull()

        assertTrue("got $thrown", thrown is HttpSyncException)
    }

    /**
     * The production client refuses a negative range start with IllegalArgumentException, and the
     * reader passes it a negative ZIP64 offset taken straight from the archive.
     */
    @Test
    fun aNegativeZip64OffsetIsNoCoverWithTheRealClient() = runBlocking {
        val archive = RawZip().apply {
            add("images/0001.jpg", page).apply { extra = RawZip.zip64Extra(Long.MIN_VALUE); localOffset = 0xFFFFFFFFL }
        }.finish()
        publish(archive)

        val thrown = runCatching { fetchRemoteMangaCover(server.client(), KEY, archive.size.toLong()) }.exceptionOrNull()

        assertEquals("expected null (no cover), got $thrown", null, thrown)
    }
}
