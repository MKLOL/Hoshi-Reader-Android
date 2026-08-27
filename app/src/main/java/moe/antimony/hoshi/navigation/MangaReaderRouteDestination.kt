package moe.antimony.hoshi.navigation

import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import moe.antimony.hoshi.features.sync.http.HttpSyncActiveBooks

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
    val lifecycleOwner = LocalLifecycleOwner.current
    val syncScope = rememberCoroutineScope()
    val activeBookLease = remember(bookId) { HttpSyncActiveBooks.Lease() }
    var bookmarkReloadKey by remember(bookId) { mutableIntStateOf(0) }
    val systemDark = isSystemInDarkTheme()
    val backgroundModifier = modifier
        .fillMaxSize()
        .background(Color(readerSettings.backgroundColor(systemDark)))

    val loader = remember(appContainer) {
        MangaReaderLoader(appContainer.bookRepository, appContainer.mokuroParser)
    }
    DisposableEffect(bookId, activeBookLease) {
        onDispose {
            appContainer.appScope.launch {
                delay(ACTIVE_READER_CLOSE_GRACE_MS)
                activeBookLease.release()
                appContainer.httpSyncBookmarkScheduler.refreshAfterReaderClosed()
            }
        }
    }
    val loadState by produceState<MangaReaderLoadState>(
        MangaReaderLoadState.Loading,
        bookId,
        loader,
        bookmarkReloadKey,
    ) {
        value = MangaReaderLoadState.Loading
        value = loader.load(bookId) { syncId ->
            activeBookLease.acquire(syncId)
            appContainer.httpSyncBookmarkScheduler.refreshBeforeOpen()
        }
    }

    DisposableEffect(lifecycleOwner, bookId) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                syncScope.launch { appContainer.httpSyncBookmarkScheduler.refreshBeforeOpen() }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
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
        is MangaReaderLoadState.Ready -> {
            val syncId = state.syncId
            LaunchedEffect(syncId) {
                appContainer.httpSyncBatchState.remoteBookmarkUpdates.collect { changedId ->
                    if (changedId == syncId) bookmarkReloadKey += 1
                }
            }
            MangaReaderScreen(
                book = state.book,
                bookRoot = state.bookRoot,
                initialPageIndex = state.initialPageIndex,
                repository = appContainer.bookRepository,
                readerSettings = readerSettings,
                onReaderSettingsChange = onReaderSettingsChange,
                dictionarySettings = dictionarySettings,
                onReaderKeyEventHandlerChange = onReaderKeyEventHandlerChange,
                onBookmarkSaved = onBookmarkSaved,
                onClose = onClose,
                modifier = modifier.fillMaxSize(),
            )
        }
    }
}

private const val ACTIVE_READER_CLOSE_GRACE_MS = 1_000L
