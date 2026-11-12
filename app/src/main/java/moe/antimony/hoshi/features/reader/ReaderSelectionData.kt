package moe.antimony.hoshi.features.reader

data class ReaderSelectionData(
    val text: String,
    val sentence: String,
    val rect: ReaderSelectionRect,
    val normalizedOffset: Int?,
    val sentenceOffset: Int? = null,
    val mangaActions: MangaActionPlacement? = null,
)

data class ReaderSelectionRect(
    val x: Double,
    val y: Double,
    val width: Double,
    val height: Double,
)


/** Geometry for the selected manga bubble and its Translate/Copy row in host coordinates. */
data class MangaActionPlacement(
    val bubble: ReaderSelectionRect,
    val row: ReaderSelectionRect,
    val side: MangaActionSide,
)

enum class MangaActionSide { Above, Below, Left, Right }
