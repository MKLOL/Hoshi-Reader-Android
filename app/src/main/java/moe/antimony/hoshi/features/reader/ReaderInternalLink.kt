package moe.antimony.hoshi.features.reader

import moe.antimony.hoshi.epub.EpubBook
import moe.antimony.hoshi.epub.decodedEpubResourcePath
import java.net.URI
import java.net.URLDecoder

internal data class ReaderInternalLinkTarget(
    val position: ReaderChapterPosition,
    val fragment: String?,
)

internal fun EpubBook.resolveInternalReaderLink(url: String): ReaderInternalLinkTarget? {
    val uri = runCatching { URI(url) }.getOrNull() ?: return null
    val scheme = uri.scheme?.lowercase() ?: return null
    if ((scheme != "http" && scheme != "https") || uri.host != "hoshi.local") return null

    val path = uri.rawPath.orEmpty()
    if (!path.startsWith("/epub/")) return null

    val href = path.removePrefix("/epub/") + (uri.rawFragment?.let { "#$it" } ?: "")
    return resolveReaderChapterHref(href)
}

internal fun EpubBook.resolveReaderChapterHref(href: String): ReaderInternalLinkTarget? {
    if (href.substringBefore('#').substringBefore('?').isBlank()) return null
    val path = href.decodedEpubResourcePath()
    val spineIndex = chapters.indexOfFirst { chapter ->
        val chapterPath = chapter.href.decodedEpubResourcePath()
        path == chapterPath
    }.takeIf { it >= 0 } ?: return null

    val rawFragment = href.substringAfter('#', "")
    return ReaderInternalLinkTarget(
        position = ReaderChapterPosition(index = spineIndex, progress = 0.0),
        fragment = runCatching {
            URLDecoder.decode(rawFragment.replace("+", "%2B"), "UTF-8")
        }.getOrDefault(rawFragment).ifBlank { null },
    )
}

/** Main-frame links never replace the reader with an unrelated document. */
internal fun EpubBook.handleReaderLinkNavigation(
    url: String,
    isMainFrame: Boolean,
    onInternalLink: (ReaderInternalLinkTarget) -> Unit,
    onExternalLink: (String) -> Unit,
): Boolean {
    if (!isMainFrame || url.isBlank() || url.startsWith('#') || url == "about:blank") return false
    resolveInternalReaderLink(url)?.let { target ->
        onInternalLink(target)
        return true
    }
    val uri = runCatching { URI(url) }.getOrNull() ?: return true
    if (uri.scheme?.lowercase() in setOf("http", "https") &&
        !uri.host.isNullOrBlank() && !uri.host.equals("hoshi.local", ignoreCase = true)
    ) {
        onExternalLink(url)
    }
    // Unknown local resources and schemes cannot navigate the privileged EPUB WebView either.
    return true
}
