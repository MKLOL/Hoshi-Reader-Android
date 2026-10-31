package moe.antimony.hoshi.features.sync.http

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import moe.antimony.hoshi.epub.BookRepository
import moe.antimony.hoshi.features.reader.CompositeReaderSettingsSyncStore
import moe.antimony.hoshi.features.reader.PopupCardStyle
import moe.antimony.hoshi.features.reader.ReaderSettingRecord
import moe.antimony.hoshi.features.reader.ReaderSettingsRepository
import moe.antimony.hoshi.features.sasayaki.SasayakiSettingsRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class HttpSyncReaderSettingsIntegrationTest {
    @get:Rule val temporaryFolder = TemporaryFolder()
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
    private val settings = HttpSyncSettings(baseUrl = "https://example.invalid", bearerToken = "test")

    @Test
    fun manualFastPathMergesRealMangaEpubAndAudioPreferencesWithEitherEngineSelection() = runBlocking {
        for (useV3 in listOf(false, true)) {
            val server = ReaderSettingsTransport()
            device().use { phone -> device().use { tablet ->
                phone.state.publishMaps(server)
                tablet.state.publishMaps(server)
                phone.reader.update { it.copy(mangaShowFurigana = false, dictionaryCard = PopupCardStyle(width = 550.0)) }
                tablet.reader.update { it.copy(fontSize = 30) }
                tablet.audio.update { it.copy(showReaderToggle = false) }
                val phoneSync = HttpSyncFastSync(phone.state, HttpSyncFullCycleRunner(this)) { server }
                val tabletSync = HttpSyncFastSync(tablet.state, HttpSyncFullCycleRunner(this)) { server }

                val upload = phoneSync.syncNow(settings.copy(useV3Sync = useV3)) { _, _, _ ->
                    error("Reader settings alone must not start a full book reconciliation")
                }
                val exchange = tabletSync.syncNow(settings.copy(useV3Sync = useV3)) { _, _, _ ->
                    error("Reader settings alone must not start a full book reconciliation")
                }
                val pull = phoneSync.syncNow(settings.copy(useV3Sync = useV3)) { _, _, _ ->
                    error("Reader settings alone must not start a full book reconciliation")
                }

                assertTrue(upload.uploadedReaderSettings)
                assertTrue(exchange.uploadedReaderSettings)
                assertTrue(exchange.downloadedReaderSettings)
                assertTrue(pull.downloadedReaderSettings)
                assertFalse(upload.hasErrors || exchange.hasErrors || pull.hasErrors)
                assertFalse(phone.reader.settings.first().mangaShowFurigana)
                assertFalse(tablet.reader.settings.first().mangaShowFurigana)
                assertEquals(30, phone.reader.settings.first().fontSize)
                assertFalse(phone.audio.settings.first().showReaderToggle)
                assertEquals(phone.composite.snapshot().records, tablet.composite.snapshot().records)

                // A real reset has a revision, so it reaches the other device as well.
                phone.reader.update { it.copy(mangaShowFurigana = true, dictionaryCard = PopupCardStyle()) }
                phoneSync.syncNow(settings.copy(useV3Sync = useV3)) { _, _, _ -> error("No book changes") }
                tabletSync.syncNow(settings.copy(useV3Sync = useV3)) { _, _, _ -> error("No book changes") }
                assertTrue(tablet.reader.settings.first().mangaShowFurigana)
                assertEquals(null, tablet.reader.settings.first().dictionaryCard.width)
                assertEquals(JsonNull, tablet.composite.snapshot().records.getValue("dictionaryCard.width").value)
            } }
        }
    }

    @Test
    fun settingsAreExchangedBeforeAForcedFullSyncWithEitherEngineSelection() = runBlocking {
        for (useV3 in listOf(false, true)) {
            val server = ReaderSettingsTransport()
            device().use { phone ->
                phone.state.publishMaps(server)
                server.stage("other", mapOf("mangaShowFurigana" to record(false)))
                phone.audio.update { it.copy(autoPause = false) }
                var fullCalls = 0

                val result = HttpSyncFastSync(phone.state, HttpSyncFullCycleRunner(this)) { server }.syncNow(
                    settings.copy(useV3Sync = useV3), forceFullSync = true,
                ) { selected, _, _ ->
                    fullCalls++
                    assertEquals(useV3, selected.useV3Sync)
                    assertFalse(phone.reader.settings.first().mangaShowFurigana)
                    assertTrue(server.blob(phone.id).records.containsKey("sasayaki.autoPause"))
                    emptyResult()
                }

                assertEquals(1, fullCalls)
                assertTrue(result.uploadedReaderSettings)
                assertTrue(result.downloadedReaderSettings)
                assertFalse(result.hasErrors)
            }
        }
    }

    @Test
    fun readerShardChangesStayOutsideTheFullBookReconcileFingerprint() = runBlocking {
        val server = ReaderSettingsTransport()
        device().use { phone ->
            phone.state.publishMaps(server)
            assertFalse(phone.state.syncMaps(server).otherChanged)
            server.stage("other", mapOf("mangaShowFurigana" to record(false)))

            val changed = phone.state.syncMaps(server)

            assertFalse(changed.needsBootstrap)
            assertFalse(changed.booksChanged)
            assertFalse(changed.otherChanged)
            assertTrue(changed.listing.any { it.key == READER_SETTINGS_PREFIX + "other" })
            assertFalse(isReconcileKey(READER_SETTINGS_PREFIX + "other"))
            assertTrue(isReconcileKey("app/ai_chat_settings"))
            assertTrue(isReconcileKey("books/example/metadata"))
            phone.state.syncReaderSettings(server, changed.listing)
            assertFalse(phone.reader.settings.first().mangaShowFurigana)
            assertFalse(phone.state.syncMaps(server).otherChanged)
        }
    }

    @Test
    fun invalidAudioPreferenceIsReportedWithoutPartialReaderApplyOrOwnPublication() = runBlocking {
        val server = ReaderSettingsTransport()
        device().use { phone ->
            phone.state.publishMaps(server)
            server.stage("other", mapOf(
                "mangaShowFurigana" to record(false),
                "sasayaki.showReaderToggle" to ReaderSettingRecord(JsonPrimitive("false"), 100, "other"),
            ))

            val result = HttpSyncFastSync(phone.state, HttpSyncFullCycleRunner(this)) { server }.syncNow(settings) { _, _, _ ->
                error("Invalid reader preferences must not start a book scan")
            }

            assertTrue(result.hasErrors)
            assertFalse(result.uploadedReaderSettings)
            assertFalse(result.downloadedReaderSettings)
            assertTrue(phone.reader.settings.first().mangaShowFurigana)
            assertTrue(phone.audio.settings.first().showReaderToggle)
            assertFalse(server.contains(READER_SETTINGS_PREFIX + phone.id))
        }
    }

    @Test(timeout = 10_000)
    fun automaticEditHookCoalescesBothRepositoriesAndRetriesAnOfflinePublication() = runBlocking {
        val server = ReaderSettingsTransport()
        val schedulerJob = Job()
        val schedulerScope = CoroutineScope(Dispatchers.Default + schedulerJob)
        try {
            device().use { phone ->
                phone.state.publishMaps(server)
                val fullCalls = AtomicInteger()
                val scheduler = HttpSyncBookmarkScheduler(
                    phone.state, { settings },
                    { _, _, _ -> fullCalls.incrementAndGet(); emptyResult() },
                    HttpSyncFullCycleRunner(schedulerScope), schedulerScope, transportFactory = { server },
                )
                phone.reader.onSyncRelevantEdit = scheduler::onReaderSettingsChanged
                phone.audio.onSyncRelevantEdit = scheduler::onReaderSettingsChanged
                server.failedReaderPuts.set(1)

                phone.reader.update { it.copy(mangaShowFurigana = false) }
                phone.audio.update { it.copy(autoScroll = false) }
                await { server.readerPutAttempts.get() == 1 }
                assertFalse(server.contains(READER_SETTINGS_PREFIX + phone.id))
                assertEquals(2, phone.composite.snapshot().records.size)

                // A later normal poll retries the durable journals after the failed request.
                scheduler.refreshNow()
                await { server.contains(READER_SETTINGS_PREFIX + phone.id) }

                assertEquals(2, server.readerPutAttempts.get())
                assertEquals(phone.composite.snapshot().records, server.blob(phone.id).records)
                assertEquals(0, fullCalls.get())
            }
        } finally {
            schedulerJob.cancelAndJoin()
        }
    }

    @Test(timeout = 10_000)
    fun automaticRefreshPullsReaderChoicesWithoutWaitingForBookWork() = runBlocking {
        val server = ReaderSettingsTransport()
        val schedulerJob = Job()
        val schedulerScope = CoroutineScope(Dispatchers.Default + schedulerJob)
        try {
            device().use { phone ->
                phone.state.publishMaps(server)
                server.stage("other", mapOf("mangaShowFurigana" to record(false), "sasayaki.autoPause" to record(false)))
                val scheduler = HttpSyncBookmarkScheduler(
                    phone.state, { settings }, { _, _, _ -> error("Only preferences changed") },
                    HttpSyncFullCycleRunner(schedulerScope), schedulerScope, transportFactory = { server },
                )

                scheduler.refreshNow()
                await { server.contains(READER_SETTINGS_PREFIX + phone.id) }

                assertFalse(phone.reader.settings.first().mangaShowFurigana)
                assertFalse(phone.audio.settings.first().autoPause)
            }
        } finally {
            schedulerJob.cancelAndJoin()
        }
    }

    private suspend fun await(predicate: () -> Boolean) = withTimeout(5_000) {
        while (!predicate()) delay(10)
    }

    private fun record(value: Boolean) = ReaderSettingRecord(JsonPrimitive(value), 100, "other")

    private fun emptyResult() = HttpSyncResult(
        uploadedBookmarks = 0, uploadedChatEntries = 0, uploadedMetadata = 0,
        downloadedBookmarks = 0, downloadedChatEntries = 0, remoteOnlyBooks = 0, errors = emptyList(),
    )

    private fun device(): Device {
        val root = temporaryFolder.newFolder()
        val id = UUID.randomUUID().toString()
        val job = Job()
        val scope = CoroutineScope(Dispatchers.IO + job)
        val reader = ReaderSettingsRepository(
            PreferenceDataStoreFactory.create(scope = scope, produceFile = { File(root, "reader.preferences_pb") }),
            syncDeviceId = id, nowMillis = { 100L },
        )
        val audio = SasayakiSettingsRepository(
            PreferenceDataStoreFactory.create(scope = scope, produceFile = { File(root, "audio.preferences_pb") }),
            syncDeviceId = id, nowMillis = { 100L },
        )
        val composite = CompositeReaderSettingsSyncStore(reader, audio) { 100L }
        val state = HttpSyncBatchState(
            BookRepository(File(root, "books").apply { mkdirs() }), installationId = id, readerSettings = composite,
        )
        return Device(id, reader, audio, composite, state, job)
    }

    private class Device(
        val id: String,
        val reader: ReaderSettingsRepository,
        val audio: SasayakiSettingsRepository,
        val composite: CompositeReaderSettingsSyncStore,
        val state: HttpSyncBatchState,
        private val job: Job,
    ) : AutoCloseable {
        override fun close() = runBlocking { job.cancelAndJoin() }
    }

    private inner class ReaderSettingsTransport : HttpSyncKvTransport {
        override val cacheIdentity = UUID.randomUUID().toString()
        private val values = ConcurrentHashMap<String, HttpSyncKvFetched>()
        private val clock = AtomicInteger()
        val readerPutAttempts = AtomicInteger()
        val failedReaderPuts = AtomicInteger()

        override suspend fun put(key: String, contentType: String, body: ByteArray): HttpSyncKvWriteResponse {
            if (key.startsWith(READER_SETTINGS_PREFIX)) {
                readerPutAttempts.incrementAndGet()
                if (failedReaderPuts.getAndUpdate { (it - 1).coerceAtLeast(0) } > 0) {
                    throw HttpSyncException("offline")
                }
            }
            val fetched = store(key, contentType, body)
            return HttpSyncKvWriteResponse(key, fetched.lastModified, fetched.etag, body.size, contentType)
        }

        private fun store(key: String, contentType: String, body: ByteArray): HttpSyncKvFetched {
            val etag = "sha256:" + MessageDigest.getInstance("SHA-256").digest(body).joinToString("") { "%02x".format(it) }
            val fetched = HttpSyncKvFetched(
                body.copyOf(), contentType, Instant.ofEpochSecond(1_800_000_000L + clock.incrementAndGet()).toString(), etag,
            )
            values[key] = fetched
            return fetched
        }

        override suspend fun get(key: String): HttpSyncKvFetched? = values[key]

        override suspend fun list(prefix: String?, since: String?, cursor: String?, limit: Int?): HttpSyncKvList {
            val matching = values.entries.asSequence()
                .filter { prefix == null || it.key.startsWith(prefix) }
                .filter { since == null || it.value.lastModified >= since }
                .filter { cursor == null || it.key > cursor }
                .map { (key, value) -> HttpSyncKvKeyMeta(key, value.lastModified, value.etag, value.body.size, value.contentType) }
                .sortedBy { it.key }.toList()
            val page = matching.take(limit ?: matching.size)
            val truncated = matching.size > page.size
            return HttpSyncKvList(page, truncated, page.lastOrNull()?.key?.takeIf { truncated })
        }

        override suspend fun delete(key: String) { values.remove(key) }

        fun stage(deviceId: String, records: Map<String, ReaderSettingRecord>) {
            val body = json.encodeToString(
                HttpSyncReaderSettingsBlob.serializer(), HttpSyncReaderSettingsBlob(deviceId = deviceId, records = records),
            ).toByteArray()
            store(READER_SETTINGS_PREFIX + deviceId, "application/json", body)
        }

        fun contains(key: String) = values.containsKey(key)

        fun blob(deviceId: String): HttpSyncReaderSettingsBlob = json.decodeFromString(
            HttpSyncReaderSettingsBlob.serializer(), values.getValue(READER_SETTINGS_PREFIX + deviceId).body.toString(Charsets.UTF_8),
        )
    }
}
