package moe.antimony.hoshi.features.mangareader

import moe.antimony.hoshi.mokuro.MokuroBook
import moe.antimony.hoshi.mokuro.MokuroPage
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MangaImageLoaderBackendTest {
    @get:Rule val temporary = TemporaryFolder()

    private val book = MokuroBook("Cache fixture", (0..7).map {
        MokuroPage(it, "$it.jpg", 800, 1200, emptyList())
    }, null)

    @Test fun repeatedRequestsHaveIndependentStreamsAndReuseTheOriginalBytes() {
        val root = temporary.newFolder()
        val file = root.resolve("1.jpg").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val backend = MangaImageLoaderBackend(root, book, MangaImageCachePolicy(16, 0))
        backend.onPageRequested(0)
        backend.get(file).use { assertEquals(1, it.read()) }
        backend.get(file).use { assertArrayEquals(byteArrayOf(1, 2, 3), it.readBytes()) }
        assertEquals(MangaImageCacheSnapshot(1, 3, 1, 1), backend.snapshot())
    }

    @Test fun cacheIsBoundedByBytesAndMovingTheWindowDropsDistantImages() {
        val root = temporary.newFolder()
        val files = (0..7).map { root.resolve("$it.jpg").apply { writeBytes(ByteArray(4) { it.toByte() }) } }
        val backend = MangaImageLoaderBackend(root, book, MangaImageCachePolicy(8, 0))
        backend.onPageRequested(0)
        for (index in listOf(0, 1, 0, 2)) backend.get(files[index]).close()
        assertEquals(8L, backend.snapshot().bytes)
        assertEquals(2, backend.snapshot().entries)
        backend.get(files[0]).close()
        assertEquals(2L, backend.snapshot().hits)
        backend.onPageRequested(7)
        assertEquals(0L, backend.snapshot().bytes)
        // A late preload from the superseded window may finish, but cannot retain data.
        backend.get(files[0]).close()
        assertEquals(0, backend.snapshot().entries)
    }

    @Test fun fartherNeighboursNeverEvictTheNextPage() {
        val root = temporary.newFolder()
        val files = (0..7).map { root.resolve("$it.jpg").apply { writeBytes(ByteArray(4) { it.toByte() }) } }
        val backend = MangaImageLoaderBackend(root, book, MangaImageCachePolicy(8, 0))
        backend.onPageRequested(4)
        // The decode preloader requests every neighbour in order, after the encoded preload.
        for (index in listOf(5, 3, 5, 3, 6, 2, 7)) backend.get(files[index]).close()
        assertEquals(2, backend.snapshot().entries)
        // Pages that cannot be kept are streamed, not read into a discarded buffer.
        assertEquals(2L, backend.snapshot().reads)
        backend.get(files[5]).close()
        backend.get(files[3]).close()
        assertEquals(4L, backend.snapshot().hits)
        // A nearer page still displaces a farther one.
        backend.onPageRequested(5)
        backend.get(files[6]).close()
        backend.get(files[6]).close()
        assertEquals(5L, backend.snapshot().hits)
    }

    @Test fun aBudgetForOnePageKeepsTheNextPageRatherThanTheDisplayedOne() {
        val root = temporary.newFolder()
        val files = (0..7).map { root.resolve("$it.jpg").apply { writeBytes(ByteArray(6) { it.toByte() }) } }
        val backend = MangaImageLoaderBackend(root, book, MangaImageCachePolicy(8, 0))
        for (page in 2..4) {
            backend.onPageRequested(page)
            backend.get(files[page]).close()
            backend.get(files[page + 1]).close()
        }
        // Every turn after the first finds its page prepared.
        assertEquals(2L, backend.snapshot().hits)
    }

    @Test fun largeImagesAndClosedBackendsStillServeOriginalsWithoutRetention() {
        val root = temporary.newFolder()
        val file = root.resolve("1.jpg").apply { writeBytes(ByteArray(9) { 7 }) }
        val backend = MangaImageLoaderBackend(root, book, MangaImageCachePolicy(8, 0))
        backend.onPageRequested(0)
        backend.get(file).use { assertEquals(9, it.readBytes().size) }
        assertEquals(0L, backend.snapshot().bytes)
        file.writeBytes(byteArrayOf(3, 4))
        backend.get(file).close()
        assertEquals(2L, backend.snapshot().bytes)
        backend.close()
        backend.onPageRequested(0)
        backend.get(file).use { assertArrayEquals(byteArrayOf(3, 4), it.readBytes()) }
        assertEquals(0L, backend.snapshot().bytes)
    }

    @Test fun replacedFilesCannotReuseOldBytesOrBrowserUrls() {
        val root = temporary.newFolder()
        val file = root.resolve("1.jpg").apply { writeBytes(byteArrayOf(1)) }
        val backend = MangaImageLoaderBackend(root, book, MangaImageCachePolicy(8, 0))
        backend.onPageRequested(0)
        val oldUrl = backend.imageUrl(book.pages[1])
        backend.get(file).close()
        file.writeBytes(byteArrayOf(2, 3))
        backend.get(file).use { assertArrayEquals(byteArrayOf(2, 3), it.readBytes()) }
        assertNotEquals(oldUrl, backend.imageUrl(book.pages[1]))
    }

    @Test fun differentBooksWithIdenticalImageNamesHaveDifferentBrowserCacheKeys() {
        val first = MangaImageLoaderBackend(temporary.newFolder(), book)
        val second = MangaImageLoaderBackend(temporary.newFolder(), book)
        assertNotEquals(first.imageUrl(book.pages[0]), second.imageUrl(book.pages[0]))
        assertEquals(first.imageUrl(book.pages[0]), first.imageUrl(book.pages[0]))
    }

    @Test fun preloadWindowIncludesThreeAheadAndTwoBehindWithoutLeavingTheBook() {
        assertEquals(listOf(5, 3, 6, 2, 7), mangaAdjacentPreloadIndexes(4, 8))
        assertEquals(listOf(1, 2, 3), mangaAdjacentPreloadIndexes(0, 8))
        assertEquals(listOf(6, 5), mangaAdjacentPreloadIndexes(7, 8))
        assertEquals(emptyList<Int>(), mangaAdjacentPreloadIndexes(0, 1))
    }

    @Test fun imagesExcludedFromDecodeStillGetEncodedPreparation() = kotlinx.coroutines.runBlocking {
        val root = temporary.newFolder()
        val file = root.resolve("1.jpg").apply { writeBytes(byteArrayOf(9, 8, 7)) }
        val backend = MangaImageLoaderBackend(root, book, MangaImageCachePolicy(8, 0))
        try {
            backend.onPageRequested(0)
            backend.preloadScript(0)
            backend.awaitEncodedPreload()
            assertEquals(3L, backend.snapshot().bytes)
            backend.get(file).use { assertArrayEquals(byteArrayOf(9, 8, 7), it.readBytes()) }
            assertEquals(1L, backend.snapshot().hits)
        } finally { backend.close() }
    }

    @Test fun concurrentRequestsShareOneRead() {
        val root = temporary.newFolder()
        val file = root.resolve("1.jpg").apply { writeBytes(ByteArray(1024 * 1024) { 4 }) }
        val backend = MangaImageLoaderBackend(root, book, MangaImageCachePolicy(2L shl 20, 0))
        backend.onPageRequested(0)
        val workers = java.util.concurrent.Executors.newFixedThreadPool(4)
        val ready = java.util.concurrent.CountDownLatch(4)
        val start = java.util.concurrent.CountDownLatch(1)
        try {
            val requests = (0..3).map { workers.submit<Int> {
                ready.countDown()
                check(start.await(5, java.util.concurrent.TimeUnit.SECONDS))
                backend.get(file).use { it.readBytes().size }
            } }
            assertTrue(ready.await(5, java.util.concurrent.TimeUnit.SECONDS))
            start.countDown()
            requests.forEach { assertEquals(1024 * 1024, it.get().toInt()) }
            assertEquals(1L, backend.snapshot().reads)
            assertEquals(3L, backend.snapshot().hits)
        } finally { workers.shutdownNow(); backend.close() }
    }

    @Test fun canceledPreparationNeverRetainsPartialBytes() {
        val root = temporary.newFolder()
        val file = root.resolve("1.jpg").apply { writeBytes(ByteArray(128 * 1024) { 5 }) }
        val backend = MangaImageLoaderBackend(root, book, MangaImageCachePolicy(256 * 1024, 0))
        backend.onPageRequested(0)
        var checks = 0
        try {
            backend.get(file) {
                if (++checks == 4) throw kotlinx.coroutines.CancellationException("Superseded page")
            }.close()
            fail("Expected cancellation")
        } catch (_: kotlinx.coroutines.CancellationException) {
            assertEquals(0L, backend.snapshot().bytes)
            backend.get(file).use { assertEquals(128 * 1024, it.readBytes().size) }
        } finally { backend.close() }
    }
}
