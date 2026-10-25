package moe.antimony.hoshi.features.reader.sentence

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import moe.antimony.hoshi.epub.BookInfo
import moe.antimony.hoshi.epub.BookMetadata
import moe.antimony.hoshi.epub.BookRepository
import moe.antimony.hoshi.epub.Bookmark
import moe.antimony.hoshi.epub.EpubBook
import moe.antimony.hoshi.epub.EpubChapter
import moe.antimony.hoshi.features.sync.http.HttpSyncActiveBooks
import moe.antimony.hoshi.navigation.ReaderRouteEpubParser
import moe.antimony.hoshi.navigation.ReaderRouteStateHolder
import moe.antimony.hoshi.navigation.RemoteBookmarkUpdateGate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SentenceReaderLoaderSyncTest {
    @get:Rule val temp = TemporaryFolder()

    @Test
    fun syncLeaseAndImportRunBeforeParsingAndRemoteBookmarkWinsOverPrivateCursor() = runBlocking {
        val repository = BookRepository(temp.root)
        val root = repository.createBookDirectory("book")
        val syncId = "sentence-load-test"
        repository.saveMetadata(root, BookMetadata("book", "Book", null, "book", 1.0, syncId = syncId))
        val positionStore = SentenceReaderPositionStore(temp.root, Dispatchers.Unconfined)
        positionStore.save("book", SentencePosition(0, 0))
        val lease = HttpSyncActiveBooks.Lease()
        val gate = RemoteBookmarkUpdateGate()
        val order = mutableListOf<String>()
        val book = EpubBook(title = "Book", coverHref = null, chapters = listOf(EpubChapter(
            "chapter", "chapter.xhtml", "application/xhtml+xml", html = "<p>一つ。二つ。三つ。</p>",
        )))
        val parser = object : ReaderRouteEpubParser {
            override fun parse(root: File, cachedBookInfo: BookInfo?): EpubBook {
                assertTrue(HttpSyncActiveBooks.contains(syncId))
                order += "parse"
                assertFalse(gate.shouldReload(syncId))
                return book
            }
        }
        val stateHolder = ReaderRouteStateHolder(repository, parser, Dispatchers.Unconfined)
        val loader = SentenceReaderLoader(stateHolder, positionStore, Dispatchers.Unconfined, awaitReaderSaves = { order += "awaitSaves" })
        try {
            val loaded = loader.load(
                "book",
                beforeBookmarkLoad = {
                    gate.expect(syncId)
                    lease.acquire(syncId)
                    order += "sync"
                    repository.saveBookmark(root, Bookmark(0, 0.7, 6, 20.0))
                },
                beforeBookmarkRead = { order += "bookmarkRead"; gate.open() },
            ) as SentenceReaderLoadState.Ready
            assertEquals(listOf("sync", "awaitSaves", "parse", "bookmarkRead"), order)
            assertEquals(0.7, loaded.readerState.bookmark!!.progress, 0.0)
            assertEquals(SentencePosition(0, 2), loaded.initialPosition)
            assertTrue(gate.shouldReload(syncId))
            assertFalse(gate.shouldReload("different-book"))
        } finally {
            lease.release()
        }
        assertFalse(HttpSyncActiveBooks.contains(syncId))
    }
}
