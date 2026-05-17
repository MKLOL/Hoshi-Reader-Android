package moe.antimony.hoshi.features.mangareader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MangaScreenshotCropTest {
    @Test
    fun normalizesReverseDragAndRoundsOutward() {
        val rect = normalizedMangaScreenshotCropRect(
            startX = 210.8f,
            startY = 320.2f,
            endX = 100.3f,
            endY = 120.7f,
            containerWidth = 400,
            containerHeight = 500,
            minSize = 32,
        )

        assertEquals(MangaScreenshotCropRect(left = 100, top = 120, right = 211, bottom = 321), rect)
    }

    @Test
    fun clampsSelectionToContainerBounds() {
        val rect = normalizedMangaScreenshotCropRect(
            startX = -50f,
            startY = -20f,
            endX = 420f,
            endY = 540f,
            containerWidth = 400,
            containerHeight = 500,
            minSize = 32,
        )

        assertEquals(MangaScreenshotCropRect(left = 0, top = 0, right = 400, bottom = 500), rect)
    }

    @Test
    fun rejectsSelectionsBelowMinimumSize() {
        val rect = normalizedMangaScreenshotCropRect(
            startX = 10f,
            startY = 20f,
            endX = 30f,
            endY = 40f,
            containerWidth = 400,
            containerHeight = 500,
            minSize = 32,
        )

        assertNull(rect)
    }

    @Test
    fun rejectsInvalidContainers() {
        val rect = normalizedMangaScreenshotCropRect(
            startX = 0f,
            startY = 0f,
            endX = 200f,
            endY = 200f,
            containerWidth = 0,
            containerHeight = 500,
            minSize = 32,
        )

        assertNull(rect)
    }
}
