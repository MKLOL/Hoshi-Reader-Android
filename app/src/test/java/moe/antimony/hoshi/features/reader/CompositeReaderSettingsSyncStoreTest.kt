package moe.antimony.hoshi.features.reader

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import moe.antimony.hoshi.features.sasayaki.SasayakiSettingsRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class CompositeReaderSettingsSyncStoreTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test
    fun mergesBothRealRepositoriesWithoutEchoingLocalEditHooks() = runBlocking {
        repository("phone").use { phone -> repository("tablet").use { tablet ->
            phone.reader.update { it.copy(mangaShowFurigana = false, dictionaryCard = PopupCardStyle(width = 550.0)) }
            tablet.audio.update { it.copy(showReaderToggle = false) }
            var remoteEchoes = 0
            tablet.reader.onSyncRelevantEdit = { remoteEchoes++ }
            tablet.audio.onSyncRelevantEdit = { remoteEchoes++ }

            assertTrue(tablet.composite.merge(phone.composite.snapshot().records))
            assertTrue(phone.composite.merge(tablet.composite.snapshot().records))

            assertFalse(tablet.reader.settings.first().mangaShowFurigana)
            assertFalse(phone.audio.settings.first().showReaderToggle)
            assertEquals(550.0, tablet.reader.settings.first().dictionaryCard.width)
            assertEquals(phone.composite.snapshot().records, tablet.composite.snapshot().records)
            assertFalse(tablet.composite.merge(phone.composite.snapshot().records))
            assertEquals(0, remoteEchoes)
        } }
    }

    @Test
    fun invalidSasayakiFieldFailsBeforeValidReaderFieldCanApply() = runBlocking {
        repository("phone").use { phone ->
            val mixed = mapOf(
                "mangaShowFurigana" to record(false),
                "sasayaki.showReaderToggle" to ReaderSettingRecord(JsonPrimitive("false"), 100, "remote"),
            )

            assertTrue(runCatching { phone.composite.merge(mixed) }.isFailure)

            assertTrue(phone.reader.settings.first().mangaShowFurigana)
            assertTrue(phone.audio.settings.first().showReaderToggle)
            assertTrue(phone.composite.snapshot().records.isEmpty())
        }
    }

    @Test
    fun invalidReaderFieldFailsBeforeValidSasayakiFieldCanApply() = runBlocking {
        repository("phone").use { phone ->
            val mixed = mapOf(
                "fontSize" to ReaderSettingRecord(JsonPrimitive(500), 100, "remote"),
                "sasayaki.showReaderToggle" to record(false),
            )

            assertTrue(runCatching { phone.composite.merge(mixed) }.isFailure)

            assertEquals(ReaderSettings().fontSize, phone.reader.settings.first().fontSize)
            assertTrue(phone.audio.settings.first().showReaderToggle)
            assertTrue(phone.composite.snapshot().records.isEmpty())
        }
    }

    @Test
    fun snapshotRejectsAccidentallyMismatchedInstallationIdentities() = runBlocking {
        repository("phone", audioDevice = "tablet").use { mismatched ->
            assertTrue(runCatching { mismatched.composite.snapshot() }.isFailure)
        }
    }

    @Test
    fun unknownFieldsStayWithTheirOwningNamespaceAndSurviveBothStores() = runBlocking {
        repository("phone").use { phone ->
            val records = mapOf("futureReader.control" to record(true), "sasayaki.futureControl" to record(false))

            assertTrue(phone.composite.merge(records))

            assertEquals(records, phone.composite.snapshot().records)
            assertEquals(setOf("futureReader.control"), phone.reader.snapshot().records.keys)
            assertEquals(setOf("sasayaki.futureControl"), phone.audio.snapshot().records.keys)
        }
    }

    private fun record(value: Boolean) = ReaderSettingRecord(JsonPrimitive(value), 100, "remote")

    private fun repository(device: String, audioDevice: String = device): Handle {
        val root = temporaryFolder.newFolder()
        val job = Job()
        val scope = CoroutineScope(Dispatchers.IO + job)
        val reader = ReaderSettingsRepository(
            PreferenceDataStoreFactory.create(scope = scope, produceFile = { File(root, "reader.preferences_pb") }),
            syncDeviceId = device, nowMillis = { 100L },
        )
        val audio = SasayakiSettingsRepository(
            PreferenceDataStoreFactory.create(scope = scope, produceFile = { File(root, "audio.preferences_pb") }),
            syncDeviceId = audioDevice, nowMillis = { 100L },
        )
        return Handle(reader, audio, CompositeReaderSettingsSyncStore(reader, audio) { 100L }, job)
    }

    private class Handle(
        val reader: ReaderSettingsRepository,
        val audio: SasayakiSettingsRepository,
        val composite: CompositeReaderSettingsSyncStore,
        private val job: Job,
    ) : AutoCloseable {
        override fun close() = runBlocking { job.cancelAndJoin() }
    }
}
