package moe.antimony.hoshi.features.ai

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * Runs the real offline-translation lookup on a real Android runtime, against the real blob that
 * the desktop pipeline uploaded for Yotsuba&! vol 4 (`app/src/androidTest/assets/`).
 *
 * The unit tests only ever saw hand-written fixtures. This is the path a bubble tap actually
 * takes: parse ~750 KB of JSON produced by a different toolchain, resolve a mokuro address, and
 * verify the source hash — the check that decides whether a cached answer is trusted at all.
 */
class PretranslationStoreDeviceTest {

    private lateinit var bookRoot: File

    private companion object {
        const val FIXTURE = "pretranslations_y4.json"
    }

    @Before
    fun setUp() {
        // The fixture is a real uploaded blob — translated manga dialogue — so it is deliberately
        // NOT committed. Drop it in app/src/androidTest/assets/ to run these tests locally.
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        assumeTrue(
            "pretranslations_y4.json fixture not present - skipping",
            assets.list("")?.contains(FIXTURE) == true,
        )
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        bookRoot = File(context.cacheDir, "pretranslation-device-test").apply {
            deleteRecursively()
            mkdirs()
        }
        // Exactly what the sync reconciler writes into a book directory.
        assets.open(FIXTURE).use { input ->
                File(bookRoot, PRETRANSLATIONS_FILENAME).outputStream().use(input::copyTo)
            }
        PretranslationStore.invalidateAll()
    }

    @After
    fun tearDown() {
        PretranslationStore.invalidateAll()
        bookRoot.deleteRecursively()
    }

    @Test
    fun realBlobParsesOnDevice() {
        assertTrue("blob should be recognised", PretranslationStore.hasPretranslations(bookRoot))
        assertEquals(1532, PretranslationStore.count(bookRoot))
    }

    @Test
    fun tappingABubbleResolvesByMokuroAddress() {
        // The exact address + OCR text the reader hands over from `data-hoshi-block`.
        val hit = PretranslationStore.lookup(bookRoot, "p0b1", "発行メディアワークス")
        assertNotNull("address lookup must hit", hit)
        assertTrue(hit!!.translation.isNotBlank())
        assertEquals("発行メディアワークス", hit.bubbleText)
        assertEquals("claude", hit.model)
    }

    @Test
    fun aStaleAddressFallsBackToTheTextItself() {
        // Address the blob has never heard of: the text hash must still find the entry, which is
        // what protects the feature against a re-OCR renumbering the blocks.
        val hit = PretranslationStore.lookup(bookRoot, "p9999b42", "発行メディアワークス")
        assertNotNull("hash fallback must hit", hit)
        assertEquals("発行メディアワークス", hit!!.bubbleText)
    }

    @Test
    fun anAddressWhoseTextChangedIsNotTrusted() {
        // Right address, different bubble text — serving the stored answer here would show a
        // translation for a neighbouring line. It must miss instead.
        val hit = PretranslationStore.lookup(bookRoot, "p0b1", "まったく違う文章です")
        assertNull("a mismatched bubble must not resolve", hit)
    }

    @Test
    fun anUnknownBubbleMisses() {
        assertNull(PretranslationStore.lookup(bookRoot, "p0b0", "これは存在しない吹き出しです"))
    }

    @Test
    fun theExplanationCarriesTheFuriganaTable() {
        val hit = PretranslationStore.lookup(bookRoot, "p100b0", "よつばとＺコマ")
        assertNotNull(hit)
        // The user's prompt asks for a word table then grammar; the popup renders it as Markdown.
        assertTrue(
            "explanation should hold the word table: ${hit!!.explanation.take(120)}",
            hit.explanation.contains("| Word |") || hit.explanation.contains("Grammar"),
        )
        assertTrue(hit.markdownResponse.startsWith(hit.translation))
    }

    @Test
    fun aMissingBlobIsNotAnError() {
        val empty = File(bookRoot.parentFile, "pretranslation-empty").apply {
            deleteRecursively(); mkdirs()
        }
        assertNull(PretranslationStore.lookup(empty, "p0b0", "おはよう"))
        assertEquals(0, PretranslationStore.count(empty))
        empty.deleteRecursively()
    }
}
