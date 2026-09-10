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
