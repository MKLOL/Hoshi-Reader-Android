package moe.antimony.hoshi.features.storage

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import moe.antimony.hoshi.features.sync.http.HttpSyncDownloadSpool
import moe.antimony.hoshi.features.sync.http.lastActivityMillis
import java.io.File
import java.util.concurrent.TimeUnit

enum class StorageCleanupCategoryId {
    AnkiMediaCache,
    EpubImportResidue,
    BackupRestoreResidue,
    DictionaryImportResidue,
    LocalAudioImportResidue,
    OrphanSasayakiAudio,
    SyncDownloadResidue,
}

data class StorageCleanupReport(
    val categories: List<StorageCleanupCategory>,
) {
    val totalSizeBytes: Long = categories.sumOf { it.sizeBytes }
    val totalItemCount: Int = categories.sumOf { it.itemCount }
    val hasCleanableItems: Boolean = categories.isNotEmpty()
}

data class StorageCleanupCategory(
    val id: StorageCleanupCategoryId,
    val title: String,
    val description: String,
    val sizeBytes: Long,
    val itemCount: Int,
    internal val targets: List<File>,
)

class StorageCleanupRepository(
    private val filesDir: File,
    private val cacheDir: File,
    private val json: Json = Json { ignoreUnknownKeys = true },
    private val now: () -> Long = System::currentTimeMillis,
) {
    fun scan(): StorageCleanupReport {
        val categories = listOfNotNull(
            category(
                id = StorageCleanupCategoryId.AnkiMediaCache,
                title = "Anki media cache",
                description = "Temporary word audio, dictionary media, and Sasayaki sentence audio prepared for AnkiDroid.",
                targets = listOf(cacheDir.resolve("anki-media")).existing(),
            ),
            category(
                id = StorageCleanupCategoryId.EpubImportResidue,
                title = "Interrupted manga imports",
                description = "Temporary files left by manga imports that did not finish.",
                targets = listOf(filesDir.resolve("ImportTemp")).existing(),
            ),
            category(
                id = StorageCleanupCategoryId.BackupRestoreResidue,
                title = "Interrupted backup restores",
                description = "Temporary .hoshi archives and restore folders left by interrupted restore operations.",
                targets = restoreResidues(),
            ),
            category(
                id = StorageCleanupCategoryId.DictionaryImportResidue,
                title = "Interrupted dictionary imports",
                description = "Staged dictionary import folders that were not committed to the dictionary collection.",
                targets = dictionaryImportResidues(),
            ),
            category(
                id = StorageCleanupCategoryId.LocalAudioImportResidue,
                title = "Interrupted local audio import",
                description = "Temporary android.db files left by a local audio database import.",
                targets = listOf(filesDir.resolve("Audio/android.db.tmp")).existing(),
            ),
            category(
                id = StorageCleanupCategoryId.OrphanSasayakiAudio,
                title = "Unlinked Sasayaki audio",
                description = "Copied audiobook files no longer referenced by any book playback metadata.",
                targets = orphanSasayakiAudioFiles(),
            ),
            category(
                id = StorageCleanupCategoryId.SyncDownloadResidue,
                title = "Interrupted sync downloads",
                description = "Partly downloaded books and unpacking folders that HTTP sync left behind.",
                targets = syncDownloadResidues(),
            ),
        )
        return StorageCleanupReport(categories)
    }

    fun clean(report: StorageCleanupReport): StorageCleanupReport {
        // The preview may outlive a playback selection or a restore/import rollback.
        // Only remove items the user saw that are still eligible for cleanup now.
        val currentTargets = scan().categories.flatMap { it.targets }.toSet()
        report.categories
            .flatMap { it.targets }
            .filter { it in currentTargets }
            .forEach { target ->
                if (target.isDirectory) {
                    target.deleteRecursively()
                } else {
                    target.delete()
                }
            }
        return scan()
    }

    private fun category(
        id: StorageCleanupCategoryId,
        title: String,
        description: String,
        targets: List<File>,
    ): StorageCleanupCategory? {
        val existing = targets.existing()
        if (existing.isEmpty()) return null
        val sizeBytes = existing.sumOf { it.sizeBytes() }
        val itemCount = existing.sumOf { it.cleanableItemCount() }
        return StorageCleanupCategory(
            id = id,
            title = title,
            description = description,
            sizeBytes = sizeBytes,
            itemCount = itemCount,
            targets = existing,
        )
    }

    private fun restoreResidues(): List<File> =
        filesDir.listFiles()
            ?.filter { file ->
                isInterruptedRestoreWorkingFile(file) || isCompletedRestoreBackup(file)
            }
            .orEmpty()

    private fun isInterruptedRestoreWorkingFile(file: File): Boolean =
        (
            file.name.startsWith(".books-restore-") ||
                file.name.startsWith(".dictionaries-restore-")
        ) && !file.name.contains("-restore-backup-")

    private fun isCompletedRestoreBackup(file: File): Boolean {
        val folderName = when {
            file.name.startsWith(".books-restore-backup-") -> "Books"
            file.name.startsWith(".dictionaries-restore-backup-") -> "Dictionaries"
            else -> return false
        }
        return filesDir.resolve(folderName).exists()
    }

    private fun dictionaryImportResidues(): List<File> {
        val dictionariesRoot = filesDir.resolve("Dictionaries")
        return listOf("Term", "Frequency", "Pitch").flatMap { typeName ->
            val typeDirectory = dictionariesRoot.resolve(typeName)
            typeDirectory.listFiles()
                ?.filter { file ->
                    file.name.startsWith(".dictionary-import-") ||
                        isCompletedDictionaryReplacementBackup(typeDirectory, file)
                }
                .orEmpty()
        }
    }

    private fun isCompletedDictionaryReplacementBackup(typeDirectory: File, file: File): Boolean {
        if (!file.name.startsWith(".") || !file.name.contains("-replace-")) return false
        val targetName = file.name.removePrefix(".").substringBeforeLast("-replace-", missingDelimiterValue = "")
        if (targetName.isBlank()) return false
        return typeDirectory.resolve(targetName).exists()
    }

    /**
     * A folder written in the last few minutes may belong to a transfer that is still running. A
     * book's unpacking folder stays empty while its archive downloads, so none is offered while
     * any download is being written.
     */
    private fun syncDownloadResidues(): List<File> {
        fun idle(file: File) = now() - file.lastActivityMillis() >= ACTIVE_SYNC_TRANSFER_MILLIS
        val downloads = filesDir.resolve(HttpSyncDownloadSpool.DIRECTORY).listFiles().orEmpty().toList()
        val staging = filesDir.listFiles { file -> file.name.startsWith(HttpSyncDownloadSpool.IMPORT_STAGING_PREFIX) }
            .orEmpty().toList()
        val downloading = downloads.any { !idle(it) }
        return downloads.filter(::idle) + if (downloading) emptyList() else staging.filter(::idle)
    }

    private fun orphanSasayakiAudioFiles(): List<File> {
        val booksRoot = filesDir.resolve("Books")
        return booksRoot.listFiles()
            ?.filter(File::isDirectory)
            ?.flatMap { bookRoot ->
                val audioRoot = bookRoot.resolve("Sasayaki")
                val files = audioRoot.listFiles()?.filter(File::isFile).orEmpty()
                if (files.isEmpty()) return@flatMap emptyList()
                val playbackFile = bookRoot.resolve("sasayaki_playback.json")
                val playback = if (playbackFile.exists()) {
                    runCatching {
                        json.decodeFromString<SasayakiPlaybackAudioReference>(playbackFile.readText())
                    }.getOrElse {
                        // Unreadable metadata does not prove that the audiobook is unlinked.
                        return@flatMap emptyList()
                    }
                } else {
                    null
                }
                val referenced = referencedSasayakiAudioFile(playback?.audioFileName, audioRoot)
                files.filter { file -> referenced == null || file.canonicalFile != referenced }
            }
            .orEmpty()
    }

    private fun referencedSasayakiAudioFile(audioFileName: String?, audioRoot: File): File? {
        val fileName = audioFileName?.takeIf { it.isNotBlank() } ?: return null
        val audioRootCanonical = audioRoot.canonicalFile
        val candidate = audioRootCanonical.resolve(fileName).canonicalFile
        if (candidate.path != audioRootCanonical.path && !candidate.path.startsWith(audioRootCanonical.path + File.separator)) {
            return null
        }
        return candidate.takeIf(File::isFile)
    }

    private fun List<File>.existing(): List<File> =
        filter { it.exists() }

    private fun File.sizeBytes(): Long =
        if (isFile) {
            length()
        } else {
            walkTopDown().filter(File::isFile).sumOf(File::length)
        }

    private fun File.cleanableItemCount(): Int =
        if (isFile) {
            1
        } else {
            val childCount = listFiles()?.sumOf { child -> child.cleanableItemCount() } ?: 0
            childCount.takeIf { it > 0 } ?: 1
        }

    @Serializable
    private data class SasayakiPlaybackAudioReference(
        val audioFileName: String? = null,
    )

}

private val ACTIVE_SYNC_TRANSFER_MILLIS = TimeUnit.MINUTES.toMillis(10)
