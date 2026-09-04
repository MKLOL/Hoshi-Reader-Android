package moe.antimony.hoshi.features.sync.integration

import moe.antimony.hoshi.epub.BookMetadata
import moe.antimony.hoshi.epub.BookRepository
import moe.antimony.hoshi.epub.Bookmark
import kotlinx.serialization.json.Json
import moe.antimony.hoshi.features.ai.PretranslationStore
import moe.antimony.hoshi.features.sync.http.PretranslationEntryBlob
import moe.antimony.hoshi.features.sync.http.PretranslationsBlob
import moe.antimony.hoshi.features.sync.http.deriveSyncId
import java.io.File
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import kotlin.random.Random

/**
 * Deterministic, realistic books for the integration suites. The same corpus, uploaded by the
 * real Android engine, is snapshotted into the iOS repo (`Tests/Fixtures/sync-corpus.json`) so
 * the iOS integration tests sync from zero against exactly what Android publishes.
 *
 * The page images are real (tiny) JPEGs so cover generation on both platforms decodes them.
 */
internal object SyncCorpus {
    const val MANGA_TITLE = "Integration Manga"
    const val NOVEL_TITLE = "Integration Novel"
    val MANGA_SYNC_ID: String = deriveSyncId(MANGA_TITLE)!!
    val NOVEL_SYNC_ID: String = deriveSyncId(NOVEL_TITLE)!!

    const val BUBBLE_TEXT = "こんにちは"
    const val NOVEL_SENTENCE = "食べる。"
    /** The sentence as the translation store normalizes it before hashing. */
    const val NOVEL_SENTENCE_NORMALIZED = "食べる"

    /** Content hash a sentence entry must carry: first 8 bytes of sha256(normalized text), hex. */
    fun sentenceHash(normalized: String = NOVEL_SENTENCE_NORMALIZED): String =
        MessageDigest.getInstance("SHA-256").digest(normalized.toByteArray(Charsets.UTF_8))
            .take(8).joinToString("") { "%02x".format(it) }
    const val NOVEL_CHAPTER_V2 = "走る。"

    val pageJpegs: List<ByteArray> = listOf(RED_JPEG, GREEN_JPEG, BLUE_JPEG).map { Base64.getDecoder().decode(it) }

    /**
     * Materializes a mokuro volume the way the importer leaves it on disk: `images/NNNN.jpg`,
     * `mokuro.json`, an import-time root cover copy, and metadata with a persisted sync id.
     * The bookshelf's post-import hook is the device's job ([SyncDevice.importManga]).
     */
    suspend fun importManga(
        repo: BookRepository,
        title: String = MANGA_TITLE,
        pageCount: Int = 3,
        extraBytesPerPage: Int = 0,
    ): File {
        require(pageCount in 1..pageJpegs.size * 10)
        val root = repo.createBookDirectoryForImportedTitle(title)
        root.resolve("images").mkdirs()
        val pages = StringBuilder()
        for (index in 0 until pageCount) {
            val name = "images/%04d.jpg".format(index + 1)
            val base = pageJpegs[index % pageJpegs.size]
            // Incompressible filler, so the zip really is large and exercises multipart upload.
            val bytes = if (extraBytesPerPage > 0) base + Random(index + 1).nextBytes(extraBytesPerPage) else base
            root.resolve(name).writeBytes(bytes)
            if (index > 0) pages.append(',')
            pages.append(
                """{"version":"0.1.8","img_width":48,"img_height":64,"img_path":"$name","blocks":[""" +
                    """{"box":[8,8,40,56],"vertical":true,"font_size":12,""" +
                    """"lines_coords":[[[8,8],[40,8],[40,56],[8,56]]],"lines":["$BUBBLE_TEXT"]}]}""",
            )
        }
        root.resolve("mokuro.json").writeText(
            """{"version":"0.1.8","title":"$title","volume":"$title","title_uuid":"11111111-1111-1111-1111-111111111111",""" +
                """"volume_uuid":"22222222-2222-2222-2222-222222222222","pages":[$pages]}""",
        )
        val cover = repo.metadataCoverPath(root, "images/0001.jpg")
        registerImport(repo, root, title, cover)
        return root
    }

    /** Materializes an unpacked EPUB the way the importer leaves it, with a manifest-declared cover. */
    suspend fun importNovel(
        repo: BookRepository,
        title: String = NOVEL_TITLE,
        chapterText: String = NOVEL_SENTENCE,
    ): File {
        val root = repo.createBookDirectoryForImportedTitle(title)
        writeEpub(root, title, chapterText)
        val cover = repo.metadataCoverPath(root, "OEBPS/images/cover.jpg")
        registerImport(repo, root, title, cover)
        return root
    }

    fun writeEpub(root: File, title: String, chapterText: String) {
        root.resolve("META-INF").mkdirs()
        root.resolve("OEBPS/images").mkdirs()
        root.resolve("mimetype").writeText("application/epub+zip")
        root.resolve("META-INF/container.xml").writeText(
            """<?xml version="1.0"?>
            <container xmlns="urn:oasis:names:tc:opendocument:xmlns:container" version="1.0">
              <rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles>
            </container>""".trimIndent(),
        )
        root.resolve("OEBPS/content.opf").writeText(
            """<?xml version="1.0" encoding="UTF-8"?>
            <package xmlns="http://www.idpf.org/2007/opf" unique-identifier="book-id" version="3.0">
              <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                <dc:identifier id="book-id">integration-novel</dc:identifier><dc:title>$title</dc:title><dc:language>ja</dc:language>
                <meta name="cover" content="cover-image"/>
              </metadata>
              <manifest>
                <item id="cover-image" href="images/cover.jpg" media-type="image/jpeg" properties="cover-image"/>
                <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
                <item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>
                <item id="chapter" href="chapter.xhtml" media-type="application/xhtml+xml"/>
              </manifest>
              <spine toc="ncx"><itemref idref="chapter"/></spine>
            </package>""".trimIndent(),
        )
        // Real books ship both navigation documents; iOS's EPUBKit refuses a book without an NCX.
        root.resolve("OEBPS/toc.ncx").writeText(
            """<?xml version="1.0" encoding="UTF-8"?>
            <ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1">
              <head><meta name="dtb:uid" content="integration-novel"/></head>
              <docTitle><text>$title</text></docTitle>
              <navMap><navPoint id="np1" playOrder="1"><navLabel><text>Chapter 1</text></navLabel><content src="chapter.xhtml"/></navPoint></navMap>
            </ncx>""".trimIndent(),
        )
        root.resolve("OEBPS/nav.xhtml").writeText(
            """<?xml version="1.0" encoding="UTF-8"?>
            <html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops"><head><title>$title</title></head>
            <body><nav epub:type="toc"><ol><li><a href="chapter.xhtml">Chapter 1</a></li></ol></nav></body></html>""".trimIndent(),
        )
        root.resolve("OEBPS/chapter.xhtml").writeText(
            """<?xml version="1.0" encoding="UTF-8"?>
            <html xmlns="http://www.w3.org/1999/xhtml"><head><title>$title</title></head><body><p>$chapterText</p></body></html>""".trimIndent(),
        )
        root.resolve("OEBPS/images/cover.jpg").writeBytes(pageJpegs[0])
    }

    /** Import-time metadata. Fixed id and timestamp so the published corpus is reproducible. */
    private suspend fun registerImport(repo: BookRepository, root: File, title: String, cover: String?) {
        repo.saveMetadata(
            root,
            BookMetadata(
                id = UUID.nameUUIDFromBytes(title.toByteArray(Charsets.UTF_8)).toString(),
                title = title,
                cover = cover,
                folder = root.name,
                lastAccess = 0.0,
                syncId = deriveSyncId(title),
                importedAt = IMPORTED_AT,
            ),
        )
    }

    /** The corpus's fixed import stamp; see [registerImport]. */
    const val IMPORTED_AT = "2026-09-01T00:00:00Z"

    /**
     * The offline bubble-translation blob exactly as the desktop tool and both clients define it
     * (`PretranslationsBlob`), addressed by mokuro block and guarded by the text hash.
     */
    fun pretranslationsBlobJson(syncId: String = MANGA_SYNC_ID, title: String = MANGA_TITLE, pageCount: Int = 3): String {
        val entries = (0 until pageCount).associate { page ->
            "p${page}b0" to PretranslationEntryBlob(
                text = BUBBLE_TEXT,
                hash = PretranslationStore.textHash(BUBBLE_TEXT),
                translation = "Hello",
                explanation = "A greeting.",
            )
        }
        val blob = PretranslationsBlob(
            version = 1, syncId = syncId, title = title, model = "integration-model",
            promptId = "integration-prompt", generatedAt = "2026-09-01T00:00:00Z", entries = entries,
        )
        return Json { encodeDefaults = true }.encodeToString(PretranslationsBlob.serializer(), blob)
    }

    fun bookmark(chapter: Int, appleSeconds: Double): Bookmark =
        Bookmark(chapterIndex = chapter, progress = 0.0, characterCount = chapter * 10, lastModified = appleSeconds)

    private const val RED_JPEG =
        "/9j/4AAQSkZJRgABAQAASABIAAD/4QBMRXhpZgAATU0AKgAAAAgAAYdpAAQAAAABAAAAGgAAAAAAA6ABAAMAAAABAAEAAKACAAQAAAABAAAAMKADAAQAAAABAAAAQAAAAAD/7QA4UGhvdG9zaG9wIDMuMAA4QklNBAQAAAAAAAA4QklNBCUAAAAAABDUHYzZjwCyBOmACZjs+EJ+/8AAEQgAQAAwAwEiAAIRAQMRAf/EAB8AAAEFAQEBAQEBAAAAAAAAAAABAgMEBQYHCAkKC//EALUQAAIBAwMCBAMFBQQEAAABfQECAwAEEQUSITFBBhNRYQcicRQygZGhCCNCscEVUtHwJDNicoIJChYXGBkaJSYnKCkqNDU2Nzg5OkNERUZHSElKU1RVVldYWVpjZGVmZ2hpanN0dXZ3eHl6g4SFhoeIiYqSk5SVlpeYmZqio6Slpqeoqaqys7S1tre4ubrCw8TFxsfIycrS09TV1tfY2drh4uPk5ebn6Onq8fLz9PX29/j5+v/EAB8BAAMBAQEBAQEBAQEAAAAAAAABAgMEBQYHCAkKC//EALURAAIBAgQEAwQHBQQEAAECdwABAgMRBAUhMQYSQVEHYXETIjKBCBRCkaGxwQkjM1LwFWJy0QoWJDThJfEXGBkaJicoKSo1Njc4OTpDREVGR0hJSlNUVVZXWFlaY2RlZmdoaWpzdHV2d3h5eoKDhIWGh4iJipKTlJWWl5iZmqKjpKWmp6ipqrKztLW2t7i5usLDxMXGx8jJytLT1NXW19jZ2uLj5OXm5+jp6vLz9PX29/j5+v/bAEMABAQEBAQEBgQEBgkGBgYJDAkJCQkMDwwMDAwMDxIPDw8PDw8SEhISEhISEhUVFRUVFRkZGRkZHBwcHBwcHBwcHP/bAEMBBAUFBwcHDAcHDB0UEBQdHR0dHR0dHR0dHR0dHR0dHR0dHR0dHR0dHR0dHR0dHR0dHR0dHR0dHR0dHR0dHR0dHf/dAAQAA//aAAwDAQACEQMRAD8A8vooor4k/qAKKKKACiiigD//0PL6KKK+JP6gCiiigAooooA//9Hy+iiiviT+oAooooAKKKKAP//S8vooor4k/qAKKKKACiiigD//2Q=="
    private const val GREEN_JPEG =
        "/9j/4AAQSkZJRgABAQAASABIAAD/4QBMRXhpZgAATU0AKgAAAAgAAYdpAAQAAAABAAAAGgAAAAAAA6ABAAMAAAABAAEAAKACAAQAAAABAAAAMKADAAQAAAABAAAAQAAAAAD/7QA4UGhvdG9zaG9wIDMuMAA4QklNBAQAAAAAAAA4QklNBCUAAAAAABDUHYzZjwCyBOmACZjs+EJ+/8AAEQgAQAAwAwEiAAIRAQMRAf/EAB8AAAEFAQEBAQEBAAAAAAAAAAABAgMEBQYHCAkKC//EALUQAAIBAwMCBAMFBQQEAAABfQECAwAEEQUSITFBBhNRYQcicRQygZGhCCNCscEVUtHwJDNicoIJChYXGBkaJSYnKCkqNDU2Nzg5OkNERUZHSElKU1RVVldYWVpjZGVmZ2hpanN0dXZ3eHl6g4SFhoeIiYqSk5SVlpeYmZqio6Slpqeoqaqys7S1tre4ubrCw8TFxsfIycrS09TV1tfY2drh4uPk5ebn6Onq8fLz9PX29/j5+v/EAB8BAAMBAQEBAQEBAQEAAAAAAAABAgMEBQYHCAkKC//EALURAAIBAgQEAwQHBQQEAAECdwABAgMRBAUhMQYSQVEHYXETIjKBCBRCkaGxwQkjM1LwFWJy0QoWJDThJfEXGBkaJicoKSo1Njc4OTpDREVGR0hJSlNUVVZXWFlaY2RlZmdoaWpzdHV2d3h5eoKDhIWGh4iJipKTlJWWl5iZmqKjpKWmp6ipqrKztLW2t7i5usLDxMXGx8jJytLT1NXW19jZ2uLj5OXm5+jp6vLz9PX29/j5+v/bAEMABAQEBAQEBgQEBgkGBgYJDAkJCQkMDwwMDAwMDxIPDw8PDw8SEhISEhISEhUVFRUVFRkZGRkZHBwcHBwcHBwcHP/bAEMBBAUFBwcHDAcHDB0UEBQdHR0dHR0dHR0dHR0dHR0dHR0dHR0dHR0dHR0dHR0dHR0dHR0dHR0dHR0dHR0dHR0dHf/dAAQAA//aAAwDAQACEQMRAD8AwaKKK+DPxcKKKKACiiigD//QwaKKK+DPxcKKKKACiiigD//RwaKKK+DPxcKKKKACiiigD//SwaKKK+DPxcKKKKACiiigD//Z"
    private const val BLUE_JPEG =
        "/9j/4AAQSkZJRgABAQAASABIAAD/4QBMRXhpZgAATU0AKgAAAAgAAYdpAAQAAAABAAAAGgAAAAAAA6ABAAMAAAABAAEAAKACAAQAAAABAAAAMKADAAQAAAABAAAAQAAAAAD/7QA4UGhvdG9zaG9wIDMuMAA4QklNBAQAAAAAAAA4QklNBCUAAAAAABDUHYzZjwCyBOmACZjs+EJ+/8AAEQgAQAAwAwEiAAIRAQMRAf/EAB8AAAEFAQEBAQEBAAAAAAAAAAABAgMEBQYHCAkKC//EALUQAAIBAwMCBAMFBQQEAAABfQECAwAEEQUSITFBBhNRYQcicRQygZGhCCNCscEVUtHwJDNicoIJChYXGBkaJSYnKCkqNDU2Nzg5OkNERUZHSElKU1RVVldYWVpjZGVmZ2hpanN0dXZ3eHl6g4SFhoeIiYqSk5SVlpeYmZqio6Slpqeoqaqys7S1tre4ubrCw8TFxsfIycrS09TV1tfY2drh4uPk5ebn6Onq8fLz9PX29/j5+v/EAB8BAAMBAQEBAQEBAQEAAAAAAAABAgMEBQYHCAkKC//EALURAAIBAgQEAwQHBQQEAAECdwABAgMRBAUhMQYSQVEHYXETIjKBCBRCkaGxwQkjM1LwFWJy0QoWJDThJfEXGBkaJicoKSo1Njc4OTpDREVGR0hJSlNUVVZXWFlaY2RlZmdoaWpzdHV2d3h5eoKDhIWGh4iJipKTlJWWl5iZmqKjpKWmp6ipqrKztLW2t7i5usLDxMXGx8jJytLT1NXW19jZ2uLj5OXm5+jp6vLz9PX29/j5+v/bAEMABAQEBAQEBgQEBgkGBgYJDAkJCQkMDwwMDAwMDxIPDw8PDw8SEhISEhISEhUVFRUVFRkZGRkZHBwcHBwcHBwcHP/bAEMBBAUFBwcHDAcHDB0UEBQdHR0dHR0dHR0dHR0dHR0dHR0dHR0dHR0dHR0dHR0dHR0dHR0dHR0dHR0dHR0dHR0dHf/dAAQAA//aAAwDAQACEQMRAD8A8jooor+hj4MKKKKACiiigD//0PI6KKK/oY+DCiiigAooooA//9HyOiiiv6GPgwooooAKKKKAP//S8jooor+hj4MKKKKACiiigD//2Q=="
}
