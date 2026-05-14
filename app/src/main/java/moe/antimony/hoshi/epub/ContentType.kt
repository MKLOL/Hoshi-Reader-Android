package moe.antimony.hoshi.epub

import kotlinx.serialization.Serializable
import java.io.File

/**
 * On-disk content format of a book directory. Hoshi originally handled only EPUBs;
 * mokuro manga support adds a parallel content path that reuses the bookshelf,
 * dictionary lookup, and Anki mining while swapping out the parser and reader.
 *
 * The type is intentionally **not** persisted in [BookMetadata]: that sidecar is shared
 * with the iOS app, which would reject unknown fields. Instead the type is derived from
 * disk structure — a mokuro book directory carries a [MOKURO_SIDECAR_FILE].
 */
@Serializable
enum class ContentType {
    Epub,
    Mokuro,
}

/**
 * Sidecar file written into a mokuro book directory by the importer (a verbatim copy of
 * the mokuro tool's `.mokuro` JSON output). Also the discriminator used by [bookContentType].
 */
const val MOKURO_SIDECAR_FILE: String = "mokuro.json"

/** Derives a book directory's [ContentType] from its on-disk structure. */
fun bookContentType(bookRoot: File): ContentType =
    if (bookRoot.resolve(MOKURO_SIDECAR_FILE).isFile) ContentType.Mokuro else ContentType.Epub
