package moe.antimony.hoshi.navigation

import kotlinx.coroutines.runBlocking
import moe.antimony.hoshi.epub.BookMetadata
import moe.antimony.hoshi.epub.BookRepository
import moe.antimony.hoshi.epub.EpubBookParser
import moe.antimony.hoshi.epub.GENERATED_COVER_FILENAME
import moe.antimony.hoshi.features.sync.http.HttpSyncPayloadCodec
import moe.antimony.hoshi.features.sync.integration.SyncCorpus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.UUID

class ReaderRoutePayloadStabilityTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test
    fun openingASyncedEpubKeepsItsPayloadHashAndCachedChapterCounts() = runBlocking {
        val repository = BookRepository(temporaryFolder.root)
        val root = repository.createBookDirectory("remote-book")
        SyncCorpus.writeEpub(root, "Remote Book", "食べる。")
        val originalBook = EpubBookParser().parse(root)
        val metadata = BookMetadata(
            id = UUID.randomUUID().toString(), title = originalBook.title,
            cover = repository.syncedCoverPath(root, originalBook.coverHref),
            folder = root.name, lastAccess = 1.0, syncId = "remote-book",
        )
        repository.saveMetadata(root, metadata)
        repository.saveBookInfo(root, originalBook.bookInfo)
        val infoFile = root.resolve("bookinfo.json")
        val coverFile = root.resolve(GENERATED_COVER_FILENAME)
        assertTrue(infoFile.setLastModified(1_000L))
        assertTrue(coverFile.setLastModified(1_000L))
        val codec = HttpSyncPayloadCodec()
        val originalHash = codec.computePayloadContentSha(root)

        repeat(2) {
            val state = ReaderRouteStateHolder(repository).load(metadata.id) as ReaderRouteLoadState.Ready
            assertEquals(originalBook.bookInfo, state.book.bookInfo)
            assertTrue(state.book.chapters.all { chapter -> chapter.html.isEmpty() })
            assertEquals(metadata.cover, state.entry.metadata.cover)
            assertEquals(originalHash, codec.computePayloadContentSha(root))
        }

        assertFalse(root.resolve("cover.jpg").exists())
        assertEquals(1_000L, infoFile.lastModified())
        assertEquals(1_000L, coverFile.lastModified())
    }
}
