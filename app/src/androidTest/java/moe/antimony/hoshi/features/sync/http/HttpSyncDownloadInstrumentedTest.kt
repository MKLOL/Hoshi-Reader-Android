package moe.antimony.hoshi.features.sync.http

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import moe.antimony.hoshi.features.sync.v3.BehaviorAction
import moe.antimony.hoshi.features.sync.v3.StubKvBehavior
import moe.antimony.hoshi.features.sync.v3.StubKvServer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random

/** Android's HttpURLConnection must preserve fixed-length partial responses just like the JVM client. */
@RunWith(AndroidJUnit4::class)
class HttpSyncDownloadInstrumentedTest {
    @get:Rule val temp = TemporaryFolder()

    @Test(timeout = 20_000)
    fun truncatedHttpResponseResumesFromItsPersistedOffsetWithoutRepeatingBytes() = runBlocking {
        StubKvServer().use { server ->
            server.start()
            val client = HttpSyncKvClient(server.baseUrl, server.token)
            val key = "books/android-resume/payload.zip"
            val body = Random(891).nextBytes(512 * 1024)
            val meta = client.put(key, "application/zip", body)
            val target = temp.newFile("archive.zip")
            val first = AtomicBoolean(true)
            val delivered = AtomicLong()
            val savedOffset = AtomicLong(-1)
            server.setBehavior(StubKvBehavior { method, path ->
                if (method != "GET" || path != "/v1/kv/$key") BehaviorAction.Passthrough
                else if (first.compareAndSet(true, false)) {
                    BehaviorAction.TruncateAndClose(24 * 1024) { delivered.addAndGet(it.toLong()) }
                } else {
                    savedOffset.set(target.length())
                    BehaviorAction.Throttle(64 * 1024, 0) { delivered.addAndGet(it.toLong()) }
                }
            })

            val downloaded = client.downloadToFile(key, target)

            assertEquals(meta.etag, downloaded?.etag)
            assertArrayEquals(body, target.readBytes())
            assertEquals(24 * 1024L, savedOffset.get())
            val requests = server.requests().filter { it.method == "GET" && it.path == "/v1/kv/$key" }
            assertEquals(2, requests.size)
            assertEquals(listOf(null, "bytes=24576-"), requests.map { it.range })
            assertEquals(meta.etag, requests.last().ifRange)
            assertEquals("No prefix is sent twice", body.size.toLong(), delivered.get())
            assertTrue(target.resolveSibling("archive.zip.resume").isFile)
        }
    }
}
