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

    /** Easy-Japanese news listings; saved articles become ordinary EPUB books. */
    @Serializable
    data object NewsRoute : AppRoute

    @Serializable
    data object SettingsRoute : AppRoute

    @Serializable
    data class SettingsDetailRoute(
        val section: SettingsDetailSection,
    ) : AppRoute

    /** Reading statistics: streak, heatmap and every book's time; pushed from Books or Settings. */
    @Serializable
    data object StatisticsRoute : AppRoute

    @Serializable
    data class StatisticsBookRoute(
        val bookId: String,
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
