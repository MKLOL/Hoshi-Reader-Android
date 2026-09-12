package moe.antimony.hoshi.navigation

import android.util.Log
import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import moe.antimony.hoshi.features.reader.ReaderSettings
import moe.antimony.hoshi.features.reader.ReaderWebView
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import moe.antimony.hoshi.LocalHoshiAppContainer
import moe.antimony.hoshi.epub.BookEntry
import moe.antimony.hoshi.features.settings.collectAsLoadedSettings
import moe.antimony.hoshi.features.sync.SyncDirection
import moe.antimony.hoshi.features.sync.SyncResult
import moe.antimony.hoshi.features.sync.http.syncIdForMetadata
import moe.antimony.hoshi.features.sync.http.HttpSyncActiveBooks
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

@Composable
internal fun ReaderRouteDestination(
    bookId: String,
    stateHolder: ReaderRouteStateHolder,
    readerSettings: ReaderSettings,
    onReaderSettingsChange: (ReaderSettings) -> Unit,
    onReaderKeyEventHandlerChange: (((KeyEvent) -> Boolean)?) -> Unit,
    onBookmarkSaved: () -> Unit,
    onClose: () -> Unit,
    onOpenSentenceMode: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val appContainer = LocalHoshiAppContainer.current
    val syncSettings = appContainer.syncSettingsRepository.settings.collectAsLoadedSettings()
    val sasayakiSettings = appContainer.sasayakiSettingsRepository.settings.collectAsLoadedSettings()
    val autoSyncState = ReaderRouteAutoSyncState(
        syncSettings = syncSettings,
        sasayakiSettings = sasayakiSettings,
    )
    val bookmarkScope = rememberCoroutineScope()
    val activeBookLease = remember(bookId) { HttpSyncActiveBooks.Lease() }
    val lifecycleOwner = LocalLifecycleOwner.current
    var reloadKey by remember(bookId) { mutableIntStateOf(0) }
    val autoSyncExportController = remember(bookId, appContainer) {
        ReaderAutoSyncExportController(appContainer.appScope)
    }
    val systemDarkTheme = isSystemInDarkTheme()
    val readerLoadingBackground = Modifier.background(
        Color(readerSettings.backgroundColor(systemDarkTheme)),
    )
    DisposableEffect(bookId, activeBookLease) {
        onDispose {
            // Keep replacement blocked through the final debounced bookmark write.
            appContainer.appScope.launch {
                delay(ACTIVE_READER_CLOSE_GRACE_MS)
                activeBookLease.release()
                appContainer.httpSyncBookmarkScheduler.refreshAfterReaderClosed()
            }
        }
    }
    val routeState by produceState<ReaderRouteLoadState>(
        ReaderRouteLoadState.Loading,
        bookId,
        stateHolder,
        reloadKey,
    ) {
        value = stateHolder.load(bookId) { entry ->
            activeBookLease.acquire(syncIdForMetadata(entry.metadata))
            appContainer.httpSyncBookmarkScheduler.refreshBeforeOpen()
            val initialAutoSyncState = ReaderRouteAutoSyncState(
                syncSettings = syncSettings ?: appContainer.syncSettingsRepository.settings.first(),
                sasayakiSettings = sasayakiSettings ?: appContainer.sasayakiSettingsRepository.settings.first(),
            )
            if (initialAutoSyncState.shouldSyncOnOpen) {
                runCatching {
                    appContainer.syncManager.syncBook(
                        entry = entry,
                        direction = null,
                        syncStats = readerSettings.statisticsSyncEnabled,
                        statsSyncMode = readerSettings.statisticsSyncMode,
                        syncAudioBook = initialAutoSyncState.shouldSyncAudioBook,
                        importOnly = true,
                    )
                }
            }
        }
    }

    DisposableEffect(lifecycleOwner, bookId) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                bookmarkScope.launch { appContainer.httpSyncBookmarkScheduler.refreshBeforeOpen() }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    suspend fun exportBook(entry: BookEntry) {
        runCatching {
            appContainer.syncManager.syncBook(
                entry = entry,
                direction = SyncDirection.ExportToTtu,
                syncStats = readerSettings.statisticsSyncEnabled,
                statsSyncMode = readerSettings.statisticsSyncMode,
                syncAudioBook = autoSyncState.shouldSyncAudioBook,
            )
        }.onSuccess { result ->
            Log.d(ReaderAutoSyncLogTag, "Reader auto export finished: ${result::class.java.simpleName}")
        }.onFailure { error ->
            Log.w(ReaderAutoSyncLogTag, "Reader auto export failed.", error)
        }
    }

    fun scheduleExport(entry: BookEntry) {
        autoSyncExportController.scheduleExport(autoSyncState.isReaderAutoSyncEnabled) {
            exportBook(entry)
        }
    }

    fun flushExport() {
        autoSyncExportController.flushExport(autoSyncState.isReaderAutoSyncEnabled)
    }

    fun importOnForeground(entry: BookEntry) {
        if (!autoSyncState.isReaderAutoSyncEnabled) return
        bookmarkScope.launch {
            val result = runCatching {
                appContainer.syncManager.syncBook(
                    entry = entry,
                    direction = null,
                    syncStats = readerSettings.statisticsSyncEnabled,
                    statsSyncMode = readerSettings.statisticsSyncMode,
                    syncAudioBook = autoSyncState.shouldSyncAudioBook,
                    importOnly = true,
                )
            }.getOrNull()
            if (result is SyncResult.Imported) {
                reloadKey += 1
            }
        }
    }

    when (val state = routeState) {
        ReaderRouteLoadState.Loading -> Box(
            modifier = modifier
                .fillMaxSize()
                .then(readerLoadingBackground),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator()
        }
        is ReaderRouteLoadState.Error -> Box(
            modifier = modifier
                .fillMaxSize()
                .then(readerLoadingBackground),
            contentAlignment = Alignment.Center,
        ) {
            Text(state.message)
        }
        is ReaderRouteLoadState.Ready -> {
            val syncId = syncIdForMetadata(state.entry.metadata)
            LaunchedEffect(syncId) {
                appContainer.httpSyncBatchState.remoteBookmarkUpdates.collect { changedId ->
                    if (changedId == syncId) reloadKey += 1
                }
            }
            ReaderWebView(
                book = state.book,
                bookRoot = state.bookRoot,
                initialChapterIndex = state.bookmark?.chapterIndex ?: 0,
                initialProgress = state.bookmark?.progress ?: 0.0,
                readerSettings = readerSettings,
                onReaderSettingsChange = onReaderSettingsChange,
                onReaderKeyEventHandlerChange = onReaderKeyEventHandlerChange,
                onSaveBookmark = { chapterIndex, progress, statistics ->
                    val save = autoSyncExportController.launchSave {
                        stateHolder.saveBookmark(
                            state = state,
                            chapterIndex = chapterIndex,
                            progress = progress,
                            statistics = statistics,
                            onBookmarkSaved = onBookmarkSaved,
                        )
                    }
                    if (statistics != null) {
                        appContainer.bookRepository.trackStatisticsSave(state.bookRoot, save)
                    }
                    scheduleExport(state.entry)
                },
                onSaveStatistics = { statistics ->
                    val save = autoSyncExportController.launchSave {
                        stateHolder.saveStatistics(state = state, statistics = statistics)
                    }
                    appContainer.bookRepository.trackStatisticsSave(state.bookRoot, save)
                },
                onFlushAutoSyncExport = ::flushExport,
                onForegroundAutoSyncImport = { importOnForeground(state.entry) },
                onClose = onClose,
                onOpenSentenceMode = onOpenSentenceMode,
                modifier = modifier.fillMaxSize(),
            )
        }
    }
}

private const val ACTIVE_READER_CLOSE_GRACE_MS = 1_000L

private const val ReaderAutoSyncLogTag = "HoshiReaderSync"
