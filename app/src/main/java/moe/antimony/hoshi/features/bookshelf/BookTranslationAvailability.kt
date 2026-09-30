package moe.antimony.hoshi.features.bookshelf

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import moe.antimony.hoshi.epub.BookEntry
import moe.antimony.hoshi.epub.ContentType
import moe.antimony.hoshi.epub.bookContentType
import moe.antimony.hoshi.features.ai.EPUB_TRANSLATIONS_FILENAME
import moe.antimony.hoshi.features.ai.EpubTranslationStore
import moe.antimony.hoshi.features.ai.PRETRANSLATIONS_FILENAME
import moe.antimony.hoshi.features.ai.PretranslationStore
import moe.antimony.hoshi.features.sync.http.HttpSyncSentencesBlob
import moe.antimony.hoshi.features.sync.http.MAX_EPUB_SENTENCES_BLOB_BYTES
import moe.antimony.hoshi.features.sync.http.PretranslationsBlob
import moe.antimony.hoshi.features.sync.http.syncIdForMetadata
import java.nio.file.Files
import java.nio.file.attribute.BasicFileAttributes

/** A small availability cache, not a second in-memory copy of every book's translations. */
internal object BookTranslationAvailability {
    private val json = Json { ignoreUnknownKeys = true }
    private data class Cached(val stamp: List<Any?>, val available: Boolean)
    private val cache = object : LinkedHashMap<String, Cached>(128, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Cached>?) = size > 128
    }

    suspend fun load(entries: List<BookEntry>): Set<String> = withContext(Dispatchers.IO) {
        entries.filter { hasTranslations(it) }.mapTo(mutableSetOf()) { it.metadata.id }
    }

    @Synchronized
    internal fun hasTranslations(entry: BookEntry): Boolean {
        val manga = bookContentType(entry.root) == ContentType.Mokuro
        val file = entry.root.resolve(if (manga) PRETRANSLATIONS_FILENAME else EPUB_TRANSLATIONS_FILENAME)
        val path = file.absolutePath
        val attributes = runCatching { Files.readAttributes(file.toPath(), BasicFileAttributes::class.java) }
            .getOrNull()
        if (attributes == null || !attributes.isRegularFile || attributes.size() > MAX_EPUB_SENTENCES_BLOB_BYTES) {
            cache.remove(path)
            return false
        }
        val syncId = syncIdForMetadata(entry.metadata)
        val stamp = listOf(attributes.lastModifiedTime(), attributes.size(), attributes.fileKey(), syncId)
        cache[path]?.takeIf { it.stamp == stamp }?.let { return it.available }
        val available = runCatching {
            val body = file.readText()
            if (manga) {
                val blob = json.decodeFromString(PretranslationsBlob.serializer(), body)
                blob.version in 1..PretranslationStore.SUPPORTED_BLOB_VERSION &&
                    (blob.syncId.isEmpty() || blob.syncId == syncId) &&
                    blob.entries.values.any { it.text.isNotBlank() && it.translation.isNotBlank() }
            } else {
                val blob = json.decodeFromString(HttpSyncSentencesBlob.serializer(), body)
                // Sync validates spine count against the EPUB before installing this sidecar.
                // The shelf checks availability without opening/parsing every EPUB again.
                EpubTranslationStore.validationError(blob, syncId.orEmpty(), blob.spineCount) == null
            }
        }.getOrDefault(false)
        cache[path] = Cached(stamp, available)
        return available
    }
}
