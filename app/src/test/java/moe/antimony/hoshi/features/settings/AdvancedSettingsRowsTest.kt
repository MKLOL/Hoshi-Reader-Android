package moe.antimony.hoshi.features.settings

import moe.antimony.hoshi.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AdvancedSettingsRowsTest {
    @Test
    fun advancedSettingsRowsExposeEpubAudiobookAndSyncFeatures() {
        val sections = advancedSettingsSections()

        assertEquals(
            listOf(
                listOf(R.string.advanced_audio, R.string.advanced_statistics, R.string.advanced_sasayaki_audiobooks),
                listOf(R.string.advanced_ttu_sync, R.string.advanced_http_sync, R.string.anki_connect_use),
                listOf(R.string.settings_backup),
            ),
            sections.map { section -> section.rows.map { it.titleRes } },
        )

        val sasayakiRow = sections.flatMap { it.rows }.single { it.destination == AdvancedDestination.Sasayaki }
        val ttuSyncRow = sections.flatMap { it.rows }.single { it.destination == AdvancedDestination.Syncing }
        val httpSyncRow = sections.flatMap { it.rows }.single { it.destination == AdvancedDestination.HttpSync }
        val ankiConnectRow = sections.flatMap { it.rows }.single { it.destination == AdvancedDestination.AnkiConnect }
        val backupRow = sections.flatMap { it.rows }.single { it.destination == AdvancedDestination.Backup }

        assertEquals(AdvancedSettingsIcon.Waveform, sasayakiRow.icon)
        assertEquals(AdvancedSettingsIcon.Cloud, ttuSyncRow.icon)
        assertEquals(AdvancedSettingsIcon.Cloud, httpSyncRow.icon)
        assertEquals(AdvancedSettingsIcon.AnkiConnect, ankiConnectRow.icon)
        assertEquals(AdvancedSettingsIcon.ExternalDrive, backupRow.icon)
        assertTrue(sections.indexOfFirst { it.rows.any { row -> row.destination == AdvancedDestination.HttpSync } } !=
            sections.indexOfFirst { it.rows.any { row -> row.destination == AdvancedDestination.Backup } })
    }
}
