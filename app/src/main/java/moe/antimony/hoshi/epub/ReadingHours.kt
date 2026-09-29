package moe.antimony.hoshi.epub

import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit

/** Splits only counted time, including midnight and DST transitions, into local wall-clock hours. */
fun readingHoursBetween(startMillis: Long, endMillis: Long, zone: ZoneId): Map<String, Double> {
    val hours = mutableMapOf<String, Double>()
    var cursor = startMillis
    while (cursor < endMillis) {
        val instant = Instant.ofEpochMilli(cursor)
        val local = instant.atZone(zone)
        val hour = local.toLocalDateTime().truncatedTo(ChronoUnit.HOURS)
        // Split at BOTH a wall-clock hour and an offset change. A zoned truncation can move
        // backwards across a half-hour overlap (Lord Howe), or normalize 02:00 to 02:30.
        val nextHour = ZonedDateTime.ofLocal(hour.plusHours(1), zone, local.offset).toInstant().toEpochMilli()
        val transition = zone.rules.nextTransition(instant)?.instant?.toEpochMilli() ?: Long.MAX_VALUE
        val end = minOf(endMillis, nextHour, transition)
        check(end > cursor)
        val key = hour.toString()
        hours[key] = (hours[key] ?: 0.0) + (end - cursor) / 1000.0
        cursor = end
    }
    return hours
}

fun Iterable<Map<String, Double>>.sumReadingHours(): Map<String, Double> {
    val result = mutableMapOf<String, Double>()
    forEach { hours -> hours.forEach { (key, seconds) -> result[key] = (result[key] ?: 0.0) + seconds } }
    return result
}
