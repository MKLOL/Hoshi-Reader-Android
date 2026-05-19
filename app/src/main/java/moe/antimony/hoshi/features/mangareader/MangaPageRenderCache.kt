package moe.antimony.hoshi.features.mangareader

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import moe.antimony.hoshi.features.reader.ReaderSelectionScripts
import moe.antimony.hoshi.mokuro.MokuroBook
import moe.antimony.hoshi.mokuro.MokuroPage
import java.io.File

internal data class MangaPageRenderConfig(
    val backgroundCssColor: String,
    val scanNonJapaneseText: Boolean,
    val eInkMode: Boolean,
    val viewportCssWidth: Int,
    val viewportCssHeight: Int,
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
    private val maxEntries: Int = 5,
    private val maxWarmedImages: Int = 12,
) {
    private val lock = Any()
    private val entries = object : LinkedHashMap<MangaPageRenderKey, String>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<MangaPageRenderKey, String>): Boolean =
            size > maxEntries.coerceAtLeast(1)
    }
    private val warmedImages = object : LinkedHashMap<String, Unit>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Unit>): Boolean =
            size > maxWarmedImages.coerceAtLeast(1)
    }

    fun htmlFor(page: MokuroPage, config: MangaPageRenderConfig): String {
        val key = MangaPageRenderKey(page.index, config)
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
        imageResolver: MangaWebResourceBridge,
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    ) {
        withContext(ioDispatcher) {
            val coroutineContext = currentCoroutineContext()
            pageIndexes
                .distinct()
                .mapNotNull { pageIndex -> book.pages.getOrNull(pageIndex) }
                .forEach { page ->
                    ensureActive()
                    htmlFor(page, config)
                    ensureActive()
                    imageResolver.resolveDeclaredImageFile(page.imagePath)
                        ?.takeIf(::shouldWarmImage)
                        ?.let { file ->
                            warmMangaImageFile(file, shouldContinue = { coroutineContext.ensureActive() })
                        }
                }
        }
    }

    fun cachedPageCount(): Int = synchronized(lock) { entries.size }

    fun warmedImageCount(): Int = synchronized(lock) { warmedImages.size }

    fun contains(pageIndex: Int, config: MangaPageRenderConfig): Boolean =
        synchronized(lock) { entries.containsKey(MangaPageRenderKey(pageIndex, config)) }

    private fun shouldWarmImage(file: File): Boolean {
        val key = runCatching { file.canonicalPath }.getOrElse { file.absolutePath }
        synchronized(lock) {
            if (warmedImages.containsKey(key)) return false
            warmedImages[key] = Unit
            return true
        }
    }

    private data class MangaPageRenderKey(
        val pageIndex: Int,
        val config: MangaPageRenderConfig,
    )
}

internal fun mangaAdjacentPreloadIndexes(currentIndex: Int, pageCount: Int): List<Int> {
    if (pageCount <= 0) return emptyList()
    return listOf(currentIndex + 1, currentIndex - 1)
        .filter { it in 0 until pageCount }
}

internal fun warmMangaImageFile(
    file: File,
    maxBytes: Long = 8L * 1024L * 1024L,
    shouldContinue: () -> Unit = {},
): Long {
    return try {
        if (!file.isFile || maxBytes <= 0L) return 0L
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        file.inputStream().use { input ->
            while (total < maxBytes) {
                shouldContinue()
                val bytesToRead = minOf(buffer.size.toLong(), maxBytes - total).toInt()
                val read = input.read(buffer, 0, bytesToRead)
                if (read <= 0) break
                total += read.toLong()
            }
        }
        total
    } catch (error: CancellationException) {
        throw error
    } catch (_: Throwable) {
        0L
    }
}
