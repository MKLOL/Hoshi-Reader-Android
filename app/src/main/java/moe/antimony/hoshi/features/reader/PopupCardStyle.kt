package moe.antimony.hoshi.features.reader

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import moe.antimony.hoshi.ui.theme.PopupReadability

/** Null fields follow this window's defaults; editing one control leaves the others adaptive. */
@Serializable
data class PopupCardStyle(
    val width: Double? = null,
    val height: Double? = null,
    val scale: Double? = null,
    val fontScale: Double? = null,
) {
    fun normalized() = copy(
        width = width?.takeIf { it.isFinite() }?.coerceIn(100.0, 1600.0),
        height = height?.takeIf { it.isFinite() }?.coerceIn(100.0, 2000.0),
        scale = scale?.takeIf { it.isFinite() }?.coerceIn(0.5, 3.0),
        fontScale = fontScale?.takeIf { it.isFinite() }?.coerceIn(0.5, 3.0),
    )

    internal fun encode(): String = cardStyleJson.encodeToString(normalized())

    companion object {
        internal fun decode(value: String?): PopupCardStyle = value?.let {
            runCatching { cardStyleJson.decodeFromString<PopupCardStyle>(it).normalized() }.getOrNull()
        } ?: PopupCardStyle()
    }
}

private val cardStyleJson = Json { ignoreUnknownKeys = true }

internal data class ResolvedPopupCardStyle(
    val width: Double,
    val height: Double,
    val scale: Double,
    val fontScale: Double,
) {
    fun asOverrides() = PopupCardStyle(width, height, scale, fontScale)
}

internal fun PopupCardStyle.resolveDictionary(
    defaults: PopupReadability,
    legacyWidth: Int = 320,
    legacyHeight: Int = 250,
    legacyScale: Double = 1.0,
): ResolvedPopupCardStyle = normalized().let {
    ResolvedPopupCardStyle(
        width = it.width ?: legacyWidth * defaults.frameScale,
        height = it.height ?: legacyHeight * defaults.frameScale,
        scale = it.scale ?: legacyScale.coerceIn(0.8, 1.5) * defaults.textScale,
        fontScale = it.fontScale ?: 1.0,
    )
}

internal fun ReaderSettings.dictionaryCardStyle(defaults: PopupReadability): ResolvedPopupCardStyle =
    dictionaryCard.resolveDictionary(defaults, popupWidth, popupHeight, popupScale)

internal fun PopupCardStyle.resolveTranslation(
    defaults: PopupReadability,
    windowWidth: Double,
    windowHeight: Double,
): ResolvedPopupCardStyle = normalized().let {
    ResolvedPopupCardStyle(
        width = it.width ?: if (defaults.textScale > 1.0) maxOf(520.0, windowWidth * 0.86) else 520.0,
        height = it.height ?: maxOf(320.0, windowHeight * 0.78),
        scale = it.scale ?: 1.0,
        fontScale = it.fontScale ?: defaults.textScale,
    )
}

internal fun ReaderSettings.resetDictionaryCard(): ReaderSettings = copy(
    dictionaryCard = PopupCardStyle(), popupWidth = 320, popupHeight = 250, popupScale = 1.0,
    popupFullWidth = false, popupActionBar = false, popupSwipeToDismiss = true, popupSwipeThreshold = 30,
    popupReducedMotionScrolling = false, popupReducedMotionScrollPercent = 100, popupReducedMotionSwipeThreshold = 40,
)
