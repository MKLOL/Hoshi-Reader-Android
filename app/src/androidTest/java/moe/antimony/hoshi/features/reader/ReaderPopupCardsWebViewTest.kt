package moe.antimony.hoshi.features.reader

import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.IntSize
import androidx.test.platform.app.InstrumentationRegistry
import de.manhhao.hoshi.GlossaryEntry
import de.manhhao.hoshi.LookupResult
import de.manhhao.hoshi.TermResult
import moe.antimony.hoshi.epub.EpubBook
import moe.antimony.hoshi.epub.EpubChapter
import moe.antimony.hoshi.epub.EpubResource
import moe.antimony.hoshi.features.audio.AudioRequestHandler
import moe.antimony.hoshi.features.audio.LocalAudioRepository
import moe.antimony.hoshi.features.dictionary.LookupPopupAssets
import moe.antimony.hoshi.features.dictionary.LookupPopupHtml
import moe.antimony.hoshi.features.dictionary.LookupPopupItem
import moe.antimony.hoshi.features.dictionary.LookupPopupState
import moe.antimony.hoshi.ui.theme.HoshiReaderTheme
import moe.antimony.hoshi.ui.theme.currentLargeScreenUiScale
import moe.antimony.hoshi.ui.theme.currentPopupReadability
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Exercises the real EPUB iframe host, resource loading, and native-to-JS stack updates. */
class ReaderPopupCardsWebViewTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun horizontalChapterAppliesCardOverridesAndRestoresDeviceDefaults() = verifyCards(false)
    @Test fun verticalChapterAppliesCardOverridesAndRestoresDeviceDefaults() = verifyCards(true)

    private fun verifyCards(vertical: Boolean) {
        val context = compose.activity
        val boox = InstrumentationRegistry.getArguments().getString("profile") == "boox"
        var settings by mutableStateOf(ReaderSettings(verticalWriting = vertical, theme = ReaderTheme.Light, eInkMode = boox))
        var webView by mutableStateOf<WebView?>(null)
        var restoring by mutableStateOf(false)
        val document = AtomicReference("")
        val uiScale = AtomicReference(1.0)
        val fontManager = ReaderFontManager(context.filesDir)
        val resources = ReaderLookupPopupResourceHandler(
            context, LookupPopupAssets.load(context), fontManager,
            AudioRequestHandler(LocalAudioRepository.fromContext(context)),
            iframeDocument = { document.get() },
        )
        val html = """<html xmlns="http://www.w3.org/1999/xhtml"><body><p id="sample">本を読む。日本語の本です。</p></body></html>"""
        val book = EpubBook(
            title = "Popup regression",
            chapters = listOf(EpubChapter("chapter", "chapter.xhtml", "application/xhtml+xml", html, 0)),
            resources = mapOf("chapter.xhtml" to EpubResource("application/xhtml+xml", html.toByteArray())),
        )
        val result = LookupResult("本", "本", emptyArray(), TermResult(
            expression = "本", reading = "ほん", rules = "",
            glossaries = arrayOf(GlossaryEntry("Sample", "book; volume", "n", "")),
            frequencies = emptyArray(), pitches = emptyArray(),
        ), 0)
        compose.setContent {
            HoshiReaderTheme(darkTheme = false, eInkMode = boox, dynamicColor = false) {
                BoxWithConstraints(Modifier.fillMaxSize()) {
                    val style = settings.dictionaryCardStyle(currentPopupReadability())
                    val scale = currentLargeScreenUiScale()
                    uiScale.set(scale)
                    val iframeHtml = LookupPopupHtml.renderIframeDocument(
                        popupScale = style.scale, fontScale = style.fontScale, uiScale = scale,
                        eInkMode = boox,
                    )
                    document.set(iframeHtml)
                    val popup = LookupPopupItem(id = "epub-card", state = LookupPopupState(
                        selection = ReaderSelectionData("本", "本を読む。", ReaderSelectionRect(80.0, 100.0, 25.0, 25.0), null, null),
                        results = listOf(result), isVertical = vertical, eInkMode = boox,
                        cardStyle = settings.dictionaryCard,
                    ))
                    val frames = listOf(ReaderLookupPopupFramePayload.fromPopup(
                        popup, 0, ReaderLookupPopupViewport(maxWidth.value.toDouble(), maxHeight.value.toDouble(), scale,
                            currentPopupReadability().frameScale),
                        iframeUrl = readerLookupPopupIframeUrl(iframeHtml.hashCode()),
                    ))
                    val density = LocalDensity.current
                    ChapterWebView(
                        book = book, chapterPosition = ReaderChapterPosition(0, 0.0), chapterFragment = null,
                        webViewViewportSize = with(density) { IntSize(maxWidth.roundToPx(), maxHeight.roundToPx()) },
                        onReaderViewportSizeChanged = {}, onWebViewReady = { webView = it },
                        isWebViewRestoring = restoring, webViewRestoreEpoch = 0,
                        onRestoreStarted = { restoring = true }, onRestoreCompleted = { restoring = false },
                        onNextChapter = { false }, onPreviousChapter = { false }, onSaveBookmark = {},
                        onDisplayProgress = {}, onContinuousScrollDisplayProgress = { _, _ -> },
                        onContinuousScrollProgress = { _, _ -> }, onInternalLink = {}, scanNonJapaneseText = false,
                        readerSettings = settings, chapterHighlightsJson = null, chapterSasayakiCuesJson = null,
                        chapterSentenceAnchorsJson = null, sasayakiTextColor = 0, sasayakiBackgroundColor = 0,
                        onTextSelected = { _, _ -> }, onClearLookupPopup = {}, onReaderTapOutside = {},
                        onReaderInteraction = {}, onImageTapped = {}, onHighlightCreated = { _, _, _ -> },
                        onSentenceTranslation = {}, readerPopupBridgeHolder = remember { ReaderLookupPopupBridgeCallbackHolder() },
                        readerPopupResourceHandler = resources, readerIframePopupSupported = true,
                        readerPopupFrames = frames, fontManager = fontManager, systemDark = false,
                        modifier = Modifier.fillMaxSize(),
                    )
                    ReaderLookupPopupIframeSync(webView, frames, null)
                }
            }
        }
        val frameMultiplier = if (boox) 1.4 else 1.0
        val textMultiplier = if (boox) 1.6 else 1.0
        awaitCard({ webView }, 320 * frameMultiplier * uiScale.get(), 250 * frameMultiplier * uiScale.get(), textMultiplier * uiScale.get(), 26.0)
        val originalWebView = webView
        evaluate(webView!!, "window.chapterSentinel = 91; 91")
        compose.runOnIdle { settings = settings.copy(dictionaryCard = PopupCardStyle(380.0, 300.0, 1.3, 1.45)) }
        awaitCard({ webView }, 380 * uiScale.get(), 300 * uiScale.get(), 1.3 * uiScale.get(), 26 * 1.45)
        assertSame(originalWebView, webView)
        assertEquals("91", evaluate(webView!!, "window.chapterSentinel"))
        assertEquals("\"本を読む。日本語の本です。\"", evaluate(webView!!, "document.getElementById('sample').textContent"))
        compose.runOnIdle { settings = settings.resetDictionaryCard() }
        awaitCard({ webView }, 320 * frameMultiplier * uiScale.get(), 250 * frameMultiplier * uiScale.get(), textMultiplier * uiScale.get(), 26.0)
        assertEquals("91", evaluate(webView!!, "window.chapterSentinel"))
    }

    private fun awaitCard(webView: () -> WebView?, width: Double, height: Double, zoom: Double, font: Double) {
        var last = JSONObject()
        compose.waitUntil(20_000) {
            webView()?.let {
                last = runCatching { JSONObject(evaluate(it, """
                    (() => {
                      const frame = document.querySelector('.hoshi-reader-popup-iframe');
                      const doc = frame?.contentDocument;
                      const term = doc?.querySelector('.expression');
                      if (!term) return {};
                      const bounds = frame.parentElement.getBoundingClientRect();
                      const shellStyle = getComputedStyle(frame.parentElement);
                      return {width:bounds.width, height:bounds.height,
                        visible:shellStyle.visibility === 'visible' && shellStyle.opacity === '1',
                        glossary:doc.body.innerText.includes('book; volume'),
                        zoom:parseFloat(getComputedStyle(doc.documentElement).zoom),
                        font:parseFloat(getComputedStyle(term).fontSize)};
                    })()
                """.trimIndent())) }.getOrDefault(JSONObject())
            }
            last.optBoolean("visible") && last.optBoolean("glossary") &&
                kotlin.math.abs(last.optDouble("width") - width) < 0.6 &&
                kotlin.math.abs(last.optDouble("height") - height) < 0.6 &&
                kotlin.math.abs(last.optDouble("zoom") - zoom) < 0.01 &&
                kotlin.math.abs(last.optDouble("font") - font) < 0.05
        }
        assertEquals(width, last.getDouble("width"), 0.6)
        assertEquals(height, last.getDouble("height"), 0.6)
        assertEquals(zoom, last.getDouble("zoom"), 0.01)
        assertEquals(font, last.getDouble("font"), 0.05)
        assertTrue(last.getBoolean("visible"))
        assertTrue(last.getBoolean("glossary"))
    }

    private fun evaluate(webView: WebView, script: String): String {
        val done = CountDownLatch(1)
        var value = "null"
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            webView.evaluateJavascript(script) { value = it; done.countDown() }
        }
        assertTrue("WebView JavaScript callback", done.await(3, TimeUnit.SECONDS))
        return value
    }
}
