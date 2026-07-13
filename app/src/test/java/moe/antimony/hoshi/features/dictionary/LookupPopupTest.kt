package moe.antimony.hoshi.features.dictionary

import de.manhhao.hoshi.GlossaryEntry
import de.manhhao.hoshi.LookupResult
import de.manhhao.hoshi.TermResult
import moe.antimony.hoshi.features.reader.ReaderSelectionData
import moe.antimony.hoshi.features.reader.ReaderSelectionRect
import moe.antimony.hoshi.features.reader.ReaderLookupPopupFramePayload
import moe.antimony.hoshi.features.reader.ReaderLookupPopupRootHighlightPayload
import moe.antimony.hoshi.features.reader.ReaderLookupPopupStackPayload
import moe.antimony.hoshi.features.reader.ReaderLookupPopupViewport
import moe.antimony.hoshi.features.reader.readerLookupPopupIframeUrl
import moe.antimony.hoshi.features.reader.readerLookupPopupTouchBlocksReaderGesture
import moe.antimony.hoshi.features.audio.AudioSettings
import android.view.MotionEvent
import android.view.View
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class LookupPopupTest {
    @Test
    fun verticalLayoutChoosesLargerSideLikeIosPopupLayout() {
        val layout = LookupPopupLayout(
            selectionRect = ReaderSelectionRect(x = 100.0, y = 200.0, width = 20.0, height = 30.0),
            screenWidth = 400.0,
            screenHeight = 800.0,
            maxWidth = 320.0,
            maxHeight = 250.0,
            isVertical = true,
        )

        val result = layout.calculate()

        assertEquals(270.0, result.width, 0.0)
        assertEquals(250.0, result.height, 0.0)
        assertEquals(259.0, result.centerX, 0.0)
        assertEquals(325.0, result.centerY, 0.0)
    }

    @Test
    fun verticalLayoutPrefersRightSideWhenItCanFitPopupLikeIosPopupLayout() {
        val layout = LookupPopupLayout(
            selectionRect = ReaderSelectionRect(x = 450.0, y = 200.0, width = 20.0, height = 30.0),
            screenWidth = 800.0,
            screenHeight = 800.0,
            maxWidth = 320.0,
            maxHeight = 250.0,
            isVertical = true,
        )

        val result = layout.calculate()

        assertEquals(320.0, result.width, 0.0)
        assertEquals(634.0, result.centerX, 0.0)
    }

    @Test
    fun horizontalLayoutAppearsBelowSelectionWhenThereIsRoom() {
        val layout = LookupPopupLayout(
            selectionRect = ReaderSelectionRect(x = 100.0, y = 100.0, width = 20.0, height = 30.0),
            screenWidth = 400.0,
            screenHeight = 800.0,
            maxWidth = 320.0,
            maxHeight = 250.0,
            isVertical = false,
        )

        val result = layout.calculate()

        assertEquals(320.0, result.width, 0.0)
        assertEquals(250.0, result.height, 0.0)
        assertEquals(234.0, result.centerX, 0.0)
        assertEquals(259.0, result.centerY, 0.0)
    }

    // Real Yotsuba bubble, measured on-device via tools/manga_debug.py:
    // mokuro's OCR box has a FIXED height and getBoundingClientRect() reports
    // bottom≈641, but the revealed OCR text overflows (overflow:visible) so the
    // trailing ！？ renders down to bottom≈681. The manga page must position the
    // popup off the TRUE painted extent (a Range over the box contents), not the
    // box's border-box, or the popup clears the box yet still covers the ！？.
    private fun rectsOverlap(
        a: LookupPopupFrame,
        left: Double,
        top: Double,
        right: Double,
        bottom: Double,
    ): Boolean {
        val aLeft = a.centerX - a.width / 2
        val aRight = a.centerX + a.width / 2
        val aTop = a.centerY - a.height / 2
        val aBottom = a.centerY + a.height / 2
        return aLeft < right && aRight > left && aTop < bottom && aBottom > top
    }

    @Test
    fun mangaPopupPositionedOffTheBoxRectWouldCoverOverflowingText() {
        // Regression witness: positioning off the box's border-box (the OLD bug)
        // leaves the popup inside the [641,681] overflow band → covers ！？.
        val fromBox = LookupPopupLayout(
            selectionRect = ReaderSelectionRect(x = 306.0, y = 531.0, width = 51.0, height = 110.0),
            screenWidth = 412.0,
            screenHeight = 915.0,
            maxWidth = 320.0,
            maxHeight = 250.0,
            isVertical = false,
        ).calculate()
        // The true text extent is x∈[300,357], y∈[505,681]. The box-rect popup overlaps it.
        assertTrue(
            "Positioning off the box rect must overlap the overflowing text (documents the bug)",
            rectsOverlap(fromBox, left = 300.0, top = 505.0, right = 357.0, bottom = 681.0),
        )
    }

    @Test
    fun mangaPopupPositionedOffTheTextExtentClearsOverflowingPunctuation() {
        // The fix: the manga page substitutes the Range extent (x∈[300,357],
        // y∈[505,681]) as the selection rect. The popup must then clear it.
        val fromExtent = LookupPopupLayout(
            selectionRect = ReaderSelectionRect(x = 300.0, y = 505.0, width = 57.0, height = 176.0),
            screenWidth = 412.0,
            screenHeight = 915.0,
            maxWidth = 320.0,
            maxHeight = 250.0,
            isVertical = false,
        ).calculate()
        assertFalse(
            "Popup must not overlap the full rendered text extent (incl. overflow ！？)",
            rectsOverlap(fromExtent, left = 300.0, top = 505.0, right = 357.0, bottom = 681.0),
        )
    }

    @Test
    fun mangaPopupNeverOverlapsTheSelectionExtentAcrossPositionsAndZoom() {
        // Invariant sweep: for a vertical bubble extent at every vertical position
        // and a range of heights (simulating pinch-zoom growth), the popup must
        // never overlap the extent it was told to avoid.
        val screenW = 412.0
        val screenH = 915.0
        val widths = listOf(30.0, 57.0, 120.0)
        val heights = listOf(40.0, 110.0, 176.0, 340.0, 620.0)
        for (w in widths) {
            for (h in heights) {
                var y = -200.0
                while (y <= screenH) {
                    val x = 300.0
                    val frame = LookupPopupLayout(
                        selectionRect = ReaderSelectionRect(x = x, y = y, width = w, height = h),
                        screenWidth = screenW,
                        screenHeight = screenH,
                        maxWidth = 320.0,
                        maxHeight = 250.0,
                        isVertical = false,
                    ).calculate()
                    // Allow the sub-pixel clamp touch (<=1px) the layout can produce,
                    // but never a real cover.
                    val aTop = frame.centerY - frame.height / 2
                    val aBottom = frame.centerY + frame.height / 2
                    val vertOverlap = minOf(aBottom, y + h) - maxOf(aTop, y)
                    assertTrue(
                        "popup covers extent w=$w h=$h y=$y by $vertOverlap px",
                        vertOverlap <= 1.0 || (y + h) <= 0.0 || y >= screenH,
                    )
                    y += 25.0
                }
            }
        }
    }

    @Test
    fun popupKeepsAUsableHeightWhenTheBubbleFillsTheViewportUnderZoom() {
        // Heavy pinch-zoom: the bubble extent spans past both top and bottom, so
        // space above/below both go negative. Without a floor the height went
        // negative and dp→px coerced it to a 1px, invisible popup.
        val frame = LookupPopupLayout(
            selectionRect = ReaderSelectionRect(x = 50.0, y = -100.0, width = 70.0, height = 1200.0),
            screenWidth = 412.0,
            screenHeight = 915.0,
            maxWidth = 320.0,
            maxHeight = 250.0,
            isVertical = false,
        ).calculate()
        assertTrue("popup collapsed to a sliver (height=${frame.height})", frame.height >= 120.0)
    }

    @Test
    fun popupKeepsAUsableHeightForATallVerticalBubbleAtNormalZoom() {
        // Reachable WITHOUT zoom: a tall vertical speech bubble (the common manga
        // case) leaves almost no room above/below, which used to yield a 1px popup.
        val frame = LookupPopupLayout(
            selectionRect = ReaderSelectionRect(x = 180.0, y = 5.0, width = 70.0, height = 900.0),
            screenWidth = 412.0,
            screenHeight = 915.0,
            maxWidth = 320.0,
            maxHeight = 250.0,
            isVertical = false,
        ).calculate()
        assertTrue("popup collapsed to a sliver (height=${frame.height})", frame.height >= 120.0)
    }

    @Test
    fun popupHeightFloorNeverExceedsMaxHeight() {
        val frame = LookupPopupLayout(
            selectionRect = ReaderSelectionRect(x = 180.0, y = 5.0, width = 70.0, height = 900.0),
            screenWidth = 412.0,
            screenHeight = 915.0,
            maxWidth = 320.0,
            maxHeight = 80.0, // user configured a short popup
            isVertical = false,
        ).calculate()
        assertTrue("floor must not exceed maxHeight (height=${frame.height})", frame.height <= 80.0)
    }

    @Test
    fun fullWidthLayoutMatchesIosPopupLayout() {
        val layout = LookupPopupLayout(
            selectionRect = ReaderSelectionRect(x = 100.0, y = 100.0, width = 20.0, height = 30.0),
            screenWidth = 400.0,
            screenHeight = 800.0,
            maxWidth = 320.0,
            maxHeight = 250.0,
            isVertical = true,
            isFullWidth = true,
        )

        val result = layout.calculate()

        assertEquals(388.0, result.width, 0.0)
        assertEquals(250.0, result.height, 0.0)
        assertEquals(200.0, result.centerX, 0.0)
        assertEquals(669.0, result.centerY, 0.0)
    }

    @Test
    fun verticalLayoutUsesIosClampWhenPopupIsTallerThanAvailableHeight() {
        val layout = LookupPopupLayout(
            selectionRect = ReaderSelectionRect(x = 100.0, y = 0.0, width = 20.0, height = 30.0),
            screenWidth = 400.0,
            screenHeight = 244.62222290039062,
            maxWidth = 320.0,
            maxHeight = 250.0,
            isVertical = true,
        )

        val result = layout.calculate()

        assertEquals(131.0, result.centerY, 0.0)
    }

    @Test
    fun rootSelectionOffsetMovesOnlyRootPopupAnchor() {
        val popups = listOf("root", "child").mapIndexed { index, id ->
            LookupPopupItem(
                id = id,
                state = LookupPopupState(
                    selection = ReaderSelectionData(
                        text = id,
                        sentence = id,
                        rect = ReaderSelectionRect(
                            x = 10.0 + index,
                            y = 20.0 + index,
                            width = 30.0,
                            height = 40.0,
                        ),
                        normalizedOffset = null,
                    ),
                    results = emptyList(),
                ),
            )
        }

        val shifted = popups.withRootSelectionOffset(offsetX = 5.0, offsetY = 7.0)

        assertEquals(15.0, shifted[0].state.selection.rect.x, 0.0)
        assertEquals(27.0, shifted[0].state.selection.rect.y, 0.0)
        assertEquals(11.0, shifted[1].state.selection.rect.x, 0.0)
        assertEquals(21.0, shifted[1].state.selection.rect.y, 0.0)
    }

    @Test
    fun readerIframeFramePayloadUsesWebViewViewportCoordinatesWithoutRootPaddingOffset() {
        val popup = LookupPopupItem(
            id = "root",
            state = LookupPopupState(
                selection = ReaderSelectionData(
                    text = "root",
                    sentence = "root",
                    rect = ReaderSelectionRect(x = 100.0, y = 100.0, width = 20.0, height = 30.0),
                    normalizedOffset = null,
                ),
                results = emptyList(),
                isVertical = false,
                width = 320,
                height = 250,
                popupActionBar = true,
            ),
        )

        val payload = ReaderLookupPopupFramePayload.fromPopup(
            popup = popup,
            popupIndex = 0,
            viewport = ReaderLookupPopupViewport(
                width = 500.0,
                height = 800.0,
            ),
            entriesCount = 3,
            backCount = 1,
            forwardCount = 2,
        )

        assertEquals("root", payload.id)
        assertEquals(100.0, payload.frame.left, 0.0)
        assertEquals(134.0, payload.frame.top, 0.0)
        assertEquals(320.0, payload.frame.width, 0.0)
        assertEquals(250.0, payload.frame.height, 0.0)
        assertEquals(171.0, payload.selectionOffsetY, 0.0)
        assertTrue(payload.popupActionBar)
        assertEquals(3, payload.entriesCount)
        assertEquals("https://hoshi.local/popup/iframe.html", payload.iframeUrl)
        assertEquals("https://hoshi.local/popup/iframe.html?v=123", readerLookupPopupIframeUrl(123))
    }

    @Test
    fun readerIframeFramePayloadSeedsFirstEntryForInitialPaint() {
        val popup = LookupPopupItem(
            id = "root",
            state = LookupPopupState(
                selection = ReaderSelectionData(
                    text = "root",
                    sentence = "root",
                    rect = ReaderSelectionRect(x = 100.0, y = 100.0, width = 20.0, height = 30.0),
                    normalizedOffset = null,
                ),
                results = listOf(
                    lookupResult(expression = "食べる", reading = "たべる", glossary = "to eat"),
                    lookupResult(expression = "読む", reading = "よむ", glossary = "to read"),
                ),
                isVertical = false,
                width = 320,
                height = 250,
            ),
        )

        val payload = ReaderLookupPopupFramePayload.fromPopup(
            popup = popup,
            popupIndex = 0,
            viewport = ReaderLookupPopupViewport(width = 500.0, height = 800.0),
        )

        assertTrue(payload.initialEntryJson?.contains(""""expression":"食べる"""") == true)
        assertFalse(payload.initialEntryJson?.contains(""""expression":"読む"""") == true)
    }

    @Test
    fun readerIframeFramePayloadCanOmitInitialEntryForFrameOnlyUpdates() {
        val popup = LookupPopupItem(
            id = "root",
            state = LookupPopupState(
                selection = ReaderSelectionData(
                    text = "root",
                    sentence = "root",
                    rect = ReaderSelectionRect(x = 100.0, y = 100.0, width = 20.0, height = 30.0),
                    normalizedOffset = null,
                ),
                results = listOf(
                    lookupResult(expression = "食べる", reading = "たべる", glossary = "to eat"),
                ),
                isVertical = false,
                width = 320,
                height = 250,
            ),
        )

        val payload = ReaderLookupPopupFramePayload.fromPopup(
            popup = popup,
            popupIndex = 0,
            viewport = ReaderLookupPopupViewport(width = 500.0, height = 800.0),
            includeInitialEntryJson = false,
        )

        assertEquals(1, payload.entriesCount)
        assertEquals(null, payload.initialEntryJson)
    }

    @Test
    fun readerIframeStackPayloadCarriesPendingRootHighlightGate() {
        val payload = ReaderLookupPopupStackPayload(
            popups = emptyList(),
            rootHighlight = ReaderLookupPopupRootHighlightPayload.fromReaderRects(
                popupId = "root",
                rects = null,
                darkMode = false,
                eInkMode = true,
                verticalWriting = true,
            ),
        )

        val rootHighlight = Json.parseToJsonElement(payload.toJson())
            .jsonObject
            .getValue("rootHighlight")
            .jsonObject

        assertEquals("root", rootHighlight.getValue("popupId").jsonPrimitive.content)
        assertTrue(rootHighlight.getValue("pending").jsonPrimitive.boolean)
        assertTrue(rootHighlight.getValue("eInkMode").jsonPrimitive.boolean)
        assertTrue(rootHighlight.getValue("verticalWriting").jsonPrimitive.boolean)
        assertEquals(0, rootHighlight.getValue("rects").jsonArray.size)
    }

    @Test
    fun readerIframeStackPayloadCarriesReadyRootHighlightRects() {
        val payload = ReaderLookupPopupStackPayload(
            popups = emptyList(),
            rootHighlight = ReaderLookupPopupRootHighlightPayload.fromReaderRects(
                popupId = "root",
                rects = listOf(
                    ReaderSelectionRect(x = 12.0, y = 24.0, width = 30.0, height = 16.0),
                ),
                darkMode = true,
                eInkMode = false,
                verticalWriting = false,
            ),
        )

        val rootHighlight = Json.parseToJsonElement(payload.toJson())
            .jsonObject
            .getValue("rootHighlight")
            .jsonObject
        val rect = rootHighlight.getValue("rects").jsonArray.first().jsonObject

        assertFalse(rootHighlight.getValue("pending").jsonPrimitive.boolean)
        assertTrue(rootHighlight.getValue("darkMode").jsonPrimitive.boolean)
        assertEquals(12.0, rect.getValue("x").jsonPrimitive.double, 0.0)
        assertEquals(24.0, rect.getValue("y").jsonPrimitive.double, 0.0)
        assertEquals(30.0, rect.getValue("width").jsonPrimitive.double, 0.0)
        assertEquals(16.0, rect.getValue("height").jsonPrimitive.double, 0.0)
    }

    @Test
    fun readerIframePopupFramesBlockReaderGesturesOnlyInsidePopupBounds() {
        val popup = LookupPopupItem(
            id = "root",
            state = LookupPopupState(
                selection = ReaderSelectionData(
                    text = "root",
                    sentence = "root",
                    rect = ReaderSelectionRect(x = 100.0, y = 100.0, width = 20.0, height = 30.0),
                    normalizedOffset = null,
                ),
                results = emptyList(),
                isVertical = false,
                width = 320,
                height = 250,
            ),
        )
        val payload = ReaderLookupPopupFramePayload.fromPopup(
            popup = popup,
            popupIndex = 0,
            viewport = ReaderLookupPopupViewport(width = 500.0, height = 800.0),
        )

        assertTrue(readerLookupPopupTouchBlocksReaderGesture(listOf(payload), x = 130.0, y = 150.0))
        assertFalse(readerLookupPopupTouchBlocksReaderGesture(listOf(payload), x = 40.0, y = 150.0))
        assertFalse(readerLookupPopupTouchBlocksReaderGesture(emptyList(), x = 130.0, y = 150.0))
    }

    @Test
    fun dismissPopupAtClosesTheSelectedPopupAndItsChildren() {
        val popups = listOf("root", "child", "grandchild").map { id ->
            LookupPopupItem(
                id = id,
                state = LookupPopupState(
                    selection = ReaderSelectionData(
                        text = id,
                        sentence = id,
                        rect = ReaderSelectionRect(x = 0.0, y = 0.0, width = 1.0, height = 1.0),
                        normalizedOffset = null,
                    ),
                    results = emptyList(),
                ),
            )
        }

        assertEquals(listOf("root"), dismissPopupAt(popups, 1).map { it.id })
        assertEquals(emptyList<String>(), dismissPopupAt(popups, 0).map { it.id })
    }

    @Test
    fun dismissingChildPopupSignalsParentSelectionClearLikeIos() {
        val popups = listOf("root", "child", "grandchild").map { id ->
            LookupPopupItem(
                id = id,
                state = LookupPopupState(
                    selection = ReaderSelectionData(
                        text = id,
                        sentence = id,
                        rect = ReaderSelectionRect(x = 0.0, y = 0.0, width = 1.0, height = 1.0),
                        normalizedOffset = null,
                    ),
                    results = emptyList(),
                ),
            )
        }

        val afterDismissingChild = dismissPopupAt(popups, 1)
        val afterDismissingGrandchild = dismissPopupAt(popups, 2)

        assertEquals(1, afterDismissingChild.single { it.id == "root" }.clearSelectionSignal)
        assertEquals(1, afterDismissingGrandchild.single { it.id == "child" }.clearSelectionSignal)
    }

    @Test
    fun scrollingRootOnlyPopupDoesNotRewritePopupState() {
        val popups = listOf("root").map { id ->
            LookupPopupItem(
                id = id,
                state = LookupPopupState(
                    selection = ReaderSelectionData(
                        text = id,
                        sentence = id,
                        rect = ReaderSelectionRect(x = 0.0, y = 0.0, width = 1.0, height = 1.0),
                        normalizedOffset = null,
                    ),
                    results = emptyList(),
                ),
            )
        }

        assertTrue(closeChildPopupsForScrolledParent(popups, 0) === popups)
    }

    @Test
    fun scrollingParentPopupClosesChildrenAndClearsSelection() {
        val popups = listOf("root", "child").map { id ->
            LookupPopupItem(
                id = id,
                state = LookupPopupState(
                    selection = ReaderSelectionData(
                        text = id,
                        sentence = id,
                        rect = ReaderSelectionRect(x = 0.0, y = 0.0, width = 1.0, height = 1.0),
                        normalizedOffset = null,
                    ),
                    results = emptyList(),
                ),
            )
        }

        val scrolled = closeChildPopupsForScrolledParent(popups, 0)

        assertEquals(listOf("root"), scrolled.map { it.id })
        assertEquals(1, scrolled.single().clearSelectionSignal)
    }

    @Test
    fun existingPopupsRetainSelectionAndHistorySignalsWhenThemeChanges() {
        val popups = listOf(
            LookupPopupItem(
                id = "root",
                clearSelectionSignal = 3,
                state = LookupPopupState(
                    selection = ReaderSelectionData(
                        text = "猫",
                        sentence = "猫です",
                        rect = ReaderSelectionRect(x = 0.0, y = 0.0, width = 1.0, height = 1.0),
                        normalizedOffset = 4,
                    ),
                    results = emptyList(),
                    darkMode = false,
                    eInkMode = false,
                    audioSettings = AudioSettings(enableAutoplay = false),
                ),
            ),
        )

        val themed = popups.withLookupPopupVisualOptions(
            darkMode = true,
            eInkMode = true,
            audioSettings = AudioSettings(enableAutoplay = true),
        )

        assertEquals("root", themed.single().id)
        assertEquals(3, themed.single().clearSelectionSignal)
        assertEquals("猫", themed.single().state.selection.text)
        assertTrue(themed.single().state.darkMode)
        assertTrue(themed.single().state.eInkMode)
        assertTrue(themed.single().state.audioSettings.enableAutoplay)
    }

    @Test
    fun popupSelectionOffsetTracksHistoryControls() {
        assertEquals(
            50.0,
            popupSelectionOffsetY(
                frameTopDp = 50.0,
                popupActionBar = false,
                backCount = 0,
                forwardCount = 0,
                hasSasayakiCue = false,
            ),
            0.0,
        )
        assertEquals(
            87.0,
            popupSelectionOffsetY(
                frameTopDp = 50.0,
                popupActionBar = false,
                backCount = 1,
                forwardCount = 0,
                hasSasayakiCue = false,
            ),
            0.0,
        )
    }

    @Test
    fun popupTouchStreamContinuesAfterMovingOutsideInitialHost() {
        val tracker = PopupTouchStreamTracker()

        assertTrue(tracker.shouldDispatch(MotionEvent.ACTION_DOWN, hitPopup = true))
        tracker.onDispatchResult(MotionEvent.ACTION_DOWN, handled = true)
        assertTrue(tracker.shouldDispatch(MotionEvent.ACTION_MOVE, hitPopup = false))
        assertTrue(tracker.shouldDispatch(MotionEvent.ACTION_UP, hitPopup = false))
        tracker.onDispatchResult(MotionEvent.ACTION_UP, handled = true)
        assertFalse(tracker.shouldDispatch(MotionEvent.ACTION_MOVE, hitPopup = false))
    }

    @Test
    fun overlayLeavesInputPathWhenThereAreNoPopups() {
        assertEquals(View.GONE, lookupPopupOverlayVisibility(hasPopups = false))
        assertEquals(View.VISIBLE, lookupPopupOverlayVisibility(hasPopups = true))
    }

    @Test
    fun stylusOutsidePopupDownConsumesStreamAndRequestsDismiss() {
        val shouldDismiss = shouldDismissForOutsideStylusTouch(
            actionMasked = MotionEvent.ACTION_DOWN,
            toolType = MotionEvent.TOOL_TYPE_STYLUS,
            hitPopup = false,
        )

        assertTrue(shouldDismiss)
    }

    @Test
    fun fingerOutsidePopupStillFallsThroughToReaderPath() {
        val shouldDismiss = shouldDismissForOutsideStylusTouch(
            actionMasked = MotionEvent.ACTION_DOWN,
            toolType = MotionEvent.TOOL_TYPE_FINGER,
            hitPopup = false,
        )

        assertFalse(shouldDismiss)
    }

    @Test
    fun stylusInsidePopupStillUsesPopupDispatchPath() {
        val shouldDismiss = shouldDismissForOutsideStylusTouch(
            actionMasked = MotionEvent.ACTION_DOWN,
            toolType = MotionEvent.TOOL_TYPE_STYLUS,
            hitPopup = true,
        )

        assertFalse(shouldDismiss)
    }

    @Test
    fun eraserOutsidePopupDownAlsoRequestsDismiss() {
        val shouldDismiss = shouldDismissForOutsideStylusTouch(
            actionMasked = MotionEvent.ACTION_DOWN,
            toolType = MotionEvent.TOOL_TYPE_ERASER,
            hitPopup = false,
        )

        assertTrue(shouldDismiss)
    }

    @Test
    fun stylusOutsidePopupMoveDoesNotStartDismissWithoutDown() {
        val shouldDismiss = shouldDismissForOutsideStylusTouch(
            actionMasked = MotionEvent.ACTION_MOVE,
            toolType = MotionEvent.TOOL_TYPE_STYLUS,
            hitPopup = false,
        )

        assertFalse(shouldDismiss)
    }

    // ---- geometry: untested placement branches -----------------------------

    private fun frame(
        rect: ReaderSelectionRect,
        screenW: Double,
        screenH: Double,
        maxW: Double = 320.0,
        maxH: Double = 250.0,
        vertical: Boolean = false,
        fullWidth: Boolean = false,
        topInset: Double = 0.0,
        bottomInset: Double = 0.0,
    ) = LookupPopupLayout(
        selectionRect = rect,
        screenWidth = screenW,
        screenHeight = screenH,
        maxWidth = maxW,
        maxHeight = maxH,
        isVertical = vertical,
        isFullWidth = fullWidth,
        topInset = topInset,
        bottomInset = bottomInset,
    ).calculate()

    @Test
    fun verticalPopupKeepsUsableWidthWhenSelectionFillsWidth() {
        // Symmetric twin of the height-floor fix: a vertical-writing selection that
        // fills the width must not collapse the popup to a 1px sliver.
        val f = frame(ReaderSelectionRect(10.0, 200.0, 390.0, 300.0), 412.0, 915.0, vertical = true)
        assertTrue("width collapsed to ${f.width}", f.width >= minOf(120.0, 320.0))
    }

    @Test
    fun fullWidthPopupTallerThanScreenStaysOnScreenTop() {
        val f = frame(ReaderSelectionRect(0.0, 0.0, 1.0, 1.0), 400.0, 200.0, maxH = 250.0, fullWidth = true)
        assertTrue("popup top hangs off screen (top=${f.centerY - f.height / 2})", f.centerY - f.height / 2 >= 0.0)
    }

    @Test
    fun horizontalLayoutAppearsAboveSelectionWhenBelowIsTight() {
        val f = frame(ReaderSelectionRect(100.0, 700.0, 20.0, 30.0), 400.0, 800.0)
        assertEquals(320.0, f.width, 0.0)
        assertEquals(250.0, f.height, 0.0)
        assertEquals(234.0, f.centerX, 0.0)
        assertEquals(571.0, f.centerY, 0.0)
    }

    @Test
    fun verticalLayoutPlacesPopupToLeftWhenRightCannotFit() {
        val f = frame(ReaderSelectionRect(380.0, 200.0, 20.0, 30.0), 400.0, 800.0, vertical = true)
        assertEquals(320.0, f.width, 0.0)
        assertEquals(216.0, f.centerX, 0.0)
        assertEquals(325.0, f.centerY, 0.0)
        assertEquals(250.0, f.height, 0.0)
    }

    @Test
    fun horizontalCenterXClampsToLeftBorderForOffscreenNegativeX() {
        val f = frame(ReaderSelectionRect(-50.0, 100.0, 20.0, 30.0), 400.0, 800.0)
        assertEquals(166.0, f.centerX, 0.0)
    }

    @Test
    fun horizontalWidthClampsToScreenOnNarrowDisplay() {
        val f = frame(ReaderSelectionRect(10.0, 100.0, 20.0, 30.0), 300.0, 800.0)
        assertEquals(288.0, f.width, 0.0)
        assertEquals(150.0, f.centerX, 0.0)
    }

    @Test
    fun verticalCenterYRespectsTopInset() {
        val withInset = frame(ReaderSelectionRect(100.0, 0.0, 20.0, 30.0), 400.0, 800.0, vertical = true, topInset = 100.0)
        assertEquals(231.0, withInset.centerY, 0.0)
        val noInset = frame(ReaderSelectionRect(100.0, 0.0, 20.0, 30.0), 400.0, 800.0, vertical = true)
        assertEquals(131.0, noInset.centerY, 0.0)
    }

    @Test
    fun horizontalBottomInsetFlipsPlacementFromBelowToAbove() {
        val below = frame(ReaderSelectionRect(100.0, 500.0, 20.0, 30.0), 400.0, 915.0)
        assertEquals(659.0, below.centerY, 0.0)
        val above = frame(ReaderSelectionRect(100.0, 500.0, 20.0, 30.0), 400.0, 915.0, bottomInset = 400.0)
        assertEquals(371.0, above.centerY, 0.0)
    }

    @Test
    fun horizontalHeightShrinksWhenInsetsEatVerticalSpaceButRespectsFloor() {
        val f = frame(ReaderSelectionRect(100.0, 400.0, 20.0, 30.0), 400.0, 600.0, topInset = 200.0, bottomInset = 150.0)
        assertEquals(190.0, f.height, 0.0)
        assertTrue(f.height >= 120.0)
    }

    @Test
    fun fullWidthIgnoresMaxWidthAndOrientation() {
        val h = frame(ReaderSelectionRect(0.0, 0.0, 1.0, 1.0), 400.0, 800.0, maxW = 100.0, fullWidth = true)
        val v = frame(ReaderSelectionRect(0.0, 0.0, 1.0, 1.0), 400.0, 800.0, maxW = 100.0, vertical = true, fullWidth = true)
        assertEquals(388.0, h.width, 0.0) // screenWidth - 12, ignores maxWidth=100
        assertEquals(h.width, v.width, 0.0)
        assertEquals(h.centerX, v.centerX, 0.0)
        assertEquals(h.centerY, v.centerY, 0.0)
    }

    // ---- geometry: property invariants across a grid -------------------------

    @Test
    fun frameStaysWithinScreenAcrossPositionsInsetsAndOrientation() {
        val screens = listOf(412.0 to 915.0, 915.0 to 412.0, 300.0 to 400.0)
        for ((sw, sh) in screens) {
            for (vertical in listOf(false, true)) {
                for (maxW in listOf(200.0, 320.0)) {
                    for (maxH in listOf(80.0, 250.0)) {
                        for (inset in listOf(0.0, 64.0)) {
                            var y = -100.0
                            while (y <= sh) {
                                var x = -100.0
                                while (x <= sw) {
                                    val f = frame(
                                        ReaderSelectionRect(x, y, 30.0, 40.0), sw, sh,
                                        maxW = maxW, maxH = maxH, vertical = vertical,
                                        topInset = inset, bottomInset = inset,
                                    )
                                    // The clamp guarantees the popup's top-left is always
                                    // on screen (never hangs off the top/left edge). The
                                    // bottom/right may overflow when the popup is larger
                                    // than the available space — the documented tradeoff of
                                    // keeping a visible popup rather than a sliver.
                                    val left = f.centerX - f.width / 2
                                    val top = f.centerY - f.height / 2
                                    assertTrue("left=$left off screen (sw=$sw vert=$vertical)", left >= -0.5)
                                    assertTrue("top=$top off screen (sh=$sh vert=$vertical)", top >= -0.5)
                                    x += 90.0
                                }
                                y += 90.0
                            }
                        }
                    }
                }
            }
        }
    }

    @Test
    fun horizontalHeightAlwaysWithinFloorToMaxBand() {
        var y = -100.0
        while (y <= 915.0) {
            for (h in listOf(20.0, 200.0, 800.0)) {
                val f = frame(ReaderSelectionRect(100.0, y, 30.0, h), 412.0, 915.0, maxH = 250.0)
                assertTrue("height ${f.height} below floor at y=$y h=$h", f.height >= minOf(120.0, 250.0))
                assertTrue("height ${f.height} above max at y=$y h=$h", f.height <= 250.0)
                assertTrue("width ${f.width} above max", f.width <= 320.0)
            }
            y += 60.0
        }
    }

    // ---- factory / stack wiring ---------------------------------------------

    private fun option(
        vertical: Boolean = false,
        fullWidth: Boolean = false,
        width: Int = 320,
        height: Int = 250,
        topInset: Double = 0.0,
        bottomInset: Double = 0.0,
        documentTitle: String? = null,
    ) = LookupPopupOptions(
        isVertical = vertical, isFullWidth = fullWidth, width = width, height = height,
        topInset = topInset, bottomInset = bottomInset, documentTitle = documentTitle,
    )

    private fun selection(text: String, sentence: String = text, sentenceOffset: Int? = null) =
        ReaderSelectionData(
            text = text, sentence = sentence,
            rect = ReaderSelectionRect(0.0, 0.0, 1.0, 1.0),
            normalizedOffset = null, sentenceOffset = sentenceOffset,
        )

    @Test
    fun createLookupPopupItemMapsOptionsIntoStateAndCountsCodePoints() {
        val (item, count) = createLookupPopupItem(
            selection = selection("食べる", sentence = "私は食べる", sentenceOffset = 7),
            options = option(vertical = false, fullWidth = true, width = 300, height = 200, topInset = 12.0, bottomInset = 34.0, documentTitle = "T"),
            dictionaryStyles = emptyMap(),
            lookup = { _, _, _ -> listOf(lookupResult("食べる", "たべる", "to eat")) },
        )!!
        assertFalse(item.state.isVertical)
        assertTrue(item.state.isFullWidth)
        assertEquals(300, item.state.width)
        assertEquals(200, item.state.height)
        assertEquals(12.0, item.state.topInset, 0.0)
        assertEquals(34.0, item.state.bottomInset, 0.0)
        assertEquals("T", item.state.ankiContext.documentTitle)
        assertEquals(7, item.state.ankiContext.sentenceOffset)
        assertEquals(3, count)
    }

    @Test
    fun createLookupPopupItemCountsCodePointsNotUtf16Units() {
        val (_, count) = createLookupPopupItem(
            selection = selection("𠮷野"),
            options = option(),
            dictionaryStyles = emptyMap(),
            lookup = { _, _, _ -> listOf(lookupResult("𠮷野", "よしの", "surname")) },
        )!!
        assertEquals(2, count) // "𠮷野" is 3 UTF-16 units but 2 code points
    }

    @Test
    fun createLookupPopupItemReturnsNullWhenLookupEmptyOrThrows() {
        assertEquals(
            null,
            createLookupPopupItem(selection("x"), option(), emptyMap()) { _, _, _ -> emptyList() },
        )
        assertEquals(
            null,
            createLookupPopupItem(selection("x"), option(), emptyMap()) { _, _, _ -> throw RuntimeException("boom") },
        )
    }

    @Test
    fun clearPopupSelectionHighlightsBumpsEverySignal() {
        val popups = listOf(0, 5, 2).mapIndexed { i, sig ->
            LookupPopupItem(
                id = "p$i", clearSelectionSignal = sig,
                state = LookupPopupState(selection = selection("t$i"), results = emptyList()),
            )
        }
        val cleared = clearPopupSelectionHighlights(popups)
        assertEquals(listOf(1, 6, 3), cleared.map { it.clearSelectionSignal })
        assertEquals(listOf("p0", "p1", "p2"), cleared.map { it.id })
    }

    @Test
    fun scrollingMiddleOfDeepStackClosesDescendantsAndSignalsScroller() {
        val popups = listOf("root", "child", "grandchild").map { id ->
            LookupPopupItem(id = id, state = LookupPopupState(selection = selection(id), results = emptyList()))
        }
        val scrolled = closeChildPopupsForScrolledParent(popups, 1)
        assertEquals(listOf("root", "child"), scrolled.map { it.id })
        assertEquals(1, scrolled.single { it.id == "child" }.clearSelectionSignal)
        assertEquals(0, scrolled.single { it.id == "root" }.clearSelectionSignal)
    }

    @Test
    fun withRootSelectionOffsetIsNoOpForZeroOffsetAndEmptyList() {
        val popups = listOf(
            LookupPopupItem(id = "root", state = LookupPopupState(selection = selection("a"), results = emptyList())),
        )
        assertTrue(popups.withRootSelectionOffset(0.0, 0.0) === popups)
        assertTrue(emptyList<LookupPopupItem>().withRootSelectionOffset(5.0, 7.0).isEmpty())
    }

    @Test
    fun withLookupPopupVisualOptionsUpdatesEveryPopupIncludingScale() {
        val popups = listOf("root", "child").map { id ->
            LookupPopupItem(
                id = id, clearSelectionSignal = 4,
                state = LookupPopupState(selection = selection(id), results = emptyList(), popupScale = 1.0),
            )
        }
        val themed = popups.withLookupPopupVisualOptions(
            darkMode = true, eInkMode = true, audioSettings = AudioSettings(),
        )
        assertEquals(2, themed.size)
        assertTrue(themed.all { it.state.darkMode && it.state.eInkMode })
        assertTrue(themed.all { it.clearSelectionSignal == 4 })
    }

    // ---- controls-height / selection offset matrix --------------------------

    @Test
    fun selectionOffsetAddsSingleBarForActionBarOrForwardHistory() {
        assertEquals(87.0, popupSelectionOffsetY(50.0, popupActionBar = true, backCount = 0, forwardCount = 0, hasSasayakiCue = false), 0.0)
        assertEquals(87.0, popupSelectionOffsetY(50.0, popupActionBar = false, backCount = 0, forwardCount = 1, hasSasayakiCue = false), 0.0)
    }

    @Test
    fun selectionOffsetDoesNotDoubleCountBarWhenActionBarAndHistoryBothPresent() {
        assertEquals(87.0, popupSelectionOffsetY(50.0, popupActionBar = true, backCount = 3, forwardCount = 1, hasSasayakiCue = false), 0.0)
    }

    @Test
    fun selectionOffsetStacksSasayakiCueOnTopOfControlBar() {
        assertEquals(87.0, popupSelectionOffsetY(50.0, popupActionBar = false, backCount = 0, forwardCount = 0, hasSasayakiCue = true), 0.0)
        assertEquals(124.0, popupSelectionOffsetY(50.0, popupActionBar = true, backCount = 0, forwardCount = 0, hasSasayakiCue = true), 0.0)
        assertEquals(124.0, popupSelectionOffsetY(50.0, popupActionBar = false, backCount = 1, forwardCount = 0, hasSasayakiCue = true), 0.0)
    }

    // ---- touch stream / stylus edge cases -----------------------------------

    @Test
    fun touchStreamIgnoresOutsideDownAndDoesNotStealTheStream() {
        val tracker = PopupTouchStreamTracker()
        assertFalse(tracker.shouldDispatch(MotionEvent.ACTION_DOWN, hitPopup = false))
        tracker.onDispatchResult(MotionEvent.ACTION_DOWN, handled = false)
        assertFalse(tracker.shouldDispatch(MotionEvent.ACTION_MOVE, hitPopup = false))
    }

    @Test
    fun touchStreamResetsOnCancelLikeUp() {
        val tracker = PopupTouchStreamTracker()
        assertTrue(tracker.shouldDispatch(MotionEvent.ACTION_DOWN, hitPopup = true))
        tracker.onDispatchResult(MotionEvent.ACTION_DOWN, handled = true)
        assertTrue(tracker.shouldDispatch(MotionEvent.ACTION_CANCEL, hitPopup = false))
        tracker.onDispatchResult(MotionEvent.ACTION_CANCEL, handled = true)
        assertFalse(tracker.shouldDispatch(MotionEvent.ACTION_MOVE, hitPopup = false))
    }

    @Test
    fun stylusDismissOnlyFiresOnDownNotUp() {
        assertFalse(
            shouldDismissForOutsideStylusTouch(
                actionMasked = MotionEvent.ACTION_UP,
                toolType = MotionEvent.TOOL_TYPE_STYLUS,
                hitPopup = false,
            ),
        )
    }

    // ---- edge cases: the popup must never cover the tapped word ----------------

    @Test
    fun tallBubbleWithMoreRoomBelowPlacesPopupBelowNotOverTheTop() {
        // Regression (would fail before the showBelow fix): the height floor must not
        // make showBelow pick the SMALLER (above) side and clamp the popup onto the
        // bubble's top. Box y=44 h=752: spaceAbove=40, spaceBelow=115 (below is larger
        // but < the 120 floor). Pre-fix this covered ~82dp of the top; now it goes below.
        val f = frame(ReaderSelectionRect(180.0, 44.0, 70.0, 752.0), 412.0, 915.0)
        val popupTop = f.centerY - f.height / 2
        assertTrue("popup covers the bubble top (popupTop=$popupTop, boxTop=44)", popupTop >= 44.0)
        assertTrue("popup not on the roomier (below) side", popupTop >= (44.0 + 796.0) / 2)
    }

    @Test
    fun fullWidthSheetSitsAboveTheBottomInsetNotUnderTheNavBar() {
        // Regression: the full-width bottom sheet must clear the bottom inset like every
        // other branch. bottomInset=200 → the sheet's bottom must be <= screenH - inset.
        val f = frame(ReaderSelectionRect(0.0, 0.0, 1.0, 1.0), 400.0, 800.0, fullWidth = true, bottomInset = 200.0)
        assertEquals(469.0, f.centerY, 0.0)
        assertTrue("sheet overlaps the bottom inset", f.centerY + f.height / 2 <= 600.0)
    }

    @Test
    fun popupCoverNeverExceedsTheUnavoidableFloorOverlapAcrossAFineGrid() {
        // The core invariant: whenever there is room for the popup beside/above/below the
        // selection, it must NOT cover it. The only permitted cover is the small,
        // unavoidable amount when a viewport-filling box leaves less than the 120dp floor
        // of room on its roomier side. Fine grid so borders/corners/thresholds are hit.
        val pad = 4.0
        val floor = 120.0
        val screens = listOf(412.0 to 915.0, 915.0 to 412.0, 360.0 to 640.0)
        for ((sw, sh) in screens) {
            for (vertical in listOf(false, true)) {
                for (bw in listOf(20.0, 60.0, 200.0, 340.0)) {
                    for (bh in listOf(20.0, 60.0, 200.0, 400.0, 720.0, 880.0)) {
                        var y = -60.0
                        while (y <= sh + 20) {
                            var x = -60.0
                            while (x <= sw + 20) {
                                val f = frame(ReaderSelectionRect(x, y, bw, bh), sw, sh, vertical = vertical)
                                val pl = f.centerX - f.width / 2
                                val pr = f.centerX + f.width / 2
                                val pt = f.centerY - f.height / 2
                                val pb = f.centerY + f.height / 2
                                assertTrue(
                                    "off-screen top-left sw=$sw sh=$sh v=$vertical x=$x y=$y bh=$bh -> ($pl,$pt)",
                                    pl >= -0.5 && pt >= -0.5,
                                )
                                val ox = maxOf(0.0, minOf(pr, x + bw) - maxOf(pl, x))
                                val oy = maxOf(0.0, minOf(pb, y + bh) - maxOf(pt, y))
                                if (vertical) {
                                    val room = maxOf(x - pad, sw - x - bw - pad)
                                    val allowed = maxOf(0.0, floor - room) + 3.0
                                    assertTrue("vertical cover ${ox}px > $allowed at x=$x bw=$bw sw=$sw", ox <= allowed)
                                } else {
                                    val room = maxOf(y - pad, sh - y - bh - pad)
                                    val allowed = maxOf(0.0, floor - room) + 3.0
                                    assertTrue("horizontal cover ${oy}px > $allowed at y=$y bh=$bh sh=$sh", oy <= allowed)
                                }
                                x += 43.0
                            }
                            y += 43.0
                        }
                    }
                }
            }
        }
    }

    // Threshold anchors — the exact `showOnRight` boundaries (vertical path).
    @Test fun verticalShowOnRightTieChoosesRight() {
        val f = frame(ReaderSelectionRect(196.0, 400.0, 20.0, 30.0), 412.0, 915.0, vertical = true)
        assertEquals(186.0, f.width, 0.0); assertEquals(313.0, f.centerX, 0.0); assertEquals(525.0, f.centerY, 0.0)
    }
    @Test fun verticalShowOnRightJustUnderTieFlipsLeft() {
        val f = frame(ReaderSelectionRect(197.0, 400.0, 20.0, 30.0), 412.0, 915.0, vertical = true)
        assertEquals(187.0, f.width, 0.0); assertEquals(99.5, f.centerX, 0.0)
    }
    @Test fun verticalSpaceRightEqualsMaxWidthForcesRight() {
        val f = frame(ReaderSelectionRect(456.0, 200.0, 20.0, 30.0), 800.0, 800.0, vertical = true)
        assertEquals(320.0, f.width, 0.0); assertEquals(640.0, f.centerX, 0.0)
    }
    @Test fun verticalSpaceRightJustUnderMaxWidthFlipsLeft() {
        val f = frame(ReaderSelectionRect(457.0, 200.0, 20.0, 30.0), 800.0, 800.0, vertical = true)
        assertEquals(293.0, f.centerX, 0.0)
    }

    // Vertical border-flush — popup goes to the roomy side with a gap (never covers).
    @Test fun verticalFlushLeftPlacesPopupRightWithGap() {
        val f = frame(ReaderSelectionRect(0.0, 400.0, 20.0, 30.0), 412.0, 915.0, vertical = true)
        assertEquals(320.0, f.width, 0.0); assertEquals(184.0, f.centerX, 0.0)
        assertTrue("covers box", f.centerX - f.width / 2 >= 20.0)
    }
    @Test fun verticalFlushRightPlacesPopupLeftOnScreen() {
        val f = frame(ReaderSelectionRect(392.0, 400.0, 20.0, 30.0), 412.0, 915.0, vertical = true)
        assertEquals(228.0, f.centerX, 0.0)
        assertTrue("off left", f.centerX - f.width / 2 >= 0.0)
        assertTrue("covers box", f.centerX + f.width / 2 <= 392.0)
    }
    @Test fun verticalFlushTopClampsTopOnScreen() {
        val f = frame(ReaderSelectionRect(100.0, 0.0, 20.0, 30.0), 412.0, 915.0, vertical = true)
        assertEquals(131.0, f.centerY, 0.0); assertEquals(6.0, f.centerY - f.height / 2, 0.0)
    }
    @Test fun verticalFlushBottomClampsBottomOnScreen() {
        val f = frame(ReaderSelectionRect(100.0, 885.0, 20.0, 30.0), 412.0, 915.0, vertical = true)
        assertEquals(784.0, f.centerY, 0.0); assertEquals(909.0, f.centerY + f.height / 2, 0.0)
    }
    @Test fun verticalNegativeXClampsLeftEdgeToZero() {
        val f = frame(ReaderSelectionRect(-30.0, 400.0, 20.0, 30.0), 412.0, 915.0, vertical = true)
        assertEquals(160.0, f.centerX, 0.0); assertEquals(0.0, f.centerX - f.width / 2, 0.0)
    }

    // Horizontal border-flush / corners — popup goes above/below and stays on screen.
    @Test fun horizontalFlushTopPlacesBelow() {
        val f = frame(ReaderSelectionRect(100.0, 0.0, 60.0, 40.0), 412.0, 915.0)
        assertEquals(246.0, f.centerX, 0.0); assertEquals(169.0, f.centerY, 0.0)
        assertTrue("above box top", f.centerY - f.height / 2 >= 40.0)
    }
    @Test fun horizontalFlushBottomPlacesAbove() {
        val f = frame(ReaderSelectionRect(100.0, 875.0, 60.0, 40.0), 412.0, 915.0)
        assertEquals(746.0, f.centerY, 0.0)
        assertTrue("below box bottom", f.centerY + f.height / 2 <= 875.0)
    }
    @Test fun horizontalTopLeftCornerClampsBothAxes() {
        val f = frame(ReaderSelectionRect(0.0, 0.0, 60.0, 40.0), 412.0, 915.0)
        assertEquals(166.0, f.centerX, 0.0); assertEquals(169.0, f.centerY, 0.0)
    }
    @Test fun horizontalBottomRightCornerClampsBothAxes() {
        val f = frame(ReaderSelectionRect(352.0, 875.0, 60.0, 40.0), 412.0, 915.0)
        assertEquals(246.0, f.centerX, 0.0); assertEquals(746.0, f.centerY, 0.0)
    }

    // Degenerate / off-screen / small-screen boxes.
    @Test fun zeroSizeBoxesDoNotBreakLayout() {
        val zw = frame(ReaderSelectionRect(200.0, 400.0, 0.0, 40.0), 412.0, 915.0)
        assertEquals(246.0, zw.centerX, 0.0); assertEquals(569.0, zw.centerY, 0.0)
        val zh = frame(ReaderSelectionRect(200.0, 400.0, 40.0, 0.0), 412.0, 915.0)
        assertEquals(529.0, zh.centerY, 0.0)
    }
    @Test fun boxFullyBelowScreenKeepsPopupOnScreen() {
        val f = frame(ReaderSelectionRect(100.0, 1000.0, 20.0, 30.0), 412.0, 915.0)
        assertEquals(784.0, f.centerY, 0.0); assertTrue(f.centerY + f.height / 2 <= 915.0)
    }
    @Test fun boxFullyAboveScreenKeepsPopupOnScreen() {
        val f = frame(ReaderSelectionRect(100.0, -200.0, 20.0, 30.0), 412.0, 915.0)
        assertEquals(131.0, f.centerY, 0.0); assertTrue(f.centerY - f.height / 2 >= 0.0)
    }
    @Test fun landscapeShortViewportHeightTracksAvailableSpace() {
        val f = frame(ReaderSelectionRect(250.0, 180.0, 40.0, 40.0), 600.0, 400.0)
        assertEquals(170.0, f.height, 0.0); assertEquals(309.0, f.centerY, 0.0)
    }

    private fun lookupResult(
        expression: String,
        reading: String,
        glossary: String,
    ): LookupResult = LookupResult(
        expression,
        expression,
        emptyArray(),
        TermResult(
            expression = expression,
            reading = reading,
            rules = "",
            glossaries = arrayOf(
                GlossaryEntry(
                    dictName = "JMdict",
                    glossary = glossary,
                    definitionTags = "",
                    termTags = "",
                ),
            ),
            frequencies = emptyArray(),
            pitches = emptyArray(),
        ),
        0,
    )
}
