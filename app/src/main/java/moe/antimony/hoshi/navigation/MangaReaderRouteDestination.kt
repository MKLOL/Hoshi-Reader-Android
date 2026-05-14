package moe.antimony.hoshi.navigation

import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import moe.antimony.hoshi.LocalHoshiAppContainer
import moe.antimony.hoshi.features.dictionary.DictionarySettings
import moe.antimony.hoshi.features.mangareader.MangaReaderLoadState
import moe.antimony.hoshi.features.mangareader.MangaReaderLoader
import moe.antimony.hoshi.features.mangareader.MangaReaderScreen
import moe.antimony.hoshi.features.reader.ReaderSettings

/**
 * Navigation entry point for the mokuro manga reader route (`AppRoute.MangaReaderRoute`).
 *
 * Loads the book directory + `mokuro.json` + saved bookmark off the main thread (mirroring
 * [ReaderRouteDestination]'s loading / error / ready states) and hands a ready
 * [moe.antimony.hoshi.mokuro.MokuroBook] to [MangaReaderScreen], which renders the page
 * WebView, RTL navigation, dictionary lookup and bookmark persistence.
 *
 * The signature is fixed by [AppShell] and must not change.
 */
@Composable
internal fun MangaReaderRouteDestination(
    bookId: String,
    readerSettings: ReaderSettings,
    onReaderSettingsChange: (ReaderSettings) -> Unit,
    onReaderKeyEventHandlerChange: (((KeyEvent) -> Boolean)?) -> Unit,
    onBookmarkSaved: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val appContainer = LocalHoshiAppContainer.current
    val systemDark = isSystemInDarkTheme()
    val backgroundModifier = modifier
        .fillMaxSize()
        .background(Color(readerSettings.backgroundColor(systemDark)))

    val loader = remember(appContainer) { MangaReaderLoader(appContainer.bookRepository) }
    val loadState by produceState<MangaReaderLoadState>(MangaReaderLoadState.Loading, bookId, loader) {
        value = MangaReaderLoadState.Loading
        value = loader.load(bookId)
    }

    var dictionarySettings by remember { mutableStateOf(DictionarySettings()) }
    LaunchedEffect(appContainer) {
        appContainer.dictionarySettingsRepository.settings.collect { settings ->
            dictionarySettings = settings
        }
    }

    when (val state = loadState) {
        MangaReaderLoadState.Loading -> Box(
            modifier = backgroundModifier,
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator()
        }
        is MangaReaderLoadState.Error -> Box(
            modifier = backgroundModifier,
            contentAlignment = Alignment.Center,
        ) {
            Text(state.message)
        }
        is MangaReaderLoadState.Ready -> MangaReaderScreen(
            book = state.book,
            bookRoot = state.bookRoot,
            initialPageIndex = state.initialPageIndex,
            repository = appContainer.bookRepository,
            readerSettings = readerSettings,
            dictionarySettings = dictionarySettings,
            onReaderKeyEventHandlerChange = onReaderKeyEventHandlerChange,
            onBookmarkSaved = onBookmarkSaved,
            onClose = onClose,
            modifier = modifier.fillMaxSize(),
        )
    }
}
