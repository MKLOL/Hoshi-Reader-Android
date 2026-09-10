package moe.antimony.hoshi.features.update

import android.app.DownloadManager
import android.os.Environment
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Run on a dedicated emulator. Uses isolated update state, files, and a loopback HTTP fixture. */
@RunWith(AndroidJUnit4::class)
class UpdateDownloadManagerInstrumentedTest {
    @Test fun failedHttpDownloadCanRetryAndVerifyCompletedBytes() = runBlocking<Unit> {
        fixture().use { f ->
            val id = f.start("fail")
            f.awaitStatus(UpdateDownloadRecordStatus.Failed)
            val next = f.manager.retry(requireNotNull(f.store.load()?.toAvailableUpdate()))
            f.ids += next
            assertNotEquals(id, next)
            val completed = f.awaitStatus(UpdateDownloadRecordStatus.Downloaded)
            assertArrayEquals(f.server.bytes, f.manager.updateFile(completed.fileName).readBytes())
            assertEquals(100, (completed.bytesDownloaded * 100 / completed.totalBytes).toInt())
        }
    }

    @Test fun networkRetryPauseIsVisibleAfterRecreatingManagerAndCanBeRetried() = runBlocking<Unit> {
        fixture().use { f ->
            f.start("pause")
            val paused = f.awaitStatus(UpdateDownloadRecordStatus.Paused)
            assertEquals(UpdateDownloadPauseReason.Retry, paused.pauseReason)
            val restored = AndroidUpdateDownloadManager(f.context, f.store, f.directory).refresh()
            assertEquals(UpdateDownloadRecordStatus.Paused, restored?.status)
            val next = f.manager.retry(requireNotNull(restored?.toAvailableUpdate()))
            f.ids += next
            f.awaitStatus(UpdateDownloadRecordStatus.Downloaded)
        }
    }

    @Test fun retryDuringStreamingSurvivesOldWriterShutdownAndLateCancelPreservesVerifiedApk() = runBlocking<Unit> {
        fixture().use { f ->
            val first = f.start("slow")
            val streaming = f.awaitStatus(UpdateDownloadRecordStatus.Downloading, requireProgress = true)
            val next = f.manager.retry(requireNotNull(streaming.toAvailableUpdate()))
            f.ids += next
            val completed = f.awaitStatus(UpdateDownloadRecordStatus.Downloaded)
            assertNotEquals(streaming.fileName, completed.fileName)
            delay(1_000) // old DownloadThread shutdown must not delete the replacement APK
            assertArrayEquals(f.server.bytes, f.manager.updateFile(completed.fileName).readBytes())
            f.manager.cancel(next)
            assertEquals(UpdateDownloadRecordStatus.Downloaded, f.store.load()?.status)
            assertArrayEquals(f.server.bytes, f.manager.updateFile(completed.fileName).readBytes())
            assertFalse(f.existsInSystem(first))
            assertTrue(f.existsInSystem(next))
        }
    }

    @Test fun cancelDuringStreamingRemovesRequestAndPartialFile() = runBlocking<Unit> {
        fixture().use { f ->
            val id = f.start("slow")
            val streaming = f.awaitStatus(UpdateDownloadRecordStatus.Downloading, requireProgress = true)

            f.manager.cancel(id)

            assertEquals(UpdateDownloadRecordStatus.Available, f.store.load()?.status)
            assertFalse(f.existsInSystem(id))
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            val partialFile = f.manager.updateFile(streaming.fileName)
            while (partialFile.exists() && System.nanoTime() < deadline) delay(50)
            assertFalse(partialFile.exists())
        }
    }

    @Test fun externallyRemovedRequestDoesNotRemainDownloading() = runBlocking<Unit> {
        fixture().use { f ->
            val id = f.start("slow")
            f.awaitStatus(UpdateDownloadRecordStatus.Downloading)
            f.system.remove(id)
            assertEquals(UpdateDownloadRecordStatus.Failed, f.manager.refresh()?.status)
        }
    }

    private fun fixture() = Fixture()

    private class Fixture : AutoCloseable {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "updater-test-${UUID.randomUUID()}")
        private val scope = CoroutineScope(Dispatchers.IO + Job())
        private val stateFile = File(context.cacheDir, "updater-test-${UUID.randomUUID()}.preferences_pb")
        val store = UpdateDownloadStore(PreferenceDataStoreFactory.create(scope = scope) { stateFile })
        val manager = AndroidUpdateDownloadManager(context, store, directory)
        val system = context.getSystemService(DownloadManager::class.java)
        val server = FixtureServer()
        val ids = mutableListOf<Long>()
        suspend fun start(path: String): Long {
            val update = AvailableUpdate(
                versionName = "99.0.0", releaseUrl = UpdateConfig.LATEST_RELEASE_URL,
                assetName = "test.apk", downloadUrl = server.url(path), fallbackDownloadUrls = listOf(server.url("ok")),
                sha256 = MessageDigest.getInstance("SHA-256").digest(server.bytes).joinToString("") { "%02x".format(it) },
            )
            return manager.enqueue(update).also { ids += it }
        }
        suspend fun awaitStatus(status: UpdateDownloadRecordStatus, requireProgress: Boolean = false): UpdateDownloadRecord {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(25)
            var record: UpdateDownloadRecord? = null
            while (System.nanoTime() < deadline) {
                record = manager.refresh()
                if (record?.status == status && (!requireProgress || record.bytesDownloaded > 0)) return record
                delay(100)
            }
            error("Expected $status, last record: $record")
        }
        fun existsInSystem(id: Long): Boolean = system.query(DownloadManager.Query().setFilterById(id)).use { it.moveToFirst() }
        override fun close() {
            ids.forEach { system.remove(it) }
            server.close()
            scope.cancel()
            directory.deleteRecursively()
            stateFile.delete()
        }
    }

    private class FixtureServer : AutoCloseable {
        val bytes = ByteArray(1024 * 1024) { (it % 251).toByte() }
        private val socket = ServerSocket(0, 10, InetAddress.getByName("127.0.0.1"))
        private val clients = ConcurrentHashMap.newKeySet<Socket>()
        private val workers = Executors.newCachedThreadPool()
        init {
            workers.execute {
                while (!socket.isClosed) {
                    val client = try { socket.accept() } catch (_: Exception) { break }
                    clients += client
                    workers.execute {
                        try { serve(client) } catch (_: Exception) { /* cancellation closes the socket */ }
                        finally { clients -= client; client.close() }
                    }
                }
            }
        }
        fun url(path: String) = "http://127.0.0.1:${socket.localPort}/$path"
        private fun serve(client: Socket) {
            client.soTimeout = 5_000
            val reader = client.getInputStream().bufferedReader()
            val path = reader.readLine()?.split(' ')?.getOrNull(1) ?: return
            while (!reader.readLine().isNullOrEmpty()) { }
            val output = client.getOutputStream()
            val header = when (path) {
                "/fail" -> "HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                "/pause" -> "HTTP/1.1 503 Service Unavailable\r\nRetry-After: 60\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                else -> "HTTP/1.1 200 OK\r\nContent-Type: application/vnd.android.package-archive\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
            }
            output.write(header.toByteArray()); output.flush()
            if (path == "/fail" || path == "/pause") return
            for (offset in bytes.indices step 4096) {
                output.write(bytes, offset, minOf(4096, bytes.size - offset))
                output.flush()
                if (path == "/slow") Thread.sleep(50)
            }
        }
        override fun close() {
            socket.close()
            clients.forEach { it.close() }
            workers.shutdownNow()
            check(workers.awaitTermination(5, TimeUnit.SECONDS))
        }
    }
}
