package moe.antimony.hoshi.epub

import java.time.DateTimeException
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import kotlin.math.abs

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

/** [id] as a zone, or null when it names none this device knows. */
fun zoneOrNull(id: String?): ZoneId? =
    id?.takeIf { it.isNotBlank() }?.let { runCatching { ZoneId.of(it) }.getOrNull() }

/**
 * The zone this entry's date and hours were recorded in, as far as it can be told; null when it
 * cannot (they are then taken as [target]'s own wall clock, as before zones were recorded).
 *
 * Newer entries name it ([ReadingStatistics.timeZone]). Older ones still tell it: the reader
 * stamps an entry ([ReadingStatistics.lastStatisticModified], an absolute instant) whenever it
 * counts time, so the stamp falls within the entry's last recorded hour, and that hour is wall
 * clock. A device whose clock is set to another zone (a tablet left on China time, 12 hours
 * from New York) thereby shows its offset. When [target]'s own offset fits, nothing is
 * converted; otherwise the whole-hour offset that fits is used.
 */
fun ReadingStatistics.recordedZone(target: ZoneId): ZoneId? {
    zoneOrNull(timeZone)?.let { return it }
    if (lastStatisticModified <= 0L) return null
    val last = readingTimeByHour.keys.mapNotNull { runCatching { LocalDateTime.parse(it) }.getOrNull() }.maxOrNull()
        ?: return null
    val stamp = Instant.ofEpochMilli(lastStatisticModified)
    // The last counted second ends within (last, last + 1 h] on the wall clock, and the stamp is
    // taken just after it: offset fits when last < stamp + offset <= last + 1 h (+ slack).
    val low = Duration.between(LocalDateTime.ofInstant(stamp, ZoneOffset.UTC), last)
    val high = low.plusHours(1).plus(STAMP_SLACK)
    val targetOffset = Duration.ofSeconds(target.rules.getOffset(stamp).totalSeconds.toLong())
    if (targetOffset > low && targetOffset <= high) return target
    // Only an entry whose time is exactly its hours is the reader's own: one that also holds
    // time from elsewhere (a ッツ import) was stamped later, which tells no zone.
    if (abs(readingTime - readingTimeByHour.values.sum()) > 1.0) return null
    // The smallest whole hour above low.
    val hours = Math.floorDiv(low.seconds, 3600) + 1
    if (hours !in -MAX_OFFSET_HOURS..MAX_OFFSET_HOURS) return null
    return try {
        ZoneOffset.ofHours(hours.toInt())
    } catch (_: DateTimeException) {
        null
    }
}

/** [hour] on [from]'s wall clock as the same instant on [to]'s; unchanged when they are one zone. */
fun convertWallClock(hour: LocalDateTime, from: ZoneId?, to: ZoneId?): LocalDateTime =
    if (from == null || to == null || from == to) hour
    else ZonedDateTime.ofLocal(hour, from, null).withZoneSameInstant(to).toLocalDateTime()

/** Every UTC offset in use lies within ±14 h. */
private const val MAX_OFFSET_HOURS = 14L

/** How long after its last counted second an entry may be stamped. */
private val STAMP_SLACK: Duration = Duration.ofSeconds(60)
