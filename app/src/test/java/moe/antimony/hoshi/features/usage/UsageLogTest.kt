package moe.antimony.hoshi.features.usage

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.LocalDate
import java.time.ZoneId

class UsageLogTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val tokyo = ZoneId.of("Asia/Tokyo")
    private var now = LocalDate.parse("2026-09-23").atTime(23, 59, 30).atZone(tokyo).toInstant().toEpochMilli()

    private fun log(directory: File = folder.root) = UsageLog(directory, clock = { now }, zone = { tokyo })

    @Test
    fun eventsLandInTheFileOfTheirLocalDayWithTheirUtcOffset() = runBlocking {
        val log = log()
        log.append(log.newEvent(UsageEventType.ReaderOpened).copy(bookTitle = "Before midnight"))
        now += 60_000
        log.append(log.newEvent(UsageEventType.PageTurned).copy(fromPage = 1, toPage = 2))

        val before = log.eventsOn(LocalDate.parse("2026-09-23"))
        val after = log.eventsOn(LocalDate.parse("2026-09-24"))

        assertEquals(listOf(UsageEventType.ReaderOpened), before.map { it.type })
        assertEquals("+09:00", before.single().utcOffset)
        assertEquals(listOf(UsageEventType.PageTurned), after.map { it.type })
        assertEquals(listOf("2026-09-23.ndjson", "2026-09-24.ndjson"), log.dayFiles().map { it.name })
    }

    @Test
    fun linesOnlyCarryTheFieldsThatWereSet() = runBlocking {
        val log = log()
        log.append(log.newEvent(UsageEventType.WordLookedUp).copy(text = "食べ", term = "食べる", outcome = "found"))
        log.eventsOn(LocalDate.parse("2026-09-23"))

        val line = File(folder.root, "2026-09-23.ndjson").readLines().single()
        assertTrue(line.contains("\"type\":\"word-looked-up\""))
        assertTrue(line.contains("\"term\":\"食べる\""))
        assertFalse(line.contains("null"))
        assertFalse(line.contains("fromPage"))
    }

    @Test
    fun aDamagedLineIsSkippedAndTheRestOfTheDaySurvives() = runBlocking {
        val log = log()
        log.append(log.newEvent(UsageEventType.ReaderOpened))
        log.eventsOn(LocalDate.parse("2026-09-23"))
        File(folder.root, "2026-09-23.ndjson").appendText("{\"at\":12,\"type\":\"reader-op\n")
        now += 1_000
        log.append(log.newEvent(UsageEventType.ReaderClosed))

        val events = log.eventsOn(LocalDate.parse("2026-09-23"))

        assertEquals(listOf(UsageEventType.ReaderOpened, UsageEventType.ReaderClosed), events.map { it.type })
    }

    @Test
    fun anEventAfterALineCutShortStartsOnItsOwnLine() = runBlocking {
        File(folder.root, "2026-09-23.ndjson").writeText("{\"at\":1,\"type\":\"reader-op")
        val log = log()
        log.append(log.newEvent(UsageEventType.ReaderClosed))

        val events = log.eventsOn(LocalDate.parse("2026-09-23"))

        assertEquals(listOf(UsageEventType.ReaderClosed), events.map { it.type })
    }

    @Test
    fun theAppSharesOneLogPerFolder() {
        val folderA = File(folder.root, "a")
        assertTrue(UsageLog.forDirectory(folderA) === UsageLog.forDirectory(File(folderA.path)))
        assertFalse(UsageLog.forDirectory(folderA) === UsageLog.forDirectory(File(folder.root, "b")))
    }

    @Test
    fun aDayWithNoFileHasNoEvents() = runBlocking {
        assertEquals(emptyList<UsageEvent>(), log(File(folder.root, "missing")).eventsOn(LocalDate.parse("2026-01-01")))
    }

    @Test
    fun changesCountEveryWrite() = runBlocking {
        val log = log()
        repeat(3) { log.append(log.newEvent(UsageEventType.PageTurned)) }
        log.eventsOn(LocalDate.parse("2026-09-23"))

        assertEquals(3L, log.changes.value)
    }
}
