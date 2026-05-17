package moe.antimony.hoshi.features.mangareader

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp

@Composable
internal fun MangaScreenshotCropOverlay(
    containerWidthPx: Int,
    containerHeightPx: Int,
    darkInterface: Boolean,
    onCancel: () -> Unit,
    onConfirm: (MangaScreenshotCropRect) -> Unit,
    modifier: Modifier = Modifier,
) {
    var dragStart by remember { mutableStateOf<Offset?>(null) }
    var dragEnd by remember { mutableStateOf<Offset?>(null) }
    val previewRect = run {
        val start = dragStart
        val end = dragEnd
        if (start == null || end == null) {
            null
        } else {
            normalizedMangaScreenshotCropRect(
                startX = start.x,
                startY = start.y,
                endX = end.x,
                endY = end.y,
                containerWidth = containerWidthPx,
                containerHeight = containerHeightPx,
                minSize = 1,
            )
        }
    }
    val currentRect = run {
        val start = dragStart
        val end = dragEnd
        if (start == null || end == null) {
            null
        } else {
            normalizedMangaScreenshotCropRect(
                startX = start.x,
                startY = start.y,
                endX = end.x,
                endY = end.y,
                containerWidth = containerWidthPx,
                containerHeight = containerHeightPx,
            )
        }
    }

    Box(modifier = modifier) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(containerWidthPx, containerHeightPx) {
                    detectDragGestures(
                        onDragStart = { position ->
                            dragStart = position
                            dragEnd = position
                        },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            val previous = dragEnd ?: change.position
                            dragEnd = previous + dragAmount
                        },
                    )
                },
        ) {
            drawRect(Color.Black.copy(alpha = 0.36f))
            val rect = previewRect
            if (rect != null) {
                val topLeft = Offset(rect.left.toFloat(), rect.top.toFloat())
                val size = Size(rect.width.toFloat(), rect.height.toFloat())
                drawRect(
                    color = Color.White.copy(alpha = 0.18f),
                    topLeft = topLeft,
                    size = size,
                )
                drawRect(
                    color = Color.Black.copy(alpha = 0.55f),
                    topLeft = topLeft,
                    size = size,
                    style = Stroke(width = 5.dp.toPx()),
                )
                drawRect(
                    color = Color.White,
                    topLeft = topLeft,
                    size = size,
                    style = Stroke(width = 2.dp.toPx()),
                )
            }
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 28.dp)
                .background(
                    color = if (darkInterface) {
                        Color.Black.copy(alpha = 0.72f)
                    } else {
                        Color.White.copy(alpha = 0.92f)
                    },
                    shape = RoundedCornerShape(999.dp),
                )
                .padding(horizontal = 10.dp, vertical = 8.dp),
        ) {
            TextButton(onClick = onCancel) {
                Text("Cancel")
            }
            Button(
                enabled = currentRect != null,
                onClick = {
                    currentRect?.let(onConfirm)
                },
            ) {
                Text("Translate")
            }
        }
    }
}
