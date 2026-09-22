package moe.antimony.hoshi.features.news

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import moe.antimony.hoshi.epub.EpubBookParser
import moe.antimony.hoshi.features.ai.EpubTranslationStore
import moe.antimony.hoshi.features.ai.parseMarkdown
import moe.antimony.hoshi.features.reader.sentence.EpubSentenceSegmenter
import moe.antimony.hoshi.features.reader.sentence.SentenceTranslationSource
import moe.antimony.hoshi.features.sync.http.HttpSyncSentencesBlob
import moe.antimony.hoshi.features.sync.integration.SyncDevice
import moe.antimony.hoshi.features.sync.integration.SyncEngine
import moe.antimony.hoshi.features.sync.integration.SyncTestServerRule
import org.commonmark.ext.gfm.tables.TableBlock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.TimeUnit

/** Drives the actual Python publisher and Android consumers, with no production account/device. */
class NewsPublisherInteropTest {
    @get:Rule val temp = TemporaryFolder()
    @get:Rule val serverRule = SyncTestServerRule()
    private val json = Json { ignoreUnknownKeys = true }
    private val repoRoot: File by lazy {
        generateSequence(File(System.getProperty("user.dir") ?: ".").canonicalFile) { it.parentFile }
            .first { it.resolve("tools/news/hoshi_news.py").isFile }
    }
    private lateinit var job: File

    @Before
    fun prepareSyntheticLesson() {
        job = temp.root.resolve("news-job")
        python("tools/news/tests/make_fixture.py", "--out", job.absolutePath)
    }

    @Test
    fun bothAndroidEnginesDiscoverPublishedNewsAndResolveEveryTutorLesson() = runBlocking {
        val server = serverRule.server
        val expected = json.decodeFromString(HttpSyncSentencesBlob.serializer(), job.resolve("sentence_translations.json").readText())
        for (engine in SyncEngine.entries) {
            server.reset()
            SyncDevice("news-$engine", engine, temp.newFolder("device-$engine"), server).use { device ->
                // An existing map cache must still notice the new per-book keys from the publisher.
                assertEquals(emptyList<String>(), device.sync().errors)
                server.clearRequests()
                python("tools/news/hoshi_news.py", "publish", "--job", job.absolutePath, "--upload")
                assertEquals(
                    listOf("epub.zip", "sentences", "metadata", "epub.manifest"),
                    server.requests().filter { it.method == "PUT" }.map { it.path.substringAfterLast('/') },
                )
                val result = device.sync()
                assertEquals("$engine sync errors", emptyList<String>(), result.errors)
                assertEquals(1, result.downloadedPayloads)
                val received = device.repo.loadBookEntries().single()
                assertEquals(expected.syncId, received.metadata.syncId)
                assertEquals("News", device.shelfNameOf(expected.syncId))
                assertEquals(
                    json.parseToJsonElement(job.resolve("plan.json").readText()).jsonObject.getValue("contentSha256").jsonPrimitive.content,
                    device.codec.computePayloadContentSha(received.root),
                )
                val book = EpubBookParser().parse(received.root)
                assertEquals(1, book.spineCount)
                assertTrue(EpubTranslationStore.preload(received.root, expected.syncId, book.spineCount))
                val chapter = book.chapters.single()
                val sentences = EpubSentenceSegmenter.segment(0, book.readResource(chapter.href)!!.toString(Charsets.UTF_8))
                assertEquals(expected.entries.keys.toList(), sentences.map { it.id })
                val source = SentenceTranslationSource(received.root, expected.syncId, book.spineCount)
                for (sentence in sentences) {
                    val lesson = requireNotNull(source.translationFor(sentence))
                    assertEquals(expected.entries.getValue(sentence.id).translation, lesson.translation)
                    assertEquals(expected.entries.getValue(sentence.id).explanation, lesson.explanation)
                    // The actual popup Markdown parser must recognize the vocabulary table.
                    val tables = generateSequence(parseMarkdown(lesson.markdownResponse).firstChild) { it.next }
                        .count { it is TableBlock }
                    assertEquals(1, tables)
                }
                val followUp = device.sync()
                assertEquals(emptyList<String>(), followUp.errors)
                assertEquals(0, followUp.transferredPayloads)
                server.clearRequests()
                python("tools/news/hoshi_news.py", "publish", "--job", job.absolutePath, "--upload")
                assertEquals("retry after Android sync must preserve its metadata", 0, server.requests().count { it.method == "PUT" })
            }
        }
    }

    @Test
    fun pythonSentenceAddressesAndHashesMatchTheActualAndroidSegmenter() {
        val vectors = json.parseToJsonElement(job.resolve("segmentation-vectors.json").readText()).jsonArray
        for (vector in vectors) {
            val html = vector.jsonObject.getValue("html").jsonPrimitive.content
            val expected = vector.jsonObject.getValue("sentences").jsonArray
            val actual = EpubSentenceSegmenter.segment(3, html)
            assertEquals("sentence count for $html", expected.size, actual.size)
            actual.zip(expected).forEach { (sentence, entry) ->
                val fields = entry.jsonObject
                assertEquals(fields.getValue("id").jsonPrimitive.content, sentence.id)
                assertEquals(fields.getValue("len").jsonPrimitive.content.toInt(), sentence.length)
                assertEquals(fields.getValue("text").jsonPrimitive.content, sentence.text)
                assertEquals(
                    fields.getValue("hash").jsonPrimitive.content,
                    EpubTranslationStore.textHash(EpubTranslationStore.normalize(sentence.text)),
                )
            }
        }
    }

    private fun python(vararg arguments: String) {
        val output = temp.newFile()
        val process = ProcessBuilder(listOf(System.getenv("HOSHI_PYTHON") ?: "python3") + arguments)
            .directory(repoRoot)
            .redirectErrorStream(true)
            .redirectOutput(output)
            .apply {
                environment()["HOSHI_KV_BASE_URL"] = serverRule.server.baseUrl
                environment()["HOSHI_KV_TOKEN"] = serverRule.server.token
            }.start()
        try {
            assertTrue("Python publisher timed out", process.waitFor(45, TimeUnit.SECONDS))
            assertEquals(output.readText(), 0, process.exitValue())
        } finally {
            if (process.isAlive) process.destroyForcibly()
        }
    }
}
