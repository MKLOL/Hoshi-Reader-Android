package moe.antimony.hoshi.features.sync.http

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import moe.antimony.hoshi.features.reader.ReaderSettingRecord
import moe.antimony.hoshi.features.reader.ReaderSettingsSyncCodec
import moe.antimony.hoshi.features.reader.ReaderSettingsSyncSnapshot
import moe.antimony.hoshi.features.reader.ReaderSettingsSyncStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class HttpSyncReaderSettingsTest {
    private val json = Json { encodeDefaults = true }

    @Test
    fun twoDevicesConvergeWithoutOverwritingIndependentOfflineChoices() = runBlocking {
        val server = Transport()
        val phone = Store("phone").apply { edit("mangaShowFurigana", JsonPrimitive(false), 10) }
        val tablet = Store("tablet").apply { edit("fontSize", JsonPrimitive(32), 20) }
        val phoneSync = HttpSyncReaderSettings(phone)
        val tabletSync = HttpSyncReaderSettings(tablet)

        assertTrue(phoneSync.sync(server).uploaded)
        val tabletResult = tabletSync.sync(server)
        assertTrue(tabletResult.downloaded)
        assertTrue(tabletResult.uploaded)
        val phoneResult = phoneSync.sync(server)
        assertTrue(phoneResult.downloaded)
        assertEquals(phone.snapshot().records, tablet.snapshot().records)
        assertEquals(JsonPrimitive(false), phone.snapshot().records.getValue("mangaShowFurigana").value)
        assertEquals(JsonPrimitive(32), phone.snapshot().records.getValue("fontSize").value)
        assertEquals(setOf(key("phone"), key("tablet")), server.values.keys)
        val writes = server.writes.size
        assertFalse(phoneSync.sync(server).uploaded)
        assertFalse(tabletSync.sync(server).uploaded)
        assertEquals("polls must not bump server modification times", writes, server.writes.size)
    }

    @Test
    fun freshDefaultsDoNotCreateAServerBlob() = runBlocking {
        val server = Transport()
        val result = HttpSyncReaderSettings(Store("fresh")).sync(server)
        assertEquals(ReaderSettingsSyncResult(), result)
        assertTrue(server.values.isEmpty())
        assertTrue(server.writes.isEmpty())
    }

    @Test
    fun freshDevicePullsThenMirrorsChoicesWithOriginalClocks() = runBlocking {
        val server = Transport()
        val remote = mapOf("mangaShowFurigana" to record(false, 5, "phone"))
        server.stage("phone", remote)
        val store = Store("fresh")

        val result = HttpSyncReaderSettings(store).sync(server)

        assertTrue(result.downloaded)
        assertTrue(result.uploaded)
        assertEquals(remote, store.snapshot().records)
        assertEquals(remote, server.blob("fresh").records)
        assertEquals("fresh", server.blob("fresh").deviceId)
        assertEquals(listOf(key("fresh")), server.writes)
    }

    @Test
    fun ownShardIsReadEvenWhenCachedListingOmitsIt() = runBlocking {
        val server = Transport()
        val own = mapOf("fontSize" to record(40, 30, "restored"))
        server.stage("restored", own)
        val store = Store("restored").apply { edit("mangaShowFurigana", JsonPrimitive(false), 31) }

        val result = HttpSyncReaderSettings(store).sync(server, listing = emptyList())

        assertTrue(result.downloaded)
        assertEquals(own.getValue("fontSize"), server.blob("restored").records.getValue("fontSize"))
        assertEquals(JsonPrimitive(false), server.blob("restored").records.getValue("mangaShowFurigana").value)
        assertEquals(0, server.listCalls)
    }

    @Test
    fun localEditWhilePullIsInFlightSurvivesAndIsPublishedImmediately() = runBlocking {
        val server = Transport()
        server.stage("peer", mapOf("fontSize" to record(24, 10, "peer")))
        val store = Store("local")
        val entered = CompletableDeferred<Unit>()
        val resume = CompletableDeferred<Unit>()
        server.onGet = { name ->
            if (name == key("peer")) {
                entered.complete(Unit)
                resume.await()
            }
        }
        val running = async { HttpSyncReaderSettings(store).sync(server) }
        entered.await()
        store.edit("fontSize", JsonPrimitive(36), 20)
        store.edit("mangaShowFurigana", JsonPrimitive(false), 21)
        resume.complete(Unit)

        assertTrue(running.await().uploaded)
        assertEquals(JsonPrimitive(36), store.snapshot().records.getValue("fontSize").value)
        assertEquals(store.snapshot().records, server.blob("local").records)
    }

    @Test
    fun paginationReadsEveryDeviceBeforePublishing() = runBlocking {
        val server = Transport().apply { pageSize = 1 }
        server.stage("one", mapOf("fontSize" to record(24, 10, "one")))
        server.stage("two", mapOf("mangaShowFurigana" to record(false, 11, "two")))
        val store = Store("local")

        val result = HttpSyncReaderSettings(store).sync(server)

        assertTrue(result.errors.isEmpty())
        assertEquals(2, server.listCalls)
        assertEquals(2, server.blob("local").records.size)
        assertEquals(listOf(READER_SETTINGS_PREFIX, READER_SETTINGS_PREFIX), server.listPrefixes)
    }

    @Test
    fun failedUploadIsRetriedAndDoesNotAcknowledgeAnUnsentSnapshot() = runBlocking {
        val server = Transport().apply { failPuts = 1 }
        val store = Store("local").apply { edit("fontSize", JsonPrimitive(28), 10) }
        val sync = HttpSyncReaderSettings(store)

        val failed = sync.sync(server)
        assertFalse(failed.uploaded)
        assertTrue(failed.errors.isNotEmpty())
        assertTrue(server.values.isEmpty())
        val retried = sync.sync(server)
        assertTrue(retried.uploaded)
        assertTrue(retried.errors.isEmpty())
        assertEquals(store.snapshot().records, server.blob("local").records)
    }

    @Test
    fun wellFormedUnknownFieldsSurviveOlderClientPublication() = runBlocking {
        val server = Transport()
        val future = json.parseToJsonElement("""{"style":"compact","ratio":1.25}""")
        val unknown = ReaderSettingRecord(future, 50, "newer")
        server.stage("newer", mapOf("futureReader.ornaments" to unknown))
        val store = Store("older").apply { edit("fontSize", JsonPrimitive(30), 51) }

        val result = HttpSyncReaderSettings(store).sync(server)

        assertTrue(result.errors.isEmpty())
        assertEquals(unknown, server.blob("older").records.getValue("futureReader.ornaments"))
        assertEquals(listOf(key("older")), server.writes)
    }

    @Test
    fun malformedOwnShardIsNotOverwrittenEvenWhenListingOmitsIt() = runBlocking {
        val server = Transport()
        val original = """{"deviceId":"local","records":{"fontSize":"corrupt"}}""".toByteArray()
        server.values[key("local")] = original
        val store = Store("local").apply { edit("fontSize", JsonPrimitive(30), 51) }

        val result = HttpSyncReaderSettings(store).sync(server, emptyList())

        assertTrue(result.errors.isNotEmpty())
        assertFalse(result.uploaded)
        assertTrue(server.values.getValue(key("local")).contentEquals(original))
        assertTrue(server.writes.isEmpty())
    }

    @Test
    fun unsupportedOwnVersionIsNotOverwrittenButValidPeersStillMerge() = runBlocking {
        val server = Transport()
        server.stage("local", mapOf("fontSize" to record(30, 51, "local")), version = 2)
        val original = server.values.getValue(key("local"))
        server.stage("peer", mapOf("mangaShowFurigana" to record(false, 52, "peer")))
        val store = Store("local")

        val result = HttpSyncReaderSettings(store).sync(server)

        assertTrue(result.errors.isNotEmpty())
        assertTrue(result.downloaded)
        assertFalse(result.uploaded)
        assertTrue(server.values.getValue(key("local")).contentEquals(original))
        assertTrue(server.writes.isEmpty())
    }

    @Test
    fun futurePeerVersionIsPreservedWhileSupportedChoicesCanStillPublish() = runBlocking {
        val server = Transport()
        server.stage("future", emptyMap(), version = 2)
        val original = server.values.getValue(key("future"))
        val store = Store("local").apply { edit("fontSize", JsonPrimitive(30), 51) }

        val result = HttpSyncReaderSettings(store).sync(server)

        assertTrue(result.errors.isNotEmpty())
        assertTrue(result.uploaded)
        assertTrue(server.values.getValue(key("future")).contentEquals(original))
        assertEquals(listOf(key("local")), server.writes)
    }

    @Test
    fun malformedPeerBlocksPublicationWithoutPreventingValidPeerPull() = runBlocking {
        val server = Transport()
        server.values[key("bad")] = "private invalid settings payload".toByteArray()
        server.stage("good", mapOf("fontSize" to record(30, 51, "good")))
        val store = Store("local")

        val result = HttpSyncReaderSettings(store).sync(server)

        assertTrue(result.downloaded)
        assertFalse(result.uploaded)
        assertTrue(result.errors.none { "private" in it.name })
        assertEquals(JsonPrimitive(30), store.snapshot().records.getValue("fontSize").value)
    }

    @Test
    fun deviceMismatchAndInvalidKnownValueAreRejectedWithoutOverwrite() = runBlocking {
        val invalid = listOf(
            HttpSyncReaderSettingsBlob(deviceId = "impostor", records = mapOf("fontSize" to record(30, 1, "local"))),
            HttpSyncReaderSettingsBlob(deviceId = "local", records = mapOf("fontSize" to record(5000, 1, "local"))),
            HttpSyncReaderSettingsBlob(deviceId = "local", records = mapOf("fontSize" to record(30, -1, "local"))),
        )
        for (blob in invalid) {
            val server = Transport()
            server.values[key("local")] = json.encodeToString(HttpSyncReaderSettingsBlob.serializer(), blob).toByteArray()
            val store = Store("local").apply { edit("fontSize", JsonPrimitive(24), 2) }

            val result = HttpSyncReaderSettings(store).sync(server)

            assertFalse(result.uploaded)
            assertTrue(result.errors.isNotEmpty())
            assertTrue(server.writes.isEmpty())
        }
    }

    @Test
    fun oversizedBodyUsesBoundedDownloadAndNeverOverwritesIt() = runBlocking {
        val server = Transport()
        server.values[key("local")] = ByteArray(65 * 1024)
        val store = Store("local").apply { edit("fontSize", JsonPrimitive(30), 51) }

        val result = HttpSyncReaderSettings(store).sync(server)

        assertFalse(result.uploaded)
        assertTrue(result.errors.isNotEmpty())
        assertEquals(listOf(64 * 1024), server.downloadLimits)
        assertTrue(server.writes.isEmpty())
    }

    @Test
    fun changingServerReadsItsOwnStateWithoutReusingAnotherAccountsSnapshot() = runBlocking {
        val store = Store("local")
        val first = Transport().apply { cacheScope = "account-one" }
        first.stage("local", mapOf("fontSize" to record(24, 10, "local")))
        val second = Transport().apply { cacheScope = "account-two" }
        second.stage("local", mapOf("fontSize" to record(36, 20, "local")))
        val sync = HttpSyncReaderSettings(store)

        sync.sync(first)
        val result = sync.sync(second, emptyList())

        assertTrue(result.downloaded)
        assertFalse(result.uploaded)
        assertEquals(JsonPrimitive(36), store.snapshot().records.getValue("fontSize").value)
        assertEquals(listOf(key("local")), second.gets)
    }

    @Test
    fun changingAccountDuringDownloadDiscardsOldResponseWithoutApplyingOrPublishing() = runBlocking {
        val store = Store("local")
        val server = Transport()
        server.stage("peer", mapOf("fontSize" to record(36, 20, "peer")))
        var current = true
        server.onGet = { name -> if (name == key("peer")) current = false }
        val sync = HttpSyncReaderSettings(store, isCurrentTransport = { current })

        val result = sync.sync(server)

        assertEquals(ReaderSettingsSyncResult(), result)
        assertTrue(store.snapshot().records.isEmpty())
        assertTrue(server.writes.isEmpty())
    }

    @Test
    fun successfulUnchangedPollsSkipBodyDownloadsButLocalEditsAndEtagsTriggerFreshReads() = runBlocking {
        val store = Store("local").apply { edit("fontSize", JsonPrimitive(30), 51) }
        val server = Transport().apply { cacheScope = "account" }
        server.stage("local", store.snapshot().records)
        val sync = HttpSyncReaderSettings(store)
        sync.sync(server)
        assertEquals(1, server.gets.size)

        assertEquals(ReaderSettingsSyncResult(), sync.sync(server))
        assertEquals("unchanged poll only lists metadata", 1, server.gets.size)
        assertTrue(server.writes.isEmpty())
        store.edit("fontSize", JsonPrimitive(32), 52)
        assertTrue(sync.sync(server).uploaded)
        assertEquals("local edit re-reads own shard", 2, server.gets.size)
        sync.sync(server) // new metadata acknowledges the just-uploaded body
        val acknowledgedGets = server.gets.size
        sync.sync(server)
        assertEquals(acknowledgedGets, server.gets.size)

        server.stage("local", mapOf("fontSize" to record(36, 53, "local")))
        assertTrue(sync.sync(server).downloaded)
        assertTrue(server.gets.size > acknowledgedGets)
        assertEquals(JsonPrimitive(36), store.snapshot().records.getValue("fontSize").value)
    }

    @Test
    fun identicalMetadataInDifferentAccountsStillRequiresARealDownload() = runBlocking {
        val store = Store("local").apply { edit("fontSize", JsonPrimitive(30), 51) }
        val first = Transport().apply { cacheScope = "first"; stage("local", store.snapshot().records) }
        val second = Transport().apply { cacheScope = "second"; stage("local", store.snapshot().records) }
        val sync = HttpSyncReaderSettings(store)
        sync.sync(first)

        sync.sync(second)

        assertEquals(listOf(key("local")), second.gets)
    }

    @Test
    fun editDuringPutDoesNotGetAcknowledgedByNoopMemoization() = runBlocking {
        val store = Store("local").apply { edit("fontSize", JsonPrimitive(30), 51) }
        val server = Transport().apply { cacheScope = "account" }
        server.onPut = { store.edit("fontSize", JsonPrimitive(32), 52) }
        val sync = HttpSyncReaderSettings(store)
        assertTrue(sync.sync(server).uploaded)
        assertEquals(JsonPrimitive(30), server.blob("local").records.getValue("fontSize").value)
        server.onPut = {}

        assertTrue(sync.sync(server).uploaded)

        assertEquals(JsonPrimitive(32), server.blob("local").records.getValue("fontSize").value)
        assertEquals(2, server.writes.size)
    }

    @Test
    fun concurrentPassesSerializeAndOnlyPublishOnce() = runBlocking {
        val store = Store("local").apply { edit("fontSize", JsonPrimitive(30), 51) }
        val server = Transport()
        val entered = CompletableDeferred<Unit>()
        val resume = CompletableDeferred<Unit>()
        server.onGet = {
            if (!entered.isCompleted) {
                entered.complete(Unit)
                resume.await()
            }
        }
        val sync = HttpSyncReaderSettings(store)
        val first = async { sync.sync(server) }
        entered.await()
        val second = async { sync.sync(server) }
        resume.complete(Unit)

        assertTrue(first.await().uploaded)
        assertFalse(second.await().uploaded)
        assertEquals(listOf(key("local")), server.writes)
    }

    @Test
    fun cancellationPropagatesWithoutPublishingOrLeakingNetworkDetails() = runBlocking {
        val store = Store("local").apply { edit("fontSize", JsonPrimitive(30), 51) }
        val server = Transport()
        server.onGet = { throw CancellationException("cancelled") }

        try {
            HttpSyncReaderSettings(store).sync(server)
            fail("cancellation must propagate")
        } catch (_: CancellationException) {
            assertTrue(server.writes.isEmpty())
        }
    }

    @Test
    fun repeatedPaginationCursorFailsClosedBeforeAnyUpload() = runBlocking {
        val server = Transport().apply { invalidPagination = true }
        val store = Store("local").apply { edit("fontSize", JsonPrimitive(30), 51) }

        val result = HttpSyncReaderSettings(store).sync(server)

        assertFalse(result.uploaded)
        assertTrue(result.errors.isNotEmpty())
        assertEquals(2, server.listCalls)
        assertTrue(server.writes.isEmpty())
    }

    private class Store(private val deviceId: String) : ReaderSettingsSyncStore {
        private val mutex = Mutex()
        private var records: Map<String, ReaderSettingRecord> = emptyMap()

        suspend fun edit(name: String, value: JsonElement, timestamp: Long) = mutex.withLock {
            records = records + (name to ReaderSettingRecord(value, timestamp, deviceId))
        }

        override suspend fun snapshot(): ReaderSettingsSyncSnapshot = mutex.withLock {
            ReaderSettingsSyncSnapshot(deviceId, records.toMap())
        }

        override suspend fun merge(remote: Map<String, ReaderSettingRecord>): Boolean = mutex.withLock {
            val merged = records.toMutableMap()
            for ((name, candidate) in remote) {
                if (ReaderSettingsSyncCodec.isNewer(candidate, merged[name])) merged[name] = candidate
            }
            (merged != records).also { records = merged.toMap() }
        }
    }

    private inner class Transport : HttpSyncKvTransport {
        val values = linkedMapOf<String, ByteArray>()
        val writes = mutableListOf<String>()
        val gets = mutableListOf<String>()
        val downloadLimits = mutableListOf<Int>()
        val listPrefixes = mutableListOf<String?>()
        var listCalls = 0
        var failPuts = 0
        var pageSize = Int.MAX_VALUE
        var invalidPagination = false
        var cacheScope: String? = null
        var onGet: suspend (String) -> Unit = {}
        var onPut: suspend (String) -> Unit = {}
        override val cacheIdentity: String? get() = cacheScope

        fun stage(deviceId: String, records: Map<String, ReaderSettingRecord>, version: Int = 1) {
            values[key(deviceId)] = json.encodeToString(
                HttpSyncReaderSettingsBlob.serializer(), HttpSyncReaderSettingsBlob(version, deviceId, records),
            ).toByteArray()
        }

        fun blob(deviceId: String): HttpSyncReaderSettingsBlob = json.decodeFromString(
            HttpSyncReaderSettingsBlob.serializer(), values.getValue(key(deviceId)).toString(Charsets.UTF_8),
        )

        override suspend fun getBounded(key: String, maxBytes: Int): HttpSyncKvFetched? {
            downloadLimits += maxBytes
            return super.getBounded(key, maxBytes)
        }

        override suspend fun get(key: String): HttpSyncKvFetched? {
            gets += key
            onGet(key)
            return values[key]?.let { HttpSyncKvFetched(it, "application/json", "stamp", it.contentHashCode().toString()) }
        }

        override suspend fun put(key: String, contentType: String, body: ByteArray): HttpSyncKvWriteResponse {
            if (failPuts-- > 0) throw IllegalStateException("private bearer token network detail")
            onPut(key)
            writes += key
            values[key] = body
            return HttpSyncKvWriteResponse(key, "stamp", body.contentHashCode().toString(), body.size, contentType)
        }

        override suspend fun list(prefix: String?, since: String?, cursor: String?, limit: Int?): HttpSyncKvList {
            listCalls++
            listPrefixes += prefix
            if (invalidPagination) return HttpSyncKvList(truncated = true, nextCursor = "same-cursor")
            val all = values.keys.filter { prefix == null || it.startsWith(prefix) }.sorted()
            val start = cursor?.toInt() ?: 0
            val count = minOf(pageSize, limit ?: Int.MAX_VALUE)
            val page = all.drop(start).take(count)
            val end = start + page.size
            return HttpSyncKvList(
                keys = page.map { HttpSyncKvKeyMeta(it, "stamp", values.getValue(it).contentHashCode().toString(), values.getValue(it).size, "application/json") },
                truncated = end < all.size,
                nextCursor = end.toString().takeIf { end < all.size },
            )
        }

        override suspend fun delete(key: String) {
            error("Settings exchange must not delete server keys")
        }
    }

    private fun record(value: Boolean, stamp: Long, device: String) = ReaderSettingRecord(JsonPrimitive(value), stamp, device)
    private fun record(value: Int, stamp: Long, device: String) = ReaderSettingRecord(JsonPrimitive(value), stamp, device)
    private fun key(deviceId: String): String = READER_SETTINGS_PREFIX + deviceId
}
