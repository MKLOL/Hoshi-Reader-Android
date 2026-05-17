package moe.antimony.hoshi.features.sync.http

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.Collections
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class HttpSyncKvClientTest {

    @get:Rule val tempFolder = TemporaryFolder()

    @Test
    fun putFileUsesSinglePutAtMultipartThreshold() = runBlocking {
        TestKvServer().use { server ->
            val client = clientFor(server, trailingSlashBaseUrl = true)
            val file = tempFolder.newFile("small-payload.zip").apply {
                writeBytes("abcd".toByteArray())
            }

            val response = client.putFile(
                key = "books/small/payload.zip",
                contentType = "application/zip",
                file = file,
            )

            assertEquals("books/small/payload.zip", response.key)
            assertEquals(4, response.size)
            assertEquals("application/zip", response.contentType)
            assertEquals("abcd", server.singlePutBodies["books/small/payload.zip"]!!.toString(Charsets.UTF_8))
            assertEquals(0, server.startRequests.size)
        }
    }

    @Test
    fun putFileSplitsLargePayloadIntoMultipartRequests() = runBlocking {
        TestKvServer().use { server ->
            val client = clientFor(server, ioDispatcher = Dispatchers.IO)
            val file = tempFolder.newFile("large-payload.zip").apply {
                writeBytes("abcdefghij".toByteArray())
            }

            val response = client.putFile(
                key = "books/big/payload.zip",
                contentType = "application/zip",
                file = file,
            )

            assertEquals("books/big/payload.zip", response.key)
            assertEquals(10, response.size)
            assertEquals("application/zip", response.contentType)
            assertEquals(1, server.startRequests.size)
            assertTrue(server.startRequests.single().body.contains(""""key":"books/big/payload.zip""""))
            assertTrue(server.startRequests.single().body.contains(""""contentType":"application/zip""""))
            assertEquals("abcd", server.partBodies[1]!!.toString(Charsets.UTF_8))
            assertEquals("efgh", server.partBodies[2]!!.toString(Charsets.UTF_8))
            assertEquals("ij", server.partBodies[3]!!.toString(Charsets.UTF_8))
            assertTrue(server.completeBody!!.contains(""""parts":[1,2,3]"""))
            assertEquals(emptyMap<String, ByteArray>(), server.singlePutBodies)
        }
    }

    @Test
    fun putFileCancelsMultipartUploadWhenAPartFails() {
        val error = TestKvServer(failingPart = 2).use { server ->
            val client = clientFor(server, ioDispatcher = Dispatchers.IO)
            val file = tempFolder.newFile("failing-payload.zip").apply {
                writeBytes("abcdefghij".toByteArray())
            }

            assertThrows(HttpSyncException::class.java) {
                runBlocking {
                    client.putFile(
                        key = "books/failing/payload.zip",
                        contentType = "application/zip",
                        file = file,
                    )
                }
            }.also {
                assertEquals(listOf("upload-1"), server.cancelledUploadIds)
                assertEquals("abcd", server.partBodies[1]!!.toString(Charsets.UTF_8))
                assertEquals("efgh", server.partBodies[2]!!.toString(Charsets.UTF_8))
                assertEquals(null, server.partBodies[3])
                assertEquals(null, server.completeBody)
            }
        }

        assertNotNull(error.message)
    }

    @Test
    fun putFileCancelsMultipartUploadWhenCompleteFails() {
        val error = TestKvServer(failComplete = true).use { server ->
            val client = clientFor(server)
            val file = tempFolder.newFile("complete-failing-payload.zip").apply {
                writeBytes("abcdefghij".toByteArray())
            }

            assertThrows(HttpSyncException::class.java) {
                runBlocking {
                    client.putFile(
                        key = "books/complete_failing/payload.zip",
                        contentType = "application/zip",
                        file = file,
                    )
                }
            }.also {
                assertEquals(listOf("upload-1"), server.cancelledUploadIds)
                assertTrue(it.message!!.contains("complete failed"))
            }
        }

        assertNotNull(error.message)
    }

    @Test
    fun putFileDoesNotCancelWhenMultipartStartFails() {
        val error = TestKvServer(failStart = true).use { server ->
            val client = clientFor(server)
            val file = tempFolder.newFile("start-failing-payload.zip").apply {
                writeBytes("abcdefghij".toByteArray())
            }

            assertThrows(HttpSyncException::class.java) {
                runBlocking {
                    client.putFile(
                        key = "books/start_failing/payload.zip",
                        contentType = "application/zip",
                        file = file,
                    )
                }
            }.also {
                assertEquals(emptyList<String>(), server.cancelledUploadIds)
                assertTrue(it.message!!.contains("start failed"))
            }
        }

        assertNotNull(error.message)
    }

    @Test
    fun putFileCancelFailureDoesNotMaskOriginalMultipartFailure() {
        val error = TestKvServer(failingPart = 2, failCancel = true).use { server ->
            val client = clientFor(server)
            val file = tempFolder.newFile("cancel-failing-payload.zip").apply {
                writeBytes("abcdefghij".toByteArray())
            }

            assertThrows(HttpSyncException::class.java) {
                runBlocking {
                    client.putFile(
                        key = "books/cancel_failing/payload.zip",
                        contentType = "application/zip",
                        file = file,
                    )
                }
            }.also {
                assertEquals(listOf("upload-1"), server.cancelledUploadIds)
                assertTrue(it.message!!.contains("part failed"))
            }
        }

        assertNotNull(error.message)
    }

    @Test
    fun putFileCancelsMultipartUploadBeforeCompleteWhenCancelledAfterLastPart() = runBlocking {
        var uploadJob: Job? = null
        TestKvServer(
            afterPartBodyRead = { partNumber ->
                if (partNumber == 3) uploadJob?.cancel()
            },
        ).use { server ->
            val client = clientFor(server)
            val file = tempFolder.newFile("cancel-after-last-part.zip").apply {
                writeBytes("abcdefghij".toByteArray())
            }
            var thrown: Throwable? = null

            uploadJob = launch(Dispatchers.Default) {
                thrown = runCatching {
                    client.putFile(
                        key = "books/cancel_after_last/payload.zip",
                        contentType = "application/zip",
                        file = file,
                    )
                }.exceptionOrNull()
            }
            uploadJob!!.join()

            assertTrue("expected CancellationException, got $thrown", thrown is CancellationException)
            assertEquals(listOf("upload-1"), server.cancelledUploadIds)
            assertNull("cancelled multipart upload must not be completed", server.completeBody)
        }
    }

    @Test
    fun putFileSingleRequestPreservesCancellation() = runBlocking {
        var uploadJob: Job? = null
        TestKvServer(afterSinglePutBodyRead = { uploadJob?.cancel() }).use { server ->
            val client = clientFor(server)
            val file = tempFolder.newFile("cancel-single.zip").apply {
                writeBytes("abcd".toByteArray())
            }
            var thrown: Throwable? = null

            uploadJob = launch {
                thrown = runCatching {
                    client.putFile(
                        key = "books/cancel_single/payload.zip",
                        contentType = "application/zip",
                        file = file,
                    )
                }.exceptionOrNull()
            }
            uploadJob!!.join()

            assertTrue("expected CancellationException, got $thrown", thrown is CancellationException)
            assertEquals("abcd", server.singlePutBodies["books/cancel_single/payload.zip"]!!.toString(Charsets.UTF_8))
        }
    }

    @Test
    fun putFileCancelsMultipartUploadWhenCancelledDuringComplete() = runBlocking {
        var uploadJob: Job? = null
        val completeEntered = CountDownLatch(1)
        TestKvServer(
            afterCompleteBodyRead = {
                completeEntered.countDown()
                uploadJob?.cancel()
                Thread.sleep(1_000)
            },
        ).use { server ->
            val client = clientFor(server, ioDispatcher = Dispatchers.IO)
            val file = tempFolder.newFile("cancel-during-complete.zip").apply {
                writeBytes("abcdefghij".toByteArray())
            }
            var thrown: Throwable? = null

            uploadJob = launch(Dispatchers.Default) {
                thrown = runCatching {
                    client.putFile(
                        key = "books/cancel_during_complete/payload.zip",
                        contentType = "application/zip",
                        file = file,
                    )
                }.exceptionOrNull()
            }
            if (!completeEntered.await(5, TimeUnit.SECONDS)) {
                uploadJob!!.cancelAndJoin()
                throw AssertionError(
                    "complete request should have started; parts=${server.partBodies.keys}; " +
                        "cancelled=${server.cancelledUploadIds}; thrown=$thrown",
                )
            }
            uploadJob!!.join()

            assertTrue("expected CancellationException, got $thrown", thrown is CancellationException)
            assertEquals(listOf("upload-1"), server.cancelledUploadIds)
            assertTrue("complete request body should have reached server", server.completeBody!!.contains(""""parts":[1,2,3]"""))
        }
    }

    private fun clientFor(
        server: TestKvServer,
        trailingSlashBaseUrl: Boolean = false,
        ioDispatcher: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.Unconfined,
    ): HttpSyncKvClient =
        HttpSyncKvClient(
            baseUrl = if (trailingSlashBaseUrl) "${server.baseUrl}/" else server.baseUrl,
            bearerToken = "test-token",
            ioDispatcher = ioDispatcher,
            multipartPartSizeBytes = 4,
            multipartThresholdBytes = 4,
        )

    private class TestKvServer(
        private val failingPart: Int? = null,
        private val failStart: Boolean = false,
        private val failComplete: Boolean = false,
        private val failCancel: Boolean = false,
        private val afterSinglePutBodyRead: (() -> Unit)? = null,
        private val afterPartBodyRead: ((Int) -> Unit)? = null,
        private val afterCompleteBodyRead: (() -> Unit)? = null,
    ) : AutoCloseable {
        private val executor: ExecutorService = Executors.newCachedThreadPool()
        private val server = HttpServer.create(
            InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0),
            0,
        )

        val baseUrl: String
        val startRequests = Collections.synchronizedList(mutableListOf<RecordedRequest>())
        val singlePutBodies = Collections.synchronizedMap(linkedMapOf<String, ByteArray>())
        val partBodies = Collections.synchronizedMap(linkedMapOf<Int, ByteArray>())
        val cancelledUploadIds = Collections.synchronizedList(mutableListOf<String>())
        var completeBody: String? = null

        init {
            server.createContext("/") { exchange ->
                val path = exchange.requestURI.path
                val method = exchange.requestMethod
                val body = exchange.requestBody.use { it.readBytes() }
                val request = RecordedRequest(
                    method = method,
                    path = path,
                    contentType = exchange.requestHeaders.getFirst("Content-Type"),
                    authorization = exchange.requestHeaders.getFirst("Authorization"),
                    body = body.toString(Charsets.UTF_8),
                )
                if (request.authorization != "Bearer test-token") {
                    exchange.respondJson(401, """{"error":"bad token"}""")
                    return@createContext
                }
                when {
                    method == "PUT" && path.startsWith("$BASE_PATH/v1/kv/") -> {
                        val key = path.removePrefix("$BASE_PATH/v1/kv/")
                        singlePutBodies[key] = body
                        afterSinglePutBodyRead?.invoke()
                        exchange.respondJson(
                            200,
                            """{"key":"$key","lastModified":"2027-01-01T00:00:00Z","etag":"sha256:single","size":${body.size},"contentType":"${request.contentType}"}""",
                        )
                    }
                    method == "POST" && path == "$BASE_PATH/v1/kv-multipart/start" -> {
                        startRequests += request
                        if (failStart) {
                            exchange.respondJson(500, """{"error":"start failed"}""")
                        } else {
                            exchange.respondJson(200, """{"uploadId":"upload-1"}""")
                        }
                    }
                    method == "PUT" && path.startsWith("$BASE_PATH/v1/kv-multipart/upload-1/") -> {
                        val partNumber = path.substringAfterLast('/').toInt()
                        partBodies[partNumber] = body
                        afterPartBodyRead?.invoke(partNumber)
                        if (partNumber == failingPart) {
                            exchange.respondJson(500, """{"error":"part failed"}""")
                        } else {
                            exchange.respondNoBody(204)
                        }
                    }
                    method == "POST" && path == "$BASE_PATH/v1/kv-multipart/upload-1/complete" -> {
                        completeBody = request.body
                        afterCompleteBodyRead?.invoke()
                        if (failComplete) {
                            exchange.respondJson(500, """{"error":"complete failed"}""")
                        } else {
                            exchange.respondJson(
                                200,
                                """{"key":"books/big/payload.zip","lastModified":"2027-01-01T00:00:03Z","etag":"sha256:complete","size":10}""",
                            )
                        }
                    }
                    method == "DELETE" && path == "$BASE_PATH/v1/kv-multipart/upload-1" -> {
                        cancelledUploadIds += "upload-1"
                        if (failCancel) {
                            exchange.respondJson(500, """{"error":"cancel failed"}""")
                        } else {
                            exchange.respondNoBody(204)
                        }
                    }
                    else -> {
                        exchange.respondJson(404, """{"error":"unexpected $method $path"}""")
                    }
                }
            }
            server.executor = executor
            server.start()
            baseUrl = "http://127.0.0.1:${server.address.port}$BASE_PATH"
        }

        override fun close() {
            server.stop(0)
            executor.shutdownNow()
        }
    }

    private data class RecordedRequest(
        val method: String,
        val path: String,
        val contentType: String?,
        val authorization: String?,
        val body: String,
    )
}

private fun HttpExchange.respondJson(code: Int, body: String) {
    val bytes = body.toByteArray()
    responseHeaders.set("Content-Type", "application/json; charset=utf-8")
    sendResponseHeaders(code, bytes.size.toLong())
    responseBody.use { it.write(bytes) }
}

private fun HttpExchange.respondNoBody(code: Int) {
    sendResponseHeaders(code, -1)
    responseBody.close()
}

private const val BASE_PATH = "/api/book_sync"
