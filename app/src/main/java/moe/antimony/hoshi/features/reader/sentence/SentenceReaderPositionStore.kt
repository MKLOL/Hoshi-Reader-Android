package moe.antimony.hoshi.features.reader.sentence

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Remembers where sentence mode left off in each book. Lives in the app's files directory, not
 * the book folder, so it never enters a synced payload or its content hash.
 */
class SentenceReaderPositionStore(
    filesDir: File,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val file = File(filesDir, FILE_NAME)
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = MapSerializer(String.serializer(), SentencePosition.serializer())

    suspend fun load(bookId: String): SentencePosition? = withContext(ioDispatcher) {
        readAll()[bookId]
    }

    suspend fun save(bookId: String, position: SentencePosition) = withContext(ioDispatcher) {
        val next = readAll() + (bookId to position)
        val temp = File(file.parentFile, "$FILE_NAME.tmp")
        temp.writeText(json.encodeToString(serializer, next))
        if (!temp.renameTo(file)) {
            file.writeText(json.encodeToString(serializer, next))
            temp.delete()
        }
    }

    private fun readAll(): Map<String, SentencePosition> =
        runCatching { json.decodeFromString(serializer, file.readText()) }.getOrDefault(emptyMap())

    private companion object {
        const val FILE_NAME = "sentence_mode_positions.json"
    }
}
