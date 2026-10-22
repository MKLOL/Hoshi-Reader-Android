package moe.antimony.hoshi.features.mangareader

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import moe.antimony.hoshi.mokuro.MokuroBook
import moe.antimony.hoshi.mokuro.MokuroPage
import java.io.ByteArrayInputStream
import java.io.Closeable
import java.io.File
import java.io.InputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Book-scoped image service. The reader only asks for a URL/stream; byte retention, the
 * look-ahead policy and WebView decode preparation live here, independently of page rendering.
 * Originals are served unchanged. Chromium owns its decoded cache, so preparation is best
 * effort across document navigations, with the ordinary file stream as the fallback.
 */
internal class MangaImageLoaderBackend(
    bookRoot: File,
    private val book: MokuroBook,
    private val policy: MangaImageCachePolicy = MangaImageCachePolicy(),
) : Closeable {
    private val resolver = MangaWebResourceBridge(bookRoot, book)
    private val namespace = UUID.randomUUID().toString()
    private val lock = Any()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var preloadJob: Job? = null
    private val bytes = LinkedHashMap<ImageKey, ByteArray>()
    private val readers = ConcurrentHashMap<String, Any>()
    private var retainedBytes = 0L
    /** The current page, then [mangaAdjacentPreloadIndexes] order: earlier paths are kept longer. */
    private var wantedPaths = emptyList<String>()
    private var closed = false
    private var hits = 0L
    private var reads = 0L

    fun imageUrl(page: MokuroPage): String {
        val file = resolver.resolveDeclaredImageFile(page.imagePath)
        val version = file?.let { "${it.length()}-${it.lastModified()}" } ?: "missing"
        // A book/session namespace prevents two volumes with e.g. images/001.jpg from
        // sharing a browser cache entry. The file version also isolates replaced content.
        return MangaPageHtml.BASE_URL + MangaPageHtml.encodeImagePath(page.imagePath) +
            "?hoshiImage=$namespace&v=$version"
    }

    fun onPageRequested(pageIndex: Int) {
        preloadJob?.cancel()
        val paths = (listOf(pageIndex) + mangaAdjacentPreloadIndexes(pageIndex, book.pages.size))
            .mapNotNull { book.pages.getOrNull(it)?.imagePath?.trimStart('/') }
            .distinct()
        synchronized(lock) {
            if (closed) return
            wantedPaths = paths
            val iterator = bytes.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                if (entry.key.relativePath !in paths) {
                    retainedBytes -= entry.value.size
                    iterator.remove()
                }
            }
        }
    }

    /** Called on WebView's resource thread, never on the UI thread. Every caller owns its stream. */
    fun get(file: File, shouldContinue: () -> Unit = {}): InputStream = synchronized(readers.getOrPut(file.canonicalPath) { Any() }) {
        shouldContinue()
        openImage(file, shouldContinue)
    }

    private fun openImage(file: File, shouldContinue: () -> Unit): InputStream {
        val key = keyFor(file)
        synchronized(lock) {
            bytes[key]?.let {
                hits++
                return ByteArrayInputStream(it)
            }
        }
        val cacheable = synchronized(lock) {
            !closed && key.relativePath in wantedPaths && key.length in 1..policy.encodedBytes
        }
        if (!cacheable) return file.inputStream()

        // Bound even a file that grows during the read. Do not retain failures or partial reads.
        shouldContinue()
        val data = try {
            ByteArray(key.length.toInt())
        } catch (_: OutOfMemoryError) {
            return file.inputStream()
        }
        file.inputStream().use { input ->
            var offset = 0
            while (offset < data.size) {
                shouldContinue()
                val count = input.read(data, offset, minOf(64 * 1024, data.size - offset))
                if (count < 0) return file.inputStream()
                offset += count
            }
            if (input.read() != -1) return file.inputStream()
        }
        shouldContinue()
        if (keyFor(file) != key) return file.inputStream()
        synchronized(lock) {
            reads++
            if (!closed && key.relativePath in wantedPaths) {
                // A page can be requested by both the visible image and a superseded preload.
                bytes.remove(key)?.let { retainedBytes -= it.size }
                // Evict by distance, not recency: the decode preloader requests every neighbour,
                // and least-recently-used order would drop the next page for a farther one.
                val priority = wantedPaths.indexOf(key.relativePath)
                val evictable = bytes.keys.filter { wantedPaths.indexOf(it.relativePath) > priority }
                    .sortedByDescending { wantedPaths.indexOf(it.relativePath) }
                val freeable = evictable.sumOf { bytes.getValue(it).size.toLong() }
                if (retainedBytes - freeable + data.size <= policy.encodedBytes) {
                    for (evicted in evictable) {
                        if (retainedBytes + data.size <= policy.encodedBytes) break
                        retainedBytes -= bytes.remove(evicted)!!.size
                    }
                    bytes[key] = data
                    retainedBytes += data.size
                }
            }
        }
        return ByteArrayInputStream(data)
    }

    fun preloadScript(pageIndex: Int): String {
        preloadEncodedImages(pageIndex)
        val candidates = mangaAdjacentPreloadIndexes(pageIndex, book.pages.size).mapNotNull { index ->
            book.pages.getOrNull(index)?.let { page ->
                MangaImageDecodeCandidate(imageUrl(page), page.imageWidth, page.imageHeight)
            }
        }
        return MangaImageDecodePreloader.script(pageIndex, candidates, policy.decodedBytes)
    }

    /** Encoded preparation is independent of the much larger decoded-pixel budget. */
    private fun preloadEncodedImages(pageIndex: Int) {
        preloadJob?.cancel()
        preloadJob = scope.launch {
            var plannedBytes = 0L
            for (index in mangaAdjacentPreloadIndexes(pageIndex, book.pages.size)) {
                ensureActive()
                val page = book.pages.getOrNull(index) ?: continue
                try {
                    val file = resolver.resolveDeclaredImageFile(page.imagePath) ?: continue
                    val length = file.length()
                    if (length in 1..(policy.encodedBytes - plannedBytes)) {
                        plannedBytes += length
                        get(file) { ensureActive() }.close()
                    } else {
                        // A huge original still benefits from bounded OS file-cache warming.
                        // This does not allocate a bitmap or retain an oversized byte array.
                        val buffer = ByteArray(64 * 1024)
                        var remaining = 8L shl 20
                        file.inputStream().use { input ->
                            while (remaining > 0) {
                                ensureActive()
                                val count = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                                if (count < 0) break
                                remaining -= count
                            }
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // A failed speculative read must not affect the visible page or poison cache state.
                } catch (_: OutOfMemoryError) {
                    // The scope has no handler: an escaping error would crash the reader.
                }
            }
        }
    }

    fun responseHeaders(url: String): Map<String, String> = mapOf(
        "Cache-Control" to if (url.contains("?hoshiImage=$namespace&v=")) {
            "private, max-age=31536000, immutable"
        } else {
            "no-store"
        },
    )

    internal fun snapshot(): MangaImageCacheSnapshot = synchronized(lock) {
        MangaImageCacheSnapshot(bytes.size, retainedBytes, hits, reads)
    }

    internal suspend fun awaitEncodedPreload() { preloadJob?.join() }

    override fun close() = synchronized(lock) {
        closed = true
        scope.cancel()
        wantedPaths = emptyList()
        bytes.clear()
        retainedBytes = 0L
    }

    private fun keyFor(file: File) = ImageKey(
        relativePath = file.canonicalFile.relativeTo(resolverRoot).invariantSeparatorsPath,
        length = file.length(),
        modified = file.lastModified(),
    )

    private val resolverRoot = bookRoot.canonicalFile
    private data class ImageKey(val relativePath: String, val length: Long, val modified: Long)
}

internal data class MangaImageCachePolicy(
    val encodedBytes: Long = (Runtime.getRuntime().maxMemory() / 16).coerceIn(4L shl 20, 24L shl 20),
    val decodedBytes: Long = (Runtime.getRuntime().maxMemory() / 2).coerceIn(32L shl 20, 128L shl 20),
) {
    init {
        require(encodedBytes in 0..Int.MAX_VALUE.toLong())
        require(decodedBytes >= 0)
    }
}

internal data class MangaImageCacheSnapshot(val entries: Int, val bytes: Long, val hits: Long, val reads: Long)

internal fun mangaAdjacentPreloadIndexes(currentIndex: Int, pageCount: Int): List<Int> {
    if (pageCount <= 0) return emptyList()
    return listOf(currentIndex + 1, currentIndex - 1, currentIndex + 2, currentIndex - 2, currentIndex + 3)
        .filter { it in 0 until pageCount }
}
