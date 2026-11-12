package moe.antimony.hoshi.epub

import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import kotlin.math.abs

/** Splits counted time into local hours, with a partial bucket when the statistics rule changes. */
fun readingHoursBetween(
    startMillis: Long,
    endMillis: Long,
    zone: ZoneId,
    cutoffMillis: Long? = null,
): Map<String, Double> {
    val hours = mutableMapOf<String, Double>()
    val cutoffAt = cutoffMillis ?: Long.MAX_VALUE
    val cutoff = cutoffMillis?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDateTime() }
    val cutoffHour = cutoff?.truncatedTo(ChronoUnit.HOURS)
    var cursor = startMillis
    while (cursor < endMillis) {
        val instant = Instant.ofEpochMilli(cursor)
        val local = instant.atZone(zone)
        val hour = local.toLocalDateTime().truncatedTo(ChronoUnit.HOURS)
        // Split at BOTH a wall-clock hour and an offset change. A zoned truncation can move
        // backwards across a half-hour overlap (Lord Howe), or normalize 02:00 to 02:30.
        val nextHour = ZonedDateTime.ofLocal(hour.plusHours(1), zone, local.offset).toInstant().toEpochMilli()
        val transition = zone.rules.nextTransition(instant)?.instant?.toEpochMilli() ?: Long.MAX_VALUE
        val nextCutoff = if (cursor < cutoffAt) cutoffAt else Long.MAX_VALUE
        val end = minOf(endMillis, nextHour, transition, nextCutoff)
        check(end > cursor)
        val key = if (cutoff != null && hour == cutoffHour && cursor >= cutoffAt) cutoff.toString() else hour.toString()
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

/** The default for legacy records without a time zone. */
internal val LEGACY_READING_ZONE: ZoneId = ZoneId.of("America/New_York")

/** The wall-clock zone a uniform row records; old rows without a zone use US Eastern. */
internal fun ReadingStatistics.recordedZone(): ZoneId = zoneOrNull(timeZone) ?: LEGACY_READING_ZONE

/** Source zone as understood by the previous shared-clock statistics. */
internal fun ReadingStatistics.legacySourceZone(target: ZoneId): ZoneId {
    zoneOrNull(timeZone)?.let { return it }
    if (lastStatisticModified <= 0L) return target
    val lastHour = readingTimeByHour.keys.mapNotNull { runCatching { LocalDateTime.parse(it) }.getOrNull() }
        .maxOrNull() ?: return target
    val stamp = Instant.ofEpochMilli(lastStatisticModified)
    // A saved timestamp shortly after the last bucket reveals its offset on old native rows.
    val low = Duration.between(LocalDateTime.ofInstant(stamp, ZoneOffset.UTC), lastHour)
    val high = low.plusHours(1).plusSeconds(60)
    val targetOffset = Duration.ofSeconds(target.rules.getOffset(stamp).totalSeconds.toLong())
    if (targetOffset > low && targetOffset <= high) return target
    if (abs(readingTime - readingTimeByHour.values.sum()) > 1.0) return target
    val hours = Math.floorDiv(low.seconds, 3600) + 1
    return if (hours in -14L..14L) ZoneOffset.ofHours(hours.toInt()) else target
}

/** The flat compatibility view is canonical; nested-only rows can still be read. */
internal fun ReadingStatistics.localHourBuckets(): Map<String, Double> =
    readingTimeByHour.ifEmpty { readingTimeByZone.values.sumReadingHours() }

/** Add hours on the clock they were read on, even if the device changes zones on the same date. */
internal fun ReadingStatistics.addReadingHours(zone: ZoneId, hours: Map<String, Double>): ReadingStatistics {
    if (hours.isEmpty()) return this
    val localHours = listOf(localHourBuckets(), hours).sumReadingHours()
    if (readingTimeByZone.isEmpty() && (readingTimeByHour.isEmpty() || recordedZone() == zone)) {
        return copy(readingTimeByHour = localHours, timeZone = zone.id)
    }
    val byZone = readingTimeByZone.ifEmpty { mapOf(recordedZone().id to readingTimeByHour) }.toMutableMap()
    byZone[zone.id] = listOf(byZone[zone.id].orEmpty(), hours).sumReadingHours()
    return copy(readingTimeByHour = localHours, timeZone = null, readingTimeByZone = byZone.toSortedMap())
}
