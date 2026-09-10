package moe.antimony.hoshi.features.ai

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Reads and writes the per-manga ChatGPT history (`ai_chat_log.json`, stored in the book's
 * directory alongside `bookmark.json` etc.).
 *
 * Deliberately standalone rather than a method on the shared `BookRepository`: the ChatGPT
 * feature is a fork addition, so keeping its storage self-contained here means it never
 * touches upstream files and stays easy to merge around. It mirrors the tiny load/save-JSON
 * pattern `BookSidecarDataSource` uses for the other per-book sidecar files.
 */
class AiChatHistoryStore(
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    /** The book's chat history, or an empty log if there is none yet / it cannot be read. */
    suspend fun load(bookRoot: File): AiChatLog = withContext(ioDispatcher) {
        val file = bookRoot.resolve(FILE_NAME)
        if (!file.isFile) return@withContext AiChatLog()
        runCatching { json.decodeFromString(AiChatLog.serializer(), file.readText()) }
            .getOrDefault(AiChatLog())
    }

    suspend fun save(bookRoot: File, log: AiChatLog) = withContext(ioDispatcher) {
        bookRoot.mkdirs()
        val payload = json.encodeToString(AiChatLog.serializer(), log)
        // Write to a sibling temp file then rename, so a crash / power loss mid-write cannot
        // truncate the real log and lose the whole accumulated history. A same-directory
        // rename is atomic on Android filesystems; fall back to an in-place write if it
        // somehow fails (then the worst case is the pre-existing truncate risk for one write).
        val target = bookRoot.resolve(FILE_NAME)
        val temp = bookRoot.resolve("$FILE_NAME.tmp")
        temp.writeText(payload)
        if (!temp.renameTo(target)) {
            target.writeText(payload)
            temp.delete()
        }
    }

    /** Appends [entry] to the book's history and returns the updated log. */
    suspend fun append(bookRoot: File, entry: AiChatEntry): AiChatLog = appendMutex.withLock {
        val updated = AiChatLog(load(bookRoot).entries + entry)
        save(bookRoot, updated)
        updated
    }

    private companion object {
        const val FILE_NAME = "ai_chat_log.json"

        // The reader and sync each own a store. Serialize their load-modify-write appends
        // together so concurrent replies cannot overwrite one another's history entries.
        val appendMutex = Mutex()
    }
}
