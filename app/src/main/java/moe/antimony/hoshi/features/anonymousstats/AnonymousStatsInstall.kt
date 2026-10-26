package moe.antimony.hoshi.features.anonymousstats

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import moe.antimony.hoshi.storage.writeSidecarAtomically
import java.io.File
import java.util.UUID

/** Everything the report keeps on this device. Times are wall-clock milliseconds. */
@Serializable
internal data class AnonymousStatsState(
    /** Random UUID v4; absent until the first report is due, or after an opt-out. */
    val installId: String? = null,
    /** The package's `firstInstallTime` this state belongs to; another value means a copied install. */
    val installedAt: Long? = null,
    /** No report before this: set when the feature first runs on an install, so people can opt out first. */
    val notBefore: Long? = null,
    /** The last report the server handled (accepted, or refused in a way a retry cannot fix). */
    val lastSentAt: Long? = null,
    /** The last report to retry (network failure, 408, 429, 5xx), so offline launches back off. */
    val lastAttemptAt: Long? = null,
)

/**
 * Stores [AnonymousStatsState]. Unrelated to the sync installation id and the device name
 * ([moe.antimony.hoshi.epub.DeviceIdentity]).
 *
 * It lives in `noBackupFilesDir`, which Auto Backup and device transfer never copy (the same
 * guarantee [moe.antimony.hoshi.RestoredInstallMarker] relies on), so a restored or transferred
 * install starts without it. Phone-clone tools that copy app data wholesale are caught by
 * [AnonymousStatsState.installedAt]. Opting out deletes the folder.
 */
internal class AnonymousStatsInstall(
    directory: () -> File,
    private val newId: () -> UUID = UUID::randomUUID,
) {
    // Resolved on first use, on the IO thread: getNoBackupFilesDir() may create the folder.
    private val directory by lazy(directory)
    private val stateFile get() = directory.resolve(STATE_FILE_NAME)
    private val lock = Any()

    fun state(): AnonymousStatsState? = synchronized(lock) {
        val state = runCatching { StateJson.decodeFromString(AnonymousStatsState.serializer(), stateFile.readText()) }
            .getOrNull()
            ?: return null
        state.copy(installId = state.installId?.takeIf(::isInstallId))
    }

    fun installId(): String? = state()?.installId

    fun write(state: AnonymousStatsState) {
        synchronized(lock) {
            directory.mkdirs()
            writeSidecarAtomically(stateFile, StateJson.encodeToString(AnonymousStatsState.serializer(), state))
        }
    }

    fun newInstallId(): String = newId().toString().lowercase()

    fun clear() {
        synchronized(lock) { directory.deleteRecursively() }
    }

    private fun isInstallId(value: String): Boolean =
        runCatching { UUID.fromString(value) }.getOrNull()?.let { it.version() == 4 && it.toString() == value } == true

    companion object {
        private const val DIRECTORY_NAME = "anonymous-stats"
        private const val STATE_FILE_NAME = "state.json"
        private val StateJson = Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        }

        fun inNoBackupDirectory(noBackupFilesDir: () -> File) =
            AnonymousStatsInstall({ noBackupFilesDir().resolve(DIRECTORY_NAME) })

        fun inNoBackupDirectory(noBackupFilesDir: File) = inNoBackupDirectory { noBackupFilesDir }
    }
}
