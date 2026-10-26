package moe.antimony.hoshi.features.mangareader

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import moe.antimony.hoshi.features.reader.ReaderSelectionScripts
import moe.antimony.hoshi.mokuro.MokuroBook
import moe.antimony.hoshi.mokuro.MokuroPage

internal data class MangaPageRenderConfig(
    val backgroundCssColor: String,
    val scanNonJapaneseText: Boolean,
    val eInkMode: Boolean,
    val viewportCssWidth: Int,
    val viewportCssHeight: Int,
    /** See ReaderSettings.mangaSingleTapLookup — flips the tap handler's first-tap behavior. */
    val singleTapLookup: Boolean = false,
    /** See ReaderSettings.mangaUseNotoSansJp — when true, emits an explicit font-family. */
    val useNotoSansJpFont: Boolean = false,
    /** See ReaderSettings.mangaShowCopyButton — off hides Copy and enlarges Translate. */
    val showCopyButton: Boolean = false,
    val enlargeSmallText: Boolean = true,
    val selectionScript: String = ReaderSelectionScripts.source(),
)

/**
 * Small LRU cache for generated manga page HTML.
 *
 * Mokuro pages can contain many OCR text boxes, so generating the HTML for every page turn
 * on the Compose update path can become visible on large volumes. This cache lets the live
 * page reuse HTML that the adjacent-page preloader already built on an IO/background turn.
 */
internal class MangaPageRenderCache(
    private val maxEntries: Int = 6,
    val imageLoader: MangaImageLoaderBackend? = null,
) {
    private val lock = Any()
    private val entries = object : LinkedHashMap<MangaPageRenderKey, String>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<MangaPageRenderKey, String>): Boolean =
            size > maxEntries.coerceAtLeast(1)
    }

    /**
     * The page's HTML for its image's current file version. With an [imageLoader] this reads file
     * metadata, so call it off the main thread.
     */
    fun htmlFor(page: MokuroPage, config: MangaPageRenderConfig): String =
        htmlFor(page, config, imageLoader?.imageUrl(page) ?: MangaPageHtml.encodeImagePath(page.imagePath))

    /**
     * The page's HTML without touching the disk: built from the image version last read off the
     * main thread, or null when this page's image has not been looked at yet. Callers revalidate
     * with [htmlFor] in the background, so a replaced image still gets its new URL.
     */
    fun knownHtmlFor(page: MokuroPage, config: MangaPageRenderConfig): String? {
        val imageUrl = if (imageLoader == null) {
            MangaPageHtml.encodeImagePath(page.imagePath)
        } else {
            imageLoader.knownImageUrl(page) ?: return null
        }
        return htmlFor(page, config, imageUrl)
    }

    private fun htmlFor(page: MokuroPage, config: MangaPageRenderConfig, imageUrl: String): String {
        val key = MangaPageRenderKey(page.index, config, imageUrl)
        synchronized(lock) {
            entries[key]?.let { return it }
        }

        val html = MangaPageHtml.build(
            page = page,
            backgroundCssColor = config.backgroundCssColor,
            selectionScript = config.selectionScript,
            scanNonJapaneseText = config.scanNonJapaneseText,
            eInkMode = config.eInkMode,
            viewportCssWidth = config.viewportCssWidth,
            viewportCssHeight = config.viewportCssHeight,
            singleTapLookup = config.singleTapLookup,
            useNotoSansJpFont = config.useNotoSansJpFont,
            showCopyButton = config.showCopyButton,
            enlargeSmallText = config.enlargeSmallText,
            imageUrl = imageUrl,
        )

        synchronized(lock) {
            entries[key]?.let { return it }
            entries[key] = html
        }
        return html
    }

    suspend fun preloadAdjacentPages(
        book: MokuroBook,
        pageIndexes: List<Int>,
        config: MangaPageRenderConfig,
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    ) {
        withContext(ioDispatcher) {
            pageIndexes
                .distinct()
                .mapNotNull { pageIndex -> book.pages.getOrNull(pageIndex) }
                .forEach { page ->
                    ensureActive()
                    htmlFor(page, config)
                }
        }
    }

    fun cachedPageCount(): Int = synchronized(lock) { entries.size }

    fun contains(pageIndex: Int, config: MangaPageRenderConfig): Boolean =
        synchronized(lock) { entries.keys.any { it.pageIndex == pageIndex && it.config == config } }

    private data class MangaPageRenderKey(
        val pageIndex: Int,
        val config: MangaPageRenderConfig,
        val imageUrl: String,
    )
}
