package moe.antimony.hoshi.epub

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import moe.antimony.hoshi.storage.writeSidecarAtomically
import java.io.File

/**
 * Reading statistics of a book this install keeps no folder for: a book never downloaded here,
 * one deleted here or on another device, or one whose download has not finished. Reading
 * history belongs to the reader, not to the book's files, so Today, the streak and every total
 * keep counting it however the library changes.
 *
 * Each entry is a folder `Books/.reading_history/{syncId}/` laid out like a book folder's
 * statistics (`statistics.json`, `manga_statistics.json`, the HTTP exchange state), so every
 * statistics read, write, merge and exchange works on it unchanged. The leading dot keeps the
 * folder off the bookshelf (see [BookFileDataSource.loadAllBooks]); it travels in backups with
 * the rest of `Books`.
 */
data class StatisticsHistoryEntry(
    val syncId: String,
    val root: File,
    val title: String,
    val contentType: ContentType,
    /** Reading position when the book was deleted here; null when this install never had it. */
    val progress: Double? = null,
)

/** The book a history folder stands for, since it has no `metadata.json` or `mokuro.json`. */
@Serializable
internal data class StatisticsHistoryInfo(
    val syncId: String,
    val title: String? = null,
    /** Null while unknown: a guess must not be passed on as fact. */
    val contentType: ContentType? = null,
    val progress: Double? = null,
)

internal const val STATISTICS_HISTORY_DIRECTORY_NAME = ".reading_history"
internal const val UNREADABLE_DIRECTORY_NAME = ".unreadable"
internal const val STATISTICS_HISTORY_INFO_FILE_NAME = "history.json"

private val historyJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = false
}

internal fun readStatisticsHistoryInfo(root: File): StatisticsHistoryInfo? {
    val file = root.resolve(STATISTICS_HISTORY_INFO_FILE_NAME)
    if (!file.isFile) return null
    return runCatching { historyJson.decodeFromString(StatisticsHistoryInfo.serializer(), file.readText()) }
        .getOrNull()
        ?.takeIf { it.syncId == root.name }
}

internal fun writeStatisticsHistoryInfo(root: File, info: StatisticsHistoryInfo) {
    root.mkdirs()
    writeSidecarAtomically(
        root.resolve(STATISTICS_HISTORY_INFO_FILE_NAME),
        historyJson.encodeToString(StatisticsHistoryInfo.serializer(), info),
    )
}

/**
 * The content type statistics code should assume for [root]: what a history folder recorded,
 * otherwise what the book folder holds (see [bookContentType]).
 */
fun statisticsContentType(root: File): ContentType {
    val info = readStatisticsHistoryInfo(root) ?: return bookContentType(root)
    // Only manga keep OCR character counts; until the type is known, they tell.
    return info.contentType ?: if (root.resolve("manga_statistics.json").isFile) ContentType.Mokuro else ContentType.Epub
}

/** A history folder name must be one key segment that cannot walk out of the history directory. */
internal fun isUsableStatisticsHistoryName(syncId: String): Boolean =
    syncId.length in 1..64 &&
        syncId.trim('.').isNotEmpty() &&
        syncId.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '_' || it == '.' || it == '-' }
