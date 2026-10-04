package moe.antimony.hoshi.features.statistics

import moe.antimony.hoshi.epub.ContentType
import moe.antimony.hoshi.epub.ReadingStatistics
import moe.antimony.hoshi.epub.deduplicateReadingStatistics
import moe.antimony.hoshi.epub.recordedZone
import moe.antimony.hoshi.features.reader.BookStatisticsInput
import moe.antimony.hoshi.features.reader.summarizeReadingStatistics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * Devices whose clocks are set to different zones must still agree on Today and the streak.
 * The fixture is the reading history on the sync server on 2026-10-03: a phone in New York and
 * a tablet left on China time (UTC+8, twelve hours ahead, which a 12-hour clock without AM/PM
 * does not show). The tablet's 20 minutes just after midnight in New York were recorded as
 * noon, so the phone showed them as today and both devices showed a one-day streak.
 */
class StatisticsTimeZoneTest {
    private val newYork = ZoneId.of("America/New_York")
    private val shanghai = ZoneId.of("Asia/Shanghai")

    private fun entry(title: String, date: String, seconds: Double, stamp: Long, device: String, hours: Map<String, Double>) =
        ReadingStatistics(
            title = title, dateKey = date, readingTime = seconds, lastStatisticModified = stamp,
            deviceId = device, readingTimeByHour = hours,
        )

    private val serverHistory = listOf(
        entry("006Yotsubato", "2026-09-12", 3843.757000000161, 1789199185321L, FOLD, mapOf()),
        entry("006Yotsubato", "2026-09-13", 981.528999999988, 1789327571396L, FOLD, mapOf()),
        entry("006Yotsubato", "2026-09-14", 1210.8529999999837, 1789384704625L, FOLD, mapOf()),
        entry("006Yotsubato", "2026-09-15", 754.078999999992, 1789527181068L, FOLD, mapOf()),
        entry("006Yotsubato", "2026-09-16", 635.0249999999949, 1789588514709L, FOLD, mapOf()),
        entry("006Yotsubato", "2026-09-17", 1295.9489999999844, 1789703966113L, FOLD, mapOf()),
        entry("006Yotsubato", "2026-09-18", 1255.4429999999866, 1789788246165L, FOLD, mapOf()),
        entry("006Yotsubato", "2026-09-19", 1143.214999999986, 1789875785441L, FOLD, mapOf()),
        entry("007Yotsubato", "2026-09-19", 222.84100000000038, 1789876028530L, FOLD, mapOf()),
        entry("007Yotsubato", "2026-09-20", 939.5479999999917, 1789931700685L, FOLD, mapOf()),
        entry("台風25号 本州に近い南の海まで来て天気が悪くなりそう", "2026-09-20", 12.314999999999998, 1789920835737L, FOLD, mapOf()),
        entry("羽田空港 飛行機に乗るためのゲートが増える", "2026-09-20", 76.09399999999998, 1789920779414L, FOLD, mapOf()),
        entry("007Yotsubato", "2026-09-21", 720.6149999999946, 1790036685956L, FOLD, mapOf()),
        entry("日本銀行 金利を1.25%ぐらいに上げることを決めた", "2026-09-21", 242.34300000000047, 1789971043329L, FOLD, mapOf()),
        entry("高市総理大臣が新しい内閣をつくった 日本維新の会からも大臣", "2026-09-21", 167.11200000000022, 1790021946273L, FOLD, mapOf()),
        entry("007Yotsubato", "2026-09-22", 2564.9920000000475, 1790098091996L, FOLD, mapOf()),
        entry("015Yotsubato", "2026-09-22", 16.251, 1790078307808L, FOLD, mapOf()),
        entry("007Yotsubato", "2026-09-23", 5232.279000000361, 1790215328156L, FOLD, mapOf()),
        entry("007Yotsubato", "2026-09-24", 610.306999999995, 1790300874386L, FOLD, mapOf()),
        entry("001 The Apothecary Diaries", "2026-09-25", 25.314000000000007, 1790354441897L, FOLD, mapOf()),
        entry("007Yotsubato", "2026-09-25", 7717.555000000151, 1790373799104L, FOLD, mapOf()),
        entry("008Yotsubato", "2026-09-25", 28.393, 1790382655123L, FOLD, mapOf()),
        entry("008Yotsubato", "2026-09-25", 823.7319999999986, 1790382948222L, NOTE, mapOf()),
        entry("008Yotsubato", "2026-09-26", 2819.38500000007, 1790449235110L, FOLD, mapOf()),
        entry("008Yotsubato", "2026-09-26", 647.5179999999988, 1790460148147L, NOTE, mapOf()),
        entry("ふしぎ駄菓子屋銭天堂", "2026-09-26", 31.106, 1790460182290L, NOTE, mapOf()),
        entry("008Yotsubato", "2026-09-27", 1038.554999999988, 1790548911659L, FOLD, mapOf()),
        entry("008Yotsubato", "2026-09-28", 403.0129999999983, 1790654387327L, FOLD, mapOf()),
        entry("008Yotsubato", "2026-09-29", 1235.4569999999826, 1790663250786L, FOLD, mapOf("2026-09-29T02:00" to 1234.6629999999825)),
        entry("008Yotsubato", "2026-09-30", 1970.940999999968, 1790805748885L, FOLD, mapOf("2026-09-30T00:00" to 159.05100000000027, "2026-09-30T01:00" to 1044.7489999999857, "2026-09-30T17:00" to 618.2559999999941, "2026-09-30T18:00" to 148.88500000000022)),
        entry("008Yotsubato", "2026-10-01", 359.3369999999989, 1790906987539L, FOLD, mapOf("2026-10-01T00:00" to 1.01, "2026-10-01T15:00" to 353.701999999999, "2026-10-01T20:00" to 1.322, "2026-10-01T22:00" to 3.303)),
        entry("008Yotsubato", "2026-10-02", 972.5389999999867, 1790914804255L, FOLD, mapOf("2026-10-02T00:00" to 972.5389999999867)),
        entry("008Yotsubato", "2026-10-03", 1162.1389999999924, 1791001743483L, OPPO, mapOf("2026-10-03T12:00" to 1162.1389999999924)),
    )

    private fun inputs(entries: List<ReadingStatistics>) = entries.groupBy { it.title }.map { (title, days) ->
        BookStatisticsInput(bookId = title, title = title, contentType = ContentType.Mokuro, statistics = days)
    }

    private data class Shown(val todaySeconds: Double, val streak: Int)

    private fun shown(zone: ZoneId?, today: LocalDate, entries: List<ReadingStatistics> = serverHistory): Shown {
        val overview = summarizeReadingStatistics(inputs(entries), today.toString(), RESET, zone)
        return Shown(overview.todaySeconds, computeReadingStreak(overview.streakDaily, GOAL, today).currentDays)
    }

    @Test
    fun bothDevicesShowNothingReadTodayAndTheWholeStreak() {
        // 23:23 in New York on Oct 3 (11:23 on Oct 4 on the tablet's clock): days are counted
        // in Eastern Time on both devices.
        val now = LocalDateTime.parse("2026-10-03T23:23").atZone(newYork)
        val today = streakDate(now, RESET)
        assertEquals(Shown(todaySeconds = 0.0, streak = 22), shown(newYork, today))
    }

    @Test
    fun withoutZonesThePhoneSawTheTabletsNightAsTodayAndBothLostTheStreak() {
        // What 0.12.2 showed: the phone 19 min today and a 1-day streak; the tablet, already
        // on Oct 4 by its clock, nothing today and a 1-day streak.
        assertEquals(Shown(todaySeconds = 1162.1389999999924, streak = 1), shown(null, LocalDate.parse("2026-10-03")))
        assertEquals(Shown(todaySeconds = 0.0, streak = 1), shown(null, LocalDate.parse("2026-10-04")))
    }

    @Test
    fun theTabletsUntaggedNightIsFoundToBeChinaTimeAndTheOthersNewYork() {
        val tablet = serverHistory.last()
        assertEquals(ZoneOffset.ofHours(8), tablet.recordedZone(newYork))
        serverHistory.filter { it.readingTimeByHour.isNotEmpty() && it.deviceId == FOLD }.forEach {
            assertEquals("${it.dateKey} was read in New York", newYork, it.recordedZone(newYork))
        }
        assertNull("no hours: nothing to tell the zone by", serverHistory.first().recordedZone(newYork))
    }

    @Test
    fun aTaggedEntryIsCountedInTheStatisticsZone() {
        val night = ReadingStatistics(
            title = "Book", dateKey = "2026-10-03", readingTime = 1200.0, lastStatisticModified = 1L,
            deviceId = OPPO, readingTimeByHour = mapOf("2026-10-03T12:00" to 1200.0), timeZone = shanghai.id,
        )
        val day = readingDays(listOf(night), emptyMap(), RESET, newYork).single()
        assertEquals("noon in Shanghai is 00:00 in New York, still the evening before", "2026-10-02", day.dateKey)
        assertEquals("2026-10-03", readingDays(listOf(night), emptyMap(), RESET, shanghai).single().dateKey)
        // A midnight reset counts calendar days, but still New York's.
        assertEquals("2026-10-03", readingDays(listOf(night), emptyMap(), 0, newYork).single().dateKey)
        val evening = night.copy(readingTimeByHour = mapOf("2026-10-03T09:00" to 1200.0))
        assertEquals("2026-10-02", readingDays(listOf(evening), emptyMap(), 0, newYork).single().dateKey)
        val streak = reconstructStreakHistory(inputs(listOf(night)), RESET, newYork)
        assertEquals(listOf("2026-10-02"), streak.days.map { it.dateKey })
    }

    @Test
    fun aStampInTheLastSecondOfAnHourStillTellsTheRightOffset() {
        val stamp = LocalDateTime.parse("2026-10-03T12:59:59.400").atZone(shanghai).toInstant().toEpochMilli()
        val entry = serverHistory.last().copy(lastStatisticModified = stamp)
        assertEquals(ZoneOffset.ofHours(8), entry.recordedZone(newYork))
        val onTheHour = LocalDateTime.parse("2026-10-03T13:00").atZone(shanghai).toInstant().toEpochMilli()
        assertEquals(ZoneOffset.ofHours(8), entry.copy(lastStatisticModified = onTheHour).recordedZone(newYork))
    }

    @Test
    fun anEntryWhoseStampIsFarFromItsHoursIsLeftAsRecorded() {
        // Stamped two days after its hour (changed later without counting time): no real offset fits.
        val edited = ReadingStatistics(
            title = "Book", dateKey = "2026-10-01", readingTime = 600.0, deviceId = FOLD,
            lastStatisticModified = LocalDateTime.parse("2026-10-03T20:00").toInstant(ZoneOffset.UTC).toEpochMilli(),
            readingTimeByHour = mapOf("2026-10-01T20:00" to 600.0),
        )
        assertNull(edited.recordedZone(newYork))
        assertEquals("2026-10-01", readingDays(listOf(edited), emptyMap(), RESET, newYork).single().dateKey)
    }

    @Test
    fun eachDevicesCharactersFollowThatDevicesOwnTime() {
        // Same calendar date, different reading days: the phone's evening is Oct 3, the
        // tablet's noon on China time is the evening of Oct 2 in New York.
        val phone = ReadingStatistics(
            title = "Book", dateKey = "2026-10-03", readingTime = 3600.0, charactersRead = 1000, lastStatisticModified = 1L,
            deviceId = FOLD, readingTimeByHour = mapOf("2026-10-03T20:00" to 3600.0), timeZone = newYork.id,
        )
        val tablet = ReadingStatistics(
            title = "Book", dateKey = "2026-10-03", readingTime = 1200.0, charactersRead = 5000, lastStatisticModified = 1L,
            deviceId = OPPO, readingTimeByHour = mapOf("2026-10-03T12:00" to 1200.0), timeZone = shanghai.id,
        )
        val input = BookStatisticsInput(bookId = "b", title = "Book", contentType = ContentType.Epub, statistics = listOf(phone, tablet))
        val days = readingDays(input.statistics, input.charactersByDate(), RESET, newYork).associateBy { it.dateKey }
        assertEquals(1000, days.getValue("2026-10-03").characters)
        assertEquals(5000, days.getValue("2026-10-02").characters)
        // A map keyed by date alone still follows every device's time on that date.
        val byDate = readingDays(input.statistics, mapOf("2026-10-03" to 4800), RESET, newYork).associateBy { it.dateKey }
        assertEquals(3600, byDate.getValue("2026-10-03").characters)
        assertEquals(1200, byDate.getValue("2026-10-02").characters)
    }

    @Test
    fun aCopyStrippedOfItsZoneByAnOlderBuildNeverReplacesTheTaggedOne() {
        val tagged = serverHistory.last().copy(timeZone = shanghai.id)
        val stripped = tagged.copy(timeZone = null)
        assertEquals(listOf(tagged), listOf(tagged, stripped).deduplicateReadingStatistics())
        assertEquals(listOf(tagged), listOf(stripped, tagged).deduplicateReadingStatistics())
    }

    private companion object {
        const val RESET = 3
        const val GOAL = 600.0
        const val FOLD = "1e66cbcd-a30a-3a09-ba15-d4d34be9e40b"
        const val OPPO = "d90dfb66-f89e-316c-9a9e-0d09b9243473"
        const val NOTE = "cb3180b1"
    }
}
