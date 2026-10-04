package moe.antimony.hoshi.features.sync.http

import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import moe.antimony.hoshi.features.usage.UsageEvent
import moe.antimony.hoshi.features.usage.UsageEventType
import moe.antimony.hoshi.features.usage.UsageLog
import moe.antimony.hoshi.features.usage.loadUsageStatistics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.concurrent.Executors

/** Lookups, bubbles and reading spans from every device count on every device. */
class HttpSyncUsageLaneTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    private val newYork = ZoneId.of("America/New_York")
    private val today = LocalDate.parse("2026-10-04")
    private var nowMs = 1_000_000L
    private val json = Json { explicitNulls = false }

    private inner class Device(val id: String) {
        val directory: File = temporaryFolder.newFolder(id)
        val log = UsageLog(directory, zone = { newYork }, dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher())
        var lane = HttpSyncUsageLane(log, now = { nowMs }, today = { today })

        fun write(date: String, vararg events: UsageEvent) {
            File(directory, "$date.ndjson").appendText(events.joinToString("") { json.encodeToString(UsageEvent.serializer(), it) + "\n" })
        }
    }

    private fun lookup(at: String, term: String) = UsageEvent(
        at = LocalDateTime.parse(at).atZone(newYork).toInstant().toEpochMilli(), utcOffset = "-04:00",
        type = UsageEventType.WordLookedUp, session = "s-$term", bookTitle = "Book", term = term, outcome = "found",
    )

    private fun bubble(at: String) = UsageEvent(
        at = LocalDateTime.parse(at).atZone(newYork).toInstant().toEpochMilli(), utcOffset = "-04:00",
        type = UsageEventType.BubbleRevealed, session = "b", bookTitle = "Manga",
    )

    @Test
    fun everyDevicesLookupsAndBubblesCountEverywhereAndEachWritesOnlyItsOwnKeys() = runBlocking {
        val server = ContentAddressedKv()
        val phone = Device("phone")
        val tablet = Device("tablet")
        phone.write("2026-10-03", lookup("2026-10-03T21:00", "天気"), lookup("2026-10-03T21:05", "食べる"))
        tablet.write("2026-10-03", lookup("2026-10-03T22:00", "天気"), bubble("2026-10-03T22:01"))

        phone.lane.run(server, server.listing(), "phone", flush = false, retryFailed = false)
        tablet.lane.run(server, server.listing(), "tablet", flush = false, retryFailed = false)
        phone.lane.run(server, server.listing(), "phone", flush = false, retryFailed = false)

        assertEquals(
            setOf(usageDayKey("phone", LocalDate.parse("2026-10-03")), usageDayKey("tablet", LocalDate.parse("2026-10-03"))),
            server.keys().filter { it.startsWith(USAGE_PREFIX) }.toSet(),
        )
        for (device in listOf(phone, tablet)) {
            val usage = loadUsageStatistics(device.log, LocalDate.parse("2026-10-03"), historyDays = 1, zone = newYork, resetHour = 3)
            assertEquals("${device.id} sees every device's lookups", 3, usage.today.wordLookups)
            assertEquals(1, usage.today.bubblesRevealed)
            assertEquals("天気 was looked up on both devices", listOf("天気"), usage.today.repeatedWords.map { it.word })
        }
        assertFalse(phone.lane.needsRun(server, server.listing(), "phone"))
        assertFalse(tablet.lane.needsRun(server, server.listing(), "tablet"))
    }

    @Test
    fun todayGoesUpOnItsCadenceAndAtOnceOnAFlush() = runBlocking {
        val server = ContentAddressedKv()
        val phone = Device("phone")
        phone.write("2026-10-04", lookup("2026-10-04T10:00", "猫"))
        phone.lane.run(server, server.listing(), "phone", flush = true, retryFailed = false)
        assertEquals(1, server.puts)

        // A new lookup a minute later waits for the two-minute cadence, unless flushed.
        nowMs += 60_000
        phone.write("2026-10-04", lookup("2026-10-04T10:01", "犬"))
        assertFalse(phone.lane.needsRun(server, server.listing(), "phone"))
        phone.lane.run(server, server.listing(), "phone", flush = false, retryFailed = false)
        assertEquals(1, server.puts)
        phone.lane.run(server, server.listing(), "phone", flush = true, retryFailed = false)
        assertEquals(2, server.puts)

        // A past day that changes goes up without waiting.
        phone.write("2026-10-02", lookup("2026-10-02T10:00", "鳥"))
        assertTrue(phone.lane.needsRun(server, server.listing(), "phone"))
    }

    @Test
    fun aReinstalledDeviceTakesBackItsDayBeforeSendingIt() = runBlocking {
        val server = ContentAddressedKv()
        val before = Device("phone")
        before.write("2026-10-04", lookup("2026-10-04T09:00", "朝"))
        before.lane.run(server, server.listing(), "phone", flush = true, retryFailed = false)

        // Reinstalled: same device id, empty log, one new lookup today.
        val after = Device("phone-reinstalled")
        after.write("2026-10-04", lookup("2026-10-04T11:00", "昼"))
        after.lane.run(server, server.listing(), "phone", flush = true, retryFailed = false)

        val stored = HttpSyncUsageLane.gunzip(server.body(usageDayKey("phone", today))).toString(Charsets.UTF_8)
        assertTrue("the earlier install's lookup is kept", "朝" in stored)
        assertTrue("昼" in stored)
        assertEquals(2, after.log.eventsOn(today).size)
    }

    @Test
    fun aMonthThatIsOverGoesUpAsOneKeyAndItsDayKeysAreRemoved() = runBlocking {
        val server = ContentAddressedKv()
        val phone = Device("phone")
        val tablet = Device("tablet")
        // September's days went up as day keys while September was the current month.
        phone.write("2026-09-29", lookup("2026-09-29T21:00", "雨"))
        phone.write("2026-09-30", lookup("2026-09-30T21:00", "雪"))
        phone.lane = HttpSyncUsageLane(phone.log, now = { nowMs }, today = { LocalDate.parse("2026-09-30") })
        phone.lane.run(server, server.listing(), "phone", flush = true, retryFailed = false)
        tablet.lane.run(server, server.listing(), "tablet", flush = false, retryFailed = false)
        assertEquals(2, server.keys().count { it.startsWith(USAGE_PREFIX) })

        // October: September goes up as one key and its day keys are removed.
        phone.lane = HttpSyncUsageLane(phone.log, now = { nowMs }, today = { today })
        assertTrue(phone.lane.needsRun(server, server.listing(), "phone"))
        phone.lane.run(server, server.listing(), "phone", flush = false, retryFailed = false)
        phone.lane.run(server, server.listing(), "phone", flush = false, retryFailed = false)
        assertEquals(setOf("sync/maps/usage/phone/2026-09.ndjson.gz"), server.keys().filter { it.startsWith(USAGE_PREFIX) }.toSet())
        assertFalse(phone.lane.needsRun(server, server.listing(), "phone"))

        // Another device keeps every day, also one that only ever arrives in the month's key.
        phone.write("2026-09-15", lookup("2026-09-15T21:00", "風"))
        phone.lane.run(server, server.listing(), "phone", flush = false, retryFailed = false)
        tablet.lane.run(server, server.listing(), "tablet", flush = false, retryFailed = false)
        for (date in listOf("2026-09-15", "2026-09-29", "2026-09-30")) {
            assertEquals(date, 1, tablet.log.eventsOn(LocalDate.parse(date)).size)
        }
        assertFalse(tablet.lane.needsRun(server, server.listing(), "tablet"))
    }

    @Test
    fun aDayTheServerLostIsSentAgainAndAVanishedKeyDoesNotRunEveryPoll() = runBlocking {
        val server = ContentAddressedKv()
        val phone = Device("phone")
        phone.write("2026-10-02", lookup("2026-10-02T21:00", "月"))
        phone.lane.run(server, server.listing(), "phone", flush = true, retryFailed = false)
        val key = usageDayKey("phone", LocalDate.parse("2026-10-02"))

        // The server was wiped: the day goes up again.
        server.delete(key)
        phone.lane = HttpSyncUsageLane(phone.log, now = { nowMs }, today = { today })
        assertTrue(phone.lane.needsRun(server, server.listing(), "phone"))
        phone.lane.run(server, server.listing(), "phone", flush = false, retryFailed = false)
        assertTrue(key in server.keys())

        // Another device's key a stale listing still shows: one try, then a quiet pause.
        server.listGhost(usageDayKey("tablet", LocalDate.parse("2026-10-01")))
        assertTrue(phone.lane.needsRun(server, server.listing(), "phone"))
        assertTrue(phone.lane.run(server, server.listing(), "phone", flush = false, retryFailed = false).isEmpty())
        assertFalse(phone.lane.needsRun(server, server.listing(), "phone"))
    }

    @Test
    fun theSameDayAlwaysHasTheSameBody() {
        val bytes = "{\"at\":1}\n".toByteArray()
        assertEquals(HttpSyncUsageLane.gzip(bytes).toList(), HttpSyncUsageLane.gzip(bytes).toList())
    }
}
