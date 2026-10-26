package moe.antimony.hoshi.features.anonymousstats

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import moe.antimony.hoshi.features.anonymousstats.AnonymousStatsOutcome.Disabled
import moe.antimony.hoshi.features.anonymousstats.AnonymousStatsOutcome.Failed
import moe.antimony.hoshi.features.anonymousstats.AnonymousStatsOutcome.NotDue
import moe.antimony.hoshi.features.anonymousstats.AnonymousStatsOutcome.Rejected
import moe.antimony.hoshi.features.anonymousstats.AnonymousStatsOutcome.Sent
import moe.antimony.hoshi.features.anonymousstats.AnonymousStatsReporter.Companion.FIRST_REPORT_DELAY_MILLIS
import moe.antimony.hoshi.features.anonymousstats.AnonymousStatsReporter.Companion.RETRY_INTERVAL_MILLIS
import moe.antimony.hoshi.features.anonymousstats.AnonymousStatsReporter.Companion.SEND_INTERVAL_MILLIS
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException
import kotlin.random.Random

class AnonymousStatsReporterTest {
    @get:Rule val temp = TemporaryFolder()

    private val installs = mutableListOf<StatsInstall>()

    @After
    fun closeInstalls() {
        installs.forEach(StatsInstall::close)
    }

    @Test
    fun theFirstReportWaitsADayAfterTheFeatureFirstRuns() = runBlocking {
        val app = install()
        val transport = ScriptedTransport()
        val reporter = app.reporter(transport)
        val firstRun = app.clock

        assertEquals(NotDue, reporter.reportIfDue())
        // Nothing identifies the install yet: only when the wait ends.
        assertNull(app.install.installId())
        assertEquals(firstRun + FIRST_REPORT_DELAY_MILLIS, app.install.state()?.notBefore)

        app.clock += FIRST_REPORT_DELAY_MILLIS - 1
        assertEquals(NotDue, reporter.reportIfDue())
        assertTrue(transport.bodies.isEmpty())
        assertEquals(0, app.collected)

        app.clock += 1
        assertEquals(Sent, reporter.reportIfDue())
        assertEquals(1, transport.bodies.size)
    }

    @Test
    fun reportsAtMostOncePerTwentyHours() = runBlocking {
        val app = install()
        val transport = ScriptedTransport()
        val reporter = app.reporter(transport)
        app.passFirstDay(reporter)

        assertEquals(Sent, reporter.reportIfDue())
        app.clock += SEND_INTERVAL_MILLIS - 1
        assertEquals(NotDue, reporter.reportIfDue())
        assertEquals(1, transport.bodies.size)

        app.clock += 1
        assertEquals(Sent, reporter.reportIfDue())
        assertEquals(2, transport.bodies.size)
        // Both reports come from the same installation.
        assertEquals(1, transport.bodies.map { it.installId() }.toSet().size)
    }

    @Test
    fun aSuccessfulReportRecordsWhenItWasSent() = runBlocking {
        val app = install()
        val reporter = app.reporter(ScriptedTransport())
        app.passFirstDay(reporter)

        reporter.reportIfDue()

        assertEquals(app.clock, app.install.state()?.lastSentAt)
        assertNull(app.install.state()?.lastAttemptAt)
    }

    @Test
    fun networkFailuresTimeoutsThrottlingAndServerErrorsRetryAfterAnHour() = runBlocking {
        val app = install()
        val transport = ScriptedTransport({ throw IOException("offline") }, { 408 }, { 429 }, { 500 }, { 503 }, { 204 })
        app.passFirstDay(app.reporter(transport))

        repeat(5) {
            assertEquals(Failed, app.reporter(transport).reportIfDue())
            assertNull(app.install.state()?.lastSentAt)
            assertEquals(app.clock, app.install.state()?.lastAttemptAt)
            // Later launches within the hour neither build nor send a report.
            val collected = app.collected
            app.clock += RETRY_INTERVAL_MILLIS - 1
            assertEquals(NotDue, app.reporter(transport).reportIfDue())
            assertEquals(collected, app.collected)
            app.clock += 1
        }

        assertEquals(Sent, app.reporter(transport).reportIfDue())
        assertEquals(6, transport.bodies.size)
        assertEquals(1, transport.bodies.map { it.installId() }.toSet().size)
    }

    @Test
    fun refusalsThatARetryCannotFixCountAsSent() = runBlocking {
        listOf(301, 302, 400, 401, 403, 404, 410, 413, 422).forEach { status ->
            val app = install()
            val transport = ScriptedTransport({ status })
            val reporter = app.reporter(transport)
            app.passFirstDay(reporter)

            assertEquals("HTTP $status", Rejected, reporter.reportIfDue())
            assertEquals("HTTP $status", app.clock, app.install.state()?.lastSentAt)
            app.clock += SEND_INTERVAL_MILLIS - 1
            assertEquals("HTTP $status", NotDue, reporter.reportIfDue())
            assertEquals("HTTP $status", 1, transport.bodies.size)
        }
    }

    @Test
    fun aReportThatCannotBeBuiltBacksOffWithoutSending() = runBlocking {
        val app = install()
        val transport = ScriptedTransport()
        val reporter = app.reporter(transport, collect = { error("unreadable library") })
        app.passFirstDay(reporter)

        assertEquals(Failed, reporter.reportIfDue())
        assertTrue(transport.bodies.isEmpty())
        assertNull(app.install.state()?.lastSentAt)
        assertEquals(app.clock, app.install.state()?.lastAttemptAt)
    }

    @Test
    fun turnedOffMeansNoReportAndNoTrace() = runBlocking {
        val app = install()
        val transport = ScriptedTransport()
        val reporter = app.reporter(transport)
        app.passFirstDay(reporter)
        app.settings.update { it.copy(enabled = false) }

        assertEquals(Disabled, reporter.reportIfDue())
        assertTrue(transport.bodies.isEmpty())
        assertTrue(app.storedFiles().isEmpty())
    }

    @Test
    fun testAutomationNeverReports() = runBlocking {
        val app = install()
        val transport = ScriptedTransport()

        assertEquals(AnonymousStatsOutcome.AutomatedRun, app.reporter(transport, automated = true).reportIfDue())
        assertTrue(transport.bodies.isEmpty())
        assertTrue(app.storedFiles().isEmpty())
    }

    @Test
    fun aForegroundReportWaitsBeforeBuildingAndSendingIt() = runBlocking {
        val app = install()
        val transport = ScriptedTransport()
        val waited = CompletableDeferred<Unit>()
        val reporter = app.reporter(transport, waitBeforeReport = { waited.await() })
        app.passFirstDay(reporter)

        val job = reporter.onForeground()
        // Coming back again while it waits does not queue a second report.
        assertSame(job, reporter.onForeground())
        assertEquals(0, app.collected)
        assertTrue(transport.bodies.isEmpty())

        waited.complete(Unit)
        job.join()

        assertEquals(1, transport.bodies.size)
        assertEquals(app.clock, app.install.state()?.lastSentAt)
    }

    @Test
    fun aProcessThatDiesWhileWaitingReportsOnTheNextLaunch() = runBlocking {
        val app = install()
        val transport = ScriptedTransport()
        app.passFirstDay(app.reporter(transport))
        val firstLaunch = CoroutineScope(Dispatchers.IO + Job())

        app.reporter(transport, scope = firstLaunch, waitBeforeReport = { CompletableDeferred<Unit>().await() }).onForeground()
        firstLaunch.coroutineContext[Job]!!.cancelAndJoin()
        assertTrue(transport.bodies.isEmpty())
        assertNull(app.install.state()?.lastSentAt)

        app.reporter(transport).onForeground().join()

        assertEquals(1, transport.bodies.size)
    }

    @Test
    fun theStartDelayIsBetweenOneAndFiveMinutes() {
        val random = Random(42)
        val delays = List(10_000) { randomStartDelayMillis(random) }

        assertTrue(delays.all { it in 60_000L..300_000L })
        assertTrue(delays.min() < 70_000 && delays.max() > 290_000)
    }

    @Test
    fun theReportMatchesTheServerContract() = runBlocking {
        val app = install()
        val transport = ScriptedTransport()
        val reporter = app.reporter(transport)
        app.passFirstDay(reporter)

        reporter.reportIfDue()

        // Fixed endpoint: never the user's HTTP sync server.
        assertEquals("https://dragos.games/api/book_sync/v1/stats", transport.urls.single())
        val json = Json.parseToJsonElement(transport.bodies.single()).jsonObject
        assertEquals(CONTRACT_KEYS, json.keys)
        assertTrue(json.getValue("installId").jsonPrimitive.content.matches(UUID_V4))
        assertEquals(app.install.installId(), json.getValue("installId").jsonPrimitive.content)
        assertEquals("android", json.getValue("platform").jsonPrimitive.content)
        val strings = setOf("installId", "platform", "appVersion", "buildType", "osVersion", "manufacturer", "model", "locale", "appLanguage")
        val booleans = setOf("emulator", "httpSyncEnabled", "driveSyncEnabled", "readerContinuous", "readerVertical")
        json.forEach { (key, value) ->
            val primitive = value as JsonPrimitive
            when (key) {
                in strings -> assertTrue("$key is a string", primitive.isString)
                in booleans -> assertTrue("$key is a boolean", !primitive.isString && primitive.booleanOrNull != null)
                else -> assertTrue("$key is an integer", !primitive.isString && primitive.intOrNull != null)
            }
        }
    }

    private fun install() = StatsInstall(temp.newFolder()).also(installs::add)

    private fun String.installId(): String = Json.parseToJsonElement(this).jsonObject.getValue("installId").jsonPrimitive.content
}
