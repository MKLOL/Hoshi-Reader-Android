package moe.antimony.hoshi.features.mangareader

import moe.antimony.hoshi.mokuro.MokuroBook
import moe.antimony.hoshi.mokuro.MokuroPage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
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
        assertEquals(listOf(1, 2), mangaAdjacentPreloadIndexes(currentIndex = 0, pageCount = 3))
        assertEquals(listOf(2, 0), mangaAdjacentPreloadIndexes(currentIndex = 1, pageCount = 3))
        assertEquals(listOf(1, 0), mangaAdjacentPreloadIndexes(currentIndex = 2, pageCount = 3))
        assertEquals(emptyList<Int>(), mangaAdjacentPreloadIndexes(currentIndex = 0, pageCount = 0))
    }

    @Test
    fun preloaderBuildsAdjacentHtml() = kotlinx.coroutines.runBlocking {
        val root = tempFolder.newFolder("book")
        root.resolve("images").mkdirs()
        root.resolve("images/page_1.jpg").writeBytes(byteArrayOf(1, 2, 3))
        val book = MokuroBook(
            title = "Volume",
            pages = listOf(page(0), page(1), page(2)),
            coverImagePath = null,
        )
        val cache = MangaPageRenderCache(maxEntries = 3)

        cache.preloadAdjacentPages(
            book = book,
            pageIndexes = listOf(1),
            config = config,
            ioDispatcher = kotlinx.coroutines.Dispatchers.Unconfined,
        )
        cache.preloadAdjacentPages(
            book = book,
            pageIndexes = listOf(1),
            config = config,
            ioDispatcher = kotlinx.coroutines.Dispatchers.Unconfined,
        )

        assertTrue(cache.contains(1, config))
        assertEquals(1, cache.cachedPageCount())
    }

    @Test
    fun aTurnToAPreparedNeighbourRendersWithoutReadingTheDiskAndAReplacedImageGetsANewUrl() =
        kotlinx.coroutines.runBlocking {
            val root = tempFolder.newFolder("book")
            root.resolve("images").mkdirs()
            val image = root.resolve("images/page_1.jpg").apply { writeBytes(byteArrayOf(1, 2, 3)) }
            val book = MokuroBook(title = "Volume", pages = listOf(page(0), page(1), page(2)), coverImagePath = null)
            val backend = MangaImageLoaderBackend(root, book)
            val cache = MangaPageRenderCache(imageLoader = backend)
            try {
                // Never looked at: the main-thread path has no version and reads none itself.
                assertNull(cache.knownHtmlFor(page(1), config))

                // The background neighbour pass for page 0 reads page 1's version.
                cache.preloadAdjacentPages(book, listOf(1), config, kotlinx.coroutines.Dispatchers.Unconfined)
                val prepared = requireNotNull(cache.knownHtmlFor(page(1), config))
                assertTrue(prepared === cache.knownHtmlFor(page(1), config))

                // The image is replaced. The main-thread path still does not touch the disk...
                image.writeBytes(byteArrayOf(4, 5, 6, 7))
                assertEquals(prepared, cache.knownHtmlFor(page(1), config))
                // ...and the background revalidation that follows gives it a new image URL.
                val revalidated = cache.htmlFor(page(1), config)
                assertNotEquals(prepared, revalidated)
                assertTrue(revalidated.contains("v=4-"))
                assertEquals(revalidated, cache.knownHtmlFor(page(1), config))
            } finally {
                backend.close()
            }
        }

    @Test
    fun withoutAnImageLoaderHtmlIsAlwaysAvailableWithoutAVersion() {
        val cache = MangaPageRenderCache()
        assertEquals(cache.htmlFor(page(0), config), cache.knownHtmlFor(page(0), config))
    }
}
