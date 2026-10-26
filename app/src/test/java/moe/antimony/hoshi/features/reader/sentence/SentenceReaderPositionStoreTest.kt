package moe.antimony.hoshi.features.reader.sentence

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SentenceReaderPositionStoreTest {
    @get:Rule val temp = TemporaryFolder()

    @Test
    fun remembersOnePositionPerBookAcrossInstances() = runBlocking {
        val dir = temp.newFolder("files")
        val store = SentenceReaderPositionStore(dir, Dispatchers.Unconfined)
        assertNull(store.load("a"))

        store.save("a", SentencePosition(2, 5))
        store.save("b", SentencePosition(0, 1))
        store.save("a", SentencePosition(3, 0))

        val reopened = SentenceReaderPositionStore(dir, Dispatchers.Unconfined)
        assertEquals(SentencePosition(3, 0), reopened.load("a"))
        assertEquals(SentencePosition(0, 1), reopened.load("b"))
        assertNull(reopened.load("c"))
    }

    @Test
    fun aCorruptFileIsTreatedAsEmpty() = runBlocking {
        val dir = temp.newFolder("files")
        dir.resolve("sentence_reader_positions.json").writeText("{not json")
        val store = SentenceReaderPositionStore(dir, Dispatchers.Unconfined)
        assertNull(store.load("a"))
        store.save("a", SentencePosition(1, 1))
        assertEquals(SentencePosition(1, 1), store.load("a"))
    }

    @Test
    fun separateEraCursorsAreMarkedUntilSentenceModeSavesAgain() = runBlocking {
        val dir = temp.newFolder("files")
        dir.resolve("sentence_mode_positions.json").writeText(
            Json.encodeToString(mapOf("a" to SentencePosition(4, 2), "b" to SentencePosition(1, 0))),
        )
        val store = SentenceReaderPositionStore(dir, Dispatchers.Unconfined)
        assertEquals(SavedSentencePosition(SentencePosition(4, 2), separate = true), store.loadSaved("a"))

        store.save("a", SentencePosition(4, 3))
        assertEquals(SavedSentencePosition(SentencePosition(4, 3), separate = false), store.loadSaved("a"))
        assertEquals(SavedSentencePosition(SentencePosition(1, 0), separate = true), store.loadSaved("b"))
    }
}
