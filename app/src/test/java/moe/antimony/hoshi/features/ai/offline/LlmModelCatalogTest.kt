package moe.antimony.hoshi.features.ai.offline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-data checks for [LlmModelCatalog]: the download URL is built correctly, [byId] round-trips
 * every catalog model, the default is part of the catalog, and ids are unique (they're persisted
 * keys, so a collision would silently route to the wrong model).
 */
class LlmModelCatalogTest {
    @Test
    fun gemmaDownloadUrlMatchesHuggingFaceFormat() {
        assertEquals(
            "https://huggingface.co/webbigdata/gemma-2-2b-jpn-it-translate-gguf/resolve/main/" +
                "gemma-2-2b-jpn-it-translate-Q8_0.gguf?download=true",
            LlmModelCatalog.GEMMA_TRANSLATE_Q8.downloadUrl,
        )
    }

    @Test
    fun byIdRoundTripsEveryModel() {
        for (model in LlmModelCatalog.ALL) {
            assertEquals(model, LlmModelCatalog.byId(model.id))
        }
    }

    @Test
    fun byIdReturnsNullForUnknownId() {
        assertNull(LlmModelCatalog.byId("not-a-real-model"))
    }

    @Test
    fun defaultIsInAll() {
        assertNotNull(LlmModelCatalog.byId(LlmModelCatalog.DEFAULT.id))
        assertTrue(LlmModelCatalog.ALL.contains(LlmModelCatalog.DEFAULT))
    }

    @Test
    fun modelIdsAreUnique() {
        val ids = LlmModelCatalog.ALL.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
    }
}
