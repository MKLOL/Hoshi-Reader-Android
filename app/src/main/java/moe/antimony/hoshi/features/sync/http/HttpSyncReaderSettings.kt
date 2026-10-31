package moe.antimony.hoshi.features.sync.http

import androidx.annotation.StringRes
import moe.antimony.hoshi.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import moe.antimony.hoshi.features.reader.ReaderSettingRecord
import moe.antimony.hoshi.features.reader.ReaderSettingsSyncCodec
import moe.antimony.hoshi.features.reader.ReaderSettingsSyncStore

internal const val READER_SETTINGS_PREFIX = "app/reader_settings/"

/** Each installation writes only its own key; the generic KV server needs no schema changes. */
@Serializable
data class HttpSyncReaderSettingsBlob(
    val version: Int = 1,
    val deviceId: String,
    val records: Map<String, ReaderSettingRecord> = emptyMap(),
)

enum class ReaderSettingsSyncError(@get:StringRes val messageResource: Int) {
    LocalRead(R.string.http_sync_reader_error_local_read),
    InvalidIdentity(R.string.http_sync_reader_error_identity),
    Listing(R.string.http_sync_reader_error_listing),
    DeviceLimit(R.string.http_sync_reader_error_device_limit),
    InvalidKey(R.string.http_sync_reader_error_invalid_key),
    UnsupportedVersion(R.string.http_sync_reader_error_version),
    InvalidRemote(R.string.http_sync_reader_error_invalid_remote),
    RemoteRead(R.string.http_sync_reader_error_remote_read),
    FieldLimit(R.string.http_sync_reader_error_field_limit),
    Apply(R.string.http_sync_reader_error_apply),
    InvalidLocal(R.string.http_sync_reader_error_invalid_local),
    UploadLimit(R.string.http_sync_reader_error_upload_limit),
    Upload(R.string.http_sync_reader_error_upload),
    Exchange(R.string.http_sync_reader_error_exchange),
}

data class ReaderSettingsSyncResult(
    val uploaded: Boolean = false,
    val downloaded: Boolean = false,
    val errors: List<ReaderSettingsSyncError> = emptyList(),
)

/**
 * Exchanges small reader preference shards independently of books and sync-engine versions.
 *
 * The store atomically resolves individual fields, including edits made while a GET is in
 * flight. We read our own server key even when a caller supplies a cached listing, so a
 * restored installation never overwrites choices it has not read. The full merged record
 * set is then mirrored under only this device's key, retaining unknown future fields and
 * their original clocks. An unchanged mirror is never PUT again.
 *
 * A complete successful pass can be skipped while both the account-scoped key metadata and
 * the local record set remain identical. Any local edit, new ETag, or account change reads
 * server bodies again before publication; no individual response is reused across accounts.
 */
class HttpSyncReaderSettings(
    private val store: ReaderSettingsSyncStore,
    /** Reject an old in-flight response after the configured server/account changes. */
    private val isCurrentTransport: suspend (HttpSyncKvTransport) -> Boolean = { true },
) {
    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private var lastSuccessful: SuccessfulPass? = null

    private data class SuccessfulPass(
        val scope: String,
        val deviceId: String,
        val listing: List<HttpSyncKvKeyMeta>,
        val records: Map<String, ReaderSettingRecord>,
    )

    suspend fun sync(
        transport: HttpSyncKvTransport,
        listing: List<HttpSyncKvKeyMeta>? = null,
    ): ReaderSettingsSyncResult = mutex.withLock {
        val errors = mutableListOf<ReaderSettingsSyncError>()
        var downloaded = false
        var uploaded = false
        var canPublish = true
        val initial = try {
            store.snapshot()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return@withLock ReaderSettingsSyncResult(errors = listOf(ReaderSettingsSyncError.LocalRead))
        }
        if (!ReaderSettingsSyncCodec.isValidDeviceId(initial.deviceId)) {
            return@withLock ReaderSettingsSyncResult(errors = listOf(ReaderSettingsSyncError.InvalidIdentity))
        }
        val ownKey = READER_SETTINGS_PREFIX + initial.deviceId
        val deviceListing = try {
            (listing ?: listAll(transport)).asSequence()
                .filter { it.key.startsWith(READER_SETTINGS_PREFIX) }.sortedBy { it.key }.toList()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return@withLock ReaderSettingsSyncResult(errors = listOf(ReaderSettingsSyncError.Listing))
        }
        val scope = transport.cacheIdentity
        val previous = lastSuccessful
        if (scope != null && previous?.scope == scope && previous.deviceId == initial.deviceId &&
            previous.listing == deviceListing && previous.records == initial.records
        ) {
            return@withLock ReaderSettingsSyncResult()
        }
        val keys = deviceListing.mapTo(linkedSetOf()) { it.key }
        if ((keys + ownKey).size > MAX_DEVICES) {
            return@withLock ReaderSettingsSyncResult(errors = listOf(ReaderSettingsSyncError.DeviceLimit))
        }

        var own: HttpSyncReaderSettingsBlob? = null
        val supported = mutableListOf<HttpSyncReaderSettingsBlob>()
        // Own first is not required for ordering (the store resolves every field), but makes
        // the read-before-write rule explicit and independent of the listing's ordering.
        for (key in listOf(ownKey) + (keys - ownKey).sorted()) {
            val deviceId = key.removePrefix(READER_SETTINGS_PREFIX)
            if (!ReaderSettingsSyncCodec.isValidDeviceId(deviceId)) {
                errors += ReaderSettingsSyncError.InvalidKey
                canPublish = false
                continue
            }
            try {
                val fetched = transport.getBounded(key, MAX_BODY_BYTES) ?: continue
                val probe = json.decodeFromString(VersionProbe.serializer(), fetched.body.toString(Charsets.UTF_8))
                if (probe.version != 1) {
                    errors += ReaderSettingsSyncError.UnsupportedVersion
                    if (key == ownKey) canPublish = false
                    continue
                }
                val blob = json.decodeFromString(HttpSyncReaderSettingsBlob.serializer(), fetched.body.toString(Charsets.UTF_8))
                if (blob.deviceId != deviceId || blob.records.size > ReaderSettingsSyncCodec.MAX_RECORDS ||
                    blob.records.any { (name, record) -> !ReaderSettingsSyncCodec.isValidRecord(name, record) }
                ) {
                    errors += ReaderSettingsSyncError.InvalidRemote
                    canPublish = false
                    continue
                }
                supported += blob
                if (key == ownKey) own = blob
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Never log the payload, raw server response, or network exception; these
                // can contain private choices or credentials from a misconfigured server.
                errors += ReaderSettingsSyncError.RemoteRead
                canPublish = false
            }
        }

        if (!isCurrentTransport(transport)) return@withLock ReaderSettingsSyncResult()
        val names = initial.records.keys + supported.flatMap { it.records.keys }
        if (names.size > ReaderSettingsSyncCodec.MAX_RECORDS) {
            return@withLock ReaderSettingsSyncResult(errors = errors + ReaderSettingsSyncError.FieldLimit)
        }
        for (blob in supported) {
            try {
                if (!isCurrentTransport(transport)) return@withLock ReaderSettingsSyncResult()
                downloaded = store.merge(blob.records) { isCurrentTransport(transport) } || downloaded
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                errors += ReaderSettingsSyncError.Apply
                canPublish = false
            }
        }
        var finalRecords: Map<String, ReaderSettingRecord>? = null
        if (canPublish) {
            try {
                // A local edit may have landed during any GET or merge above. Take the
                // publication snapshot last; do not write the pre-download snapshot back.
                val latest = store.snapshot()
                if (latest.deviceId != initial.deviceId || latest.records.size > ReaderSettingsSyncCodec.MAX_RECORDS ||
                    latest.records.any { (name, record) -> !ReaderSettingsSyncCodec.isValidRecord(name, record) }
                ) {
                    errors += ReaderSettingsSyncError.InvalidLocal
                } else {
                    finalRecords = latest.records.toMap()
                    if (latest.records.isNotEmpty() && latest.records != own?.records) {
                        val blob = HttpSyncReaderSettingsBlob(deviceId = latest.deviceId, records = latest.records.toSortedMap())
                        val body = json.encodeToString(HttpSyncReaderSettingsBlob.serializer(), blob).toByteArray(Charsets.UTF_8)
                        if (body.size > MAX_BODY_BYTES) {
                            errors += ReaderSettingsSyncError.UploadLimit
                        } else {
                            if (!isCurrentTransport(transport)) return@withLock ReaderSettingsSyncResult()
                            transport.put(ownKey, JSON_CONTENT_TYPE, body)
                            uploaded = true
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                errors += ReaderSettingsSyncError.Upload
            }
        }
        if (errors.isEmpty() && scope != null && finalRecords != null) {
            lastSuccessful = SuccessfulPass(scope, initial.deviceId, deviceListing, finalRecords)
        }
        ReaderSettingsSyncResult(uploaded = uploaded, downloaded = downloaded, errors = errors)
    }

    private suspend fun listAll(transport: HttpSyncKvTransport): List<HttpSyncKvKeyMeta> {
        val keys = linkedMapOf<String, HttpSyncKvKeyMeta>()
        val cursors = mutableSetOf<String>()
        var cursor: String? = null
        repeat(MAX_DEVICES) {
            val page = transport.list(prefix = READER_SETTINGS_PREFIX, cursor = cursor, limit = MAX_DEVICES)
            for (meta in page.keys) {
                if (meta.key.startsWith(READER_SETTINGS_PREFIX)) keys[meta.key] = meta
                if (keys.size > MAX_DEVICES) throw IllegalStateException("Too many settings devices")
            }
            if (!page.truncated) return keys.values.toList()
            val next = page.nextCursor?.takeIf { it.isNotBlank() && cursors.add(it) }
                ?: throw IllegalStateException("Invalid settings listing cursor")
            cursor = next
        }
        throw IllegalStateException("Too many settings listing pages")
    }

    @Serializable
    private data class VersionProbe(val version: Int = 1)

    private companion object {
        const val MAX_BODY_BYTES = 64 * 1024
        const val MAX_DEVICES = 256
        const val JSON_CONTENT_TYPE = "application/json; charset=utf-8"
    }
}
