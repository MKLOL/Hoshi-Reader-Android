package moe.antimony.hoshi.features.reader.sentence

import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Splitscreen
import androidx.compose.material.icons.rounded.VerticalSplit
import androidx.compose.material.icons.rounded.TextDecrease
import androidx.compose.material.icons.rounded.TextIncrease
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import moe.antimony.hoshi.ui.theme.LocalPlatformDensity
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import moe.antimony.hoshi.LocalHoshiAppContainer
import moe.antimony.hoshi.R
import moe.antimony.hoshi.features.ai.MarkdownText
import moe.antimony.hoshi.features.audio.AudioSettings
import moe.antimony.hoshi.features.dictionary.DictionarySettings
import moe.antimony.hoshi.features.dictionary.LookupPopupAndroidStack
import moe.antimony.hoshi.features.dictionary.LookupPopupItem
import moe.antimony.hoshi.features.dictionary.LookupPopupOptions
import moe.antimony.hoshi.features.dictionary.createLookupPopupItem
import moe.antimony.hoshi.features.reader.ReaderAppearanceSheet
import moe.antimony.hoshi.features.reader.ReaderLookupTouchTarget
import moe.antimony.hoshi.features.reader.dismissReaderLookupOnOutsideTouch
import moe.antimony.hoshi.features.reader.ReaderSettings
import moe.antimony.hoshi.features.reader.applySasayakiSettingsEdit
import moe.antimony.hoshi.features.reader.ReaderLifecycleAutoSyncEvent
import moe.antimony.hoshi.features.reader.readerLifecycleAutoSyncPlan
import moe.antimony.hoshi.features.settings.collectAsLoadedSettings
import moe.antimony.hoshi.features.sync.SyncDirection
import moe.antimony.hoshi.navigation.ReaderAutoSyncExportController
import moe.antimony.hoshi.navigation.ReaderForegroundImport
import moe.antimony.hoshi.navigation.ReaderRouteAutoSyncState
import moe.antimony.hoshi.navigation.ReaderSessionReloadBarrier
import moe.antimony.hoshi.navigation.RemoteBookmarkUpdateGate
import moe.antimony.hoshi.features.statistics.statisticsZone
import moe.antimony.hoshi.features.sasayaki.SasayakiSettings
import moe.antimony.hoshi.features.sync.http.HttpSyncActiveBooks
import moe.antimony.hoshi.features.sync.http.syncIdForMetadata
import moe.antimony.hoshi.features.usage.ReaderUsageSession
import moe.antimony.hoshi.features.usage.UsageContentType
import moe.antimony.hoshi.features.usage.UsageLookupSource

private const val FONT_SIZE_STEP = 2
private const val MIN_FONT_SIZE = 16
private const val MAX_FONT_SIZE = 60

/**
 * Sentence mode: one sentence of an EPUB at a time, with a stacked or side-by-side translation.
 * Swipe or use the arrows to move a sentence at a time;
 * tap a word to open the same dictionary popup the reader uses.
 */
@Composable
internal fun SentenceReaderScreen(
    bookId: String,
    readerSettings: ReaderSettings,
    onReaderSettingsChange: (ReaderSettings) -> Unit,
    onBookmarkSaved: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val appContainer = LocalHoshiAppContainer.current
    val positionStore = remember(context) { SentenceReaderPositionStore(context.filesDir) }
    val readerStateHolder = remember(appContainer) { appContainer.readerRouteStateHolder() }
    val syncSettings = appContainer.syncSettingsRepository.settings.collectAsLoadedSettings()
    val sasayakiSettings = appContainer.sasayakiSettingsRepository.settings.collectAsLoadedSettings()
    val autoSyncState = ReaderRouteAutoSyncState(syncSettings, sasayakiSettings)
    val scope = rememberCoroutineScope()
    val activeBookLease = remember(bookId) { HttpSyncActiveBooks.Lease() }
    val remoteUpdateGate = remember(bookId) { RemoteBookmarkUpdateGate() }
    val sessionReloadBarrier = remember(bookId) { ReaderSessionReloadBarrier() }
    val autoSyncExportController = remember(bookId, appContainer) { ReaderAutoSyncExportController(appContainer.appScope) }
    val foregroundImport = remember(bookId, autoSyncExportController) { ReaderForegroundImport(scope, autoSyncExportController) }
    var reloadKey by remember(bookId) { mutableIntStateOf(0) }
    // Keyed by the load that produced the content, never by the request (see ReaderRouteDestination).
    var loadedKey by remember(bookId) { mutableIntStateOf(0) }
    fun reloadReader() {
        foregroundImport.cancel()
        autoSyncExportController.holdExports()
        sessionReloadBarrier.retire()
        remoteUpdateGate.close()
        reloadKey += 1
    }
    LaunchedEffect(bookId) {
        appContainer.httpSyncBatchState.remoteBookmarkUpdates.collect { changedId ->
            if (remoteUpdateGate.shouldReload(changedId)) reloadReader()
        }
    }
    // The entry of the content a reload retires; its queued export is for this entry.
    var lastLoadedEntry by remember(bookId) { mutableStateOf<moe.antimony.hoshi.epub.BookEntry?>(null) }
    /** The automatic ッツ import; true when it changed this book's position or statistics here. */
    suspend fun importOnOpen(entry: moe.antimony.hoshi.epub.BookEntry, sync: ReaderRouteAutoSyncState): Boolean {
        var statisticsImported = false
        val result = appContainer.syncManager.syncBook(
            entry = entry,
            direction = null,
            syncStats = readerSettings.statisticsSyncEnabled,
            statsSyncMode = readerSettings.statisticsSyncMode,
            syncAudioBook = sync.shouldSyncAudioBook,
            importOnly = true,
            onStatisticsImported = { statisticsImported = true },
        )
        return result is moe.antimony.hoshi.features.sync.SyncResult.Imported || statisticsImported
    }
    val loadState by produceState<SentenceReaderLoadState>(SentenceReaderLoadState.Loading, bookId, reloadKey) {
        val requestedKey = reloadKey
        remoteUpdateGate.close()
        value = SentenceReaderLoadState.Loading
        sessionReloadBarrier.awaitWrites()
        autoSyncExportController.pauseExportsForImport()
        val loaded = runCatching {
            withContext(Dispatchers.IO) { appContainer.dictionaryRepository.rebuildLookupQuery() }
            SentenceReaderLoader(
                readerStateHolder,
                positionStore,
                awaitReaderSaves = appContainer.bookRepository::awaitPendingStatisticsSaves,
            ).load(
                bookId,
                beforeBookmarkRead = remoteUpdateGate::open,
                beforeBookmarkLoad = { entry ->
                    val syncId = syncIdForMetadata(entry.metadata)
                    remoteUpdateGate.expect(syncId)
                    // Protect static EPUB bytes before parsing, just like normal mode.
                    activeBookLease.acquire(syncId)
                    appContainer.bookRepository.awaitPendingStatisticsSaves(entry.root)
                    appContainer.httpSyncBookmarkScheduler.refreshBeforeOpen()
                    val initialSync = ReaderRouteAutoSyncState(
                        syncSettings ?: appContainer.syncSettingsRepository.settings.first(),
                        sasayakiSettings ?: appContainer.sasayakiSettingsRepository.settings.first(),
                    )
                    if (initialSync.shouldSyncOnOpen) runCatching { importOnOpen(entry, initialSync) }
                        .onFailure { if (it is CancellationException) throw it }
                },
            )
        }.getOrElse { error ->
            if (error is CancellationException) throw error
            SentenceReaderLoadState.Error(error.localizedMessage ?: error.javaClass.simpleName)
        }
        (loaded as? SentenceReaderLoadState.Ready)?.let { lastLoadedEntry = it.readerState.entry }
        loadedKey = requestedKey
        value = loaded
        autoSyncExportController.resumeExports(autoSyncState.isReaderAutoSyncEnabled)
    }
    // Leaving during a reload's spinner cancels the load before it resumes exports. The export
    // the retired content queued still goes out, after the import that reload never finished.
    val releaseAbandonedReload by rememberUpdatedState<() -> Unit> {
        // Only loaded content queues an export, so a held one always has its entry.
        val entry = lastLoadedEntry ?: return@rememberUpdatedState
        val sync = autoSyncState
        autoSyncExportController.releaseAbandonedReload(sync.isReaderAutoSyncEnabled) {
            sessionReloadBarrier.awaitWrites()
            appContainer.bookRepository.awaitPendingStatisticsSaves(entry.root)
            importOnOpen(entry, sync)
        }
    }
    DisposableEffect(bookId, autoSyncExportController) {
        onDispose { releaseAbandonedReload() }
    }
    var lastInactiveAtMillis by remember(bookId) { mutableStateOf<Long?>(null) }
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentLifecycleResume by rememberUpdatedState {
        val inactive = lastInactiveAtMillis
        lastInactiveAtMillis = null
        scope.launch { appContainer.httpSyncBookmarkScheduler.refreshBeforeOpen() }
        val import = readerLifecycleAutoSyncPlan(
            ReaderLifecycleAutoSyncEvent.Resume,
            inactiveElapsedMillis = inactive?.let { SystemClock.elapsedRealtime() - it },
        ).importOnForeground
        // As in normal mode, the import runs with the content still shown and counting, and
        // only an import that changed the book reloads it.
        val entry = (loadState as? SentenceReaderLoadState.Ready)?.readerState?.entry
        val sync = autoSyncState
        if (import && sync.isReaderAutoSyncEnabled && entry != null) foregroundImport.start(
            import = {
                appContainer.bookRepository.awaitPendingStatisticsSaves(entry.root)
                importOnOpen(entry, sync)
            },
            exportsEnabled = { sync.isReaderAutoSyncEnabled },
            reload = ::reloadReader,
        )
    }
    DisposableEffect(lifecycleOwner, bookId) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> currentLifecycleResume()
                Lifecycle.Event.ON_PAUSE -> lastInactiveAtMillis = SystemClock.elapsedRealtime()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    DisposableEffect(bookId, activeBookLease) {
        onDispose {
            appContainer.appScope.launch {
                delay(1_000)
                sessionReloadBarrier.awaitCurrentWrites()
                activeBookLease.release()
                appContainer.httpSyncBookmarkScheduler.refreshAfterReaderClosed()
            }
        }
    }
    fun scheduleExport(state: SentenceReaderLoadState.Ready) {
        autoSyncExportController.scheduleExport(autoSyncState.isReaderAutoSyncEnabled) {
            sessionReloadBarrier.awaitCurrentWrites()
            appContainer.bookRepository.awaitPendingStatisticsSaves(state.readerState.bookRoot)
            runCatching {
                appContainer.syncManager.syncBook(
                    entry = state.readerState.entry,
                    direction = SyncDirection.ExportToTtu,
                    syncStats = readerSettings.statisticsSyncEnabled,
                    statsSyncMode = readerSettings.statisticsSyncMode,
                    syncAudioBook = autoSyncState.shouldSyncAudioBook,
                )
            }.onFailure { if (it is CancellationException) throw it }
        }
    }
    val systemDark = isSystemInDarkTheme()
    val background = Color(readerSettings.backgroundColor(systemDark))
    val foreground = Color(readerSettings.textColor(systemDark))

    Box(modifier = modifier.fillMaxSize().background(background)) {
        when (val state = loadState) {
            SentenceReaderLoadState.Loading -> CircularProgressIndicator(
                color = foreground,
                modifier = Modifier.align(Alignment.Center),
            )
            is SentenceReaderLoadState.Error -> Column(
                modifier = Modifier.align(Alignment.Center).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(state.message, color = foreground, textAlign = TextAlign.Center)
                TextButton(onClick = onClose) { Text(stringResource(R.string.action_close)) }
            }
            is SentenceReaderLoadState.Ready -> key(loadedKey) { SentenceReaderContent(
                bookId = bookId,
                state = state,
                readerSettings = readerSettings,
                onReaderSettingsChange = onReaderSettingsChange,
                positionStore = positionStore,
                readerStateHolder = readerStateHolder,
                onBookmarkSaved = { onBookmarkSaved(); scheduleExport(state) },
                onScheduleExport = { scheduleExport(state) },
                onFlushExport = { autoSyncExportController.flushExport(autoSyncState.isReaderAutoSyncEnabled) },
                onPrepareSessionReloadChange = { retire, awaitWrites -> sessionReloadBarrier.attach(retire, awaitWrites) },
                foreground = foreground,
                background = background,
                onClose = onClose,
            ) }
        }
    }
}

@Composable
private fun SentenceReaderContent(
    bookId: String,
    state: SentenceReaderLoadState.Ready,
    readerSettings: ReaderSettings,
    onReaderSettingsChange: (ReaderSettings) -> Unit,
    positionStore: SentenceReaderPositionStore,
    readerStateHolder: moe.antimony.hoshi.navigation.ReaderRouteStateHolder,
    onBookmarkSaved: () -> Unit,
    onScheduleExport: () -> Unit,
    onFlushExport: () -> Unit,
    onPrepareSessionReloadChange: ((() -> Unit)?, suspend () -> Unit) -> Unit,
    foreground: Color,
    background: Color,
    onClose: () -> Unit,
) {
    val appContainer = LocalHoshiAppContainer.current
    val context = LocalContext.current
    val layoutStore = remember(context) { SentenceReaderLayoutStore(context) }
    var paneLayout by rememberSaveable { mutableStateOf(layoutStore.load()) }
    val density = LocalPlatformDensity.current ?: LocalDensity.current
    val scope = rememberCoroutineScope()
    val book = state.book
    var position by remember(state) { mutableStateOf(state.initialPosition) }
    var sessionRetired by remember(state) { mutableStateOf(false) }
    val progressMapping = state.progress
    val currentOnBookmarkSaved by rememberUpdatedState(onBookmarkSaved)
    val progressSession = remember(state, readerStateHolder) {
        SentenceReaderProgressSession(
            state = state.readerState,
            progress = progressMapping,
            stateHolder = readerStateHolder,
            scope = appContainer.appScope,
            now = appContainer.bookRepository::currentAppleReferenceDateSeconds,
            onBookmarkSaved = { currentOnBookmarkSaved() },
        )
    }
    LaunchedEffect(progressSession) {
        // Carry a place saved before sentence mode shared the bookmark into it and sync, once.
        if (state.recordsInitialPosition) state.initialPosition?.let(progressSession::navigate)
    }
    var bookFinished by rememberSaveable(bookId) {
        mutableStateOf(progressMapping.isFinished(state.initialPosition, state.readerState.bookmark))
    }
    val currentResetHour by rememberUpdatedState(readerSettings.statisticsDayResetHour)
    val currentStatisticsZone by rememberUpdatedState(readerSettings.statisticsZone())
    val statisticsSession by produceState<SentenceReaderStatisticsSession?>(null, state) {
        val repository = appContainer.bookRepository
        repository.awaitPendingStatisticsSaves(state.readerState.bookRoot)
        val statistics = repository.loadStatistics(state.readerState.bookRoot)
        value = SentenceReaderStatisticsSession(
            title = state.title,
            initialStatistics = statistics,
            initialCharacter = state.initialPosition?.let(progressMapping::at)?.characterCount ?: 0,
            device = appContainer.deviceIdentity,
            usageSession = ReaderUsageSession(
                appContainer.usageLog, state.readerState.entry.metadata.id, state.title, UsageContentType.Epub,
            ),
            resetHour = { currentResetHour },
            statisticsZone = { currentStatisticsZone },
        )
    }
    fun saveStatistics(flush: Boolean, session: SentenceReaderStatisticsSession? = statisticsSession) {
        if (sessionRetired) return
        session?.statisticsForPersistenceOrNull()?.let { statistics ->
            val save = progressSession.saveStatistics(statistics, flush)
            appContainer.bookRepository.trackStatisticsSave(state.readerState.bookRoot, save)
            // Like normal mode, idle time reaches ッツ when the reader is left, not every 30 s;
            // sentence moves schedule their own export with the bookmark.
            if (flush) onScheduleExport()
        }
    }
    val prepareSessionReload by rememberUpdatedState {
        if (!sessionRetired) {
            statisticsSession?.close()
            saveStatistics(flush = true)
            sessionRetired = true
        }
    }
    val currentOnPrepareSessionReloadChange by rememberUpdatedState(onPrepareSessionReloadChange)
    DisposableEffect(state, progressSession) {
        currentOnPrepareSessionReloadChange({ prepareSessionReload() }, progressSession::awaitSaves)
        onDispose {
            // Register the final writes with the route before dropping this content.
            prepareSessionReload()
        }
    }
    LifecycleResumeEffect(statisticsSession) {
        val session = statisticsSession
        session?.resume()
        onPauseOrDispose {
            session?.pause()
            saveStatistics(flush = true, session = session)
            onFlushExport()
        }
    }
    DisposableEffect(statisticsSession) {
        val session = statisticsSession
        onDispose {
            session?.close()
            saveStatistics(flush = true, session = session)
        }
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(statisticsSession, lifecycle) {
        val session = statisticsSession ?: return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            var seconds = 0
            while (true) {
                delay(1_000)
                session.tick()
                if (++seconds % 30 == 0) saveStatistics(flush = false, session = session)
            }
        }
    }
    var translationExpanded by remember { mutableStateOf(false) }
    var popups by remember { mutableStateOf<List<LookupPopupItem>>(emptyList()) }
    var highlight by remember { mutableStateOf<IntRange?>(null) }
    // The lookup in flight, so a swipe or a dismissal cancels it instead of letting a stale
    // result open the previous sentence's popup over the new one.
    var lookupJob by remember { mutableStateOf<Job?>(null) }
    var closing by remember { mutableStateOf(false) }
    var showAppearance by remember { mutableStateOf(false) }
    val sasayakiSettings by appContainer.sasayakiSettingsRepository.settings
        .collectAsState(initial = SasayakiSettings())
    val sentence = position?.let(book::sentenceAt)
    val translation = remember(sentence, state.translations) {
        sentence?.let { state.translations?.translationFor(it) }
    }
    val dictionarySettings by appContainer.dictionarySettingsRepository.settings
        .collectAsState(initial = DictionarySettings())
    val audioSettings by appContainer.audioSettingsRepository.settings
        .collectAsState(initial = AudioSettings())
    val darkMode = background.luminance() < 0.5f
    val statusBarTop = with(density) { WindowInsets.statusBars.getTop(this).toDp().value }
    val lookupOptions = LookupPopupOptions(
        isVertical = false,
        isFullWidth = readerSettings.popupFullWidth,
        width = readerSettings.popupWidth,
        height = readerSettings.popupHeight,
        swipeToDismiss = readerSettings.popupSwipeToDismiss,
        swipeThreshold = readerSettings.popupSwipeThreshold,
        reducedMotionScrolling = readerSettings.popupReducedMotionScrolling,
        reducedMotionScrollPercent = readerSettings.popupReducedMotionScrollPercent,
        reducedMotionSwipeThreshold = readerSettings.popupReducedMotionSwipeThreshold,
        popupScale = readerSettings.popupScale,
        cardStyle = readerSettings.dictionaryCard,
        popupActionBar = false,
        topInset = statusBarTop.toDouble(),
        dictionarySettings = dictionarySettings.normalized(),
        darkMode = darkMode,
        eInkMode = readerSettings.eInkMode,
        audioSettings = audioSettings,
        documentTitle = state.title,
    )
    val fontFamily = remember(readerSettings.selectedFont) {
        appContainer.readerFontManager.storedFont(readerSettings.selectedFont)?.file
            ?.let { file -> runCatching { FontFamily(Font(file)) }.getOrNull() }
            ?: FontFamily.Serif
    }
    val sentenceScroll = rememberScrollState()

    LaunchedEffect(position) {
        sentenceScroll.scrollTo(0)
        position?.let { positionStore.save(bookId, it) }
    }
    LaunchedEffect(popups.isEmpty()) {
        if (popups.isEmpty()) highlight = null
    }

    // What a tap anywhere outside the popup does: the reader's "tap outside", minus the web view.
    fun dismissPopups() {
        lookupJob?.cancel()
        lookupJob = null
        popups = emptyList()
        highlight = null
    }
    fun move(next: SentencePosition?) {
        if (next == null || next == position || closing || sessionRetired || statisticsSession == null) return
        currentOnBookmarkSaved()
        progressSession.navigate(next)
        progressMapping.at(next)?.let { point ->
            statisticsSession?.navigate(point.chapterIndex + 1, point.characterCount)
        }
        saveStatistics(flush = false)
        position = next
        bookFinished = false
        translationExpanded = false
        dismissPopups()
    }
    val next = position?.let { SentenceNavigation.next(book, it) }
    val previous = position?.let { SentenceNavigation.previous(book, it) }
    fun finishBook() {
        val current = position ?: return
        if (bookFinished || closing || sessionRetired || statisticsSession == null || next != null) return
        currentOnBookmarkSaved()
        progressSession.finish(current)
        progressMapping.endOf(current)?.let { point ->
            statisticsSession?.navigate(point.chapterIndex + 1, point.characterCount)
        }
        saveStatistics(flush = true)
        bookFinished = true
        dismissPopups()
    }
    fun closeReader() {
        if (closing) return
        closing = true
        dismissPopups()
        statisticsSession?.pause()
        saveStatistics(flush = true)
        appContainer.appScope.launch {
            progressSession.awaitSaves()
            onFlushExport()
            onClose()
        }
    }
    BackHandler {
        when {
            popups.isNotEmpty() -> dismissPopups()
            showAppearance -> showAppearance = false
            else -> closeReader()
        }
    }
    fun stepFontSize(delta: Int) {
        onReaderSettingsChange(readerSettings.copy(fontSize = (readerSettings.fontSize + delta).coerceIn(MIN_FONT_SIZE, MAX_FONT_SIZE)))
    }

    var screenOrigin by remember { mutableStateOf(Offset.Zero) }
    val lookupTouchTarget = remember { ReaderLookupTouchTarget() }
    // The popup overlay reports its bounds in platform dp (View px / displayMetrics.density), as
    // in the normal reader's PlatformReaderUi; large-screen UI scaling must not shift this test.
    val lookupTouchDensity = density.density
    val currentDismissLookup = rememberUpdatedState(::dismissPopups)
    val currentHasActiveLookup = rememberUpdatedState { popups.isNotEmpty() || lookupJob?.isActive == true }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .onGloballyPositioned {
                screenOrigin = it.positionInRoot()
                lookupTouchTarget.rootCoordinates = it
            }
            .dismissReaderLookupOnOutsideTouch(
                hasActiveLookup = { currentHasActiveLookup.value() },
                hitPopup = { lookupTouchTarget.containsRootPoint(it, lookupTouchDensity) },
                dismissLookup = { currentDismissLookup.value() },
            ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.systemBars),
        ) {
            SentenceHeader(
                title = state.title,
                chapterTitle = position?.let { book.chapters.getOrNull(it.chapter)?.title },
                counter = position?.let { stringResource(R.string.sentence_mode_counter, book.ordinal(it), book.totalSentences) },
                foreground = foreground,
                onClose = ::closeReader,
                onAppearance = { showAppearance = true },
                paneLayout = paneLayout,
                onPaneLayoutChange = {
                    paneLayout = it
                    layoutStore.save(it)
                    translationExpanded = true
                    dismissPopups()
                },
            )
            val swipeThresholdPx = with(density) { 56.dp.toPx() }
            SentenceReaderPanes(
                layout = paneLayout,
                translationExpanded = translationExpanded,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                sentenceContent = { paneModifier ->
                    Box(
                        modifier = paneModifier
                            .pointerInput(book) {
                                var travel = 0f
                                detectHorizontalDragGestures(
                                    onDragStart = { travel = 0f },
                                    onDragEnd = {
                                        // Read the live position: this handler outlives many recompositions.
                                        val current = position ?: return@detectHorizontalDragGestures
                                        if (travel <= -swipeThresholdPx) {
                                            move(SentenceNavigation.next(book, current))
                                        } else if (travel >= swipeThresholdPx) {
                                            move(SentenceNavigation.previous(book, current))
                                        }
                                    },
                                    onDragCancel = { travel = 0f },
                                ) { change, delta ->
                                    travel += delta
                                    change.consume()
                                }
                            }
                            .padding(horizontal = 24.dp, vertical = 12.dp)
                            .verticalScroll(sentenceScroll),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (sentence == null) {
                            Text(
                                text = stringResource(R.string.sentence_mode_empty),
                                color = foreground.copy(alpha = 0.7f),
                                textAlign = TextAlign.Center,
                            )
                        } else {
                            SentenceText(
                                sentence = sentence,
                                highlight = highlight,
                                foreground = foreground,
                                fontFamily = fontFamily,
                                fontSizeSp = readerSettings.fontSize * 1.1f,
                                lineHeightMultiplier = readerSettings.lineHeight.toFloat(),
                                showFurigana = !readerSettings.hideFurigana,
                                screenOrigin = { screenOrigin },
                                onTapOutside = ::dismissPopups,
                                onWordTap = { selection ->
                                    lookupJob?.cancel()
                                    lookupJob = scope.launch {
                                        val lookup = withContext(Dispatchers.IO) {
                                            createLookupPopupItem(selection = selection, options = lookupOptions)
                                        }
                                statisticsSession?.logLookup(selection, lookup?.first, UsageLookupSource.Page)
                                        if (lookup == null) {
                                            popups = emptyList()
                                            highlight = null
                                        } else {
                                            val (popup, matchedCodePoints) = lookup
                                            val start = selection.sentenceOffset ?: 0
                                            val end = sentence.text.offsetByCodePoints(start, matchedCodePoints.coerceAtMost(
                                                sentence.text.codePointCount(start, sentence.text.length),
                                            ))
                                            highlight = start until end
                                            popups = listOf(popup)
                                        }
                                    }
                                },
                            )
                        }
                    }
                },
                translationContent = { paneModifier ->
                    TranslationPanel(
                        translation = translation?.markdownResponse,
                        expanded = translationExpanded,
                        onToggle = { translationExpanded = !translationExpanded },
                        foreground = foreground,
                        modifier = paneModifier,
                    )
                },
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { move(previous) }, enabled = previous != null && statisticsSession != null) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowLeft,
                        contentDescription = stringResource(R.string.sentence_mode_previous),
                        tint = foreground.copy(alpha = if (previous != null) 1f else 0.3f),
                    )
                }
                IconButton(onClick = { stepFontSize(-FONT_SIZE_STEP) }, enabled = readerSettings.fontSize > MIN_FONT_SIZE) {
                    Icon(
                        imageVector = Icons.Rounded.TextDecrease,
                        contentDescription = stringResource(R.string.sentence_mode_font_smaller),
                        tint = foreground.copy(alpha = if (readerSettings.fontSize > MIN_FONT_SIZE) 0.8f else 0.3f),
                    )
                }
                Text(
                    text = position?.let {
                        stringResource(
                            R.string.sentence_mode_chapter_progress,
                            book.readableChapterOrdinal(it),
                            book.readableChapterCount,
                        )
                    }.orEmpty(),
                    color = foreground.copy(alpha = 0.7f),
                    style = MaterialTheme.typography.labelMedium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { stepFontSize(FONT_SIZE_STEP) }, enabled = readerSettings.fontSize < MAX_FONT_SIZE) {
                    Icon(
                        imageVector = Icons.Rounded.TextIncrease,
                        contentDescription = stringResource(R.string.sentence_mode_font_larger),
                        tint = foreground.copy(alpha = if (readerSettings.fontSize < MAX_FONT_SIZE) 0.8f else 0.3f),
                    )
                }
                if (next == null && position != null) {
                    TextButton(onClick = ::finishBook, enabled = !bookFinished && statisticsSession != null) {
                        Text(
                            stringResource(if (bookFinished) R.string.sentence_mode_book_finished else R.string.sentence_mode_finish_book),
                            color = foreground.copy(alpha = if (bookFinished) 0.5f else 1f),
                        )
                    }
                } else IconButton(onClick = { move(next) }, enabled = next != null && statisticsSession != null) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                        contentDescription = stringResource(R.string.sentence_mode_next),
                        tint = foreground.copy(alpha = if (next != null) 1f else 0.3f),
                    )
                }
            }
        }
        LookupPopupAndroidStack(
            popups = popups,
            onPopupsChange = { popups = it },
            lookupChildPopup = { selection ->
                createLookupPopupItem(selection = selection, options = lookupOptions).also { lookup ->
                    statisticsSession?.logLookup(selection, lookup?.first, UsageLookupSource.Popup)
                }
            },
            onPopupBoundsChanged = { lookupTouchTarget.popupRects = it },
            modifier = Modifier.fillMaxSize().onGloballyPositioned {
                lookupTouchTarget.popupCoordinates = it
            },
        )
        if (showAppearance) {
            // Use the same EPUB settings as the main Settings page, including shared-settings links.
            val previousSasayakiSettings = sasayakiSettings
            ReaderAppearanceSheet(
                settings = readerSettings,
                onSettingsChange = onReaderSettingsChange,
                sasayakiSettings = sasayakiSettings,
                onSasayakiSettingsChange = { updated ->
                    scope.launch {
                        appContainer.sasayakiSettingsRepository.update { current ->
                            applySasayakiSettingsEdit(current, previousSasayakiSettings, updated)
                        }
                    }
                },
                fontManager = appContainer.readerFontManager,
                onDismiss = { showAppearance = false },
            )
        }
    }
}

@Composable
private fun SentenceHeader(
    title: String,
    chapterTitle: String?,
    counter: String?,
    foreground: Color,
    onClose: () -> Unit,
    onAppearance: () -> Unit,
    paneLayout: SentenceReaderLayout,
    onPaneLayoutChange: (SentenceReaderLayout) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 4.dp, end = 4.dp, top = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onClose) {
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                contentDescription = stringResource(R.string.action_close),
                tint = foreground,
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = foreground,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!chapterTitle.isNullOrBlank()) {
                Text(
                    text = chapterTitle,
                    color = foreground.copy(alpha = 0.7f),
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (counter != null) {
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = counter,
                color = foreground.copy(alpha = 0.7f),
                style = MaterialTheme.typography.labelLarge,
            )
        }
        SentenceLayoutMenu(paneLayout, foreground, onPaneLayoutChange)
        IconButton(onClick = onAppearance) {
            Icon(
                imageVector = Icons.Rounded.Palette,
                contentDescription = stringResource(R.string.epub_reader_settings),
                tint = foreground,
            )
        }
    }
}

/** Each open pane owns its scrolling, so long sentences and tutor tables stay reachable. */
@Composable
internal fun SentenceReaderPanes(
    layout: SentenceReaderLayout,
    translationExpanded: Boolean,
    modifier: Modifier = Modifier,
    sentenceContent: @Composable (Modifier) -> Unit,
    translationContent: @Composable (Modifier) -> Unit,
) {
    if (translationExpanded && layout == SentenceReaderLayout.SideBySide) {
        Row(modifier = modifier) {
            sentenceContent(Modifier.weight(1f).fillMaxHeight())
            translationContent(Modifier.weight(1f).fillMaxHeight())
        }
    } else {
        Column(modifier = modifier) {
            sentenceContent(Modifier.weight(1f).fillMaxWidth())
            translationContent(
                if (translationExpanded) Modifier.weight(1f).fillMaxWidth() else Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun SentenceLayoutMenu(
    layout: SentenceReaderLayout,
    foreground: Color,
    onLayoutChange: (SentenceReaderLayout) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(
                imageVector = if (layout == SentenceReaderLayout.Stacked) Icons.Rounded.Splitscreen else Icons.Rounded.VerticalSplit,
                contentDescription = stringResource(R.string.sentence_mode_layout),
                tint = foreground,
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            SentenceReaderLayout.entries.forEach { option ->
                DropdownMenuItem(
                    text = { Text(stringResource(
                        if (option == SentenceReaderLayout.Stacked) R.string.sentence_mode_layout_stacked
                        else R.string.sentence_mode_layout_side_by_side,
                    )) },
                    leadingIcon = {
                        Icon(
                            imageVector = if (option == SentenceReaderLayout.Stacked) Icons.Rounded.Splitscreen else Icons.Rounded.VerticalSplit,
                            contentDescription = null,
                        )
                    },
                    trailingIcon = {
                        if (option == layout) Icon(Icons.Rounded.Check, contentDescription = null)
                    },
                    onClick = {
                        expanded = false
                        onLayoutChange(option)
                    },
                )
            }
        }
    }
}

@Composable
private fun TranslationPanel(
    translation: String?,
    expanded: Boolean,
    onToggle: () -> Unit,
    foreground: Color,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = foreground.copy(alpha = 0.06f),
        shape = RoundedCornerShape(16.dp),
        modifier = modifier
            .padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onToggle)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                    contentDescription = stringResource(R.string.sentence_mode_toggle_translation),
                    tint = foreground,
                )
                Text(
                    text = stringResource(R.string.sentence_mode_translation),
                    color = foreground,
                    style = MaterialTheme.typography.titleSmall,
                )
            }
            if (expanded) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 16.dp, bottom = 16.dp)
                        .weight(1f)
                        .verticalScroll(rememberScrollState()),
                ) {
                    if (translation != null) {
                        MarkdownText(markdown = translation, color = foreground)
                    } else {
                        Text(
                            text = stringResource(R.string.sentence_mode_translation_missing),
                            color = foreground.copy(alpha = 0.7f),
                        )
                    }
                }
            }
        }
    }
}
