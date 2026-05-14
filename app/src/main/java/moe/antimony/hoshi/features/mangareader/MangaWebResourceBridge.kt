package moe.antimony.hoshi.features.mangareader

import android.webkit.WebResourceResponse
import moe.antimony.hoshi.mokuro.MokuroBook
import java.io.File
import java.net.URI

/**
 * Serves a mokuro book's page images to the manga reader's WebView.
 *
 * The generated page HTML ([MangaPageHtml]) is loaded with a base URL of
 * `https://hoshi.local/manga/`, so a page image referenced by its book-root-relative path
 * (e.g. `images/page_000.jpg`) resolves to `https://hoshi.local/manga/images/page_000.jpg`.
 * `WebViewClient.shouldInterceptRequest` routes those requests here, and this bridge reads
 * the bytes straight off disk from `bookRoot.resolve(<relative path>)`.
 *
 * Mirrors `moe.antimony.hoshi.features.reader.ReaderWebResourceBridge`, but for a book that
 * lives as a plain directory of image files rather than inside an EPUB zip. Only image
 * paths declared by the book and physically inside [bookRoot] are served, so a malicious
 * `mokuro.json` cannot read arbitrary files via `../` traversal.
 *
 * URL-to-file resolution is split out into [resolveImageFile] so it can be unit-tested
 * without touching the Android-only [WebResourceResponse].
 */
internal class MangaWebResourceBridge(
    bookRoot: File,
    book: MokuroBook,
) {
    private val canonicalRoot: File = bookRoot.canonicalFile

    /** Book-root-relative image paths declared by the book, used to reject unknown requests. */
    private val knownImagePaths: Set<String> =
        book.pages.mapTo(mutableSetOf()) { it.imagePath.trimStart('/') }

    /**
     * Resolves [url] to the on-disk image file it points at, or `null` if the URL is not a
     * manga-image request, names an image the book does not declare, or escapes [bookRoot].
     */
    fun resolveImageFile(url: String): File? {
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        if (uri.host != HOST) return null
        val path = uri.path.orEmpty()
        if (!path.startsWith(PATH_PREFIX)) return null
        val relative = path.removePrefix(PATH_PREFIX).trimStart('/')
        if (relative.isEmpty() || relative !in knownImagePaths) return null

        val file = canonicalRoot.resolve(relative).canonicalFile
        if (!file.isWithin(canonicalRoot) || !file.isFile) return null
        return file
    }

    fun resourceForUrl(url: String): WebResourceResponse? {
        val file = resolveImageFile(url) ?: return null
        return WebResourceResponse(
            file.imageMediaType(),
            null,
            file.inputStream(),
        )
    }

    private fun File.isWithin(root: File): Boolean {
        if (path == root.path) return false
        return path.startsWith(root.path + File.separator)
    }

    companion object {
        const val HOST = "hoshi.local"
        const val PATH_PREFIX = "/manga/"

        fun File.imageMediaType(): String = when (extension.lowercase()) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "webp" -> "image/webp"
            "gif" -> "image/gif"
            "avif" -> "image/avif"
            "bmp" -> "image/bmp"
            else -> "application/octet-stream"
        }
    }
}
