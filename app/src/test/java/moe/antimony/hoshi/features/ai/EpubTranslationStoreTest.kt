package moe.antimony.hoshi.features.ai

import kotlinx.serialization.json.Json
import moe.antimony.hoshi.features.sync.http.HttpSyncSentenceEntry
import moe.antimony.hoshi.features.sync.http.HttpSyncSentencesBlob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class EpubTranslationStoreTest {
    @get:Rule val temp = TemporaryFolder()

    private val json = Json { encodeDefaults = true }

    @Test
    fun validatedBlobBuildsHashBoundAnchorAndLookup() {
        val root = temp.newFolder("book")
        val normalized = EpubTranslationStore.normalize(" 食べる。 ")
        val blob = validBlob(
            text = " 食べる。 ",
            len = 3,
            hash = EpubTranslationStore.textHash(normalized),
        )
        root.resolve(EPUB_TRANSLATIONS_FILENAME).writeText(
            json.encodeToString(HttpSyncSentencesBlob.serializer(), blob),
        )

        assertTrue(EpubTranslationStore.preload(root, "test_book", 1))
        val anchor = EpubTranslationStore.anchors(root, "test_book", 1, 0).single()
        assertEquals(normalized, anchor.text)
        assertTrue(anchor.id.startsWith("c0s0#"))
        val resolved = EpubTranslationStore.lookup(anchor.id, root, "test_book", 1)
        assertNotNull(resolved)
        assertEquals("To eat.", resolved!!.translation)
        assertNull(EpubTranslationStore.lookup("c0s0#0000000000000000", root, "test_book", 1))
    }

    @Test
    fun validationRejectsEveryAddressingTrustBoundary() {
        val valid = validBlob()
        val invalid = listOf(
            valid.copy(version = 0),
            valid.copy(version = 2),
            valid.copy(kind = "mokuro"),
            valid.copy(syncId = "another"),
            valid.copy(spineCount = 2),
            valid.copy(entries = emptyMap()),
            valid.copy(entries = mapOf("wrong" to valid.entries.getValue("c0s0"))),
            valid.copy(entries = mapOf("c0s0" to valid.entries.getValue("c0s0").copy(spine = 1))),
            valid.copy(entries = mapOf("c0s0" to valid.entries.getValue("c0s0").copy(len = 4))),
            valid.copy(entries = mapOf("c0s0" to valid.entries.getValue("c0s0").copy(hash = "bad"))),
            valid.copy(entries = mapOf("c0s0" to valid.entries.getValue("c0s0").copy(translation = "  "))),
        )
        invalid.forEach { blob ->
            assertThrows(IllegalArgumentException::class.java) {
                EpubTranslationStore.decodeAndValidate(
                    json.encodeToString(HttpSyncSentencesBlob.serializer(), blob),
                    "test_book",
                    1,
                )
            }
        }
    }

    @Test
    fun supplementaryPlaneIdeographCountsAsOneWireCharacter() {
        val supplementaryIdeograph = String(Character.toChars(0x20000))
        assertEquals(supplementaryIdeograph, EpubTranslationStore.normalize(" $supplementaryIdeograph。"))
        val blob = validBlob(
            text = supplementaryIdeograph,
            len = 1,
            hash = EpubTranslationStore.textHash(supplementaryIdeograph),
        )
        val decoded = EpubTranslationStore.decodeAndValidate(
            json.encodeToString(HttpSyncSentencesBlob.serializer(), blob),
            "test_book",
            1,
        )
        assertEquals(1, decoded.entries.getValue("c0s0").len)
    }

    private fun validBlob(
        text: String = "食べる。",
        len: Int = 3,
        hash: String = EpubTranslationStore.textHash(EpubTranslationStore.normalize(text)),
    ) = HttpSyncSentencesBlob(
        kind = "epub",
        syncId = "test_book",
        title = "Test Book",
        model = "test-model",
        promptId = "prompt",
        generatedAt = "2026-08-23T00:00:00Z",
        spineCount = 1,
        entries = mapOf(
            "c0s0" to HttpSyncSentenceEntry(
                spine = 0,
                start = 0,
                len = len,
                text = text,
                hash = hash,
                translation = "To eat.",
                explanation = "Dictionary form.",
            ),
        ),
    )
}
