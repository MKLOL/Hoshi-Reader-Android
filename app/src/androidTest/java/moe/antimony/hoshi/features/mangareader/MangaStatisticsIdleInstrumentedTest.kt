package moe.antimony.hoshi.features.mangareader

import android.graphics.Bitmap
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import moe.antimony.hoshi.HoshiApplication
import moe.antimony.hoshi.LocalHoshiAppContainer
import moe.antimony.hoshi.R
import moe.antimony.hoshi.epub.BookMetadata
import moe.antimony.hoshi.epub.BookRepository
import moe.antimony.hoshi.epub.DeviceIdentity
import moe.antimony.hoshi.features.dictionary.DictionarySettings
import moe.antimony.hoshi.features.reader.ReaderSettings
import moe.antimony.hoshi.mokuro.MokuroBook
import moe.antimony.hoshi.mokuro.MokuroPage
import moe.antimony.hoshi.ui.theme.HoshiReaderTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Reproduces the BOOX's one native frame per second on an untouched manga page. */
@RunWith(AndroidJUnit4::class)
class MangaStatisticsIdleInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun hiddenStatisticsKeepReaderUndrawnAndVisibleStatisticsCatchUpAndKeepCounting() = runBlocking {
        val repository = BookRepository(temporary.newFolder("library"), deviceIdentity = DeviceIdentity("idle-test", "Idle test"))
        val root = repository.createBookDirectoryForImportedTitle("Idle statistics")
        repository.saveMetadata(root, BookMetadata("idle-statistics", "Idle statistics", null, root.name, 0.0))
        val image = Bitmap.createBitmap(40, 60, Bitmap.Config.ARGB_8888)
        try {
            root.resolve("page.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally {
            image.recycle()
        }
        val book = MokuroBook("Idle statistics", listOf(MokuroPage(0, "page.png", 40, 60, emptyList())), null)
        val visible = mutableStateOf(true)
        val container = (compose.activity.application as HoshiApplication).appContainer
        val activeSince = SystemClock.elapsedRealtime()
        compose.setContent {
            CompositionLocalProvider(LocalHoshiAppContainer provides container) {
                HoshiReaderTheme(darkTheme = false, dynamicColor = false) {
                    if (visible.value) MangaReaderScreen(
                        book = book,
                        bookRoot = root,
                        initialPageIndex = 0,
                        repository = repository,
                        readerSettings = ReaderSettings(eInkMode = true),
                        onReaderSettingsChange = {},
                        dictionarySettings = DictionarySettings(),
                        onReaderKeyEventHandlerChange = {},
                        onBookmarkSaved = {},
                        onClose = {},
                    )
                }
            }
        }
        val decor = compose.activity.window.decorView
        val draws = AtomicInteger()
        val listener = ViewTreeObserver.OnDrawListener { draws.incrementAndGet() }
        try {
            var webView: WebView? = null
            compose.waitUntil(20_000) {
                compose.runOnUiThread { webView = findWebView(decor) }
                webView != null
            }
            compose.waitUntil(20_000) {
                evaluate(requireNotNull(webView), "Boolean(window.hoshiManga && document.readyState === 'complete' && document.images.length && Array.from(document.images).every(function(image) { return image.complete && image.naturalWidth > 0; }))") == "true"
            }
            // Opening the real sheet first proves statistics loaded and tracking started;
            // closing it also exercises the return to a completely static reader.
            openStatistics()
            compose.onNodeWithContentDescription(compose.activity.getString(R.string.reader_statistics_pause)).assertExists()
            val beforeHiddenSeconds = displayedReadingSeconds()
            compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
            compose.waitForIdle()
            compose.mainClock.advanceTimeBy(800)
            SystemClock.sleep(300)
            compose.waitForIdle()

            compose.mainClock.autoAdvance = false
            compose.runOnUiThread { decor.viewTreeObserver.addOnDrawListener(listener) }
            val before = draws.get()
            advanceAccountingTicks(3)
            assertEquals("A hidden statistics timer must not redraw the untouched reader", before, draws.get())
            compose.runOnUiThread { decor.viewTreeObserver.removeOnDrawListener(listener) }
            compose.mainClock.autoAdvance = true

            openStatistics()
            val caughtUp = displayedReadingSeconds()
            assertTrue("Opening statistics must include the time spent reading with its sheet hidden", caughtUp >= beforeHiddenSeconds + 3)
            compose.mainClock.autoAdvance = false
            advanceAccountingTicks(2)
            compose.mainClock.autoAdvance = true
            val later = displayedReadingSeconds()
            assertTrue("Visible statistics must continue counting", later >= caughtUp + 2)

            compose.runOnUiThread { visible.value = false }
            compose.waitForIdle()
            repository.awaitPendingStatisticsSaves(root)
            val savedSeconds = repository.loadStatistics(root).sumOf { it.readingTime }
            assertTrue("Leaving the reader must persist time counted while the sheet was hidden", savedSeconds >= later)
            assertTrue("UI refreshes must not count elapsed time twice", savedSeconds <= (SystemClock.elapsedRealtime() - activeSince) / 1000.0 + 1.0)
        } finally {
            compose.mainClock.autoAdvance = true
            compose.runOnUiThread {
                if (decor.viewTreeObserver.isAlive) decor.viewTreeObserver.removeOnDrawListener(listener)
                visible.value = false
            }
            compose.waitForIdle()
            repository.awaitPendingStatisticsSaves(root)
        }
    }

    private fun openStatistics() {
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.manga_reader_more_options)).performClick()
        compose.onNodeWithText(compose.activity.getString(R.string.reader_statistics)).performClick()
        compose.waitForIdle()
    }

    private fun advanceAccountingTicks(count: Int) {
        repeat(count) {
            // LaunchedEffect delays use Compose's test clock, but the production tracker
            // measures wall time. Advancing both makes this fail on the original redraw bug.
            SystemClock.sleep(1_050)
            compose.mainClock.advanceTimeBy(1_050)
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        }
    }

    private fun displayedReadingSeconds(): Int = compose.onAllNodes(
        SemanticsMatcher("reading duration") { node ->
            node.config.getOrNull(SemanticsProperties.Text)?.any {
                it.text.matches(Regex("\\d+[hms](?: \\d+[hms]){0,2}"))
            } == true
        },
    ).fetchSemanticsNodes().flatMap { node ->
        node.config[SemanticsProperties.Text].map { it.text }
    }.mapNotNull { text ->
        Regex("(\\d+)([hms])").findAll(text).takeIf { it.any() }?.sumOf { match ->
            match.groupValues[1].toInt() * when (match.groupValues[2]) { "h" -> 3600; "m" -> 60; else -> 1 }
        }
    }.maxOrNull() ?: error("The statistics sheet did not expose its reading time")

    private fun findWebView(view: View): WebView? {
        if (view is WebView) return view
        if (view is ViewGroup) for (index in 0 until view.childCount) findWebView(view.getChildAt(index))?.let { return it }
        return null
    }

    private fun evaluate(view: WebView, script: String): String {
        val result = AtomicReference("")
        val done = CountDownLatch(1)
        compose.runOnUiThread { view.evaluateJavascript(script) { result.set(it); done.countDown() } }
        assertTrue("Manga readiness callback timed out", done.await(5, TimeUnit.SECONDS))
        return result.get()
    }
}
