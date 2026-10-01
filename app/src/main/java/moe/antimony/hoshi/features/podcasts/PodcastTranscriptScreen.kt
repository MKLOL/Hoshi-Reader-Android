package moe.antimony.hoshi.features.podcasts

import android.content.ComponentName
import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.media3.ui.PlayerControlView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.antimony.hoshi.LocalHoshiAppContainer
import moe.antimony.hoshi.R
import moe.antimony.hoshi.dictionary.DictionaryRepository
import moe.antimony.hoshi.features.audio.AudioSettings
import moe.antimony.hoshi.features.dictionary.DictionarySettings
import moe.antimony.hoshi.features.dictionary.LookupPopupAndroidStack
import moe.antimony.hoshi.features.dictionary.LookupPopupItem
import moe.antimony.hoshi.features.dictionary.LookupPopupOptions
import moe.antimony.hoshi.features.dictionary.createLookupPopupItem
import moe.antimony.hoshi.features.reader.ReaderSelectionData
import moe.antimony.hoshi.features.reader.ReaderSelectionRect
import moe.antimony.hoshi.features.reader.ReaderSettings
import moe.antimony.hoshi.features.reader.sentence.sentenceLookupQuery
import moe.antimony.hoshi.ui.theme.LocalPlatformDensity

/** How often the highlighted line follows the player; well under one spoken word. */
private const val POSITION_TICK_MS = 200L

private sealed interface TranscriptLoad {
    data object Loading : TranscriptLoad
    data object Missing : TranscriptLoad
    data class Ready(val account: String, val episodeId: String, val title: String, val transcript: PodcastTranscript) : TranscriptLoad
}

/** Both halves from disk, the episode's title from the saved catalogue, and a ready dictionary. */
private fun loadTranscript(repository: PodcastRepository, dictionary: DictionaryRepository, account: String?, episodeId: String): TranscriptLoad {
    if (account == null || !validPodcastId(episodeId) || !repository.files.hasTranscript(account, episodeId)) return TranscriptLoad.Missing
    val transcript = runCatching { repository.files.transcript(account, episodeId).readText() }.getOrNull()
        ?.let { parsePodcastTranscript(it, episodeId) } ?: return TranscriptLoad.Missing
    runCatching { dictionary.rebuildLookupQuery() }
    val title = repository.files.loadCatalogue(account)?.episodes?.firstOrNull { it.id == episodeId }?.title.orEmpty()
    return TranscriptLoad.Ready(account, episodeId, title, transcript)
}

/**
 * An episode's original recording with its timed transcript: the line being spoken is highlighted
 * and kept in view, a tap on a word pauses playback and opens the same dictionary popup the
 * reader uses, and a line's time plays from there.
 */
@Composable
internal fun PodcastTranscriptScreen(
    episodeId: String,
    readerSettings: ReaderSettings,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val appContainer = LocalHoshiAppContainer.current
    val repository = appContainer.podcastRepository
    val account by repository.account.collectAsStateWithLifecycle()
    val load by produceState<TranscriptLoad>(TranscriptLoad.Loading, account, episodeId) {
        value = withContext(Dispatchers.IO) { loadTranscript(repository, appContainer.dictionaryRepository, account, episodeId) }
    }
    Surface(modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        when (val state = load) {
            TranscriptLoad.Loading -> Box(Modifier.fillMaxSize()) { CircularProgressIndicator(Modifier.align(Alignment.Center)) }
            TranscriptLoad.Missing -> {
                BackHandler(onBack = onClose)
                Column(
                    Modifier.fillMaxSize().padding(24.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(stringResource(R.string.podcasts_transcript_missing))
                    TextButton(onClick = onClose) { Text(stringResource(R.string.action_close)) }
                }
            }
            is TranscriptLoad.Ready -> TranscriptPlayer(state, readerSettings, onClose)
        }
    }
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
private fun TranscriptPlayer(state: TranscriptLoad.Ready, readerSettings: ReaderSettings, onClose: () -> Unit) {
    val context = LocalContext.current
    val appContainer = LocalHoshiAppContainer.current
    val density = LocalPlatformDensity.current ?: LocalDensity.current
    val scope = rememberCoroutineScope()
    val lines = state.transcript.lines
    val mediaId = podcastMediaId(state.account, state.episodeId, original = true)
    var controller by remember { mutableStateOf<MediaController?>(null) }
    var positionMs by remember { mutableLongStateOf(0L) }
    // Whether the player is on this recording; another episode playing highlights nothing.
    var ours by remember { mutableStateOf(false) }
    var popups by remember { mutableStateOf<List<LookupPopupItem>>(emptyList()) }
    // The looked-up word: its line and its range in that line.
    var highlight by remember { mutableStateOf<Pair<Int, IntRange>?>(null) }
    var lookupJob by remember { mutableStateOf<Job?>(null) }
    var showEnglish by rememberSaveable { mutableStateOf(false) }
    // Off once the reader scrolls away on purpose, so the list stops pulling them back.
    var follow by remember { mutableStateOf(true) }
    val listState = rememberLazyListState()
    val hasEnglish = remember(lines) { lines.any { !it.english.isNullOrBlank() } }

    fun load(player: Player, startMs: Long) {
        player.setMediaItem(
            MediaItem.Builder().setMediaId(mediaId).setMediaMetadata(MediaMetadata.Builder().setTitle(state.title).build()).build(),
            startMs,
        )
        player.prepare()
    }

    DisposableEffect(mediaId) {
        val future = MediaController.Builder(context, SessionToken(context, ComponentName(context, PodcastPlaybackService::class.java))).buildAsync()
        future.addListener({
            if (!future.isCancelled) runCatching { future.get() }.onSuccess { player ->
                controller = player
                if (player.currentMediaItem?.mediaId != mediaId) {
                    val saved = context.getSharedPreferences(PodcastKeys.POSITIONS_PREFS, Context.MODE_PRIVATE).getLong(mediaId, 0)
                    load(player, podcastResumePosition(saved, state.transcript.duration.takeIf { it > 0 }))
                }
            }
        }, ContextCompat.getMainExecutor(context))
        onDispose {
            controller = null
            MediaController.releaseFuture(future)
        }
    }
    LaunchedEffect(controller) {
        val player = controller ?: return@LaunchedEffect
        while (true) {
            ours = player.currentMediaItem?.mediaId == mediaId
            positionMs = player.currentPosition
            delay(POSITION_TICK_MS)
        }
    }
    val current = if (ours) podcastTranscriptLineAt(lines, positionMs) else -1

    LaunchedEffect(listState) {
        listState.interactionSource.interactions.collect { if (it is DragInteraction.Start) follow = false }
    }
    LaunchedEffect(current, follow) {
        // Keep the spoken line a third of the way down, with what comes next in view.
        if (follow && current >= 0 && popups.isEmpty()) {
            listState.animateScrollToItem(current, -listState.layoutInfo.viewportSize.height / 3)
        }
    }

    val dictionarySettings by appContainer.dictionarySettingsRepository.settings.collectAsState(initial = DictionarySettings())
    val audioSettings by appContainer.audioSettingsRepository.settings.collectAsState(initial = AudioSettings())
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
        darkMode = MaterialTheme.colorScheme.background.luminance() < 0.5f,
        eInkMode = readerSettings.eInkMode,
        audioSettings = audioSettings,
        documentTitle = state.title,
    )

    fun dismissPopups() {
        lookupJob?.cancel()
        lookupJob = null
        popups = emptyList()
        highlight = null
    }
    LaunchedEffect(popups.isEmpty()) { if (popups.isEmpty()) highlight = null }
    BackHandler { if (popups.isNotEmpty()) dismissPopups() else onClose() }

    fun lookUp(index: Int, selection: ReaderSelectionData) {
        // Looking a word up is a pause in listening; playback waits for the reader.
        controller?.pause()
        lookupJob?.cancel()
        lookupJob = scope.launch {
            val lookup = withContext(Dispatchers.IO) { createLookupPopupItem(selection = selection, options = lookupOptions) }
            if (lookup == null) {
                popups = emptyList()
                highlight = null
                return@launch
            }
            val (popup, matchedCodePoints) = lookup
            val text = selection.sentence
            val start = selection.sentenceOffset ?: 0
            val end = text.offsetByCodePoints(start, matchedCodePoints.coerceAtMost(text.codePointCount(start, text.length)))
            highlight = index to (start until end)
            popups = listOf(popup)
        }
    }

    fun playFrom(line: PodcastTranscriptLine) {
        val player = controller ?: return
        val startMs = (line.start * 1000).toLong()
        if (player.currentMediaItem?.mediaId == mediaId) player.seekTo(startMs) else load(player, startMs)
        dismissPopups()
        follow = true
        player.play()
    }

    var screenOrigin by remember { mutableStateOf(Offset.Zero) }
    Box(
        Modifier
            .fillMaxSize()
            .onGloballyPositioned { screenOrigin = it.positionInRoot() }
            .pointerInput(Unit) { detectTapGestures { dismissPopups() } },
    ) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onClose) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.action_close))
                }
                Text(
                    state.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (hasEnglish) {
                    IconButton(onClick = { showEnglish = !showEnglish }) {
                        Icon(
                            Icons.Rounded.Translate,
                            contentDescription = stringResource(
                                if (showEnglish) R.string.podcasts_transcript_hide_english else R.string.podcasts_transcript_show_english,
                            ),
                            tint = if (showEnglish) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                LazyColumn(
                    state = listState,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    itemsIndexed(lines) { index, line ->
                        TranscriptLineRow(
                            line = line,
                            isCurrent = index == current,
                            highlight = highlight?.takeIf { it.first == index }?.second,
                            showEnglish = showEnglish,
                            screenOrigin = { screenOrigin },
                            onPlayFrom = { playFrom(line) },
                            onTapOutside = ::dismissPopups,
                            onWordTap = { selection -> lookUp(index, selection) },
                        )
                    }
                }
                if (!follow && current >= 0) {
                    ExtendedFloatingActionButton(
                        onClick = { follow = true },
                        icon = { Icon(Icons.Rounded.MyLocation, contentDescription = null) },
                        text = { Text(stringResource(R.string.podcasts_transcript_follow)) },
                        modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
                    )
                }
            }
            AndroidView(
                factory = { PlayerControlView(it).apply {
                    setShowTimeoutMs(0)
                    setShowShuffleButton(false)
                    setShowSubtitleButton(false)
                    setShowPreviousButton(false)
                    setShowNextButton(false)
                } },
                update = { it.player = controller },
                onRelease = { it.player = null },
                // Any shorter and Media3 drops to its minimal layout: no time, skips or speed.
                modifier = Modifier.fillMaxWidth().height(220.dp),
            )
        }
        LookupPopupAndroidStack(
            popups = popups,
            onPopupsChange = { popups = it },
            lookupChildPopup = { selection -> createLookupPopupItem(selection = selection, options = lookupOptions) },
            modifier = Modifier.fillMaxSize(),
        )
    }
}

private fun formatTimestamp(seconds: Double): String {
    val whole = seconds.toLong().coerceAtLeast(0)
    val hours = whole / 3600
    val minutes = (whole % 3600) / 60
    val rest = whole % 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, rest) else "%d:%02d".format(minutes, rest)
}

@Composable
private fun TranscriptLineRow(
    line: PodcastTranscriptLine,
    isCurrent: Boolean,
    highlight: IntRange?,
    showEnglish: Boolean,
    screenOrigin: () -> Offset,
    onPlayFrom: () -> Unit,
    onTapOutside: () -> Unit,
    onWordTap: (ReaderSelectionData) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val timestamp = formatTimestamp(line.start)
    val playFromLabel = stringResource(R.string.podcasts_transcript_play_from, timestamp)
    Row(
        Modifier
            .fillMaxWidth()
            .background(if (isCurrent) colors.primaryContainer.copy(alpha = 0.5f) else Color.Transparent, RoundedCornerShape(12.dp))
            .padding(end = 12.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.Top,
    ) {
        TextButton(
            onClick = onPlayFrom,
            contentPadding = PaddingValues(horizontal = 8.dp),
            modifier = Modifier.semantics { contentDescription = playFromLabel },
        ) {
            Text(timestamp, style = MaterialTheme.typography.labelMedium)
        }
        Column(Modifier.weight(1f).padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            TappableJapanese(
                text = line.text,
                highlight = highlight,
                color = if (isCurrent) colors.onSurface else colors.onSurface.copy(alpha = 0.7f),
                screenOrigin = screenOrigin,
                onTapOutside = onTapOutside,
                onWordTap = onWordTap,
            )
            if (showEnglish) {
                line.english?.takeIf { it.isNotBlank() }?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                }
            }
        }
    }
}

/** Japanese text where a tap on a character looks up the word that starts there, as in sentence mode. */
@Composable
private fun TappableJapanese(
    text: String,
    highlight: IntRange?,
    color: Color,
    screenOrigin: () -> Offset,
    onTapOutside: () -> Unit,
    onWordTap: (ReaderSelectionData) -> Unit,
) {
    val density = LocalDensity.current
    val selectionDensity by rememberUpdatedState(LocalPlatformDensity.current ?: density)
    // The pointerInput block outlives recompositions, so it reads the newest callbacks.
    val currentOnTapOutside by rememberUpdatedState(onTapOutside)
    val currentOnWordTap by rememberUpdatedState(onWordTap)
    var layout by remember(text) { mutableStateOf<TextLayoutResult?>(null) }
    var origin by remember { mutableStateOf(Offset.Zero) }
    val highlightColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)
    val annotated: AnnotatedString = remember(text, highlight, highlightColor) {
        buildAnnotatedString {
            append(text)
            highlight?.let { range ->
                if (range.first in text.indices && range.last < text.length) {
                    addStyle(SpanStyle(background = highlightColor), range.first, range.last + 1)
                }
            }
        }
    }
    Text(
        text = annotated,
        color = color,
        fontSize = 20.sp,
        lineHeight = 32.sp,
        onTextLayout = { layout = it },
        modifier = Modifier
            .fillMaxWidth()
            .onGloballyPositioned { origin = it.positionInRoot() }
            .pointerInput(text) {
                detectTapGestures { tap ->
                    val current = layout
                    if (current == null) {
                        currentOnTapOutside()
                        return@detectTapGestures
                    }
                    val row = current.getLineForVerticalPosition(tap.y)
                    if (tap.x > current.getLineRight(row) || tap.x < current.getLineLeft(row)) {
                        currentOnTapOutside()
                        return@detectTapGestures
                    }
                    val query = sentenceLookupQuery(text, current.getOffsetForPosition(tap))
                    if (query == null || query.text.isBlank()) {
                        currentOnTapOutside()
                        return@detectTapGestures
                    }
                    val box = current.getBoundingBox(query.startOffset)
                    val shift = origin - screenOrigin()
                    val rect = with(selectionDensity) {
                        ReaderSelectionRect(
                            x = (box.left + shift.x).toDp().value.toDouble(),
                            y = (box.top + shift.y).toDp().value.toDouble(),
                            width = box.width.toDp().value.toDouble(),
                            height = box.height.toDp().value.toDouble(),
                        )
                    }
                    currentOnWordTap(
                        ReaderSelectionData(
                            text = query.text,
                            sentence = text,
                            rect = rect,
                            normalizedOffset = null,
                            sentenceOffset = query.startOffset,
                        ),
                    )
                }
            },
    )
}
