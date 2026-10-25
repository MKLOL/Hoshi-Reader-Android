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
import androidx.compose.runtime.key
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import moe.antimony.hoshi.LocalHoshiAppContainer
import moe.antimony.hoshi.epub.BookEntry
import moe.antimony.hoshi.features.settings.collectAsLoadedSettings
import moe.antimony.hoshi.features.sync.SyncDirection
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
    val sessionReloadBarrier = remember(bookId) { ReaderSessionReloadBarrier() }
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
    // Watched for the whole route: refreshBeforeOpen() waits a bounded time for the network, so
    // a remote bookmark can land while the book is still loading. See RemoteBookmarkUpdateGate.
    val remoteUpdateGate = remember(bookId) { RemoteBookmarkUpdateGate() }
    fun reloadReader() {
        // Retire the tracker before any asynchronous import can update its base. Disposal
        // then cannot write the old running day over newly imported statistics.
        sessionReloadBarrier.retire()
        remoteUpdateGate.close()
        reloadKey += 1
    }
    LaunchedEffect(bookId) {
        appContainer.httpSyncBatchState.remoteBookmarkUpdates.collect { changedId ->
            if (remoteUpdateGate.shouldReload(changedId)) reloadReader()
        }
    }
    val routeState by produceState<ReaderRouteLoadState>(
        ReaderRouteLoadState.Loading,
        bookId,
        stateHolder,
        reloadKey,
    ) {
        remoteUpdateGate.close()
        value = ReaderRouteLoadState.Loading
        sessionReloadBarrier.awaitWrites()
        autoSyncExportController.pauseExportsForImport()
        value = stateHolder.load(
            bookId = bookId,
            beforeBookmarkRead = remoteUpdateGate::open,
            beforeBookmarkLoad = { entry ->
                val syncId = syncIdForMetadata(entry.metadata)
                remoteUpdateGate.expect(syncId)
                activeBookLease.acquire(syncId)
                appContainer.bookRepository.awaitPendingStatisticsSaves(entry.root)
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
                    }.onFailure { if (it is CancellationException) throw it }
                }
            },
        )
        autoSyncExportController.resumeExports(autoSyncState.isReaderAutoSyncEnabled)
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

    fun importOnForeground(): Boolean {
        if (!autoSyncState.isReaderAutoSyncEnabled) return false
        // The normal opening path now owns the import, after the old reader is closed and
        // all its writes are complete. Even a skipped import starts a fresh tracking base.
        reloadReader()
        return true
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
            fun prepareBookmarkSave(chapterIndex: Int, progress: Double, onlyChanged: Boolean = false): ((List<moe.antimony.hoshi.epub.ReadingStatistics>?) -> Unit)? {
                val occurredAt = appContainer.bookRepository.currentAppleReferenceDateSeconds()
                val event = state.positionSession?.navigate(moe.antimony.hoshi.epub.Bookmark(
                    chapterIndex, progress, state.book.characterCountAt(chapterIndex, progress), occurredAt,
                ))
                if (onlyChanged && event == null && state.positionSession != null) return null
                return { statistics ->
                    val save = autoSyncExportController.launchSave {
                        stateHolder.saveBookmark(
                            state = state,
                            chapterIndex = chapterIndex,
                            progress = progress,
                            statistics = statistics,
                            onBookmarkSaved = onBookmarkSaved,
                            occurredAt = occurredAt,
                            event = event,
                            captureAlreadyDone = true,
                        )
                    }
                    if (statistics != null) {
                        appContainer.bookRepository.trackStatisticsSave(state.bookRoot, save)
                    }
                    scheduleExport(state.entry)
                }
            }
            key(reloadKey) {
            ReaderWebView(
                book = state.book,
                bookRoot = state.bookRoot,
                initialChapterIndex = state.bookmark?.chapterIndex ?: 0,
                initialProgress = state.bookmark?.progress ?: 0.0,
                readerSettings = readerSettings,
                onReaderSettingsChange = onReaderSettingsChange,
                onReaderKeyEventHandlerChange = onReaderKeyEventHandlerChange,
                onSaveBookmark = { chapterIndex, progress, statistics ->
                    prepareBookmarkSave(chapterIndex, progress)?.invoke(statistics)
                },
                onPrepareBookmarkSave = { chapterIndex, progress ->
                    prepareBookmarkSave(chapterIndex, progress, onlyChanged = true)?.let { prepared ->
                        val persist: () -> Unit = { prepared(null) }
                        persist
                    }
                },
                onSaveStatistics = { statistics ->
                    val save = autoSyncExportController.launchSave {
                        stateHolder.saveStatistics(state = state, statistics = statistics)
                    }
                    appContainer.bookRepository.trackStatisticsSave(state.bookRoot, save)
                },
                onFlushAutoSyncExport = ::flushExport,
                onForegroundAutoSyncImport = ::importOnForeground,
                onPrepareSessionReloadChange = { sessionReloadBarrier.attach(it, autoSyncExportController::awaitSaves) },
                onClose = onClose,
                onOpenSentenceMode = onOpenSentenceMode,
                modifier = modifier.fillMaxSize(),
            )
            }
        }
    }
}

private const val ACTIVE_READER_CLOSE_GRACE_MS = 1_000L

private const val ReaderAutoSyncLogTag = "HoshiReaderSync"
