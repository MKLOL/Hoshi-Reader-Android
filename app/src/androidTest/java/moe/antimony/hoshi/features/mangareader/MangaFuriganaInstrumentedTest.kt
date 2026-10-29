package moe.antimony.hoshi.features.mangareader

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import moe.antimony.hoshi.features.reader.ReaderSelectionScripts
import moe.antimony.hoshi.mokuro.MokuroPage
import moe.antimony.hoshi.mokuro.MokuroTextBox
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.io.File

/** Real Chromium coverage: ruby layout and caret hit testing cannot be checked by HTML strings. */
@RunWith(AndroidJUnit4::class)
class MangaFuriganaInstrumentedTest {
    @Test fun readingsAreEscapedAndInvalidOffsetsNeverChangeBaseText() {
        val canonical = "𠮷野<食&\n明日"
        val state = evaluate(
            boxes = listOf(box(canonical, blockIndex = 7)),
            furigana = mapOf(7 to listOf(
                MangaFuriganaSpan(1, 1, "invalid surrogate boundary"),
                MangaFuriganaSpan(4, 3, "invalid line crossing"),
                MangaFuriganaSpan(-1, 2, "invalid negative offset"),
                MangaFuriganaSpan(0, 3, "よしの"),
                MangaFuriganaSpan(7, 2, "<あ&す>"),
                MangaFuriganaSpan(Int.MAX_VALUE, Int.MAX_VALUE, "overflow"),
            )),
            script = """
                var box = document.querySelector('.ocr-box');
                var walker = window.hoshiSelection.createWalker(box.querySelector('p'));
                var base = '', node;
                while (node = walker.nextNode()) base += node.textContent;
                return {base:base, canonical:window.hoshiManga.bubbleText(box),
                  rubyCount:box.querySelectorAll('ruby').length,
                  readings:Array.from(box.querySelectorAll('rt')).map(function(rt) { return rt.textContent; }),
                  injected:box.querySelectorAll('img, script').length,
                  announced:box.getAttribute('aria-label')};
            """,
        )
        assertEquals(canonical, state.getString("base"))
        assertEquals(canonical, state.getString("canonical"))
        assertEquals(canonical, state.getString("announced"))
        assertEquals(2, state.getInt("rubyCount"))
        assertEquals("よしの", state.getJSONArray("readings").getString(0))
        assertEquals("<あ&す>", state.getJSONArray("readings").getString(1))
        assertEquals(0, state.getInt("injected"))
    }

    @Test fun revealLookupAnnotationTapsAndActionsKeepCanonicalOcr() {
        for (vertical in listOf(false, true)) {
            val state = evaluate(vertical = vertical, script = """
                var box = document.querySelector('.ocr-box');
                var ruby = box.querySelector('ruby');
                var hidden = getComputedStyle(ruby.querySelector('rt')).color;
                var first = tapCharacter(ruby.firstChild, 0);
                var revealed = box.classList.contains('revealed');
                var lookup = tapCharacter(ruby.firstChild, 0);
                var payload = testEvents.lookup;
                window.hoshiSelection.clearSelection();
                var annotationLookup = tapElement(ruby.querySelector('rt'));
                var annotationSentence = testEvents.lookup.sentence;
                tapElement(box.querySelector('.ocr-copy-btn'));
                tapElement(box.querySelector('.ocr-ai-btn'));
                var rtRect = ruby.querySelector('rt').getBoundingClientRect();
                var extent = window.hoshiOcrBoxExtent(box);
                var rect = payload.rect;
                var coversRuby = rect.x <= rtRect.left + 0.1 && rect.y <= rtRect.top + 0.1 &&
                  rect.x + rect.width >= rtRect.right - 0.1 && rect.y + rect.height >= rtRect.bottom - 0.1;
                window.hoshiManga.handleTap(398, 598, 16);
                return {first:first, revealed:revealed, lookup:lookup, annotationLookup:annotationLookup,
                  sentence:payload.sentence, sentenceOffset:payload.sentenceOffset,
                  annotationSentence:annotationSentence,
                  copied:testEvents.copied, ai:testEvents.ai, revealText:testEvents.revealed,
                  revealBlock:testEvents.revealedBlock, coversRuby:coversRuby,
                  hidden:hidden, hiddenAgain:getComputedStyle(ruby.querySelector('rt')).color,
                  cleared:!box.classList.contains('revealed')};
            """)
            assertEquals("__revealed__", state.getString("first"))
            assertTrue(state.getBoolean("revealed"))
            assertEquals("明日は学校へ行く", state.getString("lookup"))
            assertEquals("明日は学校へ行く", state.getString("annotationLookup"))
            assertEquals("明日は学校へ行く。", state.getString("sentence"))
            assertEquals(state.getString("sentence"), state.getString("annotationSentence"))
            assertEquals(0, state.getInt("sentenceOffset"))
            for (key in listOf("copied", "ai", "revealText")) assertEquals(TEXT, state.getString(key))
            assertEquals("p0b5", state.getString("revealBlock"))
            assertTrue("popup must clear ruby: $state", state.getBoolean("coversRuby"))
            assertEquals("rgba(0, 0, 0, 0)", state.getString("hidden"))
            assertEquals(state.getString("hidden"), state.getString("hiddenAgain"))
            assertTrue(state.getBoolean("cleared"))
        }
    }

    @Test fun singleTapLookupStillSelectsTheTappedBaseAfterRubyFits() {
        for (vertical in listOf(false, true)) {
            val state = evaluate(vertical = vertical, singleTap = true, script = """
                var box = document.querySelector('.ocr-box');
                var result = tapCharacter(box.querySelector('ruby').firstChild, 0);
                return {result:result, revealed:box.classList.contains('revealed'),
                  sentence:testEvents.lookup && testEvents.lookup.sentence, revealText:testEvents.revealed};
            """)
            assertEquals("明日は学校へ行く", state.getString("result"))
            assertEquals("明日は学校へ行く。", state.getString("sentence"))
            assertEquals(TEXT, state.getString("revealText"))
            assertTrue(state.getBoolean("revealed"))
        }
    }

    @Test fun annotatedBubblesFitBothOrientationsWithoutReducingGlyphSize() {
        for (vertical in listOf(false, true)) for (enlarge in listOf(false, true)) {
            val state = evaluate(vertical = vertical, enlarge = enlarge,
                screenshotName = "manga-furigana-${if (vertical) "vertical" else "horizontal"}-${if (enlarge) "enlarged" else "original"}.png",
                script = """
                var box = document.querySelector('.ocr-box');
                var before = parseFloat(getComputedStyle(box).fontSize);
                tapCharacter(box.querySelector('ruby').firstChild, 0);
                var border = box.getBoundingClientRect();
                var actions = box.querySelector('.ocr-actions').getBoundingClientRect();
                var range = document.createRange(); range.selectNodeContents(box.querySelector('p'));
                var text = range.getBoundingClientRect();
                var rtRects = Array.from(box.querySelectorAll('rt')).map(function(rt) { return rt.getBoundingClientRect(); });
                var inside = rtRects.every(function(r) {
                  return r.left >= -0.1 && r.top >= -0.1 && r.right <= 400.1 && r.bottom <= 600.1;
                });
                var textTop = Math.min.apply(null, [text.top].concat(rtRects.map(function(r) { return r.top; })));
                var textBottom = Math.max.apply(null, [text.bottom].concat(rtRects.map(function(r) { return r.bottom; })));
                var textLeft = Math.min.apply(null, [text.left].concat(rtRects.map(function(r) { return r.left; })));
                var textRight = Math.max.apply(null, [text.right].concat(rtRects.map(function(r) { return r.right; })));
                var clears = actions.bottom <= textTop - 2.8 || actions.top >= textBottom + 2.8 ||
                  actions.right <= textLeft - 2.8 || actions.left >= textRight + 2.8;
                return {before:before, after:parseFloat(getComputedStyle(box).fontSize), inside:inside,
                  clears:clears, localVertical:window.hoshiRubyGeometry.isVertical(box.querySelector('ruby').firstChild),
                  bodyVertical:window.hoshiRubyGeometry.isVertical(document.body),
                  boxInside:border.left >= 1.9 && border.top >= 1.9 && border.right <= 398.1 && border.bottom <= 598.1};
            """)
            assertTrue("ruby should fit the viewport: $state", state.getBoolean("inside"))
            assertTrue("plate should fit the viewport: $state", state.getBoolean("boxInside"))
            assertTrue("actions must clear readings: $state", state.getBoolean("clears"))
            assertTrue("font must preserve readability: $state", state.getDouble("after") >= state.getDouble("before") - 0.1)
            assertEquals(vertical, state.getBoolean("localVertical"))
            assertFalse(state.getBoolean("bodyVertical"))
        }
    }

    @Test fun missingReadingsRetainThePlainOcrAndTapBehavior() {
        val state = evaluate(furigana = emptyMap(), script = """
            var box = document.querySelector('.ocr-box');
            var first = tapCharacter(box.querySelector('p').firstChild, 0);
            var second = tapCharacter(box.querySelector('p').firstChild, 0);
            return {first:first, second:second, rubyCount:box.querySelectorAll('ruby').length,
              canonical:window.hoshiManga.bubbleText(box)};
        """)
        assertEquals("__revealed__", state.getString("first"))
        assertEquals("明日は学校へ行く", state.getString("second"))
        assertEquals(0, state.getInt("rubyCount"))
        assertEquals(TEXT, state.getString("canonical"))
    }

    @Test fun edgeSingleTapPreservesSourceGlyphAndHidingRestoresItsOriginalHitRegion() {
        for (vertical in listOf(false, true)) {
            val state = evaluate(
                singleTap = true,
                boxes = listOf(box(vertical = vertical).copy(left = 850, top = 850, width = 150, height = 150)),
                script = """
                    var box = document.querySelector('.ocr-box');
                    var originalStyle = box.getAttribute('style');
                    var original = box.getBoundingClientRect();
                    var node = box.querySelector('ruby').firstChild;
                    var first = tapCharacter(node, 0);
                    window.hoshiManga.clearRevealed();
                    var restored = box.getBoundingClientRect();
                    var restoredStyle = box.getAttribute('style');
                    var second = tapCharacter(node, 0);
                    return {first:first, second:second, originalStyle:originalStyle, restoredStyle:restoredStyle,
                      drift:Math.abs(original.left-restored.left)+Math.abs(original.top-restored.top)+
                        Math.abs(original.width-restored.width)+Math.abs(original.height-restored.height)};
                """,
            )
            assertEquals("明日は学校へ行く", state.getString("first"))
            assertEquals("明日は学校へ行く", state.getString("second"))
            assertEquals(state.getString("originalStyle"), state.getString("restoredStyle"))
            assertEquals(0.0, state.getDouble("drift"), 0.01)
        }
    }

    @Test fun singleTapOnAnEdgeAnnotationKeepsItsBaseWordWhenThePlateMoves() {
        val state = evaluate(
            enlarge = false, singleTap = true,
            boxes = listOf(box(vertical = true).copy(left = 850, top = 850, width = 150,
                height = 150, originalFontSize = 40)),
            script = """
                var box = document.querySelector('.ocr-box');
                var annotation = box.querySelector('rt');
                var before = box.getBoundingClientRect();
                var annotationRect = annotation.getBoundingClientRect();
                var x = annotationRect.left + annotationRect.width / 2;
                var y = annotationRect.top + annotationRect.height / 2;
                var inside = x >= before.left && x <= before.right && y >= before.top && y <= before.bottom;
                var result = window.hoshiManga.handleTap(x, y, 16);
                var after = box.getBoundingClientRect();
                return {result:result, inside:inside, annotation:annotation.textContent,
                  moved:Math.abs(before.left-after.left)+Math.abs(before.top-after.top)};
            """,
        )
        assertEquals("あした", state.getString("annotation"))
        assertTrue("annotation tap must start within the hidden OCR box: $state", state.getBoolean("inside"))
        assertTrue("fixture must move the revealed plate: $state", state.getDouble("moved") > 1)
        assertEquals("明日は学校へ行く", state.getString("result"))
    }

    @Test fun hiddenRubyAndTextCannotCaptureArtworkTapsOutsideTheOriginalRectangle() {
        for (vertical in listOf(false, true)) {
            val state = evaluate(
                boxes = listOf(box(vertical = vertical).copy(width = 90, height = 90)),
                script = """
                    var box = document.querySelector('.ocr-box');
                    var bounds = box.getBoundingClientRect();
                    var walker = document.createTreeWalker(box.querySelector('p'), NodeFilter.SHOW_TEXT);
                    var node, point = null;
                    while (!point && (node = walker.nextNode())) {
                      for (var i = 0; i < node.length; i++) {
                        var range = document.createRange(); range.setStart(node,i); range.setEnd(node,i+1);
                        var r = range.getBoundingClientRect();
                        var x = r.left+r.width/2, y = r.top+r.height/2;
                        if ((x < bounds.left || x > bounds.right || y < bounds.top || y > bounds.bottom) &&
                            x > 0 && x < 400 && y > 0 && y < 600) { point={x:x,y:y}; break; }
                      }
                    }
                    if (!point) return {fixture:false};
                    function captures() {
                      var el = document.elementFromPoint(point.x,point.y);
                      return !!(el && el.closest('.ocr-box') === box);
                    }
                    var before = captures();
                    var result = window.hoshiManga.handleTap(point.x,point.y,16);
                    var revealedByArtwork = box.classList.contains('revealed');
                    window.hoshiManga.handleTap(bounds.left+1,bounds.top+1,16);
                    window.hoshiManga.clearRevealed();
                    return {fixture:true, before:before, after:captures(), result:result,
                      revealedByArtwork:revealedByArtwork, regions:testEvents.regions};
                """,
            )
            assertTrue("fixture needs overflowing descendants: $state", state.getBoolean("fixture"))
            assertFalse("hidden text stole artwork tap: $state", state.getBoolean("before"))
            assertFalse("hidden text stole artwork after hide: $state", state.getBoolean("after"))
            assertFalse(state.getBoolean("revealedByArtwork"))
            assertTrue(state.isNull("result"))
            assertEquals(0, state.getJSONArray("regions").length())
        }
    }

    @Test fun revealedExpandedTextReceivesTapsOverLaterHiddenBubbles() {
        val first = box().copy(left = 100, top = 100, width = 100, height = 100)
        val later = box("後ろの気泡", blockIndex = 9).copy(left = 250, top = 100, width = 350, height = 200)
        val state = evaluate(boxes = listOf(first, later), script = """
            var boxes = document.querySelectorAll('.ocr-box');
            var active = boxes[0], hidden = boxes[1];
            tapCharacter(active.querySelector('ruby').firstChild,0);
            var target = active.querySelectorAll('ruby')[1].firstChild;
            var range = document.createRange(); range.setStart(target,1); range.setEnd(target,2);
            var r=range.getBoundingClientRect(), x=r.left+r.width/2, y=r.top+r.height/2;
            var h=hidden.getBoundingClientRect();
            var overlap=x>=h.left && x<=h.right && y>=h.top && y<=h.bottom;
            var el=document.elementFromPoint(x,y);
            var topmost=!!(el && el.closest('.ocr-box') === active);
            var result=window.hoshiManga.handleTap(x,y,16);
            return {overlap:overlap, topmost:topmost, result:result, hiddenRevealed:hidden.classList.contains('revealed')};
        """)
        assertTrue("fixture needs overlap after expansion: $state", state.getBoolean("overlap"))
        assertTrue("visible text must be above hidden OCR: $state", state.getBoolean("topmost"))
        assertEquals("校へ行く", state.getString("result"))
        assertFalse(state.getBoolean("hiddenRevealed"))
    }

    @Test fun veryLongBubblesRemainReadableAndTheirLastAnnotatedWordIsReachable() {
        val text = "学校".repeat(500)
        for (vertical in listOf(false, true)) {
            val state = evaluate(
                enlarge = false,
                boxes = listOf(box(text, vertical).copy(left = 20, top = 20, width = 150, height = 150)),
                furigana = mapOf(5 to (text.indices step 2).map { MangaFuriganaSpan(it, 2, "がっこう") }),
                script = """
                    var box = document.querySelector('.ocr-box');
                    var before = parseFloat(getComputedStyle(box).fontSize);
                    tapCharacter(box.querySelector('ruby').firstChild, 0);
                    var paragraph = box.querySelector('p');
                    paragraph.scrollLeft = -paragraph.scrollWidth;
                    paragraph.scrollTop = paragraph.scrollHeight;
                    var last = paragraph.querySelector('ruby:last-of-type');
                    var rect = last.getBoundingClientRect();
                    var clip = paragraph.getBoundingClientRect();
                    var visible = rect.left >= clip.left - 0.1 && rect.right <= clip.right + 0.1 &&
                      rect.top >= clip.top - 0.1 && rect.bottom <= clip.bottom + 0.1;
                    window.hoshiSelection.clearSelection();
                    var selected = tapCharacter(last.firstChild, 0);
                    var extent = window.hoshiOcrBoxExtent(box);
                    return {scrollable:box.classList.contains('furigana-scroll'), visible:visible, selected:selected,
                      regions:testEvents.regions,
                      before:before, after:parseFloat(getComputedStyle(box).fontSize),
                      scrollWidth:paragraph.scrollWidth, clientWidth:paragraph.clientWidth,
                      scrollHeight:paragraph.scrollHeight, clientHeight:paragraph.clientHeight,
                      extentWidth:extent.width, extentHeight:extent.height};
                """,
            )
            assertTrue("oversized bubble needs readable scrolling: $state", state.getBoolean("scrollable"))
            assertEquals("native gestures need the scrollable region: $state", 1, state.getJSONArray("regions").length())
            assertTrue("last word must be reachable: $state", state.getBoolean("visible"))
            assertEquals("学校", state.getString("selected"))
            assertEquals(state.getDouble("before"), state.getDouble("after"), 0.01)
            assertTrue("popup extent must use visible content: $state", state.getDouble("extentWidth") <= 400.1)
            assertTrue("popup extent must use visible content: $state", state.getDouble("extentHeight") <= 650.0)
        }
    }

    @Test fun zoomedRevealAndAnnotationLookupPreserveTextAndHostPopupCoordinates() {
        val state = evaluate(zoomFactor = 1.5f, script = """
            var box = document.querySelector('.ocr-box');
            tapCharacter(box.querySelector('ruby').firstChild, 0);
            var result = tapElement(box.querySelector('rt'));
            var extent = window.hoshiOcrBoxExtent(box);
            var expected = window.hoshiManga.hostRectFromViewportRect(extent);
            return {zoom:window.testNativeZoom, viewportScale:window.visualViewport.scale, result:result,
              canonical:window.hoshiManga.bubbleText(box),
              error:Math.abs(testEvents.lookup.rect.x-expected.x)+Math.abs(testEvents.lookup.rect.y-expected.y)+
                Math.abs(testEvents.lookup.rect.width-expected.width)+Math.abs(testEvents.lookup.rect.height-expected.height)};
        """)
        assertTrue("fixture must zoom the real WebView: $state", state.getDouble("zoom") > 1.2)
        assertEquals("明日は学校へ行く", state.getString("result"))
        assertEquals(TEXT, state.getString("canonical"))
        assertEquals(0.0, state.getDouble("error"), 0.01)
    }

    private fun box(text: String = TEXT, vertical: Boolean = false, blockIndex: Int = 5) =
        MokuroTextBox(100, 120, if (vertical) 120 else 300, if (vertical) 300 else 120,
            40, vertical, text.split('\n'), blockIndex = blockIndex, originalFontSize = 30)

    private fun evaluate(
        vertical: Boolean = false,
        enlarge: Boolean = true,
        singleTap: Boolean = false,
        boxes: List<MokuroTextBox> = listOf(box(vertical = vertical)),
        furigana: Map<Int, List<MangaFuriganaSpan>> = mapOf(5 to listOf(
            MangaFuriganaSpan(0, 2, "あした"), MangaFuriganaSpan(3, 2, "がっこう"),
            MangaFuriganaSpan(6, 1, "い"), MangaFuriganaSpan(10, 1, "つぎ"),
        )),
        screenshotName: String? = null,
        zoomFactor: Float = 1f,
        script: String,
    ): JSONObject {
        val bridges = """
            window.testEvents = {};
            window.HoshiTextSelection = {postMessage:function(json) { testEvents.lookup = JSON.parse(json); }};
            window.HoshiMangaTap = {
              bubbleRevealed:function(text, block) { testEvents.revealed=text; testEvents.revealedBlock=block; },
              scrollableBubbleRegions:function(page, json) { testEvents.regionPage=page; testEvents.regions=JSON.parse(json); }
            };
            window.HoshiMangaClipboard = {copyBubbleText:function(text) { testEvents.copied=text; }};
            window.HoshiMangaAi = {askAboutBubble:function(text) { testEvents.ai=text; }};
        """.trimIndent()
        val html = MangaPageHtml.build(
            MokuroPage(0, "test.png", 1000, 1000, boxes), "#ffffff", ReaderSelectionScripts.source(),
            false, false, 400, 600, singleTapLookup = singleTap, showCopyButton = true,
            enlargeSmallText = enlarge, furigana = furigana,
        ).replace("<head>", "<head><script>$bridges</script>")
        val loaded = CountDownLatch(1)
        val evaluated = CountDownLatch(1)
        var result: String? = null
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            lateinit var webView: WebView
            scenario.onActivity { activity ->
                webView = WebView(activity).apply {
                    settings.javaScriptEnabled = true
                    settings.setSupportZoom(true)
                    settings.builtInZoomControls = true
                    settings.displayZoomControls = false
                    settings.minimumFontSize = 1
                    settings.minimumLogicalFontSize = 1
                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView, url: String?) { loaded.countDown() }
                        override fun shouldInterceptRequest(view: WebView, request: android.webkit.WebResourceRequest) =
                            android.webkit.WebResourceResponse("image/png", "utf-8", java.io.ByteArrayInputStream(ByteArray(0)))
                    }
                }
                val density = activity.resources.displayMetrics.density
                activity.setContentView(FrameLayout(activity).apply {
                    addView(webView, ViewGroup.LayoutParams((400 * density).toInt(), (600 * density).toInt()))
                })
                webView.loadDataWithBaseURL(MangaPageHtml.BASE_URL, html, "text/html", "UTF-8", null)
            }
            try {
                assertTrue("Manga fixture did not load", loaded.await(15, TimeUnit.SECONDS))
                if (zoomFactor != 1f) {
                    val ready = CountDownLatch(1)
                    scenario.onActivity {
                        webView.postVisualStateCallback(3, object : WebView.VisualStateCallback() {
                            override fun onComplete(requestId: Long) { ready.countDown() }
                        })
                    }
                    assertTrue("Manga first frame did not become ready", ready.await(10, TimeUnit.SECONDS))
                    val zoomed = CountDownLatch(1)
                    scenario.onActivity {
                        webView.zoomBy(zoomFactor)
                        val deadline = android.os.SystemClock.uptimeMillis() + 9_000
                        fun awaitZoom() {
                            // Match production's native WebView zoom measurement. Chromium's
                            // visualViewport.scale can remain 1 under Android native zoom.
                            @Suppress("DEPRECATION")
                            val actualScale = webView.scale / webView.resources.displayMetrics.density
                            if (actualScale > 1.2f) {
                                webView.evaluateJavascript(
                                    "window.testNativeZoom=$actualScale; window.hoshiManga.setHostScale($actualScale);",
                                ) { zoomed.countDown() }
                            } else if (android.os.SystemClock.uptimeMillis() < deadline) {
                                webView.postDelayed({ awaitZoom() }, 50)
                            }
                        }
                        awaitZoom()
                    }
                    assertTrue("Manga zoom did not settle", zoomed.await(10, TimeUnit.SECONDS))
                }
                scenario.onActivity {
                    webView.evaluateJavascript("""
                        (function() {
                          function tapCharacter(node, offset) {
                            var range = document.createRange();
                            range.setStart(node, offset);
                            range.setEnd(node, offset + String.fromCodePoint(node.textContent.codePointAt(offset)).length);
                            var rect = range.getBoundingClientRect();
                            return window.hoshiManga.handleTap(rect.left + rect.width / 2, rect.top + rect.height / 2, 16);
                          }
                          function tapElement(element) {
                            var rect = element.getBoundingClientRect();
                            return window.hoshiManga.handleTap(rect.left + rect.width / 2, rect.top + rect.height / 2, 16);
                          }
                          $script
                        })();
                    """.trimIndent()) { value -> result = value; evaluated.countDown() }
                }
                assertTrue("Manga evaluation did not finish", evaluated.await(15, TimeUnit.SECONDS))
                if (screenshotName != null) {
                    val instrumentation = InstrumentationRegistry.getInstrumentation()
                    val painted = CountDownLatch(1)
                    scenario.onActivity {
                        webView.postVisualStateCallback(2, object : WebView.VisualStateCallback() {
                            override fun onComplete(requestId: Long) {
                                webView.postOnAnimation { webView.postOnAnimation { painted.countDown() } }
                            }
                        })
                    }
                    assertTrue("Manga compositor did not draw", painted.await(10, TimeUnit.SECONDS))
                    lateinit var screenshot: Bitmap
                    scenario.onActivity {
                        screenshot = Bitmap.createBitmap(webView.width, webView.height, Bitmap.Config.ARGB_8888)
                        webView.draw(Canvas(screenshot))
                    }
                    try {
                        File(instrumentation.targetContext.getExternalFilesDir(null), screenshotName).outputStream().use {
                            screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)
                        }
                    } finally {
                        screenshot.recycle()
                    }
                }
            } finally {
                scenario.onActivity {
                    (webView.parent as? ViewGroup)?.removeView(webView)
                    webView.destroy()
                }
            }
        }
        return JSONObject(requireNotNull(result))
    }

    private companion object {
        const val TEXT = "明日は学校へ行く。\n次の行"
    }
}
