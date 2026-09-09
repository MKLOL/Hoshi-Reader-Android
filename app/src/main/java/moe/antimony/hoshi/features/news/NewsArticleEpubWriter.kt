package moe.antimony.hoshi.features.news

import java.io.File
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Writes one article as an extracted EPUB directory, the same on-disk shape an imported `.epub`
 * has after unzipping, so the existing parser, reader, sync and sidecars treat it like any book.
 *
 * The package is EPUB 3 with an EPUB 2 NCX alongside the navigation document, because the iOS
 * app's EPUB library refuses packages without an NCX and the book may reach iOS through sync.
 * The article body is the single spine item; the navigation document is not in the spine, so the
 * spine count that sentence translations are validated against is exactly one.
 */
object NewsArticleEpubWriter {
    const val ARTICLE_HREF = "OEBPS/article.xhtml"

    data class Input(
        val title: String,
        val bodyXhtml: String,
        val sourceName: String,
        val sourceUrl: String,
        val language: String = "ja",
        val publishedAt: Long? = null,
        /** JPEG, PNG or WebP bytes; anything else is ignored. */
        val cover: ByteArray? = null,
    )

    fun write(root: File, input: Input) {
        val title = input.title.trim().ifEmpty { input.sourceUrl }
        val identifier = "urn:hoshi-news:" + sha256Hex(input.sourceUrl).take(32)
        val modified = DateTimeFormatter.ISO_INSTANT.format(Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS))
        val published = input.publishedAt?.let { DateTimeFormatter.ISO_LOCAL_DATE.format(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC)) }
        val coverType = input.cover?.let(::imageType)
        val hasCover = coverType != null
        val coverName = "cover.${coverType?.extension}"

        root.mkdirs()
        File(root, "mimetype").writeText("application/epub+zip")
        File(root, "META-INF").mkdirs()
        File(root, "META-INF/container.xml").writeText(
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
              <rootfiles>
                <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
              </rootfiles>
            </container>
            """.trimIndent() + "\n",
        )
        File(root, "OEBPS").mkdirs()
        File(root, "OEBPS/content.opf").writeText(
            buildString {
                appendLine("""<?xml version="1.0" encoding="UTF-8"?>""")
                appendLine("""<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="pub-id" xml:lang="${input.language}">""")
                appendLine("""  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">""")
                appendLine("""    <dc:identifier id="pub-id">$identifier</dc:identifier>""")
                appendLine("""    <dc:title>${NewsArticleXhtml.escape(title)}</dc:title>""")
                appendLine("""    <dc:language>${input.language}</dc:language>""")
                appendLine("""    <dc:publisher>${NewsArticleXhtml.escape(input.sourceName)}</dc:publisher>""")
                appendLine("""    <dc:source>${NewsArticleXhtml.escape(input.sourceUrl)}</dc:source>""")
                if (published != null) appendLine("""    <dc:date>$published</dc:date>""")
                appendLine("""    <meta property="dcterms:modified">$modified</meta>""")
                if (hasCover) appendLine("""    <meta name="cover" content="cover"/>""")
                appendLine("""  </metadata>""")
                appendLine("""  <manifest>""")
                appendLine("""    <item id="article" href="article.xhtml" media-type="application/xhtml+xml"/>""")
                appendLine("""    <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>""")
                appendLine("""    <item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>""")
                appendLine("""    <item id="style" href="style.css" media-type="text/css"/>""")
                if (hasCover) appendLine("""    <item id="cover" href="$coverName" media-type="${coverType?.mediaType}" properties="cover-image"/>""")
                appendLine("""  </manifest>""")
                appendLine("""  <spine toc="ncx">""")
                appendLine("""    <itemref idref="article"/>""")
                appendLine("""  </spine>""")
                appendLine("""</package>""")
            },
        )
        File(root, "OEBPS/style.css").writeText(
            """
            body { line-height: 1.8; }
            h1 { font-size: 1.4em; line-height: 1.4; margin: 0 0 0.6em; }
            .hoshi-news-source { font-size: 0.85em; opacity: 0.7; margin-bottom: 1.5em; }
            img { max-width: 100%; height: auto; }
            figcaption { font-size: 0.85em; opacity: 0.8; }
            """.trimIndent() + "\n",
        )
        File(root, "OEBPS/article.xhtml").writeText(
            buildString {
                appendLine("""<?xml version="1.0" encoding="UTF-8"?>""")
                appendLine("""<html xmlns="http://www.w3.org/1999/xhtml" xml:lang="${input.language}" lang="${input.language}">""")
                appendLine("""<head><meta charset="utf-8"/><title>${NewsArticleXhtml.escape(title)}</title><link rel="stylesheet" type="text/css" href="style.css"/></head>""")
                appendLine("""<body>""")
                appendLine("""<h1>${NewsArticleXhtml.escape(title)}</h1>""")
                val sourceLine = listOfNotNull(input.sourceName.takeIf { it.isNotBlank() }, published).joinToString(" · ")
                if (sourceLine.isNotEmpty()) appendLine("""<p class="hoshi-news-source">${NewsArticleXhtml.escape(sourceLine)}</p>""")
                appendLine(input.bodyXhtml.trim())
                appendLine("""</body>""")
                appendLine("""</html>""")
            },
        )
        File(root, "OEBPS/nav.xhtml").writeText(
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops">
            <head><title>${NewsArticleXhtml.escape(title)}</title></head>
            <body>
            <nav epub:type="toc"><ol><li><a href="article.xhtml">${NewsArticleXhtml.escape(title)}</a></li></ol></nav>
            </body>
            </html>
            """.trimIndent() + "\n",
        )
        File(root, "OEBPS/toc.ncx").writeText(
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1">
              <head><meta name="dtb:uid" content="$identifier"/><meta name="dtb:depth" content="1"/></head>
              <docTitle><text>${NewsArticleXhtml.escape(title)}</text></docTitle>
              <navMap>
                <navPoint id="article" playOrder="1"><navLabel><text>${NewsArticleXhtml.escape(title)}</text></navLabel><content src="article.xhtml"/></navPoint>
              </navMap>
            </ncx>
            """.trimIndent() + "\n",
        )
        if (hasCover) File(root, "OEBPS/$coverName").writeBytes(input.cover!!)
    }

    private data class ImageType(val extension: String, val mediaType: String)

    /** Sniffs the container from magic bytes; sites often serve PNG or WebP under a `.jpg` name. */
    private fun imageType(bytes: ByteArray): ImageType? = when {
        bytes.size < 12 -> null
        bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() -> ImageType("jpg", "image/jpeg")
        bytes[0] == 0x89.toByte() && bytes[1] == 'P'.code.toByte() && bytes[2] == 'N'.code.toByte() && bytes[3] == 'G'.code.toByte() -> ImageType("png", "image/png")
        bytes[0] == 'R'.code.toByte() && bytes[1] == 'I'.code.toByte() && bytes[2] == 'F'.code.toByte() && bytes[3] == 'F'.code.toByte() &&
            bytes[8] == 'W'.code.toByte() && bytes[9] == 'E'.code.toByte() && bytes[10] == 'B'.code.toByte() && bytes[11] == 'P'.code.toByte() -> ImageType("webp", "image/webp")
        else -> null
    }
}
