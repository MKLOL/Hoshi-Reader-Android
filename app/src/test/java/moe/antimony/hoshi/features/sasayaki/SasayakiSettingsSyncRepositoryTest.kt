package moe.antimony.hoshi.features.sasayaki

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import moe.antimony.hoshi.features.reader.ReaderSettingRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.UUID

class SasayakiSettingsSyncRepositoryTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun freshInstallContributesNoDefaultsAndReusesFallbackIdentity() = runBlocking {
        repository(deviceId = "").use { handle ->
            val snapshot = handle.repository.snapshot()
            assertTrue(snapshot.deviceId.isNotBlank())
            assertTrue(snapshot.records.isEmpty())
            assertEquals(SasayakiSettings(), handle.repository.settings.first())
            val reopened = SasayakiSettingsRepository(handle.dataStore)
            assertEquals(snapshot, reopened.snapshot())
        }
    }

    @Test
    fun existingNondefaultsSeedAtBaselineWithoutTransportOrStoragePreferences() = runBlocking {
        val existing = SasayakiSettings(
            enabled = false,
            syncEnabled = true,
            showReaderToggle = false,
            readerSkipButtonAction = SasayakiReaderSkipButtonAction.Seconds15,
            copyAudiobookToPrivateStorage = true,
            darkTextColor = 0xFF123456,
        )
        repository(legacy = existing).use { handle ->
            val snapshot = handle.repository.snapshot()
            assertEquals(existing, handle.repository.settings.first())
            assertEquals(
                setOf("sasayaki.enabled", "sasayaki.showReaderToggle", "sasayaki.readerSkipButtonAction", "sasayaki.darkTextColor"),
                snapshot.records.keys,
            )
            assertTrue(snapshot.records.values.all { it.editedAt == 0L && it.deviceId == "device-a" })
        }
    }

    @Test
    fun onlyActualReaderChoiceChangesStampAndNotifyAfterCommit() = runBlocking {
        repository(nowMillis = { 100L }).use { handle ->
            val repo = handle.repository
            var notified = 0
            repo.onSyncRelevantEdit = { notified += 1 }
            repo.update { it }
            repo.update { it.copy(syncEnabled = true, copyAudiobookToPrivateStorage = true) }
            assertEquals(0, notified)
            assertTrue(repo.snapshot().records.isEmpty())

            repo.update { it.copy(showReaderToggle = false) }
            assertEquals(1, notified)
            assertFalse(repo.settings.first().showReaderToggle)
            assertEquals(
                mapOf("sasayaki.showReaderToggle" to record(false, 100L, "device-a")),
                repo.snapshot().records,
            )
            repo.update { it.copy(showReaderToggle = false) }
            assertEquals(1, notified)
        }
    }

    @Test
    fun everyReaderChoiceRoundTripsWhileDestinationTransportPreferencesStayLocal() = runBlocking {
        val chosen = SasayakiSettings(
            enabled = false,
            syncEnabled = true,
            showReaderToggle = false,
            showReaderBottomPlaybackControls = false,
            readerSkipButtonAction = SasayakiReaderSkipButtonAction.Seconds30,
            reverseVerticalReaderSkipButtons = true,
            copyAudiobookToPrivateStorage = true,
            autoScroll = false,
            autoPause = false,
            lightTextColor = 0xFF010203,
            lightBackgroundColor = 0x44040506,
            darkTextColor = 0xFF070809,
            darkBackgroundColor = 0xAA0A0B0C,
        )
        repository().use { source ->
            repository(deviceId = "device-b").use { destination ->
                source.repository.update { chosen }
                var remoteEchoes = 0
                destination.repository.onSyncRelevantEdit = { remoteEchoes += 1 }
                assertTrue(destination.repository.merge(source.repository.snapshot().records))
                assertEquals(
                    chosen.copy(syncEnabled = false, copyAudiobookToPrivateStorage = false),
                    destination.repository.settings.first(),
                )
                assertEquals(11, destination.repository.snapshot().records.size)
                assertEquals(0, remoteEchoes)
                assertFalse(destination.repository.merge(source.repository.snapshot().records))
            }
        }
    }

    @Test
    fun independentOfflineChoicesSurviveAndExplicitResetToDefaultWins() = runBlocking {
        var clock = 100L
        repository(deviceId = "device-a", nowMillis = { clock }).use { a ->
            repository(deviceId = "device-b", nowMillis = { clock }).use { b ->
                a.repository.update { it.copy(showReaderToggle = false) }
                b.repository.update { it.copy(autoPause = false) }
                val fromA = a.repository.snapshot().records
                val fromB = b.repository.snapshot().records
                a.repository.merge(fromB)
                b.repository.merge(fromA)
                assertEquals(a.repository.settings.first(), b.repository.settings.first())
                assertFalse(a.repository.settings.first().showReaderToggle)
                assertFalse(a.repository.settings.first().autoPause)

                clock = 200L
                a.repository.update { it.copy(showReaderToggle = true) }
                b.repository.merge(a.repository.snapshot().records)
                assertTrue(b.repository.settings.first().showReaderToggle)
                assertFalse(b.repository.settings.first().autoPause)
                assertEquals(record(true, 200L, "device-a"), b.repository.snapshot().records["sasayaki.showReaderToggle"])
            }
        }
    }

    @Test
    fun equalTimestampConflictsUseOriginTieBreakInEitherMergeOrder() = runBlocking {
        repository(deviceId = "device-a", nowMillis = { 100L }).use { a ->
            repository(deviceId = "device-b", nowMillis = { 100L }).use { b ->
                a.repository.update { it.copy(readerSkipButtonAction = SasayakiReaderSkipButtonAction.Seconds5) }
                b.repository.update { it.copy(readerSkipButtonAction = SasayakiReaderSkipButtonAction.Seconds10) }
                val fromA = a.repository.snapshot().records
                val fromB = b.repository.snapshot().records
                a.repository.merge(fromB)
                assertFalse(b.repository.merge(fromA))
                assertEquals(SasayakiReaderSkipButtonAction.Seconds10, a.repository.settings.first().readerSkipButtonAction)
                assertEquals(a.repository.snapshot().records, b.repository.snapshot().records)
            }
        }
    }

    @Test
    fun partialRemoteMergeKeepsUnmentionedChoicesAndNextLocalEditBeatsKnownClock() = runBlocking {
        repository(nowMillis = { 1L }).use { handle ->
            val repo = handle.repository
            repo.update { it.copy(autoPause = false) }
            repo.merge(mapOf("sasayaki.showReaderToggle" to record(false, 500L, "device-b")))
            assertFalse(repo.settings.first().autoPause)
            repo.update { it.copy(showReaderToggle = true) }
            assertEquals(record(true, 501L, "device-a"), repo.snapshot().records["sasayaki.showReaderToggle"])
            assertEquals(record(false, 1L, "device-a"), repo.snapshot().records["sasayaki.autoPause"])
        }
    }

    @Test
    fun invalidRemoteValuesFailBeforeApplyingAnyPartOfTheJournal() = runBlocking {
        repository().use { handle ->
            val repo = handle.repository
            val invalid = mapOf(
                "sasayaki.showReaderToggle" to ReaderSettingRecord(JsonPrimitive("false"), 100L, "device-b"),
                "sasayaki.lightTextColor" to ReaderSettingRecord(JsonPrimitive(-1L), 100L, "device-b"),
                "sasayaki.darkTextColor" to ReaderSettingRecord(JsonPrimitive(0x100000000L), 100L, "device-b"),
                "sasayaki.readerSkipButtonAction" to ReaderSettingRecord(JsonPrimitive("unrecognized"), 100L, "device-b"),
                "sasayaki.enabled" to record(false, Long.MAX_VALUE, "device-b"),
                "sasayaki.autoScroll" to record(false, -1L, "device-b"),
                "sasayaki.autoPause" to record(false, 100L, "invalid/origin"),
                "sasayaki.syncEnabled" to record(true, 100L, "device-b"),
                "sasayaki.copyAudiobookToPrivateStorage" to record(true, 100L, "device-b"),
                "sasayaki.futureClock" to record(true, 100L + 86_400_001L, "device-b"),
            )
            invalid.forEach { (name, invalidRecord) ->
                val mixed = linkedMapOf("sasayaki.showReaderBottomPlaybackControls" to record(false, 100L, "device-b"))
                mixed[name] = invalidRecord
                assertTrue("Expected rejection for $name", runCatching { repo.merge(mixed) }.isFailure)
                assertEquals(SasayakiSettings(), repo.settings.first())
                assertTrue(repo.snapshot().records.isEmpty())
            }
        }
    }

    @Test
    fun futureNamespaceFieldsSurviveWithoutOwningOtherReaderPreferences() = runBlocking {
        repository().use { handle ->
            val repo = handle.repository
            val future = ReaderSettingRecord(JsonPrimitive("future-value"), 100L, "device-b")
            assertTrue(repo.merge(mapOf("sasayaki.futurePlaybackMode" to future, "mangaShowFurigana" to record(false, 100L, "device-b"))))
            assertEquals(SasayakiSettings(), repo.settings.first())
            assertEquals(mapOf("sasayaki.futurePlaybackMode" to future), repo.snapshot().records)
            repo.update { it.copy(autoPause = false) }
            assertEquals(future, repo.snapshot().records["sasayaki.futurePlaybackMode"])
        }
    }

    @Test
    fun corruptLocalSyncJournalIsNotSilentlyResetOrAppliedOver() = runBlocking {
        repository().use { handle ->
            val repo = handle.repository
            repo.snapshot()
            val journalKey = stringPreferencesKey("sasayakiReaderSettingsSyncJournal")
            handle.dataStore.edit { it[journalKey] = "not-valid-json" }
            assertTrue(runCatching { repo.snapshot() }.isFailure)
            assertTrue(runCatching { repo.merge(mapOf("sasayaki.autoPause" to record(false, 100L, "device-b"))) }.isFailure)
            assertEquals("not-valid-json", handle.dataStore.data.first()[journalKey])
            assertTrue(repo.settings.first().autoPause)
        }
    }

    @Test
    fun colorsAcceptTransparentAndUnsignedArgbLimits() = runBlocking {
        repository().use { handle ->
            handle.repository.merge(
                mapOf(
                    "sasayaki.lightTextColor" to ReaderSettingRecord(JsonPrimitive(0L), 100L, "device-b"),
                    "sasayaki.darkBackgroundColor" to ReaderSettingRecord(JsonPrimitive(0xFFFFFFFFL), 100L, "device-b"),
                ),
            )
            assertEquals(0L, handle.repository.settings.first().lightTextColor)
            assertEquals(0xFFFFFFFFL, handle.repository.settings.first().darkBackgroundColor)
        }
    }

    private fun record(value: Boolean, editedAt: Long, deviceId: String) =
        ReaderSettingRecord(JsonPrimitive(value), editedAt, deviceId)

    private fun repository(
        deviceId: String = "device-a",
        nowMillis: () -> Long = { 100L },
        legacy: SasayakiSettings? = null,
    ): RepositoryHandle {
        val scope = CoroutineScope(Dispatchers.IO + Job())
        val dataStore = PreferenceDataStoreFactory.create(
            scope = scope,
            produceFile = { tempFolder.newFile("sasayaki-${UUID.randomUUID()}.preferences_pb") },
        )
        return RepositoryHandle(
            repository = SasayakiSettingsRepository(
                dataStore = dataStore,
                legacySource = legacy?.let { object : SasayakiSettingsLegacySource { override fun load() = it } },
                syncDeviceId = deviceId,
                nowMillis = nowMillis,
            ),
            dataStore = dataStore,
            scope = scope,
        )
    }

    private class RepositoryHandle(
        val repository: SasayakiSettingsRepository,
        val dataStore: DataStore<Preferences>,
        private val scope: CoroutineScope,
    ) : AutoCloseable {
        override fun close() = scope.cancel()
    }
}
