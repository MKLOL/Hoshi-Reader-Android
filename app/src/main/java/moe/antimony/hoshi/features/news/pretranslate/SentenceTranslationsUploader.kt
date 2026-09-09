package moe.antimony.hoshi.features.news.pretranslate

import moe.antimony.hoshi.features.sync.http.HttpSyncKvTransport
import moe.antimony.hoshi.features.sync.http.sentencesKey

/**
 * Publishes a book's sentence translations to `books/{syncId}/sentences`, the key the sync engines
 * on every platform already download from. Sync itself stays download-only for this key; this is
 * the one deliberate writer, and the bytes match the local sidecar so the importer's byte-equality
 * check recognizes the copy as already installed.
 */
class SentenceTranslationsUploader(private val transport: HttpSyncKvTransport) {
    suspend fun upload(syncId: String, blobBytes: ByteArray) {
        transport.put(sentencesKey(syncId), CONTENT_TYPE, blobBytes)
    }

    companion object {
        const val CONTENT_TYPE = "application/json; charset=utf-8"
    }
}
