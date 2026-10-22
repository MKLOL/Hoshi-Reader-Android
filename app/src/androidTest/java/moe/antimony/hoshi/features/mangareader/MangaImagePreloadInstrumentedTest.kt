package moe.antimony.hoshi.features.mangareader

import android.graphics.Bitmap
import android.webkit.WebView
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import moe.antimony.hoshi.mokuro.MokuroBook
import moe.antimony.hoshi.mokuro.MokuroPage
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.roundToInt

@RunWith(AndroidJUnit4::class)
class MangaImagePreloadInstrumentedTest {
    @get:Rule val compose = createComposeRule()

    @Test fun decodesTheNeighborWindowAndServesPreparedImagesAcrossDocumentTurns() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val root = File(context.cacheDir, "preload-${System.nanoTime()}").apply { mkdirs() }
        val bitmap = Bitmap.createBitmap(1600, 2400, Bitmap.Config.ARGB_8888)
        for (i in 0..6) {
            bitmap.eraseColor(0xff000000.toInt() or ((i + 1) * 30 shl 16))
            root.resolve("$i.jpg").outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) }
        }
        bitmap.recycle()
        val book = MokuroBook("Preload", (0..6).map { MokuroPage(it, "$it.jpg", 1600, 2400, emptyList()) }, null)
        val backend = MangaImageLoaderBackend(root, book, MangaImageCachePolicy(4L shl 20, 96L shl 20))
        val cache = MangaPageRenderCache(imageLoader = backend)
        val ready = AtomicInteger(-1)
        var page by mutableIntStateOf(2)
        lateinit var view: WebView
        compose.setContent {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                MangaReaderWebView(
                    book, root, page,
                    MangaPageRenderConfig("#000000", false, false, maxWidth.value.roundToInt(),
                        maxHeight.value.roundToInt(), selectionScript = ""), cache,
                    onNavigate = {}, onTextSelected = { _, _ -> }, onSelectionCleared = {},
                    onAskAi = { _, _ -> }, onPageReady = ready::set, onWebViewReady = { view = it },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        try {
            compose.waitUntil(20_000) { ready.get() == 2 }
            waitForPreload(view)
            val state = JSONObject(evaluate(view, "JSON.stringify({ decoded: window.hoshiMangaImageCache.decoded, failed: window.hoshiMangaImageCache.failed, bytes: window.hoshiMangaImageCache.bytes })").let { org.json.JSONTokener(it).nextValue() as String })
            assertEquals(5, state.getJSONArray("decoded").length())
            assertEquals(0, state.getJSONArray("failed").length())
            assertTrue(state.getLong("bytes") <= 96L shl 20)
            kotlinx.coroutines.runBlocking { backend.awaitEncodedPreload() }
            assertEquals(6, backend.snapshot().entries)
            val beforeTurn = backend.snapshot()
            // Simulate time spent reading: preparation must remain useful beyond its first frame.
            Thread.sleep(1500)
            compose.runOnIdle { page = 3 }
            compose.waitUntil(20_000) { ready.get() == 3 }
            waitForPreload(view)
            kotlinx.coroutines.runBlocking { backend.awaitEncodedPreload() }
            // Only page 6 is new to this window. A cold read of page 3 would add another fill.
            assertEquals(beforeTurn.reads + 1, backend.snapshot().reads)
            assertTrue(backend.snapshot().hits > beforeTurn.hits)
            assertEquals("true", evaluate(view, "document.querySelector('.page-image').complete && document.querySelector('.page-image').naturalWidth === 1600"))
            assertEquals("3", evaluate(view, "document.querySelector('.frame').dataset.pageIndex").trim('"'))
            compose.runOnIdle { page = 0 }
            compose.waitUntil(20_000) { ready.get() == 0 }
            waitForPreload(view)
            assertEquals("3", evaluate(view, "window.hoshiMangaImageCache.decoded.length"))
            assertTrue(backend.snapshot().bytes <= 4L shl 20)
        } finally {
            backend.close()
        }
    }

    @Test fun decodeBudgetAndBadImagesDoNotBlockTheVisiblePage() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val root = File(context.cacheDir, "preload-failure-${System.nanoTime()}").apply { mkdirs() }
        val bitmap = Bitmap.createBitmap(200, 300, Bitmap.Config.ARGB_8888)
        root.resolve("0.jpg").outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        bitmap.recycle()
        root.resolve("1.jpg").writeText("corrupt image")
        val book = MokuroBook("Failure", listOf(
            MokuroPage(0, "0.jpg", 200, 300, emptyList()),
            MokuroPage(1, "1.jpg", 200, 300, emptyList()),
            MokuroPage(2, "missing.jpg", 200, 300, emptyList()),
            MokuroPage(3, "huge.jpg", 10000, 10000, emptyList()),
        ), null)
        val backend = MangaImageLoaderBackend(root, book, MangaImageCachePolicy(1024, 1024 * 1024))
        val cache = MangaPageRenderCache(imageLoader = backend)
        val ready = AtomicInteger(-1)
        lateinit var view: WebView
        compose.setContent {
            MangaReaderWebView(book, root, 0, MangaPageRenderConfig("#ffffff", false, false, 400, 600,
                selectionScript = ""), cache, onNavigate = {}, onTextSelected = { _, _ -> },
                onSelectionCleared = {}, onAskAi = { _, _ -> }, onPageReady = ready::set,
                onWebViewReady = { view = it }, modifier = Modifier.fillMaxSize())
        }
        try {
            compose.waitUntil(20_000) { ready.get() == 0 }
            waitForPreload(view)
            assertEquals("true", evaluate(view, "document.querySelector('.page-image').naturalWidth === 200"))
            assertEquals("2", evaluate(view, "window.hoshiMangaImageCache.failed.length"))
            assertEquals("1", evaluate(view, "window.hoshiMangaImageCache.skipped.length"))
        } finally {
            backend.close()
        }
    }

    private fun waitForPreload(view: WebView) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
        while (System.nanoTime() < deadline) {
            if (evaluate(view, "!!(window.hoshiMangaImageCache && window.hoshiMangaImageCache.done)") == "true") return
            Thread.sleep(100)
        }
        fail("Image preload did not complete")
    }

    private fun evaluate(view: WebView, script: String): String {
        val latch = CountDownLatch(1)
        var result = "null"
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            view.evaluateJavascript(script) { result = it; latch.countDown() }
        }
        assertTrue(latch.await(5, TimeUnit.SECONDS))
        return result
    }
}
