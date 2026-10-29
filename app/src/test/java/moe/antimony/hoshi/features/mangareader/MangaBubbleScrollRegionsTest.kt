package moe.antimony.hoshi.features.mangareader

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MangaBubbleScrollRegionsTest {
    @Test
    fun hitTestingConvertsTouchPixelsToHostCssCoordinates() {
        val regions = MangaBubbleScrollRegions().apply {
            reset(3)
            update(3, """[{"x":10,"y":20,"width":100,"height":150}]""")
        }
        assertTrue(regions.containsTouch(100f, 200f, density = 2f))
        assertFalse(regions.containsTouch(10f, 20f, density = 2f))
        assertFalse(regions.containsTouch(220f, 200f, density = 2f))
        assertFalse(regions.containsTouch(100f, 340f, density = 2f))
    }

    @Test
    fun pageLoadClearsTheOldRegionsAndRejectsStaleReports() {
        val regions = MangaBubbleScrollRegions().apply {
            reset(1)
            update(1, RECT)
        }
        assertTrue(regions.containsTouch(50f, 50f, 1f))
        regions.reset(2)
        assertFalse(regions.containsTouch(50f, 50f, 1f))
        regions.update(1, RECT)
        assertFalse(regions.containsTouch(50f, 50f, 1f))
        regions.update(2, RECT)
        assertTrue(regions.containsTouch(50f, 50f, 1f))
        regions.update(2, "[]")
        assertFalse(regions.containsTouch(50f, 50f, 1f))
    }

    @Test
    fun malformedHugeAndNonFinitePayloadsCannotCreateScrollRegions() {
        val regions = MangaBubbleScrollRegions().apply { reset(0) }
        for (payload in listOf(
            "not json", "{}", "[{\"x\":0}]", "[]" + " ".repeat(65_536),
            """[{"x":0,"y":0,"width":NaN,"height":100}]""",
            """[{"x":0,"y":0,"width":1e400,"height":100}]""",
            """[{"x":0,"y":0,"width":-100,"height":100}]""",
            """[{"x":0,"y":0,"width":100000000,"height":100}]""",
            List(257) { RECT.removePrefix("[").removeSuffix("]") }.joinToString(",", "[", "]"),
        )) {
            regions.update(0, RECT)
            regions.update(0, payload)
            assertFalse(payload.take(150), regions.containsTouch(50f, 50f, 1f))
        }
    }

    @Test
    fun invalidTouchMetricsNeverHitARegion() {
        val regions = MangaBubbleScrollRegions().apply { reset(0); update(0, RECT) }
        assertFalse(regions.containsTouch(Float.NaN, 50f, 1f))
        assertFalse(regions.containsTouch(50f, Float.POSITIVE_INFINITY, 1f))
        assertFalse(regions.containsTouch(50f, 50f, 0f))
        assertFalse(regions.containsTouch(50f, 50f, Float.NaN))
    }

    private companion object {
        const val RECT = """[{"x":0,"y":0,"width":100,"height":100}]"""
    }
}
