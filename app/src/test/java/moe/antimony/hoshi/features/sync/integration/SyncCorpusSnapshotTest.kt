package moe.antimony.hoshi.features.sync.integration

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import moe.antimony.hoshi.features.sync.http.BOOKMARKS_MAP_PREFIX
import moe.antimony.hoshi.features.sync.http.BOOKS_MAP_KEY
import moe.antimony.hoshi.features.sync.http.HttpSyncPayloadManifest
import moe.antimony.hoshi.features.sync.http.bookmarkKey
import moe.antimony.hoshi.features.sync.http.epubManifestKey
import moe.antimony.hoshi.features.sync.http.epubZipKey
import moe.antimony.hoshi.features.sync.http.metadataKey
import moe.antimony.hoshi.features.sync.http.payloadManifestKey
import moe.antimony.hoshi.features.sync.http.payloadZipKey
import moe.antimony.hoshi.features.sync.http.pretranslationsKey
import moe.antimony.hoshi.features.sync.http.sentencesKey
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.ClassRule
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Publishes [SyncCorpus] through the production Android "Sync now" to the real server and checks
 * the resulting server state, including the bookmark maps every later device reads first. With
 * `HOSHI_SYNC_CORPUS_OUT=<file>` in the environment it also writes the server snapshot that the
 * iOS integration suite (`Tests/Regression/test_sync_integration.py` in the iOS repo) loads, so
 * iOS syncs from zero against exactly what Android publishes.
 *
 * The publishing install uses a fixed installation id and the corpus fixes its metadata ids and
 * import stamp, so a regenerated snapshot differs from the committed one only where content
 * really changed (zip bytes still carry entry timestamps).
 */
class SyncCorpusSnapshotTest {
    companion object {
        @JvmField @ClassRule val serverRule = SyncTestServerRule()
        private const val INSTALLATION_ID = "00000000-0000-4000-8000-00000000c0de"
    }

    @get:Rule val temp = TemporaryFolder()

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private var android: SyncDevice? = null

    @After
    fun closeDevice() {
        android?.close()
    }

    @Test
    fun androidPublishesTheCorpusTheIosSuiteSyncsFrom() = runBlocking {
        val server = serverRule.server
        server.reset()
        val android = SyncDevice("android-origin", SyncEngine.V3, temp.newFolder("android"), server, installationId = INSTALLATION_ID)
            .also { this@SyncCorpusSnapshotTest.android = it }
        val library = android.publishCorpusLibrary(server)

        val etags = server.etags()
        val expectedKeys = setOf(
            payloadZipKey(SyncCorpus.MANGA_SYNC_ID), payloadManifestKey(SyncCorpus.MANGA_SYNC_ID),
            bookmarkKey(SyncCorpus.MANGA_SYNC_ID), metadataKey(SyncCorpus.MANGA_SYNC_ID), pretranslationsKey(SyncCorpus.MANGA_SYNC_ID),
            epubZipKey(SyncCorpus.NOVEL_SYNC_ID), epubManifestKey(SyncCorpus.NOVEL_SYNC_ID),
            bookmarkKey(SyncCorpus.NOVEL_SYNC_ID), metadataKey(SyncCorpus.NOVEL_SYNC_ID), sentencesKey(SyncCorpus.NOVEL_SYNC_ID),
            BOOKS_MAP_KEY,
        )
        for (key in expectedKeys) assertTrue("server holds $key", key in etags)
        val chatKeys = etags.keys.filter { it.startsWith("books/${SyncCorpus.MANGA_SYNC_ID}/chat/") }
        assertEquals("one chat entry", 1, chatKeys.size)
        val shards = etags.keys.filter { it.startsWith(BOOKMARKS_MAP_PREFIX) }
        assertEquals("exactly this install's bookmark shard", listOf("$BOOKMARKS_MAP_PREFIX$INSTALLATION_ID.json"), shards)
        assertEquals("nothing else on the server", expectedKeys + chatKeys + shards, etags.keys)

        val mangaManifest = json.decodeFromString(HttpSyncPayloadManifest.serializer(), server.client().get(payloadManifestKey(SyncCorpus.MANGA_SYNC_ID))!!.body.toString(Charsets.UTF_8))
        assertEquals(etags.getValue(payloadZipKey(SyncCorpus.MANGA_SYNC_ID)), mangaManifest.sha256)
        assertEquals(android.codec.cachedPayloadSha(library.manga), mangaManifest.contentSha256)

        val out = System.getenv("HOSHI_SYNC_CORPUS_OUT")?.takeIf { it.isNotBlank() }
        if (out != null) {
            File(out).apply { parentFile?.mkdirs() }.writeText(server.dumpJson())
        }
        Unit
    }
}
