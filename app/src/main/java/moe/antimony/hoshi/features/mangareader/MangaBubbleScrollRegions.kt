package moe.antimony.hoshi.features.mangareader

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlin.math.abs

/** Main-thread snapshot of the current page's visible, independently scrollable OCR text. */
internal class MangaBubbleScrollRegions {
    private var pageIndex: Int? = null
    private var regions: List<Region> = emptyList()

    fun reset(pageIndex: Int) {
        this.pageIndex = pageIndex
        regions = emptyList()
    }

    fun update(pageIndex: Int, payload: String) {
        if (this.pageIndex != pageIndex) return
        regions = parse(payload)
    }

    /** MotionEvent positions are physical pixels; bridge rectangles are host CSS pixels. */
    fun containsTouch(x: Float, y: Float, density: Float): Boolean {
        if (!x.isFinite() || !y.isFinite() || !density.isFinite() || density <= 0f) return false
        val cssX = x.toDouble() / density
        val cssY = y.toDouble() / density
        return regions.any { cssX >= it.x && cssX < it.x + it.width && cssY >= it.y && cssY < it.y + it.height }
    }

    @Serializable
    private data class Region(val x: Double, val y: Double, val width: Double, val height: Double) {
        fun isValid(): Boolean = x.isFinite() && y.isFinite() && width.isFinite() && height.isFinite() &&
            abs(x) <= MAX_COORDINATE && abs(y) <= MAX_COORDINATE &&
            width > 0.0 && width <= MAX_COORDINATE && height > 0.0 && height <= MAX_COORDINATE
    }

    private companion object {
        const val MAX_PAYLOAD_LENGTH = 65_536
        const val MAX_REGIONS = 256
        const val MAX_COORDINATE = 1_000_000.0
        val json = Json { ignoreUnknownKeys = true }

        fun parse(payload: String): List<Region> {
            if (payload.length > MAX_PAYLOAD_LENGTH) return emptyList()
            val parsed = try {
                json.decodeFromString<List<Region>>(payload)
            } catch (_: SerializationException) {
                return emptyList()
            } catch (_: IllegalArgumentException) {
                return emptyList()
            }
            if (parsed.size > MAX_REGIONS) return emptyList()
            return parsed.filter(Region::isValid)
        }
    }
}
