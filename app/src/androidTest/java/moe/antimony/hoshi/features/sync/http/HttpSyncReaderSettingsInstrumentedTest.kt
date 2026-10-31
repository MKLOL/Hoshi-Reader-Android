package moe.antimony.hoshi.features.sync.http

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import moe.antimony.hoshi.features.reader.CompositeReaderSettingsSyncStore
import moe.antimony.hoshi.features.reader.PopupCardStyle
import moe.antimony.hoshi.features.reader.ReaderSettings
import moe.antimony.hoshi.features.reader.ReaderSettingsRepository
import moe.antimony.hoshi.features.reader.ReaderSettingsSyncSnapshot
import moe.antimony.hoshi.features.reader.ReaderTheme
import moe.antimony.hoshi.features.sasayaki.SasayakiReaderSkipButtonAction
import moe.antimony.hoshi.features.sasayaki.SasayakiSettings
import moe.antimony.hoshi.features.sasayaki.SasayakiSettingsRepository
import moe.antimony.hoshi.features.sync.v3.StubKvServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import java.io.File

/** Two isolated on-device DataStores exchange preferences over real authenticated HTTP. */
@RunWith(AndroidJUnit4::class)
class HttpSyncReaderSettingsInstrumentedTest {
    @get:Rule val temp = TemporaryFolder()

    @Test
    fun mangaEpubSharedPopupAndAudioChoicesRoundTripAndSurviveRestartIncludingDefaultResets() = runBlocking {
        StubKvServer().use { server ->
            server.start()
            val client = HttpSyncKvClient(server.baseUrl, server.token)
            val bDirectory = temp.newFolder("device-b")
            val chosenReader = ReaderSettings(
                mangaShowFurigana = false,
                mangaSingleTapLookup = true,
                hideFurigana = true,
                fontSize = 30,
                verticalWriting = false,
                theme = ReaderTheme.Dark,
                eInkMode = true,
                volumeKeysTurnPages = true,
                dictionaryCard = PopupCardStyle(width = 410.0, height = 520.0, fontScale = 1.25),
                translationCard = PopupCardStyle(fontScale = 1.5),
            ).persisted()
            val chosenAudio = SasayakiSettings(
                autoPause = false,
                autoScroll = false,
                readerSkipButtonAction = SasayakiReaderSkipButtonAction.Seconds30,
                darkTextColor = 0xFF123456,
            )
            device("device-a", temp.newFolder("device-a")).use { a ->
                assertTrue(a.store.snapshot().records.isEmpty())
                assertEquals(ReaderSettingsSyncResult(), a.sync(client))
                assertTrue("Fresh defaults must not be published", server.keys().isEmpty())

                a.reader.update { chosenReader }
                a.audio.update { chosenAudio.copy(syncEnabled = true, copyAudiobookToPrivateStorage = true) }
                assertTrue(a.sync(client).uploaded)
                assertEquals(listOf(READER_SETTINGS_PREFIX + "device-a"), server.keys())

                lateinit var saved: ReaderSettingsSyncSnapshot
                device("device-b", bDirectory).use { b ->
                    assertEquals(ReaderSettings().persisted(), b.reader.settings.first())
                    val received = b.sync(client)
                    assertTrue(received.downloaded)
                    assertTrue(received.uploaded)
                    assertEquals(chosenReader, b.reader.settings.first())
                    assertEquals(chosenAudio, b.audio.settings.first())
                    assertTrue("Device-local audio transfer choices must remain local", a.audio.settings.first().syncEnabled)
                    assertFalse(b.audio.settings.first().syncEnabled)
                    assertFalse(b.audio.settings.first().copyAudiobookToPrivateStorage)
                    saved = b.store.snapshot()
                }

                device("device-b", bDirectory).use { restarted ->
                    assertEquals(saved, restarted.store.snapshot())
                    assertEquals(chosenReader, restarted.reader.settings.first())
                    assertEquals(chosenAudio, restarted.audio.settings.first())
                    val putsBefore = server.requests().count { it.method == "PUT" }
                    assertFalse(restarted.sync(client).uploaded)
                    assertFalse(a.sync(client).uploaded)
                    assertEquals("An unchanged preference mirror must not be rewritten", putsBefore,
                        server.requests().count { it.method == "PUT" })

                    restarted.reader.update {
                        it.copy(
                            mangaShowFurigana = true,
                            hideFurigana = false,
                            fontSize = ReaderSettings().fontSize,
                            dictionaryCard = it.dictionaryCard.copy(width = null),
                        )
                    }
                    restarted.audio.update {
                        it.copy(autoPause = true, readerSkipButtonAction = SasayakiReaderSkipButtonAction.Seconds5)
                    }
                    assertTrue(restarted.sync(client).uploaded)
                    assertTrue(a.sync(client).downloaded)
                    val resetReader = chosenReader.copy(
                        mangaShowFurigana = true,
                        hideFurigana = false,
                        fontSize = ReaderSettings().fontSize,
                        dictionaryCard = chosenReader.dictionaryCard.copy(width = null),
                    )
                    val resetAudio = chosenAudio.copy(
                        autoPause = true,
                        readerSkipButtonAction = SasayakiReaderSkipButtonAction.Seconds5,
                    )
                    assertEquals(resetReader, a.reader.settings.first())
                    assertEquals(resetReader, restarted.reader.settings.first())
                    assertEquals(resetAudio, restarted.audio.settings.first())
                    assertEquals(resetAudio.copy(syncEnabled = true, copyAudiobookToPrivateStorage = true), a.audio.settings.first())
                    assertEquals(a.store.snapshot().records, restarted.store.snapshot().records)
                }
            }
        }
    }

    @Test
    fun independentOfflineEditsMergeAcrossReadersAndIndividualPopupFields() = runBlocking {
        StubKvServer().use { server ->
            server.start()
            val client = HttpSyncKvClient(server.baseUrl, server.token)
            device("device-a", temp.newFolder()).use { a ->
                device("device-b", temp.newFolder()).use { b ->
                    a.reader.update {
                        it.copy(mangaShowFurigana = false, dictionaryCard = PopupCardStyle(width = 420.0))
                    }
                    a.audio.update { it.copy(autoPause = false) }
                    b.reader.update {
                        it.copy(fontSize = 32, dictionaryCard = PopupCardStyle(height = 620.0))
                    }
                    b.audio.update { it.copy(showReaderToggle = false) }

                    a.sync(client)
                    b.sync(client)
                    a.sync(client)
                    b.sync(client)

                    val expectedReader = ReaderSettings(
                        mangaShowFurigana = false,
                        fontSize = 32,
                        dictionaryCard = PopupCardStyle(width = 420.0, height = 620.0),
                    ).persisted()
                    val expectedAudio = SasayakiSettings(autoPause = false, showReaderToggle = false)
                    assertEquals(expectedReader, a.reader.settings.first())
                    assertEquals(expectedReader, b.reader.settings.first())
                    assertEquals(expectedAudio, a.audio.settings.first())
                    assertEquals(expectedAudio, b.audio.settings.first())
                    assertEquals(a.store.snapshot().records, b.store.snapshot().records)
                    assertEquals(setOf(READER_SETTINGS_PREFIX + "device-a", READER_SETTINGS_PREFIX + "device-b"), server.keys().toSet())
                }
            }
        }
    }

    /** These four preferences already use Float storage; compare their persisted precision. */
    private fun ReaderSettings.persisted(): ReaderSettings = copy(
        lineHeight = lineHeight.toFloat().toDouble(),
        characterSpacing = characterSpacing.toFloat().toDouble(),
        paragraphSpacing = paragraphSpacing.toFloat().toDouble(),
        popupScale = popupScale.toFloat().toDouble(),
    )

    private fun device(id: String, directory: File): Device {
        val job = Job()
        val scope = CoroutineScope(Dispatchers.IO + job)
        val reader = ReaderSettingsRepository(
            dataStore = PreferenceDataStoreFactory.create(scope = scope) { directory.resolve("reader.preferences_pb") },
            syncDeviceId = id,
        )
        val audio = SasayakiSettingsRepository(
            dataStore = PreferenceDataStoreFactory.create(scope = scope) { directory.resolve("audio.preferences_pb") },
            syncDeviceId = id,
        )
        return Device(reader, audio, job)
    }

    private class Device(
        val reader: ReaderSettingsRepository,
        val audio: SasayakiSettingsRepository,
        private val job: Job,
    ) : AutoCloseable {
        val store = CompositeReaderSettingsSyncStore(reader, audio)
        private val exchange = HttpSyncReaderSettings(store)

        suspend fun sync(client: HttpSyncKvTransport): ReaderSettingsSyncResult = exchange.sync(client).also {
            assertTrue(it.errors.toString(), it.errors.isEmpty())
        }

        override fun close() = runBlocking { job.cancelAndJoin() }
    }
}
