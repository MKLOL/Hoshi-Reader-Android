package moe.antimony.hoshi.navigation

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

@Serializable
sealed interface AppRoute : NavKey {
    @Serializable
    data object MainRoute : AppRoute

    @Serializable
    data object BooksRoute : AppRoute

    @Serializable
    data object DictionaryRoute : AppRoute

    @Serializable
    data object SettingsRoute : AppRoute

    @Serializable
    data class SettingsDetailRoute(
        val section: SettingsDetailSection,
    ) : AppRoute

    @Serializable
    data class ReaderRoute(
        val bookId: String,
    ) : AppRoute

    @Serializable
    data class MangaReaderRoute(
        val bookId: String,
    ) : AppRoute

    /** Experimental one-sentence-at-a-time view of an EPUB, opened from the reader's menu. */
    @Serializable
    data class SentenceReaderRoute(
        val bookId: String,
    ) : AppRoute

    @Serializable
    data class SasayakiMatchRoute(
        val bookId: String,
    ) : AppRoute
}

@Serializable
enum class SettingsDetailSection {
    Dictionaries,
    Anki,
    ChatGpt,
    Appearance,
    Behavior,
    Advanced,
    Diagnostics,
    About,
}
