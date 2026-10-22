package moe.antimony.hoshi.features.reader

import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import kotlinx.coroutines.runBlocking
import moe.antimony.hoshi.HoshiApplication
import moe.antimony.hoshi.LocalHoshiAppContainer
import moe.antimony.hoshi.epub.*
import moe.antimony.hoshi.ui.theme.HoshiReaderTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Real WebView callbacks must capture navigation before idle/disposal can refresh its time. */
class ReadingPositionReaderTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @get:Rule val temporary = TemporaryFolder()

    @Test fun continuousScrollCapturesBeforeIdleAndDisposeCannotOverrideRemote() = runBlocking {
        val repository = BookRepository(temporary.newFolder("phone"), deviceIdentity = DeviceIdentity("phone", "Phone"))
        val root = repository.createBookDirectoryForImportedTitle("Position regression")
        repository.saveMetadata(root, BookMetadata("book", "Position regression", null, root.name, 0.0, "example"))
        repository.saveBookmark(root, Bookmark(0, 0.0, 0, 100.0))
        val session = repository.openReadingPosition(root)
        val html = "<html xmlns=\"http://www.w3.org/1999/xhtml\"><body>" +
            (1..250).joinToString("") { "<p>本を読む。日本語の本です。長い文章を読む。</p>" } + "</body></html>"
        val book = EpubBook("Position regression",
            listOf(EpubChapter("chapter", "chapter.xhtml", "application/xhtml+xml", html, 0)),
            resources = mapOf("chapter.xhtml" to EpubResource("application/xhtml+xml", html.toByteArray())))
        val container = (compose.activity.application as HoshiApplication).appContainer
        val visible = mutableStateOf(true)
        val captured = Collections.synchronizedList(mutableListOf<ReadingPositionEvent>())
        val persisted = Collections.synchronizedList(mutableListOf<ReadingPositionEvent>())
        val clock = AtomicReference(150.0)
        val saved = CountDownLatch(1)
        val secondPrepared = CountDownLatch(1)
        val prepareCalls = java.util.concurrent.atomic.AtomicInteger()
        compose.setContent {
            CompositionLocalProvider(LocalHoshiAppContainer provides container) {
                HoshiReaderTheme(darkTheme = false, dynamicColor = false) {
                    if (visible.value) ReaderWebView(
                        book = book, bookRoot = root,
                        readerSettings = ReaderSettings(verticalWriting = false, continuousMode = true),
                        onClose = {},
                        onSaveBookmark = { chapter, progress, _ ->
                            session.navigate(Bookmark(chapter, progress, book.characterCountAt(chapter, progress), clock.get()))
                                ?.let { captured += it; persisted += it }
                        },
                        onPrepareBookmarkSave = { chapter, progress ->
                            if (prepareCalls.incrementAndGet() >= 2) secondPrepared.countDown()
                            val event = session.navigate(Bookmark(chapter, progress,
                                book.characterCountAt(chapter, progress), clock.get()))
                            if (event != null) {
                                captured += event
                                // Any idle/disposal callback now sees a later clock.
                                clock.set(250.0)
                                saved.countDown()
                            }
                            event?.let { capturedEvent ->
                                val persist: () -> Unit = { persisted += capturedEvent; Unit }
                                persist
                            }
                        },
                    )
                }
            }
        }
        var view: WebView? = null
        compose.waitUntil(20_000) {
            compose.runOnUiThread { view = findWebView(compose.activity.window.decorView) }
            view != null
        }
        val webView = requireNotNull(view)
        compose.waitUntil(20_000) {
            evaluate(webView, "Boolean(window.hoshiReader && document.readyState === 'complete' && document.documentElement.scrollHeight > innerHeight * 3)") == "true"
        }
        // The WebView becomes visible only after restore and its first visual frame complete.
        compose.waitUntil(20_000) {
            var restored = false
            compose.runOnUiThread { restored = webView.alpha == 1f }
            restored
        }
        compose.waitForIdle()
        assertTrue("Restoring/opening must not author navigation", captured.isEmpty())
        // Character-based progress can stay identical over several pixel scroll samples.
        evaluate(webView, "window.hoshiReader.calculateProgress = function() { return 0.2; }; true")
        compose.runOnUiThread { webView.scrollBy(0, webView.height) }
        assertTrue("Reader did not save a scroll position", saved.await(5, TimeUnit.SECONDS))
        // A duplicate sample must retain the first unsaved event instead of replacing it
        // with a no-op; keep both inside the reader's 250 ms idle window.
        Thread.sleep(60)
        compose.runOnUiThread { webView.scrollBy(0, 1) }
        assertTrue("The duplicate sample must be delivered before idle", secondPrepared.await(2, TimeUnit.SECONDS))
        assertTrue("Scroll samples should coalesce before idle", persisted.isEmpty())
        val pending = captured.first()
        assertEquals(150.0, pending.occurredAt, 0.0)

        val tablet = BookRepository(temporary.newFolder("tablet"), deviceIdentity = DeviceIdentity("tablet", "Tablet"))
        val tabletRoot = tablet.createBookDirectoryForImportedTitle("Position regression")
        tablet.saveMetadata(tabletRoot, BookMetadata("book", "Position regression", null, tabletRoot.name, 0.0, "example"))
        tablet.recordReadingPosition(tabletRoot, tablet.openReadingPosition(tabletRoot)
            .navigate(Bookmark(0, 0.8, book.characterCountAt(0, 0.8), 200.0))!!)
        repository.readingPositions.merge(tablet.readingPositions.export("example")!!, root)
        compose.runOnUiThread { visible.value = false }
        compose.waitForIdle()
        // Delayed persistence is deliberately completed after remote apply and reader disposal.
        for (event in persisted.toList()) repository.recordReadingPosition(root, event)
        assertEquals("An idle/disposal flush must not author the same position again", 1, captured.size)
        assertEquals("A scroll is persisted once at idle/disposal", listOf(pending.id), persisted.map { it.id })
        assertEquals(0.8, repository.loadBookmark(root)!!.progress, 0.0)
        assertEquals(setOf(pending.id), repository.readingPositions.snapshot(root).pending)
    }

    private fun findWebView(view: View): WebView? {
        if (view is WebView) return view
        if (view is ViewGroup) for (index in 0 until view.childCount) {
            findWebView(view.getChildAt(index))?.let { return it }
        }
        return null
    }

    private fun evaluate(view: WebView, script: String): String {
        val result = AtomicReference("")
        val done = CountDownLatch(1)
        compose.runOnUiThread { view.evaluateJavascript(script) { result.set(it); done.countDown() } }
        check(done.await(5, TimeUnit.SECONDS)) { "JavaScript callback timed out" }
        return result.get()
    }
}
