package moe.antimony.hoshi.features.reader

import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import moe.antimony.hoshi.epub.EpubBook
import moe.antimony.hoshi.epub.EpubChapter
import moe.antimony.hoshi.epub.EpubResource
import moe.antimony.hoshi.R
import moe.antimony.hoshi.features.ai.AiChatEntry
import moe.antimony.hoshi.features.ai.AiChatPopupView
import moe.antimony.hoshi.features.ai.AiChatUiState
import moe.antimony.hoshi.features.audio.AudioRequestHandler
import moe.antimony.hoshi.features.audio.LocalAudioRepository
import moe.antimony.hoshi.features.dictionary.DictionaryImageRequestHandler
import moe.antimony.hoshi.features.dictionary.LookupPopupAssets
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class ReaderTranslationWebViewTest {
    @get:org.junit.Rule val composeRule = createComposeRule()

    @Test
    fun translatedSentenceRendersEmptyHashBoundButtonWithoutChangingReaderText() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val loaded = CountDownLatch(1)
        val evaluated = CountDownLatch(1)
        var result = JSONObject()
        lateinit var webView: WebView
        instrumentation.runOnMainSync {
            webView = WebView(instrumentation.targetContext).apply {
                settings.javaScriptEnabled = true
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, url: String) {
                        loaded.countDown()
                    }
                }
                loadDataWithBaseURL(
                    null,
                    """<!doctype html><html lang="ja"><head><style>${ReaderTranslationScripts.css}</style></head>
                    <body><p id="sentence">食べる。</p></body></html>""",
                    "text/html",
                    "utf-8",
                    null,
                )
            }
        }
        assertTrue(loaded.await(5, TimeUnit.SECONDS))
        instrumentation.runOnMainSync {
            webView.evaluateJavascript(
                """
                (() => {
                  window.hoshiReader = {
                    isMatchableChar: ch => /[ぁ-ゖァ-ヺ一-龯]/u.test(ch || ''),
                    createWalker: root => document.createTreeWalker(root || document.body, NodeFilter.SHOW_TEXT),
                    buildNodeOffsets: () => { window.offsetRebuilds = (window.offsetRebuilds || 0) + 1; }
                  };
                  ${ReaderTranslationScripts.source()}
                  window.hoshiTranslations.apply([{id:'c0s0#0123456789abcdef',start:0,len:3,text:'食べる'}]);
                  const button = document.querySelector('.hoshi-tl');
                  return { count: document.querySelectorAll('.hoshi-tl').length,
                    id: button?.dataset.tl || '', buttonText: button?.textContent || '',
                    paragraphText: document.getElementById('sentence').textContent,
                    rebuilds: window.offsetRebuilds || 0 };
                })()
                """.trimIndent(),
            ) { value ->
                result = JSONObject(value)
                evaluated.countDown()
            }
        }
        assertTrue(evaluated.await(5, TimeUnit.SECONDS))
        assertEquals(1, result.getInt("count"))
        assertEquals("c0s0#0123456789abcdef", result.getString("id"))
        assertEquals("", result.getString("buttonText"))
        assertEquals("食べる。", result.getString("paragraphText"))
        assertEquals(1, result.getInt("rebuilds"))
    }

    @Test
    fun lateSentencePreloadAppliesToAlreadyOpenChapter() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val anchors = mutableStateOf<String?>(null)
        val webViewRef = AtomicReference<WebView?>()
        val fontManager = ReaderFontManager(context.filesDir)
        val popupHandler = ReaderLookupPopupResourceHandler(
            context = context.applicationContext,
            assets = LookupPopupAssets.load(context),
            fontManager = fontManager,
            audioRequestHandler = AudioRequestHandler(LocalAudioRepository.fromContext(context.applicationContext)),
            imageRequestHandler = DictionaryImageRequestHandler(),
            iframeDocument = { "" },
        )
        val book = EpubBook(
            title = "Late preload",
            chapters = listOf(
                EpubChapter(
                    id = "chapter",
                    href = "chapter.xhtml",
                    mediaType = "application/xhtml+xml",
                    html = """<html xmlns="http://www.w3.org/1999/xhtml"><body><p>食べる。</p></body></html>""",
                    spineIndex = 0,
                ),
            ),
            resources = mapOf(
                "chapter.xhtml" to EpubResource(
                    mediaType = "application/xhtml+xml",
                    bytes = """<html xmlns="http://www.w3.org/1999/xhtml"><body><p>食べる。</p></body></html>"""
                        .toByteArray(),
                ),
            ),
        )
        composeRule.setContent {
            val restoring = remember { mutableStateOf(false) }
            ChapterWebView(
                book = book,
                chapterPosition = ReaderChapterPosition(0, 0.0),
                chapterFragment = null,
                webViewViewportSize = IntSize(600, 800),
                onReaderViewportSizeChanged = {},
                onWebViewReady = { webViewRef.set(it) },
                isWebViewRestoring = restoring.value,
                webViewRestoreEpoch = 0,
                onRestoreStarted = { restoring.value = true },
                onRestoreCompleted = { restoring.value = false },
                onNextChapter = { false },
                onPreviousChapter = { false },
                onSaveBookmark = {},
                onDisplayProgress = {},
                onContinuousScrollDisplayProgress = { _, _ -> },
                onContinuousScrollProgress = { _, _ -> },
                onInternalLink = {},
                scanNonJapaneseText = false,
                readerSettings = ReaderSettings(),
                chapterHighlightsJson = null,
                chapterSasayakiCuesJson = null,
                chapterSentenceAnchorsJson = anchors.value,
                sasayakiTextColor = 0,
                sasayakiBackgroundColor = 0,
                onTextSelected = { _, _ -> },
                onClearLookupPopup = {},
                onReaderTapOutside = {},
                onReaderInteraction = {},
                onImageTapped = {},
                onHighlightCreated = { _, _, _ -> },
                onSentenceTranslation = {},
                readerPopupBridgeHolder = ReaderLookupPopupBridgeCallbackHolder(),
                readerPopupResourceHandler = popupHandler,
                readerIframePopupSupported = false,
                readerPopupFrames = emptyList(),
                fontManager = fontManager,
                systemDark = false,
                modifier = Modifier.fillMaxSize(),
            )
        }

        composeRule.waitUntil(10_000) { translationButtonCount(webViewRef.get()) == 0 }
        composeRule.runOnIdle {
            anchors.value = """[{"id":"c0s0#0123456789abcdef","start":0,"len":3,"text":"食べる"}]"""
        }
        composeRule.waitUntil(10_000) { translationButtonCount(webViewRef.get()) == 1 }
    }

    @Test
    fun pretranslatedPopupOffersLiveChatFallback() {
        var askedLive = false
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val popupTitle = context.getString(R.string.ai_chat_backend_pretranslated)
        val askLive = context.getString(R.string.ai_chat_ask_live_instead)
        composeRule.setContent {
            MaterialTheme {
                AiChatPopupView(
                    state = AiChatUiState.Loaded(
                        entry = AiChatEntry("食べる", "", "cached", "To eat.", 0.0),
                        pretranslated = true,
                    ),
                    onDismiss = {},
                    onRetry = {},
                    onAskLive = { askedLive = true },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        composeRule.onNodeWithText(popupTitle).assert(hasText(popupTitle, substring = true))
        composeRule.onNodeWithText(askLive).performClick()
        composeRule.runOnIdle { assertTrue(askedLive) }
    }

    private fun translationButtonCount(webView: WebView?): Int {
        if (webView == null) return -1
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val latch = CountDownLatch(1)
        var count = -1
        instrumentation.runOnMainSync {
            webView.evaluateJavascript(
                "typeof window.hoshiTranslations === 'object' ? document.querySelectorAll('.hoshi-tl').length : -1",
            ) { value ->
                count = value.trim('"').toIntOrNull() ?: -1
                latch.countDown()
            }
        }
        latch.await(2, TimeUnit.SECONDS)
        return count
    }
}
