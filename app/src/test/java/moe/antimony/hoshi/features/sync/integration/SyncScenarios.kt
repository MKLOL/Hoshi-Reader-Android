package moe.antimony.hoshi.features.sync.integration

import kotlinx.serialization.json.Json
import moe.antimony.hoshi.features.ai.AiChatEntry
import moe.antimony.hoshi.features.sync.http.HttpSyncSentenceEntry
import moe.antimony.hoshi.features.sync.http.HttpSyncSentencesBlob
import moe.antimony.hoshi.features.sync.http.pretranslationsKey
import moe.antimony.hoshi.features.sync.http.sentencesKey
import org.junit.Assert.assertEquals
import java.io.File

/** Where [publishCorpusLibrary] left the corpus on the publishing device. */
class PublishedLibrary(val manga: File, val novel: File)

/** The chat reply the corpus author saved on the manga's first bubble. */
val CORPUS_CHAT: AiChatEntry = AiChatEntry(SyncCorpus.BUBBLE_TEXT, "prompt", "model", "Hello", 800_000_000.0)

/**
 * Imports the corpus on this device the way a user would, reads a little, keeps one tutor
 * reply, runs one "Sync now", and then publishes the desktop tools' translation blobs — the
 * exact server state every scenario, and the iOS suite's fixture, starts from.
 */
suspend fun SyncDevice.publishCorpusLibrary(server: SyncTestServer): PublishedLibrary {
    val manga = importManga()
    val novel = importNovel()
    repo.saveBookmark(manga, SyncCorpus.bookmark(chapter = 2, appleSeconds = 800_000_000.0))
    repo.saveBookmark(novel, SyncCorpus.bookmark(chapter = 1, appleSeconds = 800_000_000.0))
    history.append(manga, CORPUS_CHAT)

    val first = sync()
    assertEquals("sync errors: ${first.errors}", emptyList<String>(), first.errors)
    assertEquals("both payloads uploaded", 2, first.uploadedPayloads)

    // Produced by the desktop tools, never by a device; every device must receive them.
    val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    server.client().put(pretranslationsKey(SyncCorpus.MANGA_SYNC_ID), "application/json", SyncCorpus.pretranslationsBlobJson().toByteArray())
    val sentences = HttpSyncSentencesBlob(
        kind = "epub", syncId = SyncCorpus.NOVEL_SYNC_ID, title = SyncCorpus.NOVEL_TITLE, model = "m", promptId = "p",
        generatedAt = "2026-09-01T00:00:00Z", spineCount = 1,
        entries = mapOf("c0s0" to HttpSyncSentenceEntry(0, 0, 3, SyncCorpus.NOVEL_SENTENCE, SyncCorpus.sentenceHash(), "To eat.", "Dictionary form.")),
    )
    server.client().put(sentencesKey(SyncCorpus.NOVEL_SYNC_ID), "application/json", json.encodeToString(HttpSyncSentencesBlob.serializer(), sentences).toByteArray())
    return PublishedLibrary(manga, novel)
}
