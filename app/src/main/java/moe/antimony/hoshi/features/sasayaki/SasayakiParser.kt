package moe.antimony.hoshi.features.sasayaki

import java.nio.charset.StandardCharsets

object SasayakiParser {
    fun parseCues(data: ByteArray): List<SasayakiCue> =
        String(data, StandardCharsets.UTF_8)
            .replace("\r\n", "\n")
            .split("\n\n")
            .mapIndexedNotNull { index, block ->
                val lines = block.lines()
                if (lines.size < 3 || !lines[1].contains("-->")) return@mapIndexedNotNull null
                val times = lines[1].split("-->", limit = 2)
                if (times.size != 2) return@mapIndexedNotNull null
                val startTime = parseTimestamp(times[0]) ?: return@mapIndexedNotNull null
                val endTime = parseTimestamp(times[1]) ?: return@mapIndexedNotNull null
                SasayakiCue(
                    id = index.toString(),
                    startTime = startTime,
                    endTime = endTime,
                    text = lines[2].trim(),
                )
            }

    private fun parseTimestamp(timestamp: String): Double? {
        val parts = timestamp.trim().replace(',', '.').split(':')
        if (parts.size != 3) return null
        val hours = parts[0].toDoubleOrNull() ?: return null
        val minutes = parts[1].toDoubleOrNull() ?: return null
        val seconds = parts[2].toDoubleOrNull() ?: return null
        return (hours * 3600.0 + minutes * 60.0 + seconds).takeIf { it.isFinite() }
    }
}
