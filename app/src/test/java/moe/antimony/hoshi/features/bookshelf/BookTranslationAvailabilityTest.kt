package moe.antimony.hoshi.features.bookshelf

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import moe.antimony.hoshi.epub.BookEntry
import moe.antimony.hoshi.epub.BookMetadata
import moe.antimony.hoshi.epub.BookRepository
import moe.antimony.hoshi.epub.BookSortOption
import moe.antimony.hoshi.epub.MOKURO_SIDECAR_FILE
import moe.antimony.hoshi.features.ai.EPUB_TRANSLATIONS_FILENAME
import moe.antimony.hoshi.features.ai.EpubTranslationStore
import moe.antimony.hoshi.features.ai.PRETRANSLATIONS_FILENAME
import moe.antimony.hoshi.features.ai.PretranslationStore
import moe.antimony.hoshi.features.sync.http.HttpSyncSentenceEntry
import moe.antimony.hoshi.features.sync.http.HttpSyncSentencesBlob
import moe.antimony.hoshi.features.sync.http.MAX_EPUB_SENTENCES_BLOB_BYTES
import moe.antimony.hoshi.features.sync.http.PretranslationEntryBlob
import moe.antimony.hoshi.features.sync.http.PretranslationsBlob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.FileTime

class BookTranslationAvailabilityTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    private val json = Json { encodeDefaults = true }

    @Test
    fun mixedLibraryReportsLocalIdsOnlyForBooksWithReadableTranslations() = runBlocking {
        val epub = book("local-epub", "remote-epub")
        val manga = book("local-manga", "remote-manga", manga = true)
        val untranslated = book("untranslated")
        writeEpub(epub, epubBlob("remote-epub"))
        writeManga(manga, mangaBlob("remote-manga"))

        assertEquals(setOf("local-epub", "local-manga"), BookTranslationAvailability.load(listOf(epub, manga, untranslated)))
        // Use the reader stores too: badges describe translations that the reader can resolve.
        assertTrue(EpubTranslationStore.preload(epub.root, "remote-epub", 1))
        val sentence = EpubTranslationStore.anchors(epub.root, "remote-epub", 1, 0).single()
        assertNotNull(EpubTranslationStore.lookup(sentence.id, epub.root, "remote-epub", 1))
        assertNotNull(PretranslationStore.lookup(manga.root, "p0b0", "食べる。"))
        assertTrue(BookTranslationAvailability.load(emptyList()).isEmpty())
    }

    @Test
    fun bookshelfReloadPublishesNewlyArrivedAndRemovedTranslations() = runBlocking {
        val repository = BookRepository(temporaryFolder.newFolder("library"))
        val root = repository.booksDirectory.resolve("book").apply { mkdirs() }
        val localId = "00000000-0000-4000-8000-000000000001"
        val metadata = BookMetadata(localId, "Book", null, "book", 0.0, "remote-book")
        repository.saveMetadata(root, metadata)
        suspend fun reload() = BookTranslationAvailability.load(
            loadBookshelfResult(repository, BookSortOption.Recent, BookshelfSettings()).entries,
        )

        assertTrue(reload().isEmpty())
        val translations = writeEpub(BookEntry(root, metadata), epubBlob("remote-book"))
        assertEquals(setOf(localId), reload())
        assertTrue(translations.delete())
        assertTrue(reload().isEmpty())
    }

    @Test
    fun absentEmptyMalformedAndNonFileSidecarsDoNotShowBadges() {
        for (manga in listOf(false, true)) {
            val entry = book("missing-$manga", manga = manga)
            val file = sidecar(entry, manga)
            assertFalse(BookTranslationAvailability.hasTranslations(entry))
            file.writeText("")
            assertFalse(BookTranslationAvailability.hasTranslations(entry))
            file.writeText("not json")
            assertFalse(BookTranslationAvailability.hasTranslations(entry))
            assertTrue(file.delete())
            assertTrue(file.mkdir())
            assertFalse(BookTranslationAvailability.hasTranslations(entry))
        }
    }

    @Test
    fun epubBadgesRejectUnsupportedEmptyWrongBookAndUnusableSentenceData() {
        val valid = epubBlob("remote")
        val sentence = valid.entries.getValue("c0s0")
        val invalid = listOf(
            valid.copy(version = 0),
            valid.copy(version = HttpSyncSentencesBlob.SUPPORTED_VERSION + 1),
            valid.copy(kind = "mokuro"),
            valid.copy(syncId = "another-book"),
            valid.copy(syncId = ""),
            valid.copy(entries = emptyMap()),
            valid.copy(spineCount = 0),
            valid.copy(entries = mapOf("c0s0" to sentence.copy(hash = "wrong"))),
            valid.copy(entries = mapOf("c0s0" to sentence.copy(translation = "  "))),
            valid.copy(entries = mapOf("c0s0" to sentence.copy(spine = 1))),
        )
        invalid.forEachIndexed { index, blob ->
            val entry = book("invalid-epub-$index", "remote")
            writeEpub(entry, blob)
            assertFalse("Invalid EPUB fixture $index", BookTranslationAvailability.hasTranslations(entry))
        }
    }

    @Test
    fun mangaBadgesRejectUnsupportedEmptyWrongBookAndBlankTranslations() {
        val valid = mangaBlob("remote")
        val bubble = valid.entries.getValue("p0b0")
        val invalid = listOf(
            valid.copy(version = 0),
            valid.copy(version = PretranslationStore.SUPPORTED_BLOB_VERSION + 1),
            valid.copy(syncId = "another-book"),
            valid.copy(entries = emptyMap()),
            valid.copy(entries = mapOf("p0b0" to bubble.copy(text = " "))),
            valid.copy(entries = mapOf("p0b0" to bubble.copy(translation = "\n"))),
        )
        invalid.forEachIndexed { index, blob ->
            val entry = book("invalid-manga-$index", "remote", manga = true)
            writeManga(entry, blob)
            assertFalse("Invalid manga fixture $index", BookTranslationAvailability.hasTranslations(entry))
        }
    }

    @Test
    fun legacyMangaWithoutEmbeddedSyncIdAndPartiallyTranslatedMangaRemainAvailable() {
        val entry = book("legacy-manga", manga = true)
        val translated = mangaBlob("")
        writeManga(entry, translated.copy(entries = translated.entries + ("p1b0" to PretranslationEntryBlob())))

        assertTrue(BookTranslationAvailability.hasTranslations(entry))
        assertNotNull(PretranslationStore.lookup(entry.root, "p0b0", "食べる。"))
    }

    @Test
    fun aSidecarForTheOtherBookFormatDoesNotShowABadge() {
        val epub = book("epub")
        val manga = book("manga", manga = true)
        writeManga(epub, mangaBlob("epub"))
        writeEpub(manga, epubBlob("manga"))

        assertFalse(BookTranslationAvailability.hasTranslations(epub))
        assertFalse(BookTranslationAvailability.hasTranslations(manga))
    }

    @Test
    fun creationUpdatesAndDeletionRefreshPreviouslyObservedAvailability() {
        for (manga in listOf(false, true)) {
            val entry = book("updated-$manga", manga = manga)
            val file = sidecar(entry, manga)
            assertFalse(BookTranslationAvailability.hasTranslations(entry))
            if (manga) writeManga(entry, mangaBlob(entry.metadata.syncId!!))
            else writeEpub(entry, epubBlob(entry.metadata.syncId!!))
            assertTrue(BookTranslationAvailability.hasTranslations(entry))

            val previousStamp = Files.getLastModifiedTime(file.toPath())
            file.writeText("{}")
            Files.setLastModifiedTime(file.toPath(), FileTime.fromMillis(previousStamp.toMillis() + 10_000))
            assertFalse(BookTranslationAvailability.hasTranslations(entry))
            assertTrue(file.delete())
            assertFalse(BookTranslationAvailability.hasTranslations(entry))

            if (manga) writeManga(entry, mangaBlob(entry.metadata.syncId!!))
            else writeEpub(entry, epubBlob(entry.metadata.syncId!!))
            assertTrue(BookTranslationAvailability.hasTranslations(entry))
        }
    }

    @Test
    fun atomicReplacementRefreshesCacheEvenWithTheSameSizeAndModificationTime() {
        for (manga in listOf(false, true)) {
            val entry = book("replacement-$manga", manga = manga)
            val file = if (manga) writeManga(entry, mangaBlob(entry.metadata.syncId!!, "Ready"))
            else writeEpub(entry, epubBlob(entry.metadata.syncId!!, "Ready"))
            val timestamp = Files.getLastModifiedTime(file.toPath())
            assertTrue(BookTranslationAvailability.hasTranslations(entry))
            val replacement = entry.root.resolve("incoming.tmp")
            replacement.writeText(file.readText().replace("Ready", "     "))
            assertEquals(file.length(), replacement.length())
            Files.setLastModifiedTime(replacement.toPath(), timestamp)
            Files.move(replacement.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)

            assertEquals(timestamp, Files.getLastModifiedTime(file.toPath()))
            assertFalse(BookTranslationAvailability.hasTranslations(entry))
        }
    }

    @Test
    fun metadataIdentityChangesInvalidateCachedAvailabilityWithoutChangingTheFile() {
        for (manga in listOf(false, true)) {
            val entry = book("identity-$manga", "original-id", manga)
            if (manga) writeManga(entry, mangaBlob("original-id")) else writeEpub(entry, epubBlob("original-id"))
            assertTrue(BookTranslationAvailability.hasTranslations(entry))

            val replaced = entry.copy(metadata = entry.metadata.copy(syncId = "different-id"))
            assertFalse(BookTranslationAvailability.hasTranslations(replaced))
            assertTrue(BookTranslationAvailability.hasTranslations(entry))
        }
    }

    @Test
    fun aLibraryLargerThanAnyFixedCacheIsParsedOnceAcrossReloads() = runBlocking {
        val library = (0 until 300).map { index ->
            book("large-$index", manga = index % 2 == 0).also { entry ->
                if (index % 2 == 0) writeManga(entry, mangaBlob(entry.metadata.syncId!!))
                else writeEpub(entry, epubBlob(entry.metadata.syncId!!))
            }
        }
        val expected = library.mapTo(mutableSetOf()) { it.metadata.id }
        assertEquals(expected, BookTranslationAvailability.load(library))

        val reads = BookTranslationAvailability.sidecarReads.get()
        repeat(3) { assertEquals(expected, BookTranslationAvailability.load(library)) }
        assertEquals(reads, BookTranslationAvailability.sidecarReads.get())

        // A changed sidecar is still re-read on the next reload.
        val changed = library.last()
        val file = sidecar(changed, manga = false)
        file.writeText("{}")
        Files.setLastModifiedTime(file.toPath(), FileTime.fromMillis(file.lastModified() + 10_000))
        assertEquals(expected - changed.metadata.id, BookTranslationAvailability.load(library))
        assertEquals(reads + 1, BookTranslationAvailability.sidecarReads.get())
    }

    @Test
    fun deletedBooksLeaveTheCache() = runBlocking {
        val library = (0 until 3).map { index ->
            book("trim-$index").also { writeEpub(it, epubBlob(it.metadata.syncId!!)) }
        }
        BookTranslationAvailability.load(library)
        val kept = library.first()
        assertEquals(setOf(kept.metadata.id), BookTranslationAvailability.load(listOf(kept)))
        assertEquals(1, BookTranslationAvailability.cachedBookCount())
    }

    @Test
    fun oversizedSidecarsAreNotReadOrAdvertised() {
        for (manga in listOf(false, true)) {
            val entry = book("oversized-$manga", manga = manga)
            RandomAccessFile(sidecar(entry, manga), "rw").use { it.setLength(MAX_EPUB_SENTENCES_BLOB_BYTES + 1L) }
            assertFalse(BookTranslationAvailability.hasTranslations(entry))
        }
    }

    private fun book(id: String, syncId: String = id, manga: Boolean = false): BookEntry {
        val root = temporaryFolder.newFolder(id)
        if (manga) root.resolve(MOKURO_SIDECAR_FILE).writeText("{}")
        return BookEntry(root, BookMetadata(id, "Book $id", null, id, 0.0, syncId))
    }

    private fun sidecar(entry: BookEntry, manga: Boolean): File =
        entry.root.resolve(if (manga) PRETRANSLATIONS_FILENAME else EPUB_TRANSLATIONS_FILENAME)

    private fun writeEpub(entry: BookEntry, blob: HttpSyncSentencesBlob): File =
        sidecar(entry, false).apply { writeText(json.encodeToString(HttpSyncSentencesBlob.serializer(), blob)) }

    private fun writeManga(entry: BookEntry, blob: PretranslationsBlob): File =
        sidecar(entry, true).apply { writeText(json.encodeToString(PretranslationsBlob.serializer(), blob)) }

    private fun epubBlob(syncId: String, translation: String = "To eat.") = HttpSyncSentencesBlob(
        kind = HttpSyncSentencesBlob.EXPECTED_KIND,
        syncId = syncId,
        spineCount = 1,
        entries = mapOf("c0s0" to HttpSyncSentenceEntry(
            spine = 0, start = 0, len = 3, text = "食べる。",
            hash = EpubTranslationStore.textHash("食べる"), translation = translation,
        )),
    )

    private fun mangaBlob(syncId: String, translation: String = "To eat.") = PretranslationsBlob(
        syncId = syncId,
        entries = mapOf("p0b0" to PretranslationEntryBlob(
            text = "食べる。", hash = PretranslationStore.textHash("食べる。"), translation = translation,
        )),
    )
}
