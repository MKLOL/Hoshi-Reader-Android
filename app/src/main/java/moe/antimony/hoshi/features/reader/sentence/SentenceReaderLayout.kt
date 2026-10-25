package moe.antimony.hoshi.features.reader.sentence

import android.content.Context

/** Arrangement of the sentence and translation panes, independent of text writing direction. */
enum class SentenceReaderLayout(val preferenceValue: String) {
    Stacked("stacked"),
    SideBySide("side-by-side");

    companion object {
        fun fromPreferenceValue(value: String?): SentenceReaderLayout =
            entries.firstOrNull { it.preferenceValue == value } ?: Stacked
    }
}

/** A device-local pane preference; it does not become part of synced reader settings. */
class SentenceReaderLayoutStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        "sentence-reader-layout",
        Context.MODE_PRIVATE,
    )

    fun load(): SentenceReaderLayout = SentenceReaderLayout.fromPreferenceValue(
        preferences.getString("layout", null),
    )

    fun save(layout: SentenceReaderLayout) {
        preferences.edit().putString("layout", layout.preferenceValue).apply()
    }
}
