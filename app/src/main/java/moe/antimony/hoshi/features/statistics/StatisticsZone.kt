package moe.antimony.hoshi.features.statistics

import moe.antimony.hoshi.epub.zoneOrNull
import moe.antimony.hoshi.features.reader.ReaderSettings
import moe.antimony.hoshi.features.reader.STATISTICS_DEFAULT_TIME_ZONE
import java.time.ZoneId

/**
 * The zone every device counts reading days in: the chosen one (Eastern Time unless changed),
 * never the zone a device's clock happens to be set to, so devices agree wherever they are.
 */
fun ReaderSettings.statisticsZone(): ZoneId =
    zoneOrNull(statisticsTimeZone) ?: zoneOrNull(STATISTICS_DEFAULT_TIME_ZONE) ?: ZoneId.systemDefault()
