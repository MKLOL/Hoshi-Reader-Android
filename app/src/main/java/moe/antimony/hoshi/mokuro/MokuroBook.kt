package moe.antimony.hoshi.mokuro

import kotlinx.serialization.Serializable

/**
 * Parsed representation of a mokuro manga volume. Produced by [MokuroBookParser] from the
 * `mokuro.json` sidecar in a book directory and consumed by the manga reader.
 *
 * This is the manga counterpart of [moe.antimony.hoshi.epub.EpubBook]. It deliberately
 * stays a plain, reader-agnostic model so the manga reader feature can render it however
 * it likes (currently a WebView of absolutely-positioned, selectable OCR text boxes).
 */
data class MokuroBook(
    val title: String,
    val pages: List<MokuroPage>,
    /** Book-root-relative path of the image used as the bookshelf cover, if any. */
    val coverImagePath: String?,
)

/** A single manga page: one background image plus its OCR text-box overlays. */
@Serializable
data class MokuroPage(
    /** Zero-based page index within the volume. Doubles as the reader's bookmark position. */
    val index: Int,
    /** Book-root-relative path to the page image. */
    val imagePath: String,
    val imageWidth: Int,
    val imageHeight: Int,
    val textBoxes: List<MokuroTextBox>,
)

/**
 * One OCR text region, positioned in image-pixel coordinates relative to the page image.
 * The manga reader scales these to the rendered page size.
 */
@Serializable
data class MokuroTextBox(
    val left: Int,
    val top: Int,
    val width: Int,
    val height: Int,
    val fontSize: Int,
    /** True for vertical (top-to-bottom, right-to-left) Japanese text; false for horizontal. */
    val vertical: Boolean,
    /** OCR lines; for vertical text each entry is one column, ordered right-to-left. */
    val lines: List<String>,
    /**
     * This block's index in the source `mokuro.json` page, NOT its position in [textBoxes].
     * Blocks with an unusable box are dropped during parsing, so the two diverge — and the offline
     * pre-translation cache addresses bubbles by the mokuro index, which every platform and the
     * desktop tool agree on.
     */
    val blockIndex: Int = 0,
)
