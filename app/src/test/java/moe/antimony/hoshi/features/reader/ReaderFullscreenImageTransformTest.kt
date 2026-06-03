package moe.antimony.hoshi.features.reader

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import org.junit.Assert.assertEquals
import org.junit.Test

class ReaderFullscreenImageTransformTest {
    private val viewport = Size(width = 1000f, height = 800f)

    @Test
    fun doubleTapZoomCentersTappedImagePointLikeIosZoomRect() {
        val fittedImage = Size(width = 1000f, height = 800f)

        val zoomed = ReaderFullscreenImageTransform().doubleTapZoomTo(
            targetScale = 3f,
            centroid = Offset(x = 250f, y = 300f),
            viewport = viewport,
            fittedImage = fittedImage,
        )

        assertEquals(3f, zoomed.scale, 0.001f)
        assertEquals(750f, zoomed.offset.x, 0.001f)
        assertEquals(300f, zoomed.offset.y, 0.001f)
    }

    @Test
    fun doubleTapNearImageEdgeStopsAtContentBoundary() {
        val fittedImage = Size(width = 1000f, height = 800f)

        val zoomed = ReaderFullscreenImageTransform().doubleTapZoomTo(
            targetScale = 3f,
            centroid = Offset(x = 50f, y = 40f),
            viewport = viewport,
            fittedImage = fittedImage,
        )

        assertEquals(1000f, zoomed.offset.x, 0.001f)
        assertEquals(800f, zoomed.offset.y, 0.001f)
    }

    @Test
    fun pinchZoomUsesGestureCentroidAndPanAsItsAnchor() {
        val fittedImage = Size(width = 1000f, height = 800f)

        val zoomed = ReaderFullscreenImageTransform().transformBy(
            centroid = Offset(x = 260f, y = 300f),
            pan = Offset(x = 10f, y = 0f),
            zoomChange = 2f,
            viewport = viewport,
            fittedImage = fittedImage,
        )

        assertEquals(260f, zoomed.offset.x, 0.001f)
        assertEquals(100f, zoomed.offset.y, 0.001f)
    }

    @Test
    fun panIsLimitedToTheScaledImageEdges() {
        val fittedImage = Size(width = 1000f, height = 800f)
        val zoomed = ReaderFullscreenImageTransform(scale = 3f)

        val dragged = zoomed.transformBy(
            centroid = Offset.Zero,
            pan = Offset(x = 4000f, y = -4000f),
            zoomChange = 1f,
            viewport = viewport,
            fittedImage = fittedImage,
        )

        assertEquals(1000f, dragged.offset.x, 0.001f)
        assertEquals(-800f, dragged.offset.y, 0.001f)
    }

    @Test
    fun fittedImageThatRemainsSmallerThanViewportCannotLeaveItsCenteredAxis() {
        val fittedImage = Size(width = 400f, height = 800f)
        val zoomed = ReaderFullscreenImageTransform(scale = 2f)

        val dragged = zoomed.transformBy(
            centroid = Offset.Zero,
            pan = Offset(x = 500f, y = 500f),
            zoomChange = 1f,
            viewport = viewport,
            fittedImage = fittedImage,
        )

        assertEquals(0f, dragged.offset.x, 0.001f)
        assertEquals(400f, dragged.offset.y, 0.001f)
    }

    @Test
    fun fittedSizePreservesAspectRatioInsideViewport() {
        assertEquals(
            Size(width = 400f, height = 800f),
            readerFullscreenFittedImageSize(
                image = Size(width = 600f, height = 1200f),
                viewport = viewport,
            ),
        )
    }

    @Test
    fun zoomedImageCanMovePastNormalVerticalBoundsToRevealEdgesAroundControls() {
        val panAllowance = ReaderFullscreenImagePanAllowance(
            topPx = 100f,
            bottomPx = 34f,
        )
        val zoomed = ReaderFullscreenImageTransform(scale = 3f)
        val topEdge = zoomed.transformBy(
            centroid = Offset.Zero,
            pan = Offset(x = 0f, y = 4000f),
            zoomChange = 1f,
            viewport = viewport,
            fittedImage = viewport,
            panAllowance = panAllowance,
        )
        val bottomEdge = zoomed.transformBy(
            centroid = Offset.Zero,
            pan = Offset(x = 0f, y = -4000f),
            zoomChange = 1f,
            viewport = viewport,
            fittedImage = viewport,
            panAllowance = panAllowance,
        )

        assertEquals(900f, topEdge.offset.y, 0.001f)
        assertEquals(-834f, bottomEdge.offset.y, 0.001f)
    }

    @Test
    fun unzoomedImageRemainsCenteredRegardlessOfZoomedEdgeAllowance() {
        val centered = ReaderFullscreenImageTransform().constrainedTo(
            viewport = viewport,
            fittedImage = viewport,
            panAllowance = ReaderFullscreenImagePanAllowance(topPx = 100f, bottomPx = 34f),
        )

        assertEquals(1f, centered.scale, 0.001f)
        assertEquals(Offset.Zero, centered.offset)
    }
}
