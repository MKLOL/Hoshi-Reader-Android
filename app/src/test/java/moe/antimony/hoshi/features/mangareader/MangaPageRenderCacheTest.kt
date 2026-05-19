package moe.antimony.hoshi.features.mangareader

import kotlinx.coroutines.CancellationException
import moe.antimony.hoshi.mokuro.MokuroBook
import moe.antimony.hoshi.mokuro.MokuroPage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MangaPageRenderCacheTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private val config = MangaPageRenderConfig(
        backgroundCssColor = "#ffffff",
        scanNonJapaneseText = true,
        eInkMode = false,
        viewportCssWidth = 400,
        viewportCssHeight = 800,
        selectionScript = "/* selection script */",
    )

    private fun page(index: Int): MokuroPage =
        MokuroPage(
            index = index,
            imagePath = "images/page_$index.jpg",
            imageWidth = 800,
            imageHeight = 1200,
            textBoxes = emptyList(),
        )

    @Test
    fun cacheReusesGeneratedHtmlForTheSamePageAndRenderConfig() {
        val cache = MangaPageRenderCache(maxEntries = 2)
        val page = page(0)

        val first = cache.htmlFor(page, config)
        val second = cache.htmlFor(page, config)

        assertTrue(first === second)
        assertTrue(cache.contains(page.index, config))
        assertEquals(1, cache.cachedPageCount())
    }

    @Test
    fun cacheEvictsLeastRecentlyUsedHtmlAfterCapacity() {
        val cache = MangaPageRenderCache(maxEntries = 2)

        cache.htmlFor(page(0), config)
        cache.htmlFor(page(1), config)
        cache.htmlFor(page(0), config)
        cache.htmlFor(page(2), config)

        assertTrue(cache.contains(0, config))
        assertFalse(cache.contains(1, config))
        assertTrue(cache.contains(2, config))
        assertEquals(2, cache.cachedPageCount())
    }

    @Test
    fun adjacentPreloadIndexesStayInsideTheVolume() {
        assertEquals(listOf(1), mangaAdjacentPreloadIndexes(currentIndex = 0, pageCount = 3))
        assertEquals(listOf(2, 0), mangaAdjacentPreloadIndexes(currentIndex = 1, pageCount = 3))
        assertEquals(listOf(1), mangaAdjacentPreloadIndexes(currentIndex = 2, pageCount = 3))
        assertEquals(emptyList<Int>(), mangaAdjacentPreloadIndexes(currentIndex = 0, pageCount = 0))
    }

    @Test
    fun warmImageFileReadsAtMostTheRequestedBytes() {
        val file = tempFolder.newFile("page.jpg")
        file.writeBytes(ByteArray(16) { it.toByte() })

        assertEquals(5L, warmMangaImageFile(file, maxBytes = 5))
        assertEquals(16L, warmMangaImageFile(file, maxBytes = 32))
        assertEquals(0L, warmMangaImageFile(tempFolder.root.resolve("missing.jpg")))
    }

    @Test(expected = CancellationException::class)
    fun warmImageFileChecksForCancellationBetweenChunks() {
        val file = tempFolder.newFile("large-page.jpg")
        file.writeBytes(ByteArray(128 * 1024))
        var calls = 0

        warmMangaImageFile(file) {
            calls += 1
            if (calls > 1) throw CancellationException("stale preload")
        }
    }

    @Test
    fun preloaderBuildsAdjacentHtmlAndSkipsAlreadyWarmedImages() = kotlinx.coroutines.runBlocking {
        val root = tempFolder.newFolder("book")
        root.resolve("images").mkdirs()
        root.resolve("images/page_1.jpg").writeBytes(byteArrayOf(1, 2, 3))
        val book = MokuroBook(
            title = "Volume",
            pages = listOf(page(0), page(1), page(2)),
            coverImagePath = null,
        )
        val cache = MangaPageRenderCache(maxEntries = 3)
        val bridge = MangaWebResourceBridge(root, book)

        cache.preloadAdjacentPages(
            book = book,
            pageIndexes = listOf(1),
            config = config,
            imageResolver = bridge,
            ioDispatcher = kotlinx.coroutines.Dispatchers.Unconfined,
        )
        cache.preloadAdjacentPages(
            book = book,
            pageIndexes = listOf(1),
            config = config,
            imageResolver = bridge,
            ioDispatcher = kotlinx.coroutines.Dispatchers.Unconfined,
        )

        assertTrue(cache.contains(1, config))
        assertEquals(1, cache.cachedPageCount())
        assertEquals(1, cache.warmedImageCount())
    }
}
