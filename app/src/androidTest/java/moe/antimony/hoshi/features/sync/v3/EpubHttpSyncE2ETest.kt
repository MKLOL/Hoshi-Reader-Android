package moe.antimony.hoshi.features.sync.v3

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import moe.antimony.hoshi.epub.EpubBookParser
import moe.antimony.hoshi.features.ai.EPUB_TRANSLATIONS_FILENAME
import moe.antimony.hoshi.features.ai.EpubTranslationStore
import moe.antimony.hoshi.features.sync.http.HttpSyncKvClient
import moe.antimony.hoshi.features.sync.http.HttpSyncSentenceEntry
import moe.antimony.hoshi.features.sync.http.HttpSyncSentencesBlob
import moe.antimony.hoshi.features.sync.http.sentencesKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import java.security.MessageDigest

@RunWith(AndroidJUnit4::class)
class EpubHttpSyncE2ETest {
    @get:Rule val temp = TemporaryFolder()

    @Test
    fun syncNowDownloadsIosEpubSentencesAndReaderTranslation() = runBlocking {
        StubKvServer().use { server ->
            server.start()
            val sender = freshDevice(server, "sender", temp.newFolder("sender-root"))
            val receiver = freshDevice(server, "receiver", temp.newFolder("receiver-root"))
            val title = "HTTP EPUB Translation E2E"
            val syncId = "Zenitendou-01"
            val sourceRoot = sender.importEpub(title)
            sender.repo.saveMetadata(
                sourceRoot,
                sender.repo.loadMetadata(sourceRoot)!!.copy(syncId = syncId),
            )

            val upload = sender.sync()
            assertTrue("sender errors: ${upload.errors}", upload.errors.isEmpty())
            assertTrue("canonical EPUB manifest missing", "books/$syncId/epub.manifest" in server.keys())
            assertTrue("canonical EPUB zip missing", "books/$syncId/epub.zip" in server.keys())

            val normalized = "食べる"
            val hash = MessageDigest.getInstance("SHA-256")
                .digest(normalized.toByteArray()).take(8).joinToString("") { "%02x".format(it) }
            val blob = HttpSyncSentencesBlob(
                kind = "epub",
                syncId = syncId,
                title = title,
                model = "simulator-model",
                promptId = "simulator-prompt",
                generatedAt = "2026-08-23T00:00:00Z",
                spineCount = 1,
                entries = mapOf(
                    "c0s0" to HttpSyncSentenceEntry(
                        spine = 0,
                        start = 0,
                        len = 3,
                        text = "食べる。",
                        hash = hash,
                        translation = "To eat.",
                        explanation = "Dictionary form.",
                    ),
                ),
            )
            HttpSyncKvClient(server.baseUrl, server.token).put(
                key = sentencesKey(syncId),
                contentType = "application/json",
                body = Json { encodeDefaults = true }
                    .encodeToString(HttpSyncSentencesBlob.serializer(), blob)
                    .toByteArray(),
            )

            val download = receiver.sync()
            assertTrue("receiver errors: ${download.errors}", download.errors.isEmpty())
            assertEquals(1, download.applied.payloads)
            assertEquals(1, download.applied.sentenceTranslations)
            val received = receiver.repo.loadBookEntries().single()
            assertEquals(syncId, received.metadata.syncId)
            assertTrue(received.root.resolve(EPUB_TRANSLATIONS_FILENAME).isFile)
            val parsed = EpubBookParser().parse(received.root)
            assertEquals(1, parsed.spineCount)
            assertTrue(EpubTranslationStore.preload(received.root, syncId, parsed.spineCount))
            val anchor = EpubTranslationStore.anchors(received.root, syncId, parsed.spineCount, 0).single()
            val translation = EpubTranslationStore.lookup(anchor.id, received.root, syncId, parsed.spineCount)
            assertEquals("To eat.", translation?.translation)
            assertEquals("Dictionary form.", translation?.explanation)
        }
    }
}
