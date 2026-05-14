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
    return MokuroTextBox(
        left = xMin.toInt(),
        top = yMin.toInt(),
        width = (xMax - xMin).toInt().coerceAtLeast(0),
        height = (yMax - yMin).toInt().coerceAtLeast(0),
        fontSize = fontSize.toInt().coerceAtLeast(1),
        vertical = vertical,
        lines = lines,
    )
}

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
