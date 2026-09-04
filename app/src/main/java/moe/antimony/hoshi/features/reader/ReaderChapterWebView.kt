package moe.antimony.hoshi.features.reader

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color as AndroidColor
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.net.Uri
import android.os.SystemClock
import android.view.ActionMode
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.View.MeasureSpec
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.viewinterop.AndroidView
import java.io.File
import java.lang.ref.WeakReference
import java.util.UUID
import java.util.WeakHashMap
import kotlin.math.abs
import moe.antimony.hoshi.R
import moe.antimony.hoshi.epub.EpubBook
import moe.antimony.hoshi.epub.HighlightColor
import moe.antimony.hoshi.features.sasayaki.SasayakiSettings
import moe.antimony.hoshi.webview.applyHoshiWebViewSecurityDefaults

@SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
@Composable
internal fun ChapterWebView(
    book: EpubBook,
    chapterPosition: ReaderChapterPosition,
    chapterFragment: String?,
    webViewViewportSize: IntSize,
    onReaderViewportSizeChanged: (IntSize) -> Unit,
    onWebViewReady: (WebView) -> Unit,
    isWebViewRestoring: Boolean,
    webViewRestoreEpoch: Int,
    onRestoreStarted: () -> Unit,
    onRestoreCompleted: () -> Unit,
    onNextChapter: () -> Boolean,
    onPreviousChapter: () -> Boolean,
    onSaveBookmark: (progress: Double) -> Unit,
    onDisplayProgress: (progress: Double) -> Unit,
    onContinuousScrollDisplayProgress: (progress: Double, restoreEpoch: Int) -> Unit,
    onContinuousScrollProgress: (progress: Double, restoreEpoch: Int) -> Unit,
    onInternalLink: (ReaderInternalLinkTarget) -> Unit,
    scanNonJapaneseText: Boolean,
    readerSettings: ReaderSettings,
    chapterHighlightsJson: String?,
    chapterSasayakiCuesJson: String?,
    chapterSentenceAnchorsJson: String?,
    sasayakiTextColor: Long,
    sasayakiBackgroundColor: Long,
    onTextSelected: (ReaderSelectionData, selectionRects: (Int, (List<ReaderSelectionRect>) -> Unit) -> Unit) -> Unit,
    onClearLookupPopup: () -> Unit,
    onReaderTapOutside: () -> Unit,
    onReaderInteraction: () -> Unit,
    onImageTapped: (String) -> Unit,
    onHighlightCreated: (HighlightColor, String, ReaderHighlightCreationResult) -> Unit,
    onSentenceTranslation: (String) -> Unit,
    readerPopupBridgeHolder: ReaderLookupPopupBridgeCallbackHolder,
    readerPopupResourceHandler: ReaderLookupPopupResourceHandler,
    readerIframePopupSupported: Boolean,
    readerPopupFrames: List<ReaderLookupPopupFramePayload>,
    fontManager: ReaderFontManager,
    systemDark: Boolean,
    modifier: Modifier = Modifier,
) {
    val currentOnTextSelected = rememberUpdatedState(onTextSelected)
    val currentOnSaveBookmark = rememberUpdatedState(onSaveBookmark)
    val currentOnDisplayProgress = rememberUpdatedState(onDisplayProgress)
    val currentOnContinuousScrollDisplayProgress = rememberUpdatedState(onContinuousScrollDisplayProgress)
    val currentOnContinuousScrollProgress = rememberUpdatedState(onContinuousScrollProgress)
    val currentOnClearLookupPopup = rememberUpdatedState(onClearLookupPopup)
    val currentOnReaderTapOutside = rememberUpdatedState(onReaderTapOutside)
    val currentOnReaderInteraction = rememberUpdatedState(onReaderInteraction)
    val currentOnImageTapped = rememberUpdatedState(onImageTapped)
    val currentOnHighlightCreated = rememberUpdatedState(onHighlightCreated)
    val currentOnSentenceTranslation = rememberUpdatedState(onSentenceTranslation)
    val currentReaderPopupResourceHandler = rememberUpdatedState(readerPopupResourceHandler)
    val currentReaderPopupFrames = rememberUpdatedState(readerPopupFrames)
    val currentOnNextChapter = rememberUpdatedState(onNextChapter)
    val currentOnPreviousChapter = rememberUpdatedState(onPreviousChapter)
    val currentIsWebViewRestoring = rememberUpdatedState(isWebViewRestoring)
    val currentWebViewRestoreEpoch = rememberUpdatedState(webViewRestoreEpoch)
    val currentOnRestoreStarted = rememberUpdatedState(onRestoreStarted)
    val currentOnRestoreCompleted = rememberUpdatedState(onRestoreCompleted)
    val currentReaderSettings = rememberUpdatedState(readerSettings)
    var lastContinuousProgressUpdate by remember { mutableStateOf(0L) }
    var continuousScrollSaveRequestId by remember { mutableStateOf(0L) }
    val chapter = book.chapters[chapterPosition.index]
    var readerWebView by remember { mutableStateOf<WebView?>(null) }
    var readerPageReadyKey by remember { mutableStateOf<String?>(null) }
    val fontFaceUrl = remember(readerSettings.selectedFont) {
        fontManager.webViewFontUrl(readerSettings.selectedFont)
    }
    val baseUrl = remember(chapter) { "https://hoshi.local/epub/${chapter.href}" }
    val readerContentReloadKey = remember(readerSettings) {
        readerSettings.readerContentReloadKey()
    }
    val appearanceUpdateKey = readerAppearanceUpdateKey(
        settings = readerSettings,
        systemDark = systemDark,
        sasayakiTextColor = sasayakiTextColor,
        sasayakiBackgroundColor = sasayakiBackgroundColor,
    )
    val readerAppearanceScript = remember(appearanceUpdateKey) {
        readerAppearanceScript(appearanceUpdateKey)
    }
    val readerSetupReloadKey = remember(
        chapterPosition.progress,
        chapterFragment,
        scanNonJapaneseText,
        fontFaceUrl,
    ) {
        ReaderWebViewSetupReloadKey(
            initialProgress = chapterPosition.progress,
            initialFragment = chapterFragment,
            scanNonJapaneseText = scanNonJapaneseText,
            fontFaceUrl = fontFaceUrl,
        )
    }
    val loadKey = readerWebViewLoadKey(
        baseUrl = baseUrl,
        readerContentReloadKey = readerContentReloadKey,
        readerSetupReloadKey = readerSetupReloadKey,
        webViewViewportSize = webViewViewportSize,
    )
    val readerSetupScript = remember(
        chapter,
        chapterPosition.progress,
        chapterFragment,
        readerContentReloadKey,
        appearanceUpdateKey,
        fontFaceUrl,
        systemDark,
        scanNonJapaneseText,
        sasayakiTextColor,
        sasayakiBackgroundColor,
        chapterSasayakiCuesJson,
        chapterHighlightsJson,
        chapterSentenceAnchorsJson,
        loadKey,
    ) {
        readerSetupScript(
            initialProgress = chapterPosition.progress,
            initialFragment = chapterFragment,
            settings = readerSettings,
            fontFaceUrl = fontFaceUrl,
            systemDark = systemDark,
            scanNonJapaneseText = scanNonJapaneseText,
            sasayakiTextColor = sasayakiTextColor,
            sasayakiBackgroundColor = sasayakiBackgroundColor,
            sasayakiCuesJson = chapterSasayakiCuesJson,
            highlightsJson = chapterHighlightsJson,
            sentenceAnchorsJson = chapterSentenceAnchorsJson,
            restoreToken = loadKey,
        )
    }
    val currentOnFragmentRestored = rememberUpdatedState<(WebView, String) -> Boolean> { restoredWebView, restoreToken ->
        if (restoreToken != loadKey) return@rememberUpdatedState false
        if (chapterFragment != null) {
            restoredWebView.evaluateJavascript(ReaderPaginationScripts.progressInvocation()) { progressResult ->
                ReaderPaginationScripts.doubleResult(progressResult)?.let(currentOnSaveBookmark.value)
                currentOnRestoreCompleted.value()
            }
        } else {
            currentOnRestoreCompleted.value()
        }
        true
    }
    LaunchedEffect(readerWebView, loadKey, chapterSasayakiCuesJson, isWebViewRestoring) {
        val webView = readerWebView ?: return@LaunchedEffect
        val cuesJson = chapterSasayakiCuesJson ?: return@LaunchedEffect
        if (isWebViewRestoring) return@LaunchedEffect
        if (webView.tag != loadKey) return@LaunchedEffect
        webView.evaluateJavascript(ReaderPaginationScripts.applySasayakiCuesInvocation(cuesJson), null)
    }
    LaunchedEffect(
        readerWebView,
        loadKey,
        readerPageReadyKey,
        chapterSentenceAnchorsJson,
        isWebViewRestoring,
    ) {
        val webView = readerWebView ?: return@LaunchedEffect
        if (isWebViewRestoring) return@LaunchedEffect
        if (webView.tag != loadKey) return@LaunchedEffect
        if (readerPageReadyKey != loadKey) return@LaunchedEffect
        webView.evaluateJavascript(
            "window.hoshiTranslations?.apply(${chapterSentenceAnchorsJson ?: "[]"});",
            null,
        )
    }
    // Appearance (colours, e-ink mode, writing direction) is pushed into the page only when it
    // actually changes. It used to be re-evaluated from the AndroidView update block, which
    // continuous scrolling re-runs on every reported position — up to 20 times a second during a
    // drag — for a script that had nothing new to say.
    LaunchedEffect(readerWebView, readerAppearanceScript, readerPageReadyKey) {
        val webView = readerWebView ?: return@LaunchedEffect
        webView.evaluateJavascript(readerAppearanceScript, null)
    }
    // Reader gestures are stateful: the pointer-down origin recorded on ACTION_DOWN is what
    // ACTION_UP measures the drag against. Continuous scrolling reports progress on every scroll
    // change, which recomposes the reader and re-runs the AndroidView update block, so a listener
    // built inside that block would be replaced mid-drag and would measure the rest of the gesture
    // from (0, 0). Keep one instance per reader and read the changing inputs through state.
    val continuousScrollTouchListener = remember {
        ContinuousScrollTouchListener(
            settings = { currentReaderSettings.value },
            shouldIgnoreReaderGesture = { webView, event ->
                webView.shouldIgnoreReaderGesture(
                    event = event,
                    isWebViewRestoring = currentIsWebViewRestoring.value,
                    popups = currentReaderPopupFrames.value,
                )
            },
            onTap = { webView, x, y ->
                webView.selectReaderTextAt(x, y) { currentOnReaderTapOutside.value() }
            },
            onScrollGesture = { currentOnReaderInteraction.value() },
            onNextChapter = { webView ->
                currentOnReaderInteraction.value()
                currentOnClearLookupPopup.value()
                val changed = currentOnNextChapter.value()
                if (changed) webView.hideForReaderRestore()
                changed
            },
            onPreviousChapter = { webView ->
                currentOnReaderInteraction.value()
                currentOnClearLookupPopup.value()
                val changed = currentOnPreviousChapter.value()
                if (changed) webView.hideForReaderRestore()
                changed
            },
        )
    }
    val swipePageTouchListener = remember {
        object : SwipePageTouchListener() {
            override fun shouldIgnoreReaderGesture(event: MotionEvent): Boolean {
                val webView = readerWebView as? HoshiReaderWebView ?: return true
                return webView.shouldIgnoreReaderGesture(
                    event = event,
                    isWebViewRestoring = currentIsWebViewRestoring.value,
                    popups = currentReaderPopupFrames.value,
                )
            }

            override fun onTap(x: Float, y: Float) {
                readerWebView?.selectReaderTextAt(x, y) { currentOnReaderTapOutside.value() }
            }

            override fun onLeftSwipe() = navigatePage(ReaderSwipeDirection.Left)

            override fun onRightSwipe() = navigatePage(ReaderSwipeDirection.Right)

            private fun navigatePage(swipeDirection: ReaderSwipeDirection) {
                val webView = readerWebView ?: return
                currentOnReaderInteraction.value()
                currentOnClearLookupPopup.value()
                webView.navigatePageForDirection(
                    direction = readerNavigationDirectionForSwipe(
                        isVerticalWriting = currentReaderSettings.value.verticalWriting,
                        swipeDirection = swipeDirection,
                    ),
                    onNextChapter = currentOnNextChapter.value,
                    onPreviousChapter = currentOnPreviousChapter.value,
                    onDisplayedProgress = currentOnDisplayProgress.value,
                    onSaveProgress = currentOnSaveBookmark.value,
                )
            }
        }
    }
    // Like the touch listener above: one instance for the reader's lifetime. The scroll listener
    // reads everything that changes through state holders, so it never has to be rebuilt, and the
    // update block below re-registers listeners only when the reading mode actually changes.
    val continuousScrollListener = remember {
        View.OnScrollChangeListener { scrolledView, _, _, _, _ ->
            val webView = scrolledView as? WebView ?: return@OnScrollChangeListener
            val now = SystemClock.uptimeMillis()
            if (now - lastContinuousProgressUpdate < CONTINUOUS_PROGRESS_THROTTLE_MS) {
                return@OnScrollChangeListener
            }
            lastContinuousProgressUpdate = now
            if (currentIsWebViewRestoring.value) return@OnScrollChangeListener
            val restoreEpoch = currentWebViewRestoreEpoch.value
            continuousScrollSaveRequestId += 1L
            val requestId = continuousScrollSaveRequestId
            webView.cancelPendingProgressSave()
            currentOnClearLookupPopup.value()
            webView.evaluateJavascript(ReaderPaginationScripts.progressInvocation()) { progressResult ->
                if (continuousScrollSaveRequestId != requestId) return@evaluateJavascript
                ReaderPaginationScripts.doubleResult(progressResult)?.let { progress ->
                    when (readerProgressPersistenceAction(ReaderProgressPersistenceEvent.ContinuousScrollChanged)) {
                        ReaderProgressPersistenceAction.DisplayOnly -> {
                            currentOnContinuousScrollDisplayProgress.value(progress, restoreEpoch)
                        }
                        ReaderProgressPersistenceAction.SaveBookmark -> {
                            currentOnContinuousScrollProgress.value(progress, restoreEpoch)
                        }
                    }
                    // The pending-save map is keyed weakly by WebView, so the callback filed under
                    // a WebView must not hold that WebView: a strong reference from the value back
                    // to the key keeps the entry — and the whole reader WebView — alive forever.
                    val webViewRef = WeakReference(webView)
                    lateinit var saveCallback: Runnable
                    saveCallback = Runnable {
                        if (continuousScrollSaveRequestId != requestId) return@Runnable
                        val savedWebView = webViewRef.get()
                        if (savedWebView != null &&
                            readerPendingProgressSaveCallbacks[savedWebView] === saveCallback
                        ) {
                            readerPendingProgressSaveCallbacks.remove(savedWebView)
                        }
                        when (readerProgressPersistenceAction(ReaderProgressPersistenceEvent.ContinuousScrollIdle)) {
                            ReaderProgressPersistenceAction.DisplayOnly -> currentOnDisplayProgress.value(progress)
                            ReaderProgressPersistenceAction.SaveBookmark -> {
                                currentOnContinuousScrollProgress.value(progress, restoreEpoch)
                            }
                        }
                    }
                    readerPendingProgressSaveCallbacks[webView] = saveCallback
                    webView.postDelayed(saveCallback, CONTINUOUS_SCROLL_SAVE_IDLE_DELAY_MS)
                }
            }
        }
    }
    // Plain object, not Compose state: the update block runs during layout, and a snapshot write
    // from there would invalidate the very pass that made it.
    val listenerWiring = remember { ReaderWebViewListenerWiring() }
    AndroidView(
        modifier = modifier
            .onSizeChanged(onReaderViewportSizeChanged)
            .background(Color(readerSettings.backgroundColor(systemDark))),
        onRelease = { webView ->
            // Runs the pending save rather than dropping it (the reader flushes on dispose too,
            // and whichever runs first wins), then leaves nothing behind in the process-wide map.
            webView.flushPendingProgressSave()
            webView.setOnScrollChangeListener(null)
            webView.setOnTouchListener(null)
            listenerWiring.reset()
        },
        factory = { context ->
            HoshiReaderWebView(context).apply {
                applyHoshiWebViewSecurityDefaults()
                isVerticalScrollBarEnabled = false
                isHorizontalScrollBarEnabled = false
                this.onHighlightCreated = { color, id, creation ->
                    currentOnHighlightCreated.value(color, id, creation)
                }
                hideForReaderRestore()
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
                addJavascriptInterface(
                    ReaderSelectionBridge(this) { selection, selectionRects ->
                        currentOnTextSelected.value(selection, selectionRects)
                    },
                    "HoshiTextSelection",
                )
                addJavascriptInterface(
                    ReaderRestoreBridge(this) { restoredWebView, restoreToken ->
                        currentOnFragmentRestored.value(restoredWebView, restoreToken)
                    },
                    "HoshiReaderRestore",
                )
                addJavascriptInterface(
                    ReaderImageTapBridge(this) { sourceUrl ->
                        currentOnImageTapped.value(sourceUrl)
                    },
                    "HoshiReaderImage",
                )
                addJavascriptInterface(
                    ReaderSentenceTranslationBridge(this) { id -> currentOnSentenceTranslation.value(id) },
                    "HoshiSentenceTranslation",
                )
                if (readerIframePopupSupported) {
                    ReaderLookupPopupWebBridge.install(this, readerPopupBridgeHolder)
                }
                webViewClient = EpubWebViewClient(
                    book = book,
                    fontManager = fontManager,
                    onInternalLink = onInternalLink,
                    popupResourceHandler = { currentReaderPopupResourceHandler.value },
                ) { view ->
                    view.evaluateJavascript(readerSetupScript) {
                        readerPageReadyKey = loadKey
                    }
                }
                readerWebView = this
                onWebViewReady(this)
            }
        },
        update = { webView ->
            if (listenerWiring.needsWiring(webView, readerSettings.continuousMode)) {
                if (readerSettings.continuousMode) {
                    webView.setOnTouchListener(continuousScrollTouchListener)
                    webView.setOnScrollChangeListener(continuousScrollListener)
                } else {
                    webView.cancelPendingProgressSave()
                    webView.setOnScrollChangeListener(null)
                    webView.setOnTouchListener(swipePageTouchListener)
                }
            }
            if (!readerWebViewReadyToLoad(webViewViewportSize)) return@AndroidView
            if (webView.tag != loadKey) {
                webView.tag = loadKey
                webView.hideForReaderRestore()
                currentOnRestoreStarted.value()
                webView.webViewClient = EpubWebViewClient(
                    book = book,
                    fontManager = fontManager,
                    onInternalLink = onInternalLink,
                    popupResourceHandler = { currentReaderPopupResourceHandler.value },
                ) { view ->
                    view.evaluateJavascript(readerSetupScript) {
                        readerPageReadyKey = loadKey
                    }
                }
                webView.loadUrl(baseUrl)
            }
        },
    )
}

internal fun readerWebViewReadyToLoad(webViewViewportSize: IntSize): Boolean =
    webViewViewportSize != IntSize.Zero

internal data class ReaderWebViewSetupReloadKey(
    val initialProgress: Double,
    val initialFragment: String?,
    val scanNonJapaneseText: Boolean,
    val fontFaceUrl: String?,
)

internal data class ReaderAppearanceUpdateKey(
    val backgroundColorCss: String,
    val textColorCss: String,
    val eInkLineColorCss: String,
    val eInkModeCss: String,
    val verticalWritingCss: String,
    val sasayakiTextColorCss: String,
    val sasayakiBackgroundColorCss: String,
)

internal fun readerAppearanceUpdateKey(
    settings: ReaderSettings,
    systemDark: Boolean,
    sasayakiTextColor: Long,
    sasayakiBackgroundColor: Long,
): ReaderAppearanceUpdateKey =
    ReaderAppearanceUpdateKey(
        backgroundColorCss = settings.backgroundColorCss(systemDark),
        textColorCss = settings.textColorCss(systemDark),
        eInkLineColorCss = if (settings.usesDarkInterface(systemDark)) "#fff" else "#000",
        eInkModeCss = if (settings.eInkMode) "1" else "0",
        verticalWritingCss = if (settings.verticalWriting) "1" else "0",
        sasayakiTextColorCss = sasayakiTextColor.toReaderCssColor(),
        sasayakiBackgroundColorCss = sasayakiBackgroundColor.toReaderCssColor(includeAlpha = true),
    )

internal fun readerWebViewLoadKey(
    baseUrl: String,
    readerContentReloadKey: ReaderContentReloadKey,
    readerSetupReloadKey: ReaderWebViewSetupReloadKey,
    webViewViewportSize: IntSize,
): String =
    "$baseUrl#${readerContentReloadKey.hashCode()}#${readerSetupReloadKey.hashCode()}#$webViewViewportSize"

internal fun readerHtmlWithEarlyViewport(html: String): String {
    val withoutViewport = readerViewportMetaRegex.replace(html, "")
    val head = readerHeadOpenTagRegex.find(withoutViewport)
    val viewport = """<meta name="viewport" content="$ReaderViewportContent" />"""
    if (head != null) {
        val insertAt = head.range.last + 1
        return withoutViewport.substring(0, insertAt) + "\n$viewport" + withoutViewport.substring(insertAt)
    }
    val htmlTag = readerHtmlOpenTagRegex.find(withoutViewport)
    if (htmlTag != null) {
        val insertAt = htmlTag.range.last + 1
        return withoutViewport.substring(0, insertAt) + "\n<head>$viewport</head>" + withoutViewport.substring(insertAt)
    }
    return "<head>$viewport</head>\n$withoutViewport"
}

private const val ReaderViewportContent = "width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no"

private val readerViewportMetaRegex =
    Regex("""(?is)<meta\b(?=[^>]*\bname\s*=\s*(['"])viewport\1)[^>]*>""")

private val readerHeadOpenTagRegex = Regex("""(?is)<head\b[^>]*>""")

private val readerHtmlOpenTagRegex = Regex("""(?is)<html\b[^>]*>""")

internal fun readerShouldReserveSasayakiTopToggle(bookRoot: File?, settings: SasayakiSettings): Boolean =
    settings.enabled &&
        settings.showReaderToggle &&
        bookRoot?.resolve(ReaderSasayakiMatchFileName)?.isFile == true &&
        bookRoot.resolve(ReaderSasayakiPlaybackFileName).isFile

private class HoshiReaderWebView(context: Context) : WebView(context) {
    var onHighlightCreated: (HighlightColor, String, ReaderHighlightCreationResult) -> Unit = { _, _, _ -> }
    private var nativeSelectionActionModeActive = false
    private var nativeSelectionActionMode: ActionMode? = null
    private var nativeSelectionContentRect: Rect? = null
    private var highlightColorPopup: PopupWindow? = null

    fun isNativeSelectionActionModeActive(): Boolean = nativeSelectionActionModeActive
    fun setNativeSelectionActionMode(mode: ActionMode?) {
        nativeSelectionActionMode = mode
        nativeSelectionActionModeActive = mode != null
        evaluateJavascript(ReaderPaginationScripts.nativeSelectionActiveInvocation(nativeSelectionActionModeActive), null)
        if (mode == null) {
            nativeSelectionContentRect = null
            dismissHighlightColorPopup()
        }
    }

    fun setNativeSelectionContentRect(rect: Rect) {
        nativeSelectionContentRect = Rect(rect)
    }

    fun prepareHighlightColorPicker(mode: ActionMode) {
        val anchor = nativeSelectionContentRect?.let { Rect(it) }
        evaluateJavascript(ReaderHighlightCommand.PrepareSelection.source) { result ->
            if (result?.trim() == "true") {
                mode.finish()
                post { showHighlightColorPicker(anchor) }
            }
        }
    }

    fun showHighlightColorPicker(anchorRect: Rect? = nativeSelectionContentRect) {
        dismissHighlightColorPopup()
        val density = resources.displayMetrics.density
        val popupContent = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            background = GradientDrawable().apply {
                setColor(AndroidColor.WHITE)
                cornerRadius = 20f * density
                setStroke((1f * density).toInt().coerceAtLeast(1), 0x22000000)
            }
            elevation = 8f * density
            val paddingHorizontal = (ReaderHighlightSelectionMenu.colorPickerHorizontalPaddingDp * density).toInt()
            val paddingVertical = (ReaderHighlightSelectionMenu.colorPickerVerticalPaddingDp * density).toInt()
            setPadding(paddingHorizontal, paddingVertical, paddingHorizontal, paddingVertical)
            ReaderHighlightSelectionMenu.colorItems.forEach { item ->
                addView(highlightColorButton(item, density))
            }
        }
        popupContent.measure(
            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
        )
        highlightColorPopup = PopupWindow(
            popupContent,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            false,
        ).apply {
            isOutsideTouchable = true
            setBackgroundDrawable(ColorDrawable(AndroidColor.TRANSPARENT))
            elevation = 8f * density
        }

        val location = IntArray(2)
        getLocationOnScreen(location)
        val margin = (16f * density).toInt()
        val screenWidth = resources.displayMetrics.widthPixels
        val screenHeight = resources.displayMetrics.heightPixels
        val popupWidth = popupContent.measuredWidth
        val popupHeight = popupContent.measuredHeight
        val anchor = anchorRect?.let {
            ReaderHighlightSelectionAnchor(
                left = it.left,
                top = it.top,
                right = it.right,
                bottom = it.bottom,
            )
        }
        val position = ReaderHighlightSelectionMenu.colorPickerPopupPosition(
            viewLeft = location[0],
            viewTop = location[1],
            viewWidth = width,
            screenWidth = screenWidth,
            screenHeight = screenHeight,
            popupWidth = popupWidth,
            popupHeight = popupHeight,
            margin = margin,
            anchor = anchor,
        )
        highlightColorPopup?.showAtLocation(this, Gravity.NO_GRAVITY, position.x, position.y)
    }

    private fun highlightColorButton(item: ReaderHighlightSelectionMenuItem, density: Float): TextView {
        val touchTargetSize = (ReaderHighlightSelectionMenu.colorPickerTouchTargetSizeDp * density).toInt()
        val swatchInset = (
            (ReaderHighlightSelectionMenu.colorPickerTouchTargetSizeDp - ReaderHighlightSelectionMenu.colorPickerSwatchSizeDp) *
                density / 2f
            ).toInt()
        val margin = (ReaderHighlightSelectionMenu.colorPickerButtonMarginDp * density).toInt()
        return TextView(context).apply {
            contentDescription = item.title
            background = InsetDrawable(
                GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(item.color.swatchArgb.toInt())
                },
                swatchInset,
            )
            setOnClickListener { createHighlightFromNativeSelection(item.color) }
            layoutParams = LinearLayout.LayoutParams(touchTargetSize, touchTargetSize).apply {
                setMargins(margin, 0, margin, 0)
            }
        }
    }

    fun createHighlightFromNativeSelection(color: HighlightColor) {
        val mode = nativeSelectionActionMode
        val id = UUID.randomUUID().toString()
        dismissHighlightColorPopup()
        evaluateJavascript(ReaderHighlightCommand.Create(color, id).source) { result ->
            ReaderHighlightCreationResult.fromWebViewResult(result)?.let { creation ->
                onHighlightCreated(color, id, creation)
            }
            mode?.finish()
        }
    }

    private fun dismissHighlightColorPopup() {
        highlightColorPopup?.dismiss()
        highlightColorPopup = null
    }

    override fun startActionMode(callback: ActionMode.Callback): ActionMode? =
        super.startActionMode(ReaderHighlightActionModeCallback(this, callback))

    override fun startActionMode(callback: ActionMode.Callback, type: Int): ActionMode? =
        super.startActionMode(ReaderHighlightActionModeCallback(this, callback), type)
}

private class ReaderHighlightActionModeCallback(
    private val webView: HoshiReaderWebView,
    private val delegate: ActionMode.Callback,
) : ActionMode.Callback2() {
    override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
        addHighlightMenu(menu)
        val created = delegate.onCreateActionMode(mode, menu)
        if (created) {
            webView.setNativeSelectionActionMode(mode)
            addHighlightMenu(menu)
        }
        return created
    }

    override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean {
        addHighlightMenu(menu)
        return delegate.onPrepareActionMode(mode, menu)
    }

    override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
        if (item.itemId == ReaderHighlightSelectionMenu.parentItemId) {
            webView.prepareHighlightColorPicker(mode)
            return true
        }
        val color = ReaderHighlightSelectionMenu.colorForItemId(item.itemId)
        if (color != null) {
            webView.createHighlightFromNativeSelection(color)
            return true
        }
        return delegate.onActionItemClicked(mode, item)
    }

    override fun onDestroyActionMode(mode: ActionMode) {
        webView.setNativeSelectionActionMode(null)
        delegate.onDestroyActionMode(mode)
    }

    override fun onGetContentRect(mode: ActionMode, view: View, outRect: Rect) {
        if (delegate is ActionMode.Callback2) {
            delegate.onGetContentRect(mode, view, outRect)
        } else {
            super.onGetContentRect(mode, view, outRect)
        }
        webView.setNativeSelectionContentRect(outRect)
    }

    private fun addHighlightMenu(menu: Menu) {
        if (menu.findItem(ReaderHighlightSelectionMenu.parentItemId) != null) return
        ReaderHighlightSelectionMenu.actionModeItems.forEach { item ->
            menu.add(
                ReaderHighlightSelectionMenu.groupId,
                item.id,
                item.order,
                webView.context.getString(R.string.reader_highlight_action),
            ).setShowAsAction(item.showAsAction)
        }
    }
}

private class EpubWebViewClient(
    private val book: EpubBook,
    private val fontManager: ReaderFontManager,
    private val onInternalLink: (ReaderInternalLinkTarget) -> Unit,
    private val popupResourceHandler: () -> ReaderLookupPopupResourceHandler?,
    private val onReaderPageFinished: (WebView) -> Unit,
) : WebViewClient() {
    private val resourceBridge = ReaderWebResourceBridge(book, fontManager)

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        val target = book.resolveInternalReaderLink(request.url?.toString().orEmpty()) ?: return false
        onInternalLink(target)
        return true
    }

    override fun onPageFinished(view: WebView, url: String?) {
        super.onPageFinished(view, url)
        if (Uri.parse(url ?: return).host == "hoshi.local") {
            onReaderPageFinished(view)
        }
    }

    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
        val uri = request.url ?: return null
        return popupResourceHandler()?.handle(uri)
            ?: resourceBridge.resourceForUrl(uri.toString())?.toWebResourceResponse()
    }

    override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
        view.destroy()
        return true
    }
}

private fun readerSetupScript(
    initialProgress: Double,
    initialFragment: String?,
    settings: ReaderSettings,
    fontFaceUrl: String?,
    systemDark: Boolean,
    scanNonJapaneseText: Boolean,
    sasayakiTextColor: Long,
    sasayakiBackgroundColor: Long,
    sasayakiCuesJson: String?,
    highlightsJson: String?,
    sentenceAnchorsJson: String?,
    restoreToken: String,
): String {
    val eInkMode = readerJavaScriptStringLiteral(if (settings.eInkMode) "true" else "false")
    val css = (ReaderContentStyles.css(
        settings = settings,
        fontFaceUrl = fontFaceUrl,
        systemDark = systemDark,
        sasayakiTextColor = sasayakiTextColor,
        sasayakiBackgroundColor = sasayakiBackgroundColor,
    ) + "\n" + ReaderTranslationScripts.css).let(::readerJavaScriptStringLiteral)
    val selectionScript = ReaderSelectionScripts.source()
    val translationScript = ReaderTranslationScripts.source()
    val translationInvocation = "window.hoshiTranslations.apply(${sentenceAnchorsJson ?: "[]"});"
    val paginationScript = ReaderPaginationScripts.shellScriptWithRestoreToken(
        initialProgress = initialProgress,
        initialFragment = initialFragment,
        settings = settings,
        sasayakiCuesJson = sasayakiCuesJson,
        highlightsJson = highlightsJson,
        restoreToken = restoreToken,
    ).scriptTagBody()
    return """
        (function() {
          document.documentElement.dataset.hoshiReaderEinkMode = $eInkMode;
          var style = document.createElement('style');
          style.textContent = $css;
          document.head.appendChild(style);
          window.scanNonJapaneseText = $scanNonJapaneseText;
          $selectionScript
          $translationScript
          if (!document.getElementById('hoshi-reader-popup-host-script')) {
            var popupHostScript = document.createElement('script');
            popupHostScript.id = 'hoshi-reader-popup-host-script';
            popupHostScript.src = 'https://hoshi.local/popup/reader-popup-host.js';
            document.head.appendChild(popupHostScript);
          }
          $paginationScript
          $translationInvocation
        })();
    """.trimIndent()
}

private fun readerAppearanceScript(
    appearanceUpdateKey: ReaderAppearanceUpdateKey,
): String {
    val backgroundColor = readerJavaScriptStringLiteral(appearanceUpdateKey.backgroundColorCss)
    val textColor = readerJavaScriptStringLiteral(appearanceUpdateKey.textColorCss)
    val eInkLineColor = readerJavaScriptStringLiteral(appearanceUpdateKey.eInkLineColorCss)
    val eInkMode = readerJavaScriptStringLiteral(appearanceUpdateKey.eInkModeCss)
    val verticalWriting = readerJavaScriptStringLiteral(appearanceUpdateKey.verticalWritingCss)
    val sasayakiText = readerJavaScriptStringLiteral(appearanceUpdateKey.sasayakiTextColorCss)
    val sasayakiBackground = readerJavaScriptStringLiteral(appearanceUpdateKey.sasayakiBackgroundColorCss)
    return """
        (function() {
          document.documentElement.style.setProperty('--hoshi-background-color', $backgroundColor);
          document.documentElement.style.setProperty('--hoshi-text-color', $textColor);
          document.documentElement.style.setProperty('--hoshi-eink-line-color', $eInkLineColor);
          document.documentElement.style.setProperty('--hoshi-reader-eink-mode', $eInkMode);
          document.documentElement.dataset.hoshiReaderEinkMode = $eInkMode === '1' ? 'true' : 'false';
          document.documentElement.style.setProperty('--hoshi-reader-vertical-writing', $verticalWriting);
          document.documentElement.style.setProperty('--hoshi-sasayaki-text-color', $sasayakiText);
          document.documentElement.style.setProperty('--hoshi-sasayaki-background-color', $sasayakiBackground);
          window.hoshiReader?.refreshSasayakiCuePresentation?.();
        })();
    """.trimIndent()
}

private fun String.scriptTagBody(): String =
    substringAfter("<script>").substringBeforeLast("</script>").trim()

internal fun readerJavaScriptStringLiteral(value: String): String =
    buildString(value.length + 2) {
        append('"')
        value.forEach { ch ->
            when (ch) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(ch)
            }
        }
        append('"')
    }

internal fun File.mediaType(): String = when (extension.lowercase()) {
    "ttf" -> "font/ttf"
    "otf" -> "font/otf"
    "woff" -> "font/woff"
    "woff2" -> "font/woff2"
    else -> "application/octet-stream"
}

internal fun WebView.navigatePage(
    direction: ReaderNavigationDirection,
    onLimit: () -> Boolean,
    onDisplayedProgress: (progress: Double) -> Unit,
    onSaveProgress: (progress: Double) -> Unit,
) {
    evaluateJavascript(ReaderPaginationScripts.paginateInvocation(direction)) { result ->
        if (ReaderPaginationScripts.didScroll(result)) {
            val webView = this
            val requestId = nextReaderPageTurnProgressRequestId()
            readerPageTurnProgressRequestIds[webView] = requestId
            webView.postVisualStateCallback(
                requestId,
                object : WebView.VisualStateCallback() {
                    override fun onComplete(requestId: Long) {
                        if (readerPageTurnProgressRequestIds[webView] != requestId) return
                        webView.postOnAnimation {
                            webView.evaluateJavascript(ReaderPaginationScripts.progressInvocation()) { progressResult ->
                                if (readerPageTurnProgressRequestIds[webView] != requestId) return@evaluateJavascript
                                readerPageTurnProgressRequestIds.remove(webView)
                                val progress = ReaderPaginationScripts.doubleResult(progressResult) ?: return@evaluateJavascript
                                onDisplayedProgress(progress)
                                when (readerProgressPersistenceAction(ReaderProgressPersistenceEvent.PaginatedPageTurnCompleted)) {
                                    ReaderProgressPersistenceAction.DisplayOnly -> Unit
                                    ReaderProgressPersistenceAction.SaveBookmark -> onSaveProgress(progress)
                                }
                            }
                        }
                    }
                },
            )
        } else {
            readerPageTurnProgressRequestIds.remove(this)
            onLimit()
        }
    }
}

private fun nextReaderPageTurnProgressRequestId(): Long {
    readerPageTurnProgressRequestId += 1
    return readerPageTurnProgressRequestId
}

private fun WebView.navigatePageForDirection(
    direction: ReaderNavigationDirection,
    onNextChapter: () -> Boolean,
    onPreviousChapter: () -> Boolean,
    onDisplayedProgress: (progress: Double) -> Unit,
    onSaveProgress: (progress: Double) -> Unit,
) {
    val onLimit = when (direction) {
        ReaderNavigationDirection.Forward -> onNextChapter
        ReaderNavigationDirection.Backward -> onPreviousChapter
    }
    navigatePage(direction, onLimit, onDisplayedProgress, onSaveProgress)
}

internal fun WebView.flushPendingProgressSave() {
    val progressCallback = readerPendingProgressSaveCallbacks.remove(this) ?: return
    removeCallbacks(progressCallback)
    progressCallback.run()
}

/** Drops a queued idle save without running it — the next scroll will queue a fresher one. */
private fun WebView.cancelPendingProgressSave() {
    val progressCallback = readerPendingProgressSaveCallbacks.remove(this) ?: return
    removeCallbacks(progressCallback)
}

/**
 * What the reader last wired onto its WebView, so the `AndroidView` update block can skip the
 * work when nothing changed. Continuous scrolling recomposes the reader on every reported
 * position, and re-registering listeners mid-drag is how a gesture used to lose its origin.
 */
private class ReaderWebViewListenerWiring {
    private var view: WebView? = null
    private var continuousMode: Boolean? = null

    /** True (and records the new state) when [webView] is not already wired for [continuous]. */
    fun needsWiring(webView: WebView, continuous: Boolean): Boolean {
        if (view === webView && continuousMode == continuous) return false
        view = webView
        continuousMode = continuous
        return true
    }

    fun reset() {
        view = null
        continuousMode = null
    }
}

private class ContinuousScrollTouchListener(
    private val settings: () -> ReaderSettings,
    private val shouldIgnoreReaderGesture: (HoshiReaderWebView, MotionEvent) -> Boolean,
    private val onTap: (HoshiReaderWebView, Float, Float) -> Unit,
    private val onScrollGesture: () -> Unit,
    private val onNextChapter: (HoshiReaderWebView) -> Boolean,
    private val onPreviousChapter: (HoshiReaderWebView) -> Boolean,
) : View.OnTouchListener {
    private val tracker = ReaderContinuousScrollGestureTracker(
        tapSlop = CONTINUOUS_READER_TAP_SLOP,
        maxTapDurationMs = CONTINUOUS_READER_MAX_TAP_DURATION_MS,
    )

    override fun onTouch(view: View, event: MotionEvent): Boolean {
        val webView = view as? HoshiReaderWebView ?: return false
        if (shouldIgnoreReaderGesture(webView, event)) {
            tracker.suppressCurrentGesture()
            return false
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> tracker.onDown(event.x, event.y, event.eventTime)
            MotionEvent.ACTION_CANCEL -> tracker.onCancel()
            MotionEvent.ACTION_MOVE -> if (tracker.onMove(event.x, event.y)) onScrollGesture()
            MotionEvent.ACTION_UP -> {
                val readerSettings = settings()
                val result = tracker.onUp(
                    x = event.x,
                    y = event.y,
                    eventTime = event.eventTime,
                    verticalWriting = readerSettings.verticalWriting,
                    chapterSwipeDistancePx = readerSettings.chapterSwipeDistance *
                        webView.resources.displayMetrics.density,
                )
                when (result) {
                    is ReaderContinuousScrollGestureTracker.Result.Tap -> onTap(webView, result.x, result.y)
                    is ReaderContinuousScrollGestureTracker.Result.ChapterSwipe ->
                        turnChapterAtBoundary(webView, result.direction, readerSettings.verticalWriting)
                    ReaderContinuousScrollGestureTracker.Result.None -> Unit
                }
            }
        }
        return false
    }

    private fun turnChapterAtBoundary(
        webView: HoshiReaderWebView,
        direction: ReaderNavigationDirection,
        verticalWriting: Boolean,
    ) {
        when (direction) {
            ReaderNavigationDirection.Forward ->
                if (!webView.canScrollReaderForward(verticalWriting)) onNextChapter(webView)
            ReaderNavigationDirection.Backward ->
                if (!webView.canScrollReaderBackward(verticalWriting)) onPreviousChapter(webView)
        }
    }
}

private const val CONTINUOUS_READER_TAP_SLOP = 12f
private const val CONTINUOUS_READER_MAX_TAP_DURATION_MS = 500L

/**
 * Gesture state machine for the continuous reader.
 *
 * Every gesture is measured against the pointer-down origin recorded by [onDown]: a drag shorter
 * than [tapSlop] is a tap, and a longer one is a chapter swipe whose reading direction comes from
 * its dominant axis. Moves and lifts that arrive without a matching [onDown] are reported as
 * [Result.None] instead of being measured against a zeroed origin, which would turn every drag
 * into a rightward (vertical writing) or downward (horizontal writing) swipe and make backward
 * chapter turns unreachable.
 */
internal class ReaderContinuousScrollGestureTracker(
    private val tapSlop: Float,
    private val maxTapDurationMs: Long,
) {
    private var downX = 0f
    private var downY = 0f
    private var downTime = 0L
    private var hasDown = false
    private var scrollGestureStarted = false

    fun onDown(x: Float, y: Float, eventTime: Long) {
        downX = x
        downY = y
        downTime = eventTime
        hasDown = true
        scrollGestureStarted = false
    }

    /** True once, when the pointer first travels far enough for the gesture to count as a drag. */
    fun onMove(x: Float, y: Float): Boolean {
        if (!hasDown || scrollGestureStarted) return false
        if (abs(x - downX) < tapSlop && abs(y - downY) < tapSlop) return false
        scrollGestureStarted = true
        return true
    }

    fun onUp(
        x: Float,
        y: Float,
        eventTime: Long,
        verticalWriting: Boolean,
        chapterSwipeDistancePx: Float,
    ): Result {
        if (!hasDown) return Result.None
        val dx = x - downX
        val dy = y - downY
        val elapsedMs = eventTime - downTime
        onCancel()
        if (elapsedMs <= maxTapDurationMs && abs(dx) < tapSlop && abs(dy) < tapSlop) {
            return Result.Tap(x, y)
        }
        return chapterSwipe(dx, dy, verticalWriting, chapterSwipeDistancePx)
    }

    fun onCancel() {
        hasDown = false
        scrollGestureStarted = false
    }

    fun suppressCurrentGesture() {
        onCancel()
    }

    private fun chapterSwipe(
        dx: Float,
        dy: Float,
        verticalWriting: Boolean,
        chapterSwipeDistancePx: Float,
    ): Result {
        // Vertical writing scrolls sideways and reads right to left, so dragging right advances.
        // Horizontal writing scrolls down, so dragging up advances.
        val travel = if (verticalWriting) dx else dy
        val crossTravel = if (verticalWriting) dy else dx
        if (abs(travel) < chapterSwipeDistancePx || abs(travel) < abs(crossTravel)) return Result.None
        val forward = if (verticalWriting) travel > 0f else travel < 0f
        return Result.ChapterSwipe(
            if (forward) ReaderNavigationDirection.Forward else ReaderNavigationDirection.Backward,
        )
    }

    sealed class Result {
        data object None : Result()
        data class Tap(val x: Float, val y: Float) : Result()
        data class ChapterSwipe(val direction: ReaderNavigationDirection) : Result()
    }
}

private fun WebView.canScrollReaderForward(verticalWriting: Boolean): Boolean =
    if (verticalWriting) canScrollHorizontally(-1) else canScrollVertically(1)

private fun WebView.canScrollReaderBackward(verticalWriting: Boolean): Boolean =
    if (verticalWriting) canScrollHorizontally(1) else canScrollVertically(-1)

private fun HoshiReaderWebView.shouldIgnoreReaderGesture(
    event: MotionEvent,
    isWebViewRestoring: Boolean,
    popups: List<ReaderLookupPopupFramePayload>,
): Boolean {
    if (isWebViewRestoring || isNativeSelectionActionModeActive()) return true
    val density = resources.displayMetrics.density
    return readerLookupPopupTouchBlocksReaderGesture(
        popups = popups,
        x = androidPixelsToCssPixels(event.x, density).toDouble(),
        y = androidPixelsToCssPixels(event.y, density).toDouble(),
    )
}

private fun WebView.selectReaderTextAt(x: Float, y: Float, onSelectedNothing: () -> Unit) {
    val density = resources.displayMetrics.density
    evaluateJavascript(
        ReaderSelectionCommand.SelectText(
            x = androidPixelsToCssPixels(x, density),
            y = androidPixelsToCssPixels(y, density),
            maxLength = MAX_SELECTION_LENGTH,
        ).source,
    ) { result ->
        val selectionResult = ReaderSelectionResult.fromWebViewResult(result)
        when {
            selectionResult.isImageTap || selectionResult.isLinkTap || selectionResult.isTranslationTap -> Unit
            selectionResult.selectedNothing -> onSelectedNothing()
        }
    }
}

private class ReaderRestoreBridge(
    private val webView: WebView,
    private val onRestoreCompleted: (WebView, String) -> Boolean,
) {
    @JavascriptInterface
    fun postMessage(message: String) {
        webView.post {
            if (onRestoreCompleted(webView, message)) {
                webView.showAfterReaderRestore()
            }
        }
    }
}

private class ReaderImageTapBridge(
    private val webView: WebView,
    private val onImageTapped: (String) -> Unit,
) {
    @JavascriptInterface
    fun postMessage(sourceUrl: String) {
        webView.post {
            onImageTapped(sourceUrl)
        }
    }
}

private class ReaderSentenceTranslationBridge(
    private val webView: WebView,
    private val onSentenceTranslation: (String) -> Unit,
) {
    @JavascriptInterface
    fun postMessage(id: String) {
        webView.post { onSentenceTranslation(id) }
    }
}

private fun WebView.hideForReaderRestore() {
    animate().cancel()
    readerRestoreGenerations[this] = (readerRestoreGenerations[this] ?: 0L) + 1L
    alpha = 0f
}

private fun WebView.showAfterReaderRestore() {
    animate().cancel()
    val generation = readerRestoreGenerations[this] ?: 0L
    postVisualStateCallback(
        generation,
        object : WebView.VisualStateCallback() {
            override fun onComplete(requestId: Long) {
                post {
                    if (readerRestoreGenerations[this@showAfterReaderRestore] == generation) {
                        animate().cancel()
                        alpha = 1f
                    }
                }
            }
        },
    )
}

private val readerRestoreGenerations = WeakHashMap<WebView, Long>()
private val readerPendingProgressSaveCallbacks = WeakHashMap<WebView, Runnable>()
private val readerPageTurnProgressRequestIds = WeakHashMap<WebView, Long>()
private var readerPageTurnProgressRequestId = 0L
private const val MAX_SELECTION_LENGTH = 16
private const val CONTINUOUS_PROGRESS_THROTTLE_MS = 50L
private const val CONTINUOUS_SCROLL_SAVE_IDLE_DELAY_MS = 250L


private const val ReaderSasayakiMatchFileName = "sasayaki_match.json"
private const val ReaderSasayakiPlaybackFileName = "sasayaki_playback.json"

internal fun androidPixelsToCssPixels(value: Float, density: Float): Float =
    value / density.coerceAtLeast(1f)
