package moe.antimony.hoshi.features.reader

import moe.antimony.hoshi.features.sasayaki.SasayakiSettings
import moe.antimony.hoshi.features.sync.StatisticsSyncMode
import org.junit.Assert.assertEquals
import org.junit.Test

class ReaderSettingsEditTest {
    @Test
    fun unchangedSnapshotPreservesEveryRemoteSetting() {
        val entry = ReaderSettings()
        val remote = entry.copy(mangaShowFurigana = false, fontSize = 32, statisticsDayResetHour = 5)

        assertEquals(remote, applyReaderSettingsEdit(remote, entry, entry))
    }

    @Test
    fun cardEditPreservesRemoteMangaAndIndependentCardField() {
        val entry = ReaderSettings(dictionaryCard = PopupCardStyle(width = 420.0))
        val remote = entry.copy(
            mangaShowFurigana = false,
            dictionaryCard = entry.dictionaryCard.copy(height = 610.0),
        )
        val edited = entry.copy(dictionaryCard = entry.dictionaryCard.copy(width = 510.0))

        assertEquals(
            remote.copy(dictionaryCard = remote.dictionaryCard.copy(width = 510.0)),
            applyReaderSettingsEdit(remote, entry, edited),
        )
    }

    @Test
    fun delayedSeparateUiEditsPreserveBothChanges() {
        val displayed = ReaderSettings()
        val first = applyReaderSettingsEdit(displayed, displayed, displayed.copy(mangaShowFurigana = false))
        val second = applyReaderSettingsEdit(first, displayed, displayed.copy(fontSize = 30))

        assertEquals(displayed.copy(mangaShowFurigana = false, fontSize = 30), second)
    }

    @Test
    fun explicitDefaultAndNullableCardResetsRemainEdits() {
        val previous = ReaderSettings(mangaShowFurigana = false, dictionaryCard = PopupCardStyle(width = 420.0))
        val current = previous.copy(fontSize = 30, dictionaryCard = previous.dictionaryCard.copy(height = 610.0))
        val edited = previous.copy(mangaShowFurigana = true, dictionaryCard = previous.dictionaryCard.copy(width = null))

        assertEquals(
            current.copy(mangaShowFurigana = true, dictionaryCard = current.dictionaryCard.copy(width = null)),
            applyReaderSettingsEdit(current, previous, edited),
        )
    }

    @Test
    fun statisticsAndDeviceLocalSasayakiChoicesRemainEditable() {
        val reader = ReaderSettings()
        val changedReader = reader.copy(
            statisticsSyncEnabled = true,
            statisticsSyncMode = StatisticsSyncMode.entries.last(),
            statisticsStreakMinimumMinutes = 12,
            statisticsDayResetHour = 6,
            statisticsSettingsUpdatedAt = 123L,
            statisticsTimeZone = "Asia/Tokyo",
        )
        assertEquals(changedReader, applyReaderSettingsEdit(reader, reader, changedReader))
        val audio = SasayakiSettings()
        val changedAudio = audio.copy(syncEnabled = true, copyAudiobookToPrivateStorage = true)
        assertEquals(changedAudio, applySasayakiSettingsEdit(audio, audio, changedAudio))
    }

    @Test
    fun staleSasayakiSnapshotChangesOnlyTheEditedControl() {
        val previous = SasayakiSettings()
        val remote = previous.copy(autoPause = false, darkTextColor = 0xFF123456)
        val edited = previous.copy(showReaderToggle = false)

        assertEquals(remote.copy(showReaderToggle = false), applySasayakiSettingsEdit(remote, previous, edited))
        assertEquals(remote, applySasayakiSettingsEdit(remote, previous, previous))
    }
}
