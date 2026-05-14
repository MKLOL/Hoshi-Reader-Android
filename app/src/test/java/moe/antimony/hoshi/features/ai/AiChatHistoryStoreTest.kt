package moe.antimony.hoshi.features.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
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
        )
        val second = first.copy(bubbleText = "おーっ", response = "Oh!", timestampSeconds = 200.0)

        store.append(bookRoot, first)
        val log = store.append(bookRoot, second)

        // append() returns the updated log, newest last...
        assertEquals(listOf(first, second), log.entries)
        // ...and it survives a reload from disk.
        assertEquals(listOf(first, second), store.load(bookRoot).entries)
    }

    @Test
    fun loadIgnoresCorruptFile() = runBlocking {
        val bookRoot = tempFolder.newFolder("book")
        bookRoot.resolve("ai_chat_log.json").writeText("{ this is not valid json")
        assertTrue(store.load(bookRoot).entries.isEmpty())
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
}
