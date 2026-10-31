package moe.antimony.hoshi.features.reader

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import moe.antimony.hoshi.features.sasayaki.SasayakiSettingsRepository
import moe.antimony.hoshi.features.sync.http.HttpSyncKvFetched
import moe.antimony.hoshi.features.sync.http.HttpSyncKvKeyMeta
import moe.antimony.hoshi.features.sync.http.HttpSyncKvList
import moe.antimony.hoshi.features.sync.http.HttpSyncKvTransport
import moe.antimony.hoshi.features.sync.http.HttpSyncKvWriteResponse
import moe.antimony.hoshi.features.sync.http.HttpSyncReaderSettings
import moe.antimony.hoshi.features.sync.http.HttpSyncReaderSettingsBlob
import moe.antimony.hoshi.features.sync.http.READER_SETTINGS_PREFIX
import moe.antimony.hoshi.features.sync.http.ReaderSettingsSyncResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

class ReaderSettingsSyncAccountGuardTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test(timeout = 10_000)
    fun queuedReaderAndAudioTransactionsDiscardAnAccountChangedWhileTheyWaited() = runBlocking {
        withTimeout(5_000) {
            for (audio in listOf(false, true)) {
                fixture().use { local ->
                    val repository: ReaderSettingsSyncStore = if (audio) local.audio else local.reader
                    val dataStore = if (audio) local.audioDataStore else local.readerDataStore
                    repository.snapshot()
                    val current = AtomicBoolean(true)
                    val occupied = CompletableDeferred<Unit>()
                    val release = CompletableDeferred<Unit>()
                    val busy = async {
                        dataStore.edit { occupied.complete(Unit); release.await() }
                    }
                    occupied.await()
                    try {
                        // UNDISPATCHED reaches the queued real DataStore edit before returning.
                        val applying = async(start = CoroutineStart.UNDISPATCHED) {
                            repository.merge(mapOf(field(audio) to record(false))) { current.get() }
                        }
                        current.set(false)
                        release.complete(Unit)
                        assertFalse(applying.await())
                        busy.await()
                        assertTrue(repository.snapshot().records.isEmpty())
                        assertTrue(local.reader.settings.first().mangaShowFurigana)
                        assertTrue(local.audio.settings.first().autoPause)
                    } finally {
                        release.complete(Unit)
                        busy.cancelAndJoin()
                    }
                }
            }
        }
    }

    @Test(timeout = 10_000)
    fun httpExchangeRechecksAccountInsideAQueuedCompositeDataStoreMerge() = runBlocking {
        withTimeout(5_000) {
            fixture().use { local ->
                val current = AtomicBoolean(true)
                val occupied = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()
                val merging = CompletableDeferred<Unit>()
                val busy = async(start = CoroutineStart.LAZY) {
                    local.readerDataStore.edit { occupied.complete(Unit); release.await() }
                }
                val remote = mapOf(field(false) to record(false), field(true) to record(false))
                val body = Json.encodeToString(
                    HttpSyncReaderSettingsBlob.serializer(),
                    HttpSyncReaderSettingsBlob(deviceId = "peer", records = remote),
                ).toByteArray()
                var uploads = 0
                val server = object : HttpSyncKvTransport {
                    override suspend fun get(key: String): HttpSyncKvFetched? {
                        if (key != READER_SETTINGS_PREFIX + "peer") return null
                        busy.start()
                        occupied.await()
                        return HttpSyncKvFetched(body, "application/json", "stamp", "etag")
                    }

                    override suspend fun put(key: String, contentType: String, body: ByteArray): HttpSyncKvWriteResponse {
                        uploads++
                        error("A stale account must never be published")
                    }

                    override suspend fun list(prefix: String?, since: String?, cursor: String?, limit: Int?): HttpSyncKvList =
                        error("A complete listing was supplied")

                    override suspend fun delete(key: String) = error("Settings must not delete server keys")
                }
                val observed = object : ReaderSettingsSyncStore by local.composite {
                    override suspend fun merge(
                        remote: Map<String, ReaderSettingRecord>,
                        stillCurrent: suspend () -> Boolean,
                    ): Boolean {
                        merging.complete(Unit)
                        return local.composite.merge(remote, stillCurrent)
                    }
                }
                val exchange = HttpSyncReaderSettings(observed) { current.get() }
                try {
                    val running = async {
                        exchange.sync(server, listOf(HttpSyncKvKeyMeta(
                            READER_SETTINGS_PREFIX + "peer", "stamp", "etag", body.size, "application/json",
                        )))
                    }
                    merging.await()
                    current.set(false)
                    release.complete(Unit)

                    assertEquals(ReaderSettingsSyncResult(), running.await())
                    busy.await()
                    assertTrue(local.composite.snapshot().records.isEmpty())
                    assertTrue(local.reader.settings.first().mangaShowFurigana)
                    assertTrue(local.audio.settings.first().autoPause)
                    assertEquals(0, uploads)
                } finally {
                    release.complete(Unit)
                    busy.cancelAndJoin()
                }
            }
        }
    }

    @Test
    fun compositeRechecksTheAccountForTheSecondRepository() = runBlocking {
        fixture().use { local ->
            val current = AtomicBoolean(true)
            val reader = object : ReaderSettingsSyncStore by local.reader {
                override suspend fun merge(
                    remote: Map<String, ReaderSettingRecord>,
                    stillCurrent: suspend () -> Boolean,
                ): Boolean = local.reader.merge(remote, stillCurrent).also { current.set(false) }
            }
            val composite = CompositeReaderSettingsSyncStore(reader, local.audio) { 100L }

            assertTrue(composite.merge(mapOf(field(false) to record(false), field(true) to record(false))) { current.get() })

            assertFalse(local.reader.settings.first().mangaShowFurigana)
            assertTrue(local.audio.settings.first().autoPause)
            assertEquals(setOf(field(false)), composite.snapshot().records.keys)
        }
    }

    private fun field(audio: Boolean) = if (audio) "sasayaki.autoPause" else "mangaShowFurigana"
    private fun record(value: Boolean) = ReaderSettingRecord(JsonPrimitive(value), 100L, "peer")

    private fun fixture(): Fixture {
        val root = temporaryFolder.newFolder()
        val job = Job()
        val scope = CoroutineScope(Dispatchers.IO + job)
        val readerStore = PreferenceDataStoreFactory.create(scope = scope) { File(root, "reader.preferences_pb") }
        val audioStore = PreferenceDataStoreFactory.create(scope = scope) { File(root, "audio.preferences_pb") }
        val reader = ReaderSettingsRepository(readerStore, syncDeviceId = "local", nowMillis = { 100L })
        val audio = SasayakiSettingsRepository(audioStore, syncDeviceId = "local", nowMillis = { 100L })
        return Fixture(reader, audio, readerStore, audioStore, job)
    }

    private class Fixture(
        val reader: ReaderSettingsRepository,
        val audio: SasayakiSettingsRepository,
        val readerDataStore: DataStore<Preferences>,
        val audioDataStore: DataStore<Preferences>,
        private val job: Job,
    ) : AutoCloseable {
        val composite = CompositeReaderSettingsSyncStore(reader, audio) { 100L }
        override fun close() = runBlocking { job.cancelAndJoin() }
    }
}
