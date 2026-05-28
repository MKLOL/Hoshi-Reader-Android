package moe.antimony.hoshi.mokuro

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import moe.antimony.hoshi.epub.MOKURO_SIDECAR_FILE
import java.io.File

/**
 * Parses the `mokuro.json` sidecar — the raw mokuro tool output, copied verbatim into the
 * book directory by the importer — into a [MokuroBook].
 *
 * Pure Kotlin: unlike the EPUB parser this has no native/Rust dependency, since the mokuro
 * format is already structured JSON.
 */
class MokuroBookParser {
    private val json = Json { ignoreUnknownKeys = true }

    // Single-entry cache keyed by (sidecar path, mtime, size). The book-open path parses
    // the same mokuro.json twice in quick succession — once in BookshelfRepository to write
    // metadata sidecars, once in MangaReaderLoader when navigation lands on the reader.
    // Coalesces those into one parse + decode. Cleared automatically when the file changes.
    @Volatile private var cached: CachedParse? = null

    /** Parses [bookRoot]/`mokuro.json`. Throws if the directory is not a mokuro book. */
    @OptIn(ExperimentalSerializationApi::class)
    fun parse(bookRoot: File): MokuroBook {
        val sidecar = bookRoot.resolve(MOKURO_SIDECAR_FILE)
        require(sidecar.isFile) { "Not a mokuro book directory: ${bookRoot.absolutePath}" }
        val path = sidecar.absolutePath
        val mtime = sidecar.lastModified()
        val size = sidecar.length()
        cached?.let { hit ->
            if (hit.path == path && hit.mtime == mtime && hit.size == size) return hit.book
        }
        val raw = sidecar.inputStream().buffered().use { json.decodeFromStream<RawMokuro>(it) }
        val pages = raw.pages.mapIndexed { index, page -> page.toMokuroPage(index) }
        require(pages.isNotEmpty()) { "mokuro.json contains no pages" }
        val book = MokuroBook(
            title = raw.volume?.ifBlank { null }
                ?: raw.title?.ifBlank { null }
                ?: bookRoot.nameWithoutExtension,
            pages = pages,
            coverImagePath = pages.firstOrNull()?.imagePath,
        )
        cached = CachedParse(path = path, mtime = mtime, size = size, book = book)
        return book
    }

    private data class CachedParse(
        val path: String,
        val mtime: Long,
        val size: Long,
        val book: MokuroBook,
    )
}

private fun RawMokuroPage.toMokuroPage(index: Int): MokuroPage =
    MokuroPage(
        index = index,
        imagePath = imgPath,
        imageWidth = imgWidth,
        imageHeight = imgHeight,
        textBoxes = blocks.mapNotNull { it.toMokuroTextBox() },
    )

private fun RawMokuroBlock.toMokuroTextBox(): MokuroTextBox? {
    if (box.size < 4) return null
    // mokuro stores box as [xMin, yMin, xMax, yMax] in image-pixel coordinates.
    val xMin = box[0]
    val yMin = box[1]
    val xMax = box[2]
    val yMax = box[3]
    val width = (xMax - xMin).toInt().coerceAtLeast(0)
    val height = (yMax - yMin).toInt().coerceAtLeast(0)
    return MokuroTextBox(
        left = xMin.toInt(),
        top = yMin.toInt(),
        width = width,
        height = height,
        fontSize = clampMokuroFontSize(
            mokuroFontSize = fontSize,
            boxWidth = width,
            boxHeight = height,
            vertical = vertical,
            lines = lines,
        ),
        vertical = vertical,
        lines = lines,
    )
}

/**
 * Continuous boost curve over mokuro's reported drawn-glyph height (image pixels)
 * so the OCR-text reveal stays tappable for tiny chars without ballooning the plate
 * for already-big chars:
 *
 *  - `result = mokuroFs + max(0, READABLE_TARGET_PX - mokuroFs) × BOOST_STRENGTH`;
 *  - at `mokuroFs = 1` the boost lifts to ~15 px (still readable);
 *  - at `mokuroFs = 15` the boost lifts to ~22 px (comfortably tappable);
 *  - at `mokuroFs = 30` the boost is zero — bubbles already big in the artwork
 *    reveal at exactly mokuro's reported size (the "BIG TEXT keep it the same"
 *    half of the user-facing contract);
 *  - at `mokuroFs ≥ 30` the boost stays zero.
 *
 * Crucially the result depends only on `mokuroFs` — not on `boxWidth`, `boxHeight`,
 * `vertical`, or `lines` — so two bubbles whose mokuro-reported drawn-glyph height
 * is the same reveal at the same OCR text size regardless of how many characters
 * fit beside them in the bubble. The wrap-fallback in
 * [moe.antimony.hoshi.features.mangareader.MangaPageHtml] handles the cases where
 * mokuro mis-tagged a tall narrow vertical bubble as horizontal — switching that
 * one bubble to a word-wrapped layout gets a much larger, readable glyph.
 *
 * `boxWidth`/`boxHeight`/`vertical`/`lines` are kept on the signature for the
 * callers that already pass them — useful when future tuning needs the box dims
 * back, and keeps the change to this function purely internal.
 */
@Suppress("UNUSED_PARAMETER")
internal fun clampMokuroFontSize(
    mokuroFontSize: Double,
    boxWidth: Int,
    boxHeight: Int,
    vertical: Boolean,
    lines: List<String>,
): Int {
    val mokuroFs = mokuroFontSize.toInt().coerceAtLeast(1)
    val headroom = (READABLE_TARGET_PX - mokuroFs).coerceAtLeast(0.0)
    return (mokuroFs + headroom * BOOST_STRENGTH).toInt().coerceAtLeast(1)
}

// Target drawn-glyph height (image pixels) considered comfortably tappable. The
// boost in [clampMokuroFontSize] is the remaining headroom up to this target,
// scaled by [BOOST_STRENGTH], so very small artwork gets a big absolute bump and
// artwork already at or above the target gets none.
private const val READABLE_TARGET_PX = 30.0
private const val BOOST_STRENGTH = 0.5

// --- Raw mokuro tool schema (mokuro >= 0.2.x). Unknown fields are ignored so newer
// mokuro versions keep parsing; only the fields the reader needs are modelled here. ---

@Serializable
private data class RawMokuro(
    val title: String? = null,
    val volume: String? = null,
    val pages: List<RawMokuroPage> = emptyList(),
)

@Serializable
private data class RawMokuroPage(
    @SerialName("img_width") val imgWidth: Int = 0,
    @SerialName("img_height") val imgHeight: Int = 0,
    @SerialName("img_path") val imgPath: String = "",
    val blocks: List<RawMokuroBlock> = emptyList(),
)

@Serializable
private data class RawMokuroBlock(
    /** [xMin, yMin, xMax, yMax] in image pixels. */
    val box: List<Double> = emptyList(),
    val vertical: Boolean = false,
    @SerialName("font_size") val fontSize: Double = 0.0,
    val lines: List<String> = emptyList(),
)
