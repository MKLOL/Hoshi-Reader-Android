package moe.antimony.hoshi.features.reader

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ReaderSettingsSyncRepositoryTest {
    @get:Rule val temp = TemporaryFolder()

    @Test
    fun freshInstallHasNoDefaultsToOverwriteAnExistingDevice() = runBlocking {
        repository("fresh").use { fresh ->
            assertTrue(fresh.repo.snapshot().records.isEmpty())
            assertTrue(fresh.repo.settings.first().mangaShowFurigana)
            fresh.repo.update { it }
            assertTrue(fresh.repo.snapshot().records.isEmpty())
        }
    }

    @Test
    fun migratesOnlyExistingNonDefaultChoicesAtBaselineTime() = runBlocking {
        repository(
            "old", initial = mutablePreferencesOf(
                booleanPreferencesKey("readerSettingsMigratedFromSharedPreferences") to true,
                intPreferencesKey("fontSize") to 30,
                booleanPreferencesKey("mangaShowFurigana") to false,
            ),
        ).use { old ->
            val records = old.repo.snapshot().records
            assertEquals(setOf("fontSize", "mangaShowFurigana"), records.keys)
            assertTrue(records.values.all { it.editedAt == 0L && it.deviceId == "old" })
            old.repo.merge(mapOf("fontSize" to record(28, 1)))
            assertEquals(28, old.repo.settings.first().fontSize)
        }
    }

    @Test
    fun sharedPreferencesMigrationSeedsExistingChoiceButDoesNotReloadIt() = runBlocking {
        var loads = 0
        repository("legacy", legacy = object : ReaderSettingsLegacySource {
            override fun load(): ReaderSettings {
                loads++
                return ReaderSettings(theme = ReaderTheme.Dark, mangaShowFurigana = false)
            }
        }).use { legacy ->
            assertEquals(setOf("theme", "mangaShowFurigana"), legacy.repo.snapshot().records.keys)
            legacy.repo.update { it.copy(fontSize = 25) }
            legacy.repo.snapshot()
            assertEquals(1, loads)
        }
    }

    @Test
    fun independentOfflineMangaAndEpubEditsBothSurviveAndConverge() = runBlocking {
        repository("a").use { a -> repository("b").use { b ->
            a.repo.update { it.copy(mangaShowFurigana = false) }
            b.repo.update { it.copy(fontSize = 30, verticalWriting = false) }
            val aOffline = a.repo.snapshot()
            val bOffline = b.repo.snapshot()
            a.repo.merge(bOffline.records)
            b.repo.merge(aOffline.records)
            assertEquals(a.repo.settings.first(), b.repo.settings.first())
            assertFalse(a.repo.settings.first().mangaShowFurigana)
            assertFalse(a.repo.settings.first().verticalWriting)
            assertEquals(30, a.repo.settings.first().fontSize)
            assertEquals(a.repo.snapshot().records, b.repo.snapshot().records)
        } }
    }

    @Test
    fun sameFieldUsesTimestampThenStableDeviceIdRegardlessOfMergeOrder() = runBlocking {
        repository("a").use { a -> repository("z").use { z ->
            a.repo.update { it.copy(fontSize = 25) }
            z.repo.update { it.copy(fontSize = 31) }
            val aOffline = a.repo.snapshot()
            val zOffline = z.repo.snapshot()
            a.repo.merge(zOffline.records)
            z.repo.merge(aOffline.records)
            assertEquals(31, a.repo.settings.first().fontSize)
            assertEquals(a.repo.snapshot().records, z.repo.snapshot().records)
            a.repo.merge(mapOf("fontSize" to record(27, 101, "b")))
            assertEquals(27, a.repo.settings.first().fontSize)
        } }
    }

    @Test
    fun nextLocalEditFollowsObservedRemoteClockEvenWhenDeviceClockIsBehind() = runBlocking {
        repository("slow", now = { 10L }).use { slow ->
            slow.repo.merge(mapOf("fontSize" to record(28, 500, "fast")))
            slow.repo.update { it.copy(fontSize = 29) }
            assertEquals(501L, slow.repo.snapshot().records.getValue("fontSize").editedAt)
            slow.repo.update { it.copy(fontSize = 30) }
            assertEquals(502L, slow.repo.snapshot().records.getValue("fontSize").editedAt)
        }
    }

    @Test
    fun explicitReturnToDefaultAndAdaptiveCardResetAreSynced() = runBlocking {
        repository("a").use { a -> repository("b").use { b ->
            a.repo.update { it.copy(mangaShowFurigana = false, dictionaryCard = PopupCardStyle(width = 550.0)) }
            b.repo.merge(a.repo.snapshot().records)
            a.repo.update { it.copy(mangaShowFurigana = true, dictionaryCard = PopupCardStyle()) }
            val reset = a.repo.snapshot().records
            assertEquals(JsonNull, reset.getValue("dictionaryCard.width").value)
            assertEquals(JsonPrimitive(true), reset.getValue("mangaShowFurigana").value)
            b.repo.merge(reset)
            assertTrue(b.repo.settings.first().mangaShowFurigana)
            assertEquals(null, b.repo.settings.first().dictionaryCard.width)
        } }
    }

    @Test
    fun popupFieldsMergeIndependentlyAcrossTheSameCardAndOtherCard() = runBlocking {
        repository("a").use { a -> repository("b").use { b ->
            a.repo.update { it.copy(dictionaryCard = PopupCardStyle(width = 550.0)) }
            b.repo.update { it.copy(dictionaryCard = PopupCardStyle(fontScale = 1.8), translationCard = PopupCardStyle(height = 700.0)) }
            val aOffline = a.repo.snapshot().records
            a.repo.merge(b.repo.snapshot().records)
            b.repo.merge(aOffline)
            assertEquals(PopupCardStyle(width = 550.0, fontScale = 1.8), a.repo.settings.first().dictionaryCard)
            assertEquals(700.0, a.repo.settings.first().translationCard.height)
            assertEquals(a.repo.settings.first(), b.repo.settings.first())
        } }
    }

    @Test
    fun allAllowedPreferenceTypesRoundTripWithoutRestampingFloats() = runBlocking {
        repository("a").use { a -> repository("b").use { b ->
            a.repo.update { it.copy(
                theme = ReaderTheme.Custom, uiTheme = ReaderInterfaceTheme.Dark, eInkMode = true,
                customBackgroundColor = 0xFF123456, customTextColor = 0xFF654321, customInfoColor = 0xFF101010,
                fontSize = 32, selectedFont = "KleeOne-SemiBold", verticalWriting = false,
                hideFurigana = true, showSentenceTranslations = false, continuousMode = true,
                horizontalPadding = 15, verticalPadding = 10, layoutAdvanced = true,
                lineHeight = 1.9, characterSpacing = -2.0, paragraphSpacing = 1.7,
                popupWidth = 450, popupHeight = 350, popupScale = 1.2,
                dictionaryCard = PopupCardStyle(550.0, 700.0, 1.6, 1.4),
                translationCard = PopupCardStyle(600.0, 800.0, 1.7, 1.5),
                mangaShowFurigana = false, mangaUseNotoSansJp = true, mangaSingleTapLookup = true,
                mangaShowCopyButton = true, mangaEnlargeSmallText = false, volumeKeysTurnPages = true,
                volumeKeysSeekSasayaki = true, keepScreenOnWhileReading = true,
            ) }
            b.repo.merge(a.repo.snapshot().records)
            assertEquals(a.repo.settings.first(), b.repo.settings.first())
            val before = b.repo.snapshot().records
            b.repo.update { it.copy(lineHeight = 1.9, popupScale = 1.2, paragraphSpacing = 1.7) }
            assertEquals(before, b.repo.snapshot().records)
        } }
    }

    @Test
    fun onlyRealLocalEditsEnqueueSyncAndRemoteMergesDoNotEcho() = runBlocking {
        repository("local").use { local ->
            var callbacks = 0
            local.repo.onSyncRelevantEdit = { callbacks++ }
            local.repo.update { it }
            local.repo.update { it.copy(statisticsSyncEnabled = true, statisticsStreakMinimumMinutes = 25) }
            assertEquals(0, callbacks)
            local.repo.update { it.copy(mangaShowFurigana = false) }
            assertEquals(1, callbacks)
            assertTrue(local.repo.merge(mapOf("fontSize" to record(28))))
            assertEquals(1, callbacks)
            val snapshot = local.repo.snapshot()
            assertFalse(local.repo.merge(snapshot.records))
            assertEquals(snapshot, local.repo.snapshot())
            assertEquals(1, callbacks)
        }
    }

    @Test
    fun statisticsSyncConfigurationAndStreakSettingsStayInTheirExistingLane() = runBlocking {
        repository("local").use { local ->
            local.repo.update { it.copy(statisticsSyncEnabled = true, statisticsStreakMinimumMinutes = 27, statisticsTimeZone = "UTC") }
            assertTrue(local.repo.snapshot().records.isEmpty())
            expectInvalidMerge(local.repo, mapOf(
                "statisticsSyncEnabled" to record(false),
                "statisticsStreakMinimumMinutes" to record(19),
                "statisticsTimeZone" to record("Asia/Tokyo"),
            ))
            assertTrue(local.repo.settings.first().statisticsSyncEnabled)
            assertEquals(27, local.repo.settings.first().statisticsStreakMinimumMinutes)
            assertEquals("UTC", local.repo.settings.first().statisticsTimeZone)
        }
    }

    @Test
    fun invalidRemoteTypesEnumsRangesPathsAndClockOverflowFailClosed() = runBlocking {
        repository("local").use { local ->
            val before = local.repo.settings.first()
            val invalid = mapOf(
                "mangaShowFurigana" to record("false"), "fontSize" to record(999),
                "theme" to record("not-a-theme"), "selectedFont" to record("/sdcard/font.ttf"),
                "lineHeight" to record(-2.0), "dictionaryCard.scale" to record(9.0),
                "customBackgroundColor" to record(-1),
                "verticalWriting" to record(false, Long.MAX_VALUE),
                "showTitle" to record(false, -1),
                "showPercentage" to record(false, 100, "../../device"),
            )
            invalid.forEach { (name, invalidRecord) ->
                expectInvalidMerge(local.repo, mapOf("showCharacters" to record(false), name to invalidRecord))
            }
            assertEquals(before, local.repo.settings.first())
            assertTrue(local.repo.snapshot().records.isEmpty())
        }
    }

    @Test
    fun customFontSyncKeepsOnlyFamilyNameAndUsesExistingGenericFallback() = runBlocking {
        repository("local").use { local ->
            local.repo.merge(mapOf("selectedFont" to record("Missing Custom Font")))
            assertEquals("Missing Custom Font", local.repo.settings.first().selectedFont)
            val css = ReaderContentStyles.css(local.repo.settings.first())
            assertTrue(css.contains("'Missing Custom Font', serif"))
            assertFalse(css.contains("@font-face"))
        }
    }

    @Test
    fun importedFontNamesWithPunctuationAndLongNamesRemainSelectableAndSyncable() = runBlocking {
        val names = listOf("My:Font", "My\\Font", "F".repeat(129))
        val fontRoot = temp.newFolder("font-name-compatibility")
        val manager = ReaderFontManager(fontRoot)
        repository("local").use { local ->
            names.forEach { name ->
                val source = File(temp.newFolder(), "$name.ttf").apply { writeBytes(byteArrayOf(1)) }
                val imported = manager.importFont(source)
                local.repo.update { it.copy(selectedFont = imported.name) }
                assertEquals(name, local.repo.settings.first().selectedFont)
                assertEquals(JsonPrimitive(name), local.repo.snapshot().records.getValue("selectedFont").value)
                repository("peer").use { peer ->
                    peer.repo.merge(local.repo.snapshot().records)
                    assertEquals(name, peer.repo.settings.first().selectedFont)
                }
            }
        }
    }

    @Test
    fun unknownFutureFieldIsPreservedWithoutChangingVisibleSettings() = runBlocking {
        repository("local").use { local ->
            val before = local.repo.settings.first()
            val unknown = mapOf("futureReader.control" to record("adaptive"))
            assertTrue(local.repo.merge(unknown))
            assertEquals(before, local.repo.settings.first())
            local.repo.update { it.copy(fontSize = 30) }
            assertEquals(unknown.getValue("futureReader.control"), local.repo.snapshot().records["futureReader.control"])
        }
    }

    @Test
    fun repositoryRecreationKeepsIdentityAndJournal() = runBlocking {
        val file = File(temp.newFolder(), "reader.preferences_pb")
        lateinit var snapshot: ReaderSettingsSyncSnapshot
        repository(file = file).use { original ->
            original.repo.update { it.copy(mangaShowFurigana = false) }
            snapshot = original.repo.snapshot()
        }
        repository(file = file).use { reopened ->
            assertEquals(snapshot, reopened.repo.snapshot())
            assertFalse(reopened.repo.settings.first().mangaShowFurigana)
        }
    }

    @Test
    fun concurrentLocalAndRemoteIndependentEditsAreAtomicAndPreserved() = runBlocking {
        repository("local").use { local ->
            coroutineScope {
                val update = async(Dispatchers.Default) { local.repo.update { it.copy(fontSize = 30) } }
                val merge = async(Dispatchers.Default) { local.repo.merge(mapOf("mangaShowFurigana" to record(false))) }
                update.await()
                merge.await()
            }
            val settings = local.repo.settings.first()
            assertEquals(30, settings.fontSize)
            assertFalse(settings.mangaShowFurigana)
            assertEquals(setOf("fontSize", "mangaShowFurigana"), local.repo.snapshot().records.keys)
        }
    }

    @Test
    fun incomingFutureClockFailsBeforeApplyingAnyFieldAndLocalChoiceRemainsEditable() = runBlocking {
        repository("local").use { local ->
            expectInvalidMerge(local.repo, mapOf("fontSize" to record(40, 100 + 24 * 60 * 60 * 1000L + 1)))
            local.repo.update { it.copy(fontSize = 29) }
            assertEquals(100L, local.repo.snapshot().records.getValue("fontSize").editedAt)
        }
    }

    @Test
    fun malformedPersistedJournalFailsClosedInsteadOfSeedingAReplacement() = runBlocking {
        repository("local", initial = mutablePreferencesOf(
            booleanPreferencesKey("readerSettingsMigratedFromSharedPreferences") to true,
            stringPreferencesKey("readerSettingsSyncJournalV1") to "broken json",
        )).use { local ->
            var failed = false
            try { local.repo.snapshot() } catch (_: IllegalArgumentException) { failed = true }
            assertTrue(failed)
        }
    }

    @Test
    fun moreThanTheFieldLimitFailsClosedWithoutDroppingFuturePreferences() = runBlocking {
        repository("local").use { local ->
            val records = (1..256).associate { "future$it" to record(true) }
            local.repo.merge(records)
            expectInvalidMerge(local.repo, mapOf("future257" to record(true)))
            assertEquals(records, local.repo.snapshot().records)
        }
    }

    @Test
    fun sasayakiFieldsAreRoutedToTheirOwnStore() = runBlocking {
        repository("local").use { local ->
            assertFalse(local.repo.merge(mapOf("sasayaki.repeatMode" to record("book"))))
            assertTrue(local.repo.snapshot().records.isEmpty())
        }
    }

    private suspend fun expectInvalidMerge(repository: ReaderSettingsRepository, records: Map<String, ReaderSettingRecord>) {
        var failed = false
        try { repository.merge(records) } catch (_: IllegalArgumentException) { failed = true }
        assertTrue("Invalid sync input must fail before applying any preference", failed)
    }

    private fun record(value: Any, at: Long = 100, device: String = "remote"): ReaderSettingRecord = ReaderSettingRecord(
        value = when (value) {
            is Boolean -> JsonPrimitive(value)
            is Number -> JsonPrimitive(value)
            else -> JsonPrimitive(value.toString())
        }, editedAt = at, deviceId = device,
    )

    private suspend fun repository(
        device: String = "", now: () -> Long = { 100L }, initial: Preferences? = null,
        legacy: ReaderSettingsLegacySource? = null, file: File = File(temp.newFolder(), "reader.preferences_pb"),
    ): Handle {
        val job = Job()
        val dataStore = PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.IO + job), produceFile = { file })
        initial?.let { dataStore.updateData { initial } }
        return Handle(ReaderSettingsRepository(dataStore, legacy, device, now), job)
    }

    private class Handle(val repo: ReaderSettingsRepository, private val job: Job) : AutoCloseable {
        override fun close() = runBlocking { job.cancelAndJoin() }
    }
}
