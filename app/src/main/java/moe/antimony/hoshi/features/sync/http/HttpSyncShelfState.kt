package moe.antimony.hoshi.features.sync.http

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
internal data class HttpSyncShelfPlacementRecord(
    val shelfName: String? = null,
    val updatedAt: String,
)

internal class HttpSyncShelfStateStore(
    private val json: Json,
) {
    fun load(booksRoot: File?): Map<String, HttpSyncShelfPlacementRecord> {
        val file = stateFile(booksRoot) ?: return emptyMap()
        if (!file.isFile) return emptyMap()
        return runCatching {
            json.decodeFromString(serializer, file.readText())
        }.getOrDefault(emptyMap())
    }

    fun save(booksRoot: File?, state: Map<String, HttpSyncShelfPlacementRecord>) {
        val file = stateFile(booksRoot) ?: return
        file.parentFile?.mkdirs()
        file.writeText(json.encodeToString(serializer, state.toSortedMap()))
    }

    private fun stateFile(booksRoot: File?): File? =
        booksRoot?.resolve(STATE_FILE_NAME)

    private companion object {
        const val STATE_FILE_NAME = ".http_sync_shelf_state.json"
        val serializer = MapSerializer(String.serializer(), HttpSyncShelfPlacementRecord.serializer())
    }
}

@Serializable
internal data class HttpSyncDeletedBookRecord(
    val title: String,
    val contentType: HttpSyncContentType,
    val deletedAt: String,
)

internal class HttpSyncDeletedBookStateStore(
    private val json: Json,
) {
    fun load(booksRoot: File?): Map<String, HttpSyncDeletedBookRecord> {
        val file = stateFile(booksRoot) ?: return emptyMap()
        if (!file.isFile) return emptyMap()
        return runCatching {
            json.decodeFromString(serializer, file.readText())
        }.getOrDefault(emptyMap())
    }

    fun save(booksRoot: File?, state: Map<String, HttpSyncDeletedBookRecord>) {
        val file = stateFile(booksRoot) ?: return
        file.parentFile?.mkdirs()
        file.writeText(json.encodeToString(serializer, state.toSortedMap()))
    }

    fun recordDeletedBook(booksRoot: File?, syncId: String, record: HttpSyncDeletedBookRecord) {
        val updated = load(booksRoot).toMutableMap()
        updated[syncId] = record
        save(booksRoot, updated)
    }

    private fun stateFile(booksRoot: File?): File? =
        booksRoot?.resolve(STATE_FILE_NAME)

    private companion object {
        const val STATE_FILE_NAME = ".http_sync_deleted_books.json"
        val serializer = MapSerializer(String.serializer(), HttpSyncDeletedBookRecord.serializer())
    }
}
