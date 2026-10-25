package moe.antimony.hoshi.features.reader.sentence

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.getTextLayoutResult
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import moe.antimony.hoshi.features.reader.ReaderSelectionData
import moe.antimony.hoshi.features.reader.ReaderSelectionRect
import moe.antimony.hoshi.ui.theme.LocalPlatformDensity

/**
 * Native paragraph layout with each complete ruby base in one inline placeholder. Alternate
 * text is the original base, so accessibility, sentence offsets and surrounding text stay intact.
 * The reading participates in measurement instead of being painted over a word that can wrap.
 */
@Composable
internal fun SentenceText(
    sentence: ReaderSentence,
    highlight: IntRange?,
    foreground: Color,
    fontFamily: FontFamily,
    fontSizeSp: Float,
    lineHeightMultiplier: Float,
    showFurigana: Boolean,
    screenOrigin: () -> Offset,
    onTapOutside: () -> Unit,
    onWordTap: (ReaderSelectionData) -> Unit,
) {
    val density = LocalDensity.current
    val selectionDensity by rememberUpdatedState(LocalPlatformDensity.current ?: density)
    val currentScreenOrigin by rememberUpdatedState(screenOrigin)
    val currentOnTapOutside by rememberUpdatedState(onTapOutside)
    val currentOnWordTap by rememberUpdatedState(onWordTap)
    val textMeasurer = rememberTextMeasurer()
    val groups = remember(sentence.text, sentence.ruby) { sentenceRubyGroups(sentence.text, sentence.ruby) }
    var layout by remember(sentence) { mutableStateOf<TextLayoutResult?>(null) }
    var origin by remember { mutableStateOf(Offset.Zero) }
    val plainStyle = TextStyle(
        color = foreground,
        fontFamily = fontFamily,
        fontSize = fontSizeSp.sp,
        fontWeight = FontWeight.Normal,
        textAlign = TextAlign.Start,
    )
    val readingStyle = plainStyle.copy(fontSize = (fontSizeSp * 0.5f).sp)

    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val availableWidth = with(density) { maxWidth.toPx() }
        val measuredGroups = remember(groups, plainStyle, readingStyle, highlight, showFurigana, availableWidth, textMeasurer, density) {
            groups.map { group ->
                val readings = if (showFurigana) group.annotations.map { annotation ->
                    annotation to textMeasurer.measure(annotation.reading, readingStyle, softWrap = false, maxLines = 1)
                } else emptyList()
                val base = buildAnnotatedString {
                    append(group.base)
                    // A long child reading reserves space over its own base; it must not drift
                    // over an unannotated neighbor just because both belong to one compound.
                    readings.forEach { (annotation, reading) ->
                        val end = annotation.start + annotation.length
                        val child = group.base.substring(annotation.start, end)
                        val childWidth = textMeasurer.measure(child, plainStyle, softWrap = false, maxLines = 1).size.width
                        val extra = (reading.size.width - childWidth).coerceAtLeast(0).toFloat()
                        if (extra > 0f) addStyle(
                            SpanStyle(letterSpacing = with(density) { (extra / child.codePointCount(0, child.length)).toSp() }),
                            annotation.start, end,
                        )
                    }
                    highlight?.let { range ->
                        val start = maxOf(group.start, range.first)
                        val end = minOf(group.end, range.last + 1)
                        if (start < end) addStyle(
                            SpanStyle(background = foreground.copy(alpha = 0.18f)),
                            start - group.start,
                            end - group.start,
                        )
                    }
                }
                val baseLayout = textMeasurer.measure(base, plainStyle, softWrap = false, maxLines = 1)
                val measuredReadings = readings.map { (annotation, reading) ->
                    val bounds = (annotation.start until annotation.start + annotation.length).map { baseLayout.getBoundingBox(it) }
                    val center = (bounds.minOf { it.left } + bounds.maxOf { it.right }) / 2f
                    MeasuredSentenceReading(reading, center - reading.size.width / 2f)
                }
                val left = minOf(0f, measuredReadings.minOfOrNull { it.left } ?: 0f)
                val right = maxOf(baseLayout.size.width.toFloat(), measuredReadings.maxOfOrNull { it.left + it.layout.size.width } ?: 0f)
                val placement = sentenceRubyPlacement(
                    right - left,
                    baseLayout.size.height.toFloat(),
                    0f,
                    measuredReadings.maxOfOrNull { it.layout.size.height.toFloat() } ?: 0f,
                    availableWidth,
                )
                MeasuredSentenceRuby(
                    group,
                    baseLayout,
                    measuredReadings,
                    placement.copy(baseLeft = placement.baseLeft - left * placement.scale),
                )
            }
        }
        // The pointer handler lives for the sentence, but fonts, pane width and visibility change.
        val currentGroups by rememberUpdatedState(measuredGroups)
        val annotated = remember(sentence.text, groups, highlight, foreground) {
            buildAnnotatedString {
                var position = 0
                fun appendPlain(end: Int) {
                    append(sentence.text.substring(position, end))
                    highlight?.let { range ->
                        val start = maxOf(position, range.first)
                        val highlightEnd = minOf(end, range.last + 1)
                        if (start < highlightEnd) addStyle(
                            SpanStyle(background = foreground.copy(alpha = 0.18f)), start, highlightEnd,
                        )
                    }
                    position = end
                }
                groups.forEachIndexed { index, group ->
                    appendPlain(group.start)
                    appendInlineContent("ruby-$index", group.base)
                    position = group.end
                }
                appendPlain(sentence.text.length)
            }
        }
        val inlineContent = measuredGroups.mapIndexed { index, measured ->
            "ruby-$index" to InlineTextContent(
                Placeholder(
                    width = with(density) { measured.placement.width.toSp() },
                    height = with(density) { measured.placement.height.toSp() },
                    placeholderVerticalAlign = PlaceholderVerticalAlign.TextBottom,
                ),
            ) {
                Canvas(Modifier.fillMaxSize().testTag("sentence-ruby-$index").semantics {
                    getTextLayoutResult { result -> result.add(measured.base); true }
                    this[SentenceRubyBaseBounds] = Rect(
                        measured.placement.baseLeft,
                        measured.placement.baseTop,
                        measured.placement.baseLeft + measured.base.size.width * measured.placement.scale,
                        measured.placement.baseTop + measured.base.size.height * measured.placement.scale,
                    )
                }) {
                    val placement = measured.placement
                    withTransform({
                        translate(placement.baseLeft, placement.baseTop)
                        scale(placement.scale, placement.scale, pivot = Offset.Zero)
                    }) { drawText(measured.base) }
                    measured.readings.forEach { reading ->
                        withTransform({
                            translate(placement.baseLeft + reading.left * placement.scale, 0f)
                            scale(placement.scale, placement.scale, pivot = Offset.Zero)
                        }) { drawText(reading.layout) }
                    }
                }
            }
        }.toMap()
        val lineHeight = with(density) {
            maxOf(
                fontSizeSp.sp.toPx() * lineHeightMultiplier,
                measuredGroups.maxOfOrNull { it.placement.height } ?: 0f,
            ).toSp()
        }
        Text(
            text = annotated,
            inlineContent = inlineContent,
            style = plainStyle.copy(lineHeight = lineHeight),
            onTextLayout = { layout = it },
            modifier = Modifier
                .fillMaxWidth()
                .testTag("sentence-text")
                .onGloballyPositioned { origin = it.positionInRoot() }
                .pointerInput(sentence) {
                    detectTapGestures { tap ->
                        val current = layout
                        if (current == null || sentence.text.isEmpty() || tap.y !in 0f..current.size.height.toFloat()) {
                            currentOnTapOutside()
                            return@detectTapGestures
                        }
                        val inlineIndex = current.placeholderRects.indexOfFirst { it?.contains(tap) == true }
                        val measured = currentGroups.getOrNull(inlineIndex)
                        val hit: Pair<Int, Rect> = if (measured != null) {
                            val placeholder = current.placeholderRects[inlineIndex]!!
                            val placement = measured.placement
                            val baseOrigin = placeholder.topLeft + Offset(placement.baseLeft, placement.baseTop)
                            val local = (tap - baseOrigin) / placement.scale
                            val offset = sentenceRubyHitOffset(
                                measured.group,
                                characterAt(measured.base, local.copy(y = measured.base.size.height / 2f)),
                            )
                            val bounds = measured.base.getBoundingBox(offset - measured.group.start)
                            offset to Rect(
                                baseOrigin + bounds.topLeft * placement.scale,
                                baseOrigin + bounds.bottomRight * placement.scale,
                            )
                        } else {
                            val line = current.getLineForVerticalPosition(tap.y)
                            if (tap.x < current.getLineLeft(line) || tap.x > current.getLineRight(line)) {
                                currentOnTapOutside()
                                return@detectTapGestures
                            }
                            val offset = characterAt(current, tap)
                            offset to current.getBoundingBox(offset)
                        }
                        val query = sentenceLookupQuery(sentence.text, hit.first)
                        if (query == null || query.text.isBlank()) {
                            currentOnTapOutside()
                            return@detectTapGestures
                        }
                        val box = hit.second
                        val shift = origin - currentScreenOrigin()
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
                                sentence = sentence.text,
                                rect = rect,
                                normalizedOffset = sentence.start + EpubSentenceSegmenter.matchableCount(sentence.text, query.startOffset),
                                sentenceOffset = query.startOffset,
                            ),
                        )
                    }
                },
        )
    }
}

private data class MeasuredSentenceRuby(
    val group: SentenceRubyGroup,
    val base: TextLayoutResult,
    val readings: List<MeasuredSentenceReading>,
    val placement: SentenceRubyPlacement,
)

private data class MeasuredSentenceReading(val layout: TextLayoutResult, val left: Float)

/** Relative visual base bounds, also useful to inspect a shrunken word in accessibility tests. */
internal val SentenceRubyBaseBounds = SemanticsPropertyKey<Rect>("SentenceRubyBaseBounds")

/** Text hit testing returns a caret; use the glyph under the finger on either side of it. */
private fun characterAt(layout: TextLayoutResult, point: Offset): Int {
    val text = layout.layoutInput.text.text
    return sentenceCharacterHit(text, layout.getOffsetForPosition(point), point.x, point.y) { start, end ->
        var bounds = layout.getBoundingBox(start)
        for (offset in start + 1 until end) {
            val next = layout.getBoundingBox(offset)
            bounds = Rect(
                minOf(bounds.left, next.left), minOf(bounds.top, next.top),
                maxOf(bounds.right, next.right), maxOf(bounds.bottom, next.bottom),
            )
        }
        SentenceGlyphBounds(bounds.left, bounds.top, bounds.right, bounds.bottom)
    }
}
