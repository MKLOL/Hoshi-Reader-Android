package moe.antimony.hoshi.features.dictionary

import moe.antimony.hoshi.features.reader.MangaActionPlacement
import moe.antimony.hoshi.features.reader.MangaActionSide
import moe.antimony.hoshi.features.reader.ReaderSelectionBridgePayload
import moe.antimony.hoshi.features.reader.ReaderSelectionRect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MangaLookupPopupLayoutTest {
    private val bubble = ReaderSelectionRect(130.0, 300.0, 140.0, 180.0)

    @Test fun lookupUsesTheOppositeSideForEveryActionRowPlacement() {
        val rows = mapOf(
            MangaActionSide.Above to ReaderSelectionRect(130.0, 270.0, 50.0, 24.0),
            MangaActionSide.Below to ReaderSelectionRect(130.0, 486.0, 50.0, 24.0),
            MangaActionSide.Left to ReaderSelectionRect(74.0, 350.0, 50.0, 24.0),
            MangaActionSide.Right to ReaderSelectionRect(276.0, 350.0, 50.0, 24.0),
        )
        for ((side, row) in rows) {
            val frame = layout(MangaActionPlacement(bubble, row, side))
            when (side) {
                MangaActionSide.Above -> assertTrue(frame.centerY - frame.height / 2 >= 484.0)
                MangaActionSide.Below -> assertTrue(frame.centerY + frame.height / 2 <= 296.0)
                MangaActionSide.Left -> assertTrue(frame.centerX - frame.width / 2 >= 274.0)
                MangaActionSide.Right -> assertTrue(frame.centerX + frame.width / 2 <= 126.0)
            }
            assertTrue("Lookup overlaps action row on $side: $frame", !frame.overlaps(row))
        }
    }

    @Test fun fullWidthRemainsAvailableAboveButtonsPlacedBelowAnEdgeBubble() {
        val edgeBubble = ReaderSelectionRect(12.0, 120.0, 380.0, 240.0)
        val row = ReaderSelectionRect(320.0, 365.0, 72.0, 24.0)
        val frame = layout(MangaActionPlacement(edgeBubble, row, MangaActionSide.Below), fullWidth = true)
        assertEquals(388.0, frame.width, 0.01)
        assertTrue(frame.centerY + frame.height / 2 <= 116.0)
        assertTrue(!frame.overlaps(row))
    }

    @Test fun fullWidthUsesAVerticalLaneWhenTheActionRowIsLateral() {
        val row = ReaderSelectionRect(276.0, 350.0, 50.0, 24.0)
        val frame = layout(MangaActionPlacement(bubble, row, MangaActionSide.Right), fullWidth = true)
        assertEquals(388.0, frame.width, 0.01)
        assertTrue(frame.centerY - frame.height / 2 >= 484.0 || frame.centerY + frame.height / 2 <= 296.0)
        assertTrue(!frame.overlaps(row))
    }

    @Test fun aReadableSideWinsOverTheButtonSideWhenTheOppositeGapIsTooShort() {
        val edgeBubble = ReaderSelectionRect(130.0, 10.0, 140.0, 180.0)
        val row = ReaderSelectionRect(175.0, 196.0, 50.0, 24.0)
        val frame = layout(MangaActionPlacement(edgeBubble, row, MangaActionSide.Below))
        assertTrue(frame.centerX + frame.width / 2 <= 126.0 || frame.centerX - frame.width / 2 >= 274.0)
        assertTrue(!frame.overlaps(row))
    }

    @Test fun zoomedBubbleAndNestedSelectionKeepTheActionRowClear() {
        val zoomedBubble = ReaderSelectionRect(-70.0, 30.0, 700.0, 1000.0)
        val row = ReaderSelectionRect(320.0, 13.0, 70.0, 38.0)
        val placement = MangaActionPlacement(zoomedBubble, row, MangaActionSide.Above)
        val nestedAnchor = ReaderSelectionRect(375.0, 43.0, 8.0, 18.0)
        val frame = layout(placement, selection = nestedAnchor)
        assertTrue(frame.centerY - frame.height / 2 >= 55.0)
        assertTrue(!frame.overlaps(row))
    }

    @Test fun selectionBridgeDecodesMeasuredPlacementWithoutChangingTheSelectionAnchor() {
        val selection = checkNotNull(ReaderSelectionBridgePayload.fromJson("""
            {
              "text":"日本語","sentence":"日本語を読む",
              "rect":{"x":10,"y":20,"width":120,"height":180},
              "mangaActions":{
                "bubble":{"x":10,"y":45,"width":120,"height":155},
                "row":{"x":30,"y":20,"width":85,"height":20},"side":"above"
              }
            }
        """.trimIndent()))
        assertEquals(ReaderSelectionRect(10.0, 20.0, 120.0, 180.0), selection.rect)
        assertEquals(
            MangaActionPlacement(
                ReaderSelectionRect(10.0, 45.0, 120.0, 155.0),
                ReaderSelectionRect(30.0, 20.0, 85.0, 20.0),
                MangaActionSide.Above,
            ),
            selection.mangaActions,
        )
    }

    private fun layout(
        placement: MangaActionPlacement,
        fullWidth: Boolean = false,
        selection: ReaderSelectionRect = bubble,
    ) = LookupPopupLayout(
        selectionRect = selection,
        screenWidth = 400.0,
        screenHeight = 800.0,
        maxWidth = 285.0,
        maxHeight = 340.0,
        isVertical = false,
        isFullWidth = fullWidth,
        mangaActions = placement,
    ).calculate()

    private fun LookupPopupFrame.overlaps(rect: ReaderSelectionRect): Boolean =
        centerX - width / 2 < rect.x + rect.width && centerX + width / 2 > rect.x &&
            centerY - height / 2 < rect.y + rect.height && centerY + height / 2 > rect.y
}
