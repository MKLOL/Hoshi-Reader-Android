package moe.antimony.hoshi.features.usage

import moe.antimony.hoshi.features.dictionary.LookupPopupItem
import moe.antimony.hoshi.features.reader.ReaderSelectionData

/**
 * Logs a word press and what the dictionaries made of it: the matched text and the entry's
 * headword when something matched (たべた and 食べた both log 食べる, so repeats group
 * together), otherwise the text the press scanned.
 */
internal fun ReaderUsageSession.logLookup(
    selection: ReaderSelectionData,
    popup: LookupPopupItem?,
    source: UsageLookupSource,
    page: Int? = null,
) {
    val first = popup?.state?.results?.firstOrNull()
    wordLookedUp(
        text = first?.matched ?: selection.text,
        source = source,
        found = first != null,
        page = page,
        term = first?.term?.expression,
    )
}
