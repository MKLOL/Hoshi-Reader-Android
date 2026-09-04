package moe.antimony.hoshi.features.sync.integration

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import moe.antimony.hoshi.features.ai.AiChatEntry
import moe.antimony.hoshi.features.sync.http.HttpSyncPayloadManifest
import moe.antimony.hoshi.features.sync.http.HttpSyncSentenceEntry
import moe.antimony.hoshi.features.sync.http.HttpSyncSentencesBlob
import moe.antimony.hoshi.features.sync.http.bookmarkKey
import moe.antimony.hoshi.features.sync.http.epubManifestKey
import moe.antimony.hoshi.features.sync.http.epubZipKey
import moe.antimony.hoshi.features.sync.http.metadataKey
import moe.antimony.hoshi.features.sync.http.payloadManifestKey
import moe.antimony.hoshi.features.sync.http.payloadZipKey
import moe.antimony.hoshi.features.sync.http.pretranslationsKey
import moe.antimony.hoshi.features.sync.http.sentencesKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.ClassRule
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Publishes [SyncCorpus] through the real Android engine to the real server and checks the
 * resulting server state. With `HOSHI_SYNC_CORPUS_OUT=<file>` in the environment it also writes
 * the server snapshot that the iOS integration suite (`Tests/Regression/test_sync_integration.py`
 * in the iOS repo) loads, so iOS syncs from zero against exactly what Android publishes.
 */
class SyncCorpusSnapshotTest {
    companion object {
        @JvmField @ClassRule val serverRule = SyncTestServerRule()
    }

    @get:Rule val temp = TemporaryFolder()

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test
    fun androidPublishesTheCorpusTheIosSuiteSyncsFrom() = runBlocking {
        val server = serverRule.server
        server.reset()
        val android = SyncDevice("android-origin", SyncEngine.V3, temp.newFolder("android"), server)
        val manga = SyncCorpus.importManga(android.repo)
        val novel = SyncCorpus.importNovel(android.repo)
        android.repo.saveBookmark(manga, SyncCorpus.bookmark(chapter = 2, appleSeconds = 800_000_000.0))
        android.repo.saveBookmark(novel, SyncCorpus.bookmark(chapter = 1, appleSeconds = 800_000_000.0))
        android.history.append(manga, AiChatEntry(SyncCorpus.BUBBLE_TEXT, "prompt", "model", "Hello", 800_000_000.0))

        val outcome = android.sync()
        assertEquals(emptyList<String>(), outcome.errors)
        assertEquals(2, outcome.uploadedPayloads)

        server.client().put(
            pretranslationsKey(SyncCorpus.MANGA_SYNC_ID), "application/json",
            """{"kind":"mokuro","syncId":"${SyncCorpus.MANGA_SYNC_ID}","entries":{"p0b0":{"text":"${SyncCorpus.BUBBLE_TEXT}","translation":"Hello"}}}""".toByteArray(),
        )
        val sentences = HttpSyncSentencesBlob(
            kind = "epub", syncId = SyncCorpus.NOVEL_SYNC_ID, title = SyncCorpus.NOVEL_TITLE, model = "m", promptId = "p",
            generatedAt = "2026-09-01T00:00:00Z", spineCount = 1,
            entries = mapOf("c0s0" to HttpSyncSentenceEntry(0, 0, 3, SyncCorpus.NOVEL_SENTENCE, SyncCorpus.sentenceHash(), "To eat.", "Dictionary form.")),
        )
        server.client().put(sentencesKey(SyncCorpus.NOVEL_SYNC_ID), "application/json", json.encodeToString(HttpSyncSentencesBlob.serializer(), sentences).toByteArray())

        val etags = server.etags()
        for (key in listOf(
            payloadZipKey(SyncCorpus.MANGA_SYNC_ID), payloadManifestKey(SyncCorpus.MANGA_SYNC_ID), bookmarkKey(SyncCorpus.MANGA_SYNC_ID), metadataKey(SyncCorpus.MANGA_SYNC_ID),
            epubZipKey(SyncCorpus.NOVEL_SYNC_ID), epubManifestKey(SyncCorpus.NOVEL_SYNC_ID), bookmarkKey(SyncCorpus.NOVEL_SYNC_ID), metadataKey(SyncCorpus.NOVEL_SYNC_ID),
        )) {
            assertTrue("server holds $key", key in etags)
        }
        val mangaManifest = json.decodeFromString(HttpSyncPayloadManifest.serializer(), server.client().get(payloadManifestKey(SyncCorpus.MANGA_SYNC_ID))!!.body.toString(Charsets.UTF_8))
        assertEquals(etags.getValue(payloadZipKey(SyncCorpus.MANGA_SYNC_ID)), mangaManifest.sha256)
        assertEquals(android.codec.cachedPayloadSha(manga), mangaManifest.contentSha256)

        val out = System.getenv("HOSHI_SYNC_CORPUS_OUT")?.takeIf { it.isNotBlank() }
        if (out != null) {
            File(out).apply { parentFile?.mkdirs() }.writeText(server.dumpJson())
        }
        Unit
    }
}
