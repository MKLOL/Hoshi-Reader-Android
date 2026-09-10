package moe.antimony.hoshi.features.news.pretranslate

import java.io.File
import moe.antimony.hoshi.epub.EpubBookParser
import moe.antimony.hoshi.features.ai.EPUB_TRANSLATIONS_FILENAME
import moe.antimony.hoshi.features.ai.EpubTranslationStore
import moe.antimony.hoshi.features.news.NewsArticleEpubWriter
import moe.antimony.hoshi.features.reader.sentence.SentenceTranslationSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SentenceTranslationsWriterTest {
    @get:Rule val temp = TemporaryFolder()

    private fun articleBook(): File {
        val root = temp.newFolder("article")
        NewsArticleEpubWriter.write(
            root,
            NewsArticleEpubWriter.Input(
                title = "見出し",
                bodyXhtml = "<p><ruby>東京<rt>とうきょう</rt></ruby>で雨が降りました。</p><p>二つ目の文です。</p>",
                sourceName = "Source",
                sourceUrl = "https://s/1",
            ),
        )
        return root
    }

    private fun plan(root: File, config: PretranslationConfig = PretranslationConfig(PretranslationEngine.Cloud("gpt-4o-mini"))) =
        PretranslationPlanner.plan("book", "見出し-sync", root, config)

    @Test
    fun blobPassesTheSyncImporterValidationAndIsReadableByTheReaderStore() {
        val root = articleBook()
        val plan = plan(root)
        val translations = plan.sentences.associate { it.id to SentenceTranslation(it.id, "EN ${it.text}", "note") }

        val blob = SentenceTranslationsWriter.buildBlob(plan, translations, model = "gpt-4o-mini", promptId = SentenceBatchPrompt.promptId(true))

        assertNull(SentenceTranslationsWriter.validationError(blob))
        assertEquals("epub", blob.kind)
        assertEquals(1, blob.spineCount)
        assertEquals(plan.sentences.size, blob.entries.size)
        blob.entries.forEach { (id, entry) -> assertEquals(id, "c${entry.spine}s${entry.start}") }

        val bytes = SentenceTranslationsWriter.encode(blob)
        SentenceTranslationsWriter.write(root, bytes)
        assertTrue(File(root, EPUB_TRANSLATIONS_FILENAME).isFile)

        val parsed = EpubBookParser().parse(root)
        assertTrue(EpubTranslationStore.preload(root, plan.syncId, parsed.spineCount))
        val rainSentence = plan.sentences.first { it.text.startsWith("東京") }
        val lookup = SentenceTranslationSource(root, plan.syncId, parsed.spineCount).translationFor(rainSentence)
        assertNotNull(lookup)
        assertEquals("EN 東京で雨が降りました。", lookup!!.translation)
        assertEquals("note", lookup.explanation)
        assertEquals("gpt-4o-mini", lookup.model)
        assertEquals(1, EpubTranslationStore.anchors(root, plan.syncId, parsed.spineCount, spine = 0).count { it.text == "東京で雨が降りました" })
    }

    @Test
    fun sentencesWithoutATranslationAreLeftOutAndTheBlobIsStillValid() {
        val root = articleBook()
        val plan = plan(root)
        val one = plan.sentences.last()
        val blob = SentenceTranslationsWriter.buildBlob(plan, mapOf(one.id to SentenceTranslation(one.id, "only")), "m", "p")
        assertEquals(setOf(one.id), blob.entries.keys)
        assertNull(SentenceTranslationsWriter.validationError(blob))
    }

    @Test
    fun sidecarForAnotherConfigurationIsDetectedSoPartialsNeverReplaceIt() {
        val root = articleBook()
        val plan = plan(root)
        assertTrue(!SentenceTranslationsWriter.hasSidecarForOtherConfiguration(root, plan.syncId, "m", "p"))
        val one = plan.sentences.last()
        SentenceTranslationsWriter.write(root, SentenceTranslationsWriter.encode(SentenceTranslationsWriter.buildBlob(plan, mapOf(one.id to SentenceTranslation(one.id, "x")), "m", "p")))
        assertTrue(!SentenceTranslationsWriter.hasSidecarForOtherConfiguration(root, plan.syncId, "m", "p"))
        assertTrue(SentenceTranslationsWriter.hasSidecarForOtherConfiguration(root, plan.syncId, "other", "p"))
        assertTrue(SentenceTranslationsWriter.hasSidecarForOtherConfiguration(root, plan.syncId, "m", "other"))
        assertTrue(!SentenceTranslationsWriter.hasSidecarForOtherConfiguration(root, "different-sync", "other", "p"))
    }

    @Test
    fun anEmptyBlobIsRejected() {
        val root = articleBook()
        val blob = SentenceTranslationsWriter.buildBlob(plan(root), emptyMap(), "m", "p")
        assertEquals("no entries", SentenceTranslationsWriter.validationError(blob))
    }

    @Test
    fun partialRerunKeepsPreviousConfigurationUntilReplacementIsComplete() {
        val root = articleBook()
        val plan = plan(root)
        val translations = plan.sentences.associate { it.id to SentenceTranslation(it.id, "original") }
        val original = SentenceTranslationsWriter.buildBlob(plan, translations, "old-model", "p")
        assertTrue(SentenceTranslationsWriter.writeResult(root, original, plan.sentences.size))
        val file = File(root, EPUB_TRANSLATIONS_FILENAME)
        val originalBytes = file.readText()
        val partial = SentenceTranslationsWriter.buildBlob(plan, translations.entries.take(1).associate { it.toPair() }, "new-model", "p")

        assertTrue(!SentenceTranslationsWriter.writeResult(root, partial, plan.sentences.size))
        assertEquals(originalBytes, file.readText())

        val complete = SentenceTranslationsWriter.buildBlob(plan, translations, "new-model", "p")
        assertTrue(SentenceTranslationsWriter.writeResult(root, complete, plan.sentences.size))
        assertEquals(SentenceTranslationsWriter.encode(complete).decodeToString(), file.readText())
    }

    @Test
    fun partialResultForSameConfigurationCanStillBeSaved() {
        val root = articleBook()
        val plan = plan(root)
        val first = plan.sentences.first()
        val partial = SentenceTranslationsWriter.buildBlob(plan, mapOf(first.id to SentenceTranslation(first.id, "saved")), "m", "p")

        assertTrue(SentenceTranslationsWriter.writeResult(root, partial, plan.sentences.size))
        assertEquals(setOf(first.id), SentenceTranslationsWriter.existingTranslations(root, plan, "m", "p").keys)
    }

    @Test
    fun existingTranslationsAreReusedOnlyWhenTheSentenceTextStillMatches() {
        val root = articleBook()
        val plan = plan(root)
        val first = plan.sentences.first()
        val second = plan.sentences[1]
        val blob = SentenceTranslationsWriter.buildBlob(
            plan,
            mapOf(first.id to SentenceTranslation(first.id, "keep"), second.id to SentenceTranslation(second.id, "drop")),
            "m", "p",
        )
        // Corrupt the second entry's hash as if the article text had changed since it was written.
        val tampered = blob.copy(entries = blob.entries.mapValues { (id, entry) -> if (id == second.id) entry.copy(hash = "0000000000000000") else entry })
        File(root, EPUB_TRANSLATIONS_FILENAME).writeBytes(SentenceTranslationsWriter.encode(tampered))

        val existing = SentenceTranslationsWriter.existingTranslations(root, plan, "m", "p")

        assertEquals(mapOf(first.id to SentenceTranslation(first.id, "keep", "")), existing)
        assertTrue(SentenceTranslationsWriter.existingTranslations(root, plan.copy(syncId = "other"), "m", "p").isEmpty())
        // A different model or prompt (notes on/off) must not reuse the old entries.
        assertTrue(SentenceTranslationsWriter.existingTranslations(root, plan, "other-model", "p").isEmpty())
        assertTrue(SentenceTranslationsWriter.existingTranslations(root, plan, "m", "other-prompt").isEmpty())
    }
}
