package moe.antimony.hoshi.features.update

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

internal data class UpdateDownloadRecord(
    val versionName: String,
    val releaseUrl: String = "",
    val assetName: String,
    val fileName: String,
    val downloadId: Long?,
    val sha256: String?,
    val downloadUrl: String? = null,
    val fallbackDownloadUrls: List<String> = emptyList(),
    val status: UpdateDownloadRecordStatus,
    val bytesDownloaded: Long = 0,
    val totalBytes: Long = -1,
    val pauseReason: UpdateDownloadPauseReason? = null,
) {
    fun matches(update: AvailableUpdate): Boolean =
        versionName == update.versionName &&
            assetName == update.assetName &&
            sha256 == update.sha256

    fun toAvailableUpdate(): AvailableUpdate? {
        val url = downloadUrl ?: return null
        return AvailableUpdate(
            versionName = versionName,
            releaseUrl = releaseUrl,
            assetName = assetName,
            downloadUrl = url,
            fallbackDownloadUrls = fallbackDownloadUrls,
            sha256 = sha256,
        )
    }
}

internal enum class UpdateDownloadRecordStatus {
    Available,
    Skipped,
    Downloading,
    Downloaded,
    Failed,
    Queued,
    Paused,
}

internal val UpdateDownloadRecordStatus.isInFlight: Boolean
    get() = this == UpdateDownloadRecordStatus.Queued ||
        this == UpdateDownloadRecordStatus.Downloading || this == UpdateDownloadRecordStatus.Paused

internal enum class UpdateDownloadPauseReason { Network, Wifi, Retry, Unknown }

private val Context.updateDownloadDataStore by preferencesDataStore(name = "update-downloads")

internal fun Context.updateDownloadStore(): UpdateDownloadStore =
    UpdateDownloadStore(updateDownloadDataStore)

internal class UpdateDownloadStore(
    private val dataStore: DataStore<Preferences>,
) {
    val record: Flow<UpdateDownloadRecord?> = dataStore.data.map { it.toRecord() }

    suspend fun load(): UpdateDownloadRecord? = dataStore.data.map { it.toRecord() }.first()

    suspend fun saveDownloading(
        update: AvailableUpdate,
        fileName: String,
        downloadId: Long,
        downloadUrl: String,
    ) {
        dataStore.edit { preferences ->
            preferences[KEY_VERSION_NAME] = update.versionName
            preferences[KEY_RELEASE_URL] = update.releaseUrl
            preferences[KEY_ASSET_NAME] = update.assetName
            preferences[KEY_FILE_NAME] = fileName
            preferences[KEY_DOWNLOAD_ID] = downloadId
            preferences[KEY_DOWNLOAD_URL] = downloadUrl
            val candidates = update.downloadUrlCandidates()
            val selectedIndex = candidates.indexOf(downloadUrl)
            // Restored records start with the last attempted URL. Rotate the remaining
            // candidates too, so repeated retries reach every mirror before starting over.
            val remaining = if (selectedIndex >= 0) {
                candidates.drop(selectedIndex + 1) + candidates.take(selectedIndex)
            } else candidates
            preferences[KEY_FALLBACK_DOWNLOAD_URLS] = remaining.joinToString(separator = "\n")
            preferences[KEY_STATUS] = UpdateDownloadRecordStatus.Queued.name
            preferences.remove(KEY_BYTES_DOWNLOADED)
            preferences.remove(KEY_TOTAL_BYTES)
            preferences.remove(KEY_PAUSE_REASON)
            update.sha256?.let { preferences[KEY_SHA256] = it } ?: preferences.remove(KEY_SHA256)
        }
    }

    suspend fun saveAvailable(update: AvailableUpdate) {
        dataStore.edit { writeAvailable(it, update) }
    }

    suspend fun saveAvailableIfUnchanged(update: AvailableUpdate, expected: UpdateDownloadRecord?) {
        dataStore.edit { preferences ->
            val current = preferences.toRecord()
            // Preserve an active transfer even when this check found a newer release.
            if (current != expected || current?.status?.isInFlight == true) return@edit
            if (current?.matches(update) == true && current.status == UpdateDownloadRecordStatus.Downloaded) return@edit
            writeAvailable(preferences, update)
        }
    }

    private fun writeAvailable(preferences: MutablePreferences, update: AvailableUpdate) {
        preferences[KEY_VERSION_NAME] = update.versionName
        preferences[KEY_RELEASE_URL] = update.releaseUrl
        preferences[KEY_ASSET_NAME] = update.assetName
        preferences[KEY_FILE_NAME] = AndroidUpdateDownloadManager.UpdateFileName
        preferences.remove(KEY_DOWNLOAD_ID)
        preferences[KEY_DOWNLOAD_URL] = update.downloadUrl
        preferences[KEY_FALLBACK_DOWNLOAD_URLS] = update.fallbackDownloadUrls.joinToString(separator = "\n")
        preferences[KEY_STATUS] = UpdateDownloadRecordStatus.Available.name
        preferences.remove(KEY_BYTES_DOWNLOADED)
        preferences.remove(KEY_TOTAL_BYTES)
        preferences.remove(KEY_PAUSE_REASON)
        update.sha256?.let { preferences[KEY_SHA256] = it } ?: preferences.remove(KEY_SHA256)
    }

    suspend fun skip(update: AvailableUpdate) {
        dataStore.edit { preferences ->
            val current = preferences.toRecord()
            // Skip and Download sit in the same dialog. A transfer that already started, or a
            // verified download of this update, must not be orphaned by a late Skip. Like
            // saveAvailableIfUnchanged, this also keeps a transfer of another version; skipping a
            // newer release found by a manual check while an older one downloads is not recorded.
            if (current?.status?.isInFlight == true) return@edit
            if (current?.matches(update) == true && current.status == UpdateDownloadRecordStatus.Downloaded) return@edit
            preferences[KEY_VERSION_NAME] = update.versionName
            preferences[KEY_RELEASE_URL] = update.releaseUrl
            preferences[KEY_ASSET_NAME] = update.assetName
            preferences[KEY_FILE_NAME] = AndroidUpdateDownloadManager.UpdateFileName
            preferences.remove(KEY_DOWNLOAD_ID)
            preferences[KEY_DOWNLOAD_URL] = update.downloadUrl
            preferences[KEY_FALLBACK_DOWNLOAD_URLS] = update.fallbackDownloadUrls.joinToString(separator = "\n")
            preferences[KEY_STATUS] = UpdateDownloadRecordStatus.Skipped.name
            update.sha256?.let { preferences[KEY_SHA256] = it } ?: preferences.remove(KEY_SHA256)
        }
    }

    suspend fun updateTransfer(record: UpdateDownloadRecord, snapshot: UpdateTransferSnapshot) {
        dataStore.edit { preferences ->
            // A delayed completion must never overwrite a replacement download or a skip.
            if (preferences.toRecord() != record) return@edit
            preferences[KEY_STATUS] = snapshot.status.name
            preferences[KEY_BYTES_DOWNLOADED] = snapshot.bytesDownloaded
            preferences[KEY_TOTAL_BYTES] = snapshot.totalBytes
            snapshot.pauseReason?.let { preferences[KEY_PAUSE_REASON] = it.name }
                ?: preferences.remove(KEY_PAUSE_REASON)
        }
    }

    suspend fun clear(expected: UpdateDownloadRecord? = null) {
        dataStore.edit { preferences ->
            if (expected != null && preferences.toRecord() != expected) return@edit
            preferences.remove(KEY_VERSION_NAME)
            preferences.remove(KEY_RELEASE_URL)
            preferences.remove(KEY_ASSET_NAME)
            preferences.remove(KEY_FILE_NAME)
            preferences.remove(KEY_DOWNLOAD_ID)
            preferences.remove(KEY_DOWNLOAD_URL)
            preferences.remove(KEY_FALLBACK_DOWNLOAD_URLS)
            preferences.remove(KEY_SHA256)
            preferences.remove(KEY_STATUS)
            preferences.remove(KEY_BYTES_DOWNLOADED)
            preferences.remove(KEY_TOTAL_BYTES)
            preferences.remove(KEY_PAUSE_REASON)
        }
    }

    private fun Preferences.toRecord(): UpdateDownloadRecord? {
        val versionName = this[KEY_VERSION_NAME] ?: return null
        val fileName = this[KEY_FILE_NAME] ?: return null
        val assetName = this[KEY_ASSET_NAME] ?: fileName
        val status = this[KEY_STATUS]
            ?.let { raw -> UpdateDownloadRecordStatus.entries.firstOrNull { it.name == raw } }
            ?: return null
        return UpdateDownloadRecord(
            versionName = versionName,
            releaseUrl = this[KEY_RELEASE_URL].orEmpty(),
            assetName = assetName,
            fileName = fileName,
            downloadId = this[KEY_DOWNLOAD_ID],
            sha256 = this[KEY_SHA256],
            downloadUrl = this[KEY_DOWNLOAD_URL],
            fallbackDownloadUrls = this[KEY_FALLBACK_DOWNLOAD_URLS]
                ?.lineSequence()
                ?.filter(String::isNotBlank)
                ?.toList()
                .orEmpty(),
            status = status,
            bytesDownloaded = this[KEY_BYTES_DOWNLOADED] ?: 0,
            totalBytes = this[KEY_TOTAL_BYTES] ?: -1,
            pauseReason = this[KEY_PAUSE_REASON]?.let { raw ->
                UpdateDownloadPauseReason.entries.firstOrNull { it.name == raw }
            },
        )
    }

    companion object {
        private val KEY_VERSION_NAME = stringPreferencesKey("versionName")
        private val KEY_RELEASE_URL = stringPreferencesKey("releaseUrl")
        private val KEY_ASSET_NAME = stringPreferencesKey("assetName")
        private val KEY_FILE_NAME = stringPreferencesKey("fileName")
        private val KEY_DOWNLOAD_ID = longPreferencesKey("downloadId")
        private val KEY_DOWNLOAD_URL = stringPreferencesKey("downloadUrl")
        private val KEY_FALLBACK_DOWNLOAD_URLS = stringPreferencesKey("fallbackDownloadUrls")
        private val KEY_SHA256 = stringPreferencesKey("sha256")
        private val KEY_STATUS = stringPreferencesKey("status")
        private val KEY_BYTES_DOWNLOADED = longPreferencesKey("bytesDownloaded")
        private val KEY_TOTAL_BYTES = longPreferencesKey("totalBytes")
        private val KEY_PAUSE_REASON = stringPreferencesKey("pauseReason")
    }
}
