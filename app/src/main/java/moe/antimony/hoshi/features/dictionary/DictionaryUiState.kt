package moe.antimony.hoshi.features.dictionary

import moe.antimony.hoshi.dictionary.DictionaryInfo
import moe.antimony.hoshi.dictionary.DictionaryType
import moe.antimony.hoshi.dictionary.DictionaryUpdateCandidate

internal data class DictionaryUiState(
    val selectedType: DictionaryType = DictionaryType.Term,
    val dictionaries: Map<DictionaryType, List<DictionaryInfo>> = emptyMap(),
    val updatableDictionaries: List<DictionaryUpdateCandidate> = emptyList(),
    val settings: DictionarySettings = DictionarySettings(),
    val isImporting: Boolean = false,
    val isUpdating: Boolean = false,
    val currentImportMessage: String? = null,
    val errorMessage: String? = null,
) {
    val currentDictionaries: List<DictionaryInfo>
        get() = dictionaries[selectedType].orEmpty()
}
