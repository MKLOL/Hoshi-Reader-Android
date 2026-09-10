package moe.antimony.hoshi.features.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlin.coroutines.CoroutineContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AiChatHistoryStoreTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private val store = AiChatHistoryStore()

    @Test
    fun loadReturnsEmptyLogWhenFileMissing() = runBlocking {
        assertEquals(emptyList<AiChatEntry>(), store.load(tempFolder.root).entries)
    }

    @Test
    fun appendPersistsAndRoundTrips() = runBlocking {
        val bookRoot = tempFolder.newFolder("book")
        val first = AiChatEntry(
            bubbleText = "もうすぐだぞー",
            prompt = "Translate:",
            model = "gpt-5.5",
            response = "Almost there!",
            timestampSeconds = 100.0,
            dictionaryLookup = sampleDictionaryLookup(),
        )
        val screenshot = AiChatImage(mimeType = "image/png", base64Data = "iVBORw0KGgo=")
        val second = first.copy(
            bubbleText = "Screenshot translation",
            response = "Panel text.",
            timestampSeconds = 200.0,
            screenshotImage = screenshot,
            dictionaryLookup = null,
        )

        store.append(bookRoot, first)
        val log = store.append(bookRoot, second)

        // append() returns the updated log, newest last...
        assertEquals(listOf(first, second), log.entries)
        // ...and it survives a reload from disk.
        assertEquals(listOf(first, second), store.load(bookRoot).entries)
        assertEquals(sampleDictionaryLookup(), store.load(bookRoot).entries[0].dictionaryLookup)
        assertEquals(screenshot, store.load(bookRoot).entries[1].screenshotImage)
    }

    @Test
    fun loadIgnoresCorruptFile() = runBlocking {
        val bookRoot = tempFolder.newFolder("book")
        bookRoot.resolve("ai_chat_log.json").writeText("{ this is not valid json")
        assertTrue(store.load(bookRoot).entries.isEmpty())
    }

    @Test
    fun loadOlderLogWithoutNewOptionalFieldsDefaultsToNull() = runBlocking {
        val bookRoot = tempFolder.newFolder("book")
        bookRoot.resolve("ai_chat_log.json").writeText(
            """
            {
              "entries": [
                {
                  "bubbleText": "もうすぐだぞー",
                  "prompt": "Translate:",
                  "model": "gpt-5.5",
                  "response": "Almost there!",
                  "timestampSeconds": 100.0
                }
              ]
            }
            """.trimIndent(),
        )

        val entry = store.load(bookRoot).entries.single()
        assertEquals(null, entry.screenshotImage)
        assertEquals(null, entry.dictionaryLookup)
    }

    @Test
    fun concurrentAppendsAllPersist() = runBlocking {
        // append() is a load-modify-write; without serialization, overlapping appends would
        // read the same on-disk log and clobber each other. This drives many appends in
        // parallel and asserts none are lost.
        val bookRoot = tempFolder.newFolder("book")
        val count = 24

        (1..count).map { n ->
            async(Dispatchers.Default) {
                store.append(
                    bookRoot,
                    AiChatEntry(
                        bubbleText = "bubble-$n",
                        prompt = "p",
                        model = "gpt-5.5",
                        response = "response-$n",
                        timestampSeconds = n.toDouble(),
                    ),
                )
            }
        }.awaitAll()

        val persisted = store.load(bookRoot).entries
        assertEquals(count, persisted.size)
        assertEquals(
            (1..count).map { "bubble-$it" }.toSet(),
            persisted.map { it.bubbleText }.toSet(),
        )
    }

    @Test
    fun readerAndSyncStoreAppendsPreserveBothEntries() = runBlocking {
        val bookRoot = tempFolder.newFolder("shared-book")
        val pending = ArrayDeque<Runnable>()
        val ioDispatcher = object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) {
                pending.addLast(block)
            }
        }
        val readerStore = AiChatHistoryStore(ioDispatcher)
        val syncStore = AiChatHistoryStore(ioDispatcher)
        val readerEntry = AiChatEntry("reader", "p", "model", "reader reply", 1.0)
        val syncEntry = AiChatEntry("sync", "p", "model", "synced reply", 2.0)

        val writes = listOf(
            async(start = CoroutineStart.UNDISPATCHED) { readerStore.append(bookRoot, readerEntry) },
            async(start = CoroutineStart.UNDISPATCHED) { syncStore.append(bookRoot, syncEntry) },
        )
        // Drain reads together before their continuations can queue writes. Without a shared
        // lock both stores read an empty log, then overwrite one another deterministically.
        while (writes.any { !it.isCompleted }) {
            while (pending.isNotEmpty()) pending.removeFirst().run()
            yield()
        }
        writes.awaitAll()

        assertEquals(setOf(readerEntry, syncEntry), store.load(bookRoot).entries.toSet())
    }
}

private fun sampleDictionaryLookup(): AiChatDictionaryLookup = AiChatDictionaryLookup(
    query = "もうすぐ",
    results = listOf(
        AiChatDictionaryLookupResult(
            expression = "もうすぐ",
            reading = "",
            matched = "もうすぐ",
            deinflectionTrace = listOf(AiChatDeinflectionStep("plain", "dictionary form")),
            glossaries = listOf(AiChatGlossary("JMdict", "soon", "", "")),
            frequencies = listOf(
                AiChatFrequencyGroup(
                    dictionary = "frequency",
                    frequencies = listOf(AiChatFrequency(50, "rank 50")),
                ),
            ),
            pitches = listOf(AiChatPitchGroup("pitch", listOf(0))),
            rules = listOf("adv"),
        ),
    ),
)
