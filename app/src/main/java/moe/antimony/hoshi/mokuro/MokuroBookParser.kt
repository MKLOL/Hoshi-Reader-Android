package moe.antimony.hoshi.mokuro

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
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

    /** Parses [bookRoot]/`mokuro.json`. Throws if the directory is not a mokuro book. */
    fun parse(bookRoot: File): MokuroBook {
        val sidecar = bookRoot.resolve(MOKURO_SIDECAR_FILE)
        require(sidecar.isFile) { "Not a mokuro book directory: ${bookRoot.absolutePath}" }
        val raw = json.decodeFromString(RawMokuro.serializer(), sidecar.readText())
        val pages = raw.pages.mapIndexed { index, page -> page.toMokuroPage(index) }
        require(pages.isNotEmpty()) { "mokuro.json contains no pages" }
        return MokuroBook(
            title = raw.volume?.ifBlank { null }
                ?: raw.title?.ifBlank { null }
                ?: bookRoot.nameWithoutExtension,
            pages = pages,
            coverImagePath = pages.firstOrNull()?.imagePath,
        )
    }
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
 * Adaptive cap on mokuro's per-block `font_size` so the revealed OCR plate doesn't grow
 * many times past the artwork's actual character size. mokuro's `font_size` is OCR-derived
 * and frequently overshoots — slightly for most boxes (5-25%), dramatically for the ~6%
 * where the OCR clearly got it wrong. The artwork's drawn characters fit the OCR-detected
 * box, so a font_size that *also* fits the box is approximately "no larger than the drawn
 * characters." [SAFETY_FOR_SMALL_FONT] keeps the cap permissive for small-font bubbles
 * (preserves the slight-zoom feel small text already has); [SAFETY_FOR_BIG_FONT] is strict
 * for large-font bubbles, where overshoot is the visible eyesore. Linear interpolation
 * between [SMALL_FONT_THRESHOLD_PX] and [BIG_FONT_THRESHOLD_PX].
 *
 * The runtime wrap-fallback in [moe.antimony.hoshi.features.mangareader.MangaPageHtml]
 * then handles the cases where the clamp result is still uncomfortably small (e.g. mokuro
 * tagging a tall narrow bubble as horizontal — `font_size` clamps to a one-line strip,
 * but switching to a word-wrapped multi-row layout gets a much larger, readable glyph).
 */
internal fun clampMokuroFontSize(
    mokuroFontSize: Double,
    boxWidth: Int,
    boxHeight: Int,
    vertical: Boolean,
    lines: List<String>,
): Int {
    val mokuroFs = mokuroFontSize.toInt().coerceAtLeast(1)
    val nonEmptyLines = lines.filter { it.isNotEmpty() }
    if (nonEmptyLines.isEmpty() || boxWidth <= 0 || boxHeight <= 0) return mokuroFs
    val numLines = nonEmptyLines.size
    val maxCharsPerLine = nonEmptyLines.maxOf { it.codePointCount(0, it.length) }
    if (maxCharsPerLine <= 0) return mokuroFs
    val fit = if (vertical) {
        minOf(
            boxWidth.toDouble() / numLines,
            boxHeight.toDouble() / (maxCharsPerLine * MANGA_OCR_LINE_HEIGHT),
        )
    } else {
        minOf(
            boxWidth.toDouble() / maxCharsPerLine,
            boxHeight.toDouble() / (numLines * MANGA_OCR_LINE_HEIGHT),
        )
    }
    if (fit <= 0.0 || !fit.isFinite()) return mokuroFs
    val safety = adaptiveFontSizeSafety(mokuroFs.toDouble())
    val cap = fit * safety
    return minOf(mokuroFs.toDouble(), cap).toInt().coerceAtLeast(1)
}

private fun adaptiveFontSizeSafety(mokuroFontSize: Double): Double {
    if (mokuroFontSize <= SMALL_FONT_THRESHOLD_PX) return SAFETY_FOR_SMALL_FONT
    if (mokuroFontSize >= BIG_FONT_THRESHOLD_PX) return SAFETY_FOR_BIG_FONT
    val t = (mokuroFontSize - SMALL_FONT_THRESHOLD_PX) /
        (BIG_FONT_THRESHOLD_PX - SMALL_FONT_THRESHOLD_PX)
    return SAFETY_FOR_SMALL_FONT + t * (SAFETY_FOR_BIG_FONT - SAFETY_FOR_SMALL_FONT)
}

/** Must match the CSS `line-height` in [moe.antimony.hoshi.features.mangareader.MangaPageHtml]. */
private const val MANGA_OCR_LINE_HEIGHT = 1.1
private const val SMALL_FONT_THRESHOLD_PX = 40.0
private const val BIG_FONT_THRESHOLD_PX = 100.0
private const val SAFETY_FOR_SMALL_FONT = 1.5
private const val SAFETY_FOR_BIG_FONT = 1.0

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
