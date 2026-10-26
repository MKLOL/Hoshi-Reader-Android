package moe.antimony.hoshi.features.anonymousstats

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import moe.antimony.hoshi.features.anonymousstats.AnonymousStatsOutcome.Disabled
import moe.antimony.hoshi.features.anonymousstats.AnonymousStatsOutcome.NotDue
import moe.antimony.hoshi.features.anonymousstats.AnonymousStatsOutcome.Sent
import moe.antimony.hoshi.features.anonymousstats.AnonymousStatsReporter.Companion.FIRST_REPORT_DELAY_MILLIS
import moe.antimony.hoshi.features.anonymousstats.AnonymousStatsReporter.Companion.SEND_INTERVAL_MILLIS
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AnonymousStatsInstallTest {
    @get:Rule val temp = TemporaryFolder()

    private val installs = mutableListOf<StatsInstall>()

    @After
    fun closeInstalls() {
        installs.forEach(StatsInstall::close)
    }

    @Test
    fun theIdIsCreatedOnceAndKeptAcrossLaunchesAndUpdates() = runBlocking {
        val app = install("data")
        val transport = ScriptedTransport()
        app.passFirstDay(app.reporter(transport))
        app.reporter(transport).reportIfDue()
        val id = app.install.installId()!!

        // A later launch of an updated app: same package install time, same ID.
        app.clock += SEND_INTERVAL_MILLIS
        assertEquals(Sent, app.reporter(transport).reportIfDue())

        assertTrue(id.matches(UUID_V4))
        assertEquals(listOf(id, id), transport.bodies.map { Json.parseToJsonElement(it).jsonObject.getValue("installId").jsonPrimitive.content })
    }

    @Test
    fun turningStatisticsOffDeletesTheIdAndTurningThemOnMakesANewOne() = runBlocking {
        val app = install("data")
        val reporter = app.reporter(ScriptedTransport())
        app.passFirstDay(reporter)
        reporter.reportIfDue()
        val first = app.install.installId()!!

        reporter.setEnabled(false)

        assertFalse(app.settings.settings.first().enabled)
        assertTrue(app.storedFiles().isEmpty())

        reporter.setEnabled(true)

        val second = app.install.installId()!!
        assertTrue(app.settings.settings.first().enabled)
        assertNotEquals(first, second)
        assertTrue(second.matches(UUID_V4))
        // Turned on deliberately: the new ID reports at the next check, without another day's wait.
        assertEquals(Sent, reporter.reportIfDue())
    }

    @Test
    fun turningStatisticsOffWhileTheReportIsBuiltSendsNothing() = runBlocking {
        val app = install("data")
        val transport = ScriptedTransport()
        lateinit var reporter: AnonymousStatsReporter
        reporter = app.reporter(transport, collect = { id ->
            reporter.setEnabled(false)
            anonymousStatsPayload(id, fullDevice(), fullLibrary(), fullFlags())
        })
        app.passFirstDay(reporter)

        assertEquals(Disabled, reporter.reportIfDue())

        assertTrue(transport.bodies.isEmpty())
        assertTrue(app.storedFiles().isEmpty())
    }

    @Test
    fun turningStatisticsOffDuringASendKeepsNoTrace() = runBlocking {
        val app = install("data")
        val started = CompletableDeferred<Unit>()
        val stopped = CompletableDeferred<Unit>()
        val delivered = mutableListOf<String>()
        val serverAnswer = CompletableDeferred<Int>()
        // A request the server has not answered yet: it counts as delivered only if it completes.
        val transport = AnonymousStatsTransport { _, json ->
            started.complete(Unit)
            try {
                serverAnswer.await().also { delivered += json }
            } finally {
                stopped.complete(Unit)
            }
        }
        val reporter = app.reporter(transport)
        app.passFirstDay(reporter)
        val job = reporter.onForeground()
        withTimeout(10_000) { started.await() }

        reporter.setEnabled(false)
        withTimeout(10_000) { job.join() }

        assertTrue(job.isCancelled)
        assertTrue(stopped.isCompleted)
        assertTrue(delivered.isEmpty())
        assertFalse(app.settings.settings.first().enabled)
        assertTrue(app.storedFiles().isEmpty())
    }

    @Test
    fun quickTogglingEndsWithTheSavedSetting() = runBlocking {
        val app = install("data")
        val reporter = app.reporter(ScriptedTransport())

        repeat(40) { round ->
            List(4) { index ->
                launch(Dispatchers.IO) { reporter.setEnabled((round + index) % 2 == 0) }
            }.joinAll()

            val enabled = app.settings.settings.first().enabled
            assertEquals("round $round", enabled, app.install.installId() != null)
        }
    }

    @Test
    fun theIdStaysOutOfBackupsSoARestoredInstallMakesItsOwn() = runBlocking {
        val original = install("original")
        val reporter = original.reporter(ScriptedTransport())
        reporter.setEnabled(true)
        reporter.reportIfDue()
        val originalId = original.install.installId()!!

        // Only noBackupFilesDir, which Auto Backup and device transfer skip, holds the ID.
        val backedUp = original.dataDir.walk().filter { it.isFile && !it.startsWith(original.noBackup) }.toList()
        assertTrue(backedUp.any { it.name == "anonymous-stats-settings.preferences_pb" })
        assertTrue(backedUp.none { it.readBytes().toString(Charsets.ISO_8859_1).contains(originalId) })
        assertTrue(original.storedFiles().any { it.readText().contains(originalId) })

        val restored = original.restoreOnto(temp.newFolder("restored"), installedAt = StatsInstall.INSTALLED_AT + 1).also(installs::add)
        val restoredReporter = restored.reporter(ScriptedTransport())

        assertNull(restored.install.installId())
        assertEquals(NotDue, restoredReporter.reportIfDue())
        restored.clock += FIRST_REPORT_DELAY_MILLIS
        assertEquals(Sent, restoredReporter.reportIfDue())
        assertNotEquals(originalId, restored.install.installId())
    }

    @Test
    fun aRestoredOptOutStaysOff() = runBlocking {
        val original = install("original")
        original.reporter(ScriptedTransport()).setEnabled(false)

        val restored = original.restoreOnto(temp.newFolder("restored"), installedAt = StatsInstall.INSTALLED_AT + 1).also(installs::add)

        assertEquals(Disabled, restored.reporter(ScriptedTransport()).reportIfDue())
        assertTrue(restored.storedFiles().isEmpty())
    }

    @Test
    fun aPhoneCloneThatCopiesNoBackupFilesStillGetsItsOwnId() = runBlocking {
        val original = install("original")
        val reporter = original.reporter(ScriptedTransport())
        original.passFirstDay(reporter)
        reporter.reportIfDue()
        val originalId = original.install.installId()!!

        // Clone tools copy the whole data folder, no_backup included, onto a fresh install.
        val cloneDir = temp.newFolder("clone")
        original.dataDir.copyRecursively(cloneDir, overwrite = true)
        val clone = StatsInstall(cloneDir, installedAt = StatsInstall.INSTALLED_AT + 86_400_000).also(installs::add)
        clone.clock = original.clock + SEND_INTERVAL_MILLIS
        val transport = ScriptedTransport()
        val cloneReporter = clone.reporter(transport)

        assertEquals(originalId, clone.install.installId())
        assertEquals(NotDue, cloneReporter.reportIfDue())
        assertNull(clone.install.installId())
        clone.clock += FIRST_REPORT_DELAY_MILLIS
        assertEquals(Sent, cloneReporter.reportIfDue())
        assertNotEquals(originalId, clone.install.installId())
        assertFalse(transport.bodies.single().contains(originalId))
    }

    private fun install(name: String) = StatsInstall(temp.newFolder(name)).also(installs::add)
}
