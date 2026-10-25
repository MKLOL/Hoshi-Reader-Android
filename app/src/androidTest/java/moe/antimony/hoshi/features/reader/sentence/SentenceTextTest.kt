package moe.antimony.hoshi.features.reader.sentence

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import moe.antimony.hoshi.features.reader.ReaderSelectionData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SentenceTextTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun eitherSideOfEachPlainGlyphLooksUpThatCharacter() {
        val text = "あいうえお"
        val taps = mutableListOf<ReaderSelectionData>()
        showSentence(text, taps = taps)
        val node = compose.onNodeWithTag("sentence-text")
        val layout = node.textLayout()

        text.indices.forEach { offset ->
            val glyph = layout.getBoundingBox(offset)
            listOf(0.25f, 0.75f).forEach { fraction ->
                node.performTouchInput {
                    click(Offset(glyph.left + glyph.width * fraction, glyph.center.y))
                }
                compose.runOnIdle {
                    assertEquals("Glyph $offset at $fraction", offset, taps.last().sentenceOffset)
                    assertEquals(text.substring(offset), taps.last().text)
                }
            }
        }
    }

    @Test
    fun rubyMovesAsOneWordAndEitherSideOfEachBaseGlyphLooksUpThatCharacter() {
        val prefix = "あああああ"
        val text = prefix + "大丈夫ですよ。"
        val taps = mutableListOf<ReaderSelectionData>()
        showSentence(text, listOf(RubyAnnotation(prefix.length, 3, "だいじょうぶ")), width = 180, taps = taps)

        val paragraph = compose.onNodeWithTag("sentence-text").textLayout()
        val placeholder = paragraph.placeholderRects.single()
        assertNotNull("Complete ruby must have one visible placeholder", placeholder)
        val previousLine = paragraph.getLineForOffset(prefix.lastIndex)
        val wordLine = paragraph.getLineForOffset(prefix.length)
        assertTrue("The complete word moves to a new line", wordLine > previousLine)
        (prefix.length until prefix.length + 3).forEach { offset ->
            assertEquals("Every character in the ruby base stays on the same line", wordLine, paragraph.getLineForOffset(offset))
        }
        assertEquals("Original sentence text is preserved", text, paragraph.layoutInput.text.text)
        assertRubyGlyphTaps(prefix.length, 3, taps)
    }

    @Test
    fun oversizedRubyFitsNarrowPaneWithoutLosingCharacterHitTargets() {
        val text = "大丈夫大丈夫大丈夫"
        val taps = mutableListOf<ReaderSelectionData>()
        showSentence(text, listOf(RubyAnnotation(0, text.length, "だいじょうぶだいじょうぶだいじょうぶ")), width = 64, taps = taps)
        val paragraph = compose.onNodeWithTag("sentence-text").textLayout()
        val placeholder = paragraph.placeholderRects.single()
        assertNotNull("A long grouped word remains visible", placeholder)
        assertTrue("A long grouped word fits its pane", placeholder!!.right <= paragraph.size.width + 1f)
        val ruby = compose.onNodeWithTag("sentence-ruby-0", useUnmergedTree = true)
        val original = ruby.textLayout()
        val bounds = ruby.fetchSemanticsNode().config[SentenceRubyBaseBounds]
        assertTrue("The complete base is reduced as one unit", bounds.width < original.size.width)
        assertRubyGlyphTaps(0, text.length, taps)
    }

    @Test
    fun partiallyAnnotatedCompoundStaysWholeWithScaledFontsAndCharacterHighlights() {
        val prefix = "あああああ"
        val text = prefix + "大丈夫ですよ。"
        val taps = mutableListOf<ReaderSelectionData>()
        showSentence(
            text, listOf(RubyAnnotation(prefix.length, 1, "だい")), width = 96, fontScale = 2f,
            highlight = prefix.length + 1..prefix.length + 1, taps = taps,
        )
        val paragraph = compose.onNodeWithTag("sentence-text").textLayout()
        assertEquals("Partial annotation keeps one compound atom", 1, paragraph.placeholderRects.size)
        val line = paragraph.getLineForOffset(prefix.length)
        (prefix.length until prefix.length + 3).forEach { assertEquals(line, paragraph.getLineForOffset(it)) }
        assertEquals(text, paragraph.layoutInput.text.text)
        val base = compose.onNodeWithTag("sentence-ruby-0", useUnmergedTree = true).textLayout()
        val highlights = base.layoutInput.text.spanStyles.filter { it.item.background != Color.Unspecified }
        assertEquals(listOf(1 to 2), highlights.map { it.start to it.end })
        assertRubyGlyphTaps(prefix.length, 3, taps)
    }

    @Test
    fun splitPanesHaveIndependentScrollingAndRearrangeWithoutResettingIt() {
        var layout by mutableStateOf(SentenceReaderLayout.SideBySide)
        var expanded by mutableStateOf(true)
        var sentenceScroll = 0
        var translationScroll = 0
        compose.setContent {
            MaterialTheme {
                val sentenceState = rememberScrollState()
                val translationState = rememberScrollState()
                sentenceScroll = sentenceState.value
                translationScroll = translationState.value
                SentenceReaderPanes(
                    layout = layout,
                    translationExpanded = expanded,
                    modifier = Modifier.width(300.dp).height(240.dp),
                    sentenceContent = { modifier ->
                        Box(modifier.testTag("sentence-pane").verticalScroll(sentenceState)) {
                            Text("あいうえお\n".repeat(100))
                        }
                    },
                    translationContent = { modifier ->
                        Box(modifier.testTag("translation-pane").verticalScroll(translationState)) {
                            Text("Translation paragraph\n".repeat(if (expanded) 100 else 1))
                        }
                    },
                )
            }
        }
        val sentence = compose.onNodeWithTag("sentence-pane")
        val translation = compose.onNodeWithTag("translation-pane")
        var first = sentence.fetchSemanticsNode().boundsInRoot
        var second = translation.fetchSemanticsNode().boundsInRoot
        assertTrue("Expanded side layout puts translation to the right", second.left >= first.right - 1f)
        assertEquals(first.top, second.top, 1f)
        assertEquals(first.height, second.height, 1f)

        sentence.performTouchInput { swipeUp() }
        compose.runOnIdle {
            assertTrue("Sentence overflow is reachable", sentenceScroll > 0)
            assertEquals("Sentence scrolling does not move translation", 0, translationScroll)
        }
        translation.performTouchInput { swipeUp() }
        compose.runOnIdle {
            assertTrue("Translation overflow is reachable", translationScroll > 0)
            layout = SentenceReaderLayout.Stacked
        }
        compose.waitForIdle()
        first = sentence.fetchSemanticsNode().boundsInRoot
        second = translation.fetchSemanticsNode().boundsInRoot
        assertTrue("Stacked layout puts translation below the sentence", second.top >= first.bottom - 1f)
        assertEquals(first.left, second.left, 1f)
        assertEquals(first.width, second.width, 1f)
        compose.runOnIdle {
            assertTrue("Changing pane direction keeps sentence scroll", sentenceScroll > 0)
            assertTrue("Changing pane direction keeps translation scroll", translationScroll > 0)
            layout = SentenceReaderLayout.SideBySide
            expanded = false
        }
        compose.waitForIdle()
        first = sentence.fetchSemanticsNode().boundsInRoot
        second = translation.fetchSemanticsNode().boundsInRoot
        assertTrue("A collapsed translation leaves the sentence full width", second.top >= first.bottom - 1f)
        assertEquals(first.width, second.width, 1f)
    }

    private fun showSentence(
        text: String,
        ruby: List<RubyAnnotation> = emptyList(),
        width: Int = 240,
        fontScale: Float = 1f,
        highlight: IntRange? = null,
        taps: MutableList<ReaderSelectionData>,
    ) {
        compose.setContent {
            MaterialTheme {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                    Box(Modifier.width(width.dp)) {
                        SentenceText(
                            sentence = ReaderSentence(0, 11, text.length, text, 0, ruby),
                            highlight = highlight,
                            foreground = Color.Black,
                            fontFamily = FontFamily.Serif,
                            fontSizeSp = 32f,
                            lineHeightMultiplier = 1.5f,
                            showFurigana = true,
                            screenOrigin = { Offset.Zero },
                            onTapOutside = {},
                            onWordTap = { taps += it },
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun assertRubyGlyphTaps(start: Int, length: Int, taps: List<ReaderSelectionData>) {
        val node = compose.onNodeWithTag("sentence-ruby-0", useUnmergedTree = true)
        val baseLayout = node.textLayout()
        val baseBounds = node.fetchSemanticsNode().config[SentenceRubyBaseBounds]
        val scale = baseBounds.width / baseLayout.size.width
        (0 until length).forEach { localOffset ->
            val glyph = baseLayout.getBoundingBox(localOffset)
            listOf(0.25f, 0.75f).forEach { fraction ->
                node.performTouchInput {
                    click(baseBounds.topLeft + Offset(glyph.left + glyph.width * fraction, glyph.center.y) * scale)
                }
                compose.runOnIdle {
                    assertEquals("Ruby glyph $localOffset at $fraction", start + localOffset, taps.last().sentenceOffset)
                }
            }
        }
    }

    private fun SemanticsNodeInteraction.textLayout(): TextLayoutResult {
        val layouts = mutableListOf<TextLayoutResult>()
        performSemanticsAction(SemanticsActions.GetTextLayoutResult) { action ->
            assertTrue("The visible text exposes its actual layout", action(layouts))
        }
        return layouts.single()
    }
}
