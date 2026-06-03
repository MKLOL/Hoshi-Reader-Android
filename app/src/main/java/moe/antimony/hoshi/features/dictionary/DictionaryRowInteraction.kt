package moe.antimony.hoshi.features.dictionary

internal object DictionaryRowInteraction {
    enum class Area {
        DragHandle,
        Content,
        EnableSwitch,
    }

    fun canRevealDeleteOnLongPress(area: Area): Boolean =
        area == Area.Content
}
