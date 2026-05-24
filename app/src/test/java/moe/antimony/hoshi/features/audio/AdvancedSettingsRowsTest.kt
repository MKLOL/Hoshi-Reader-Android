package moe.antimony.hoshi.features.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import moe.antimony.hoshi.R

class AdvancedSettingsRowsTest {
    // The manga-only fork hides the upstream Sasayaki (audiobook) and ッツ Sync (TTU/EPUB)
    // rows from Advanced. Their AdvancedDestination + dispatch code is intentionally kept so
    // existing deep-links still resolve — see comments in advancedSettingsSections().
    @Test
    fun advancedSettingsRowsMatchMangaSectionStructureForSyncAndBackup() {
        val sections = advancedSettingsSections()

        assertEquals(
            listOf(
                listOf(R.string.advanced_audio, R.string.advanced_statistics),
                listOf(R.string.advanced_http_sync, R.string.anki_connect_use),
                listOf(R.string.settings_backup),
            ),
            sections.map { section -> section.rows.map { it.titleRes } },
        )

        val httpSyncRow = sections.flatMap { it.rows }.single { it.destination == AdvancedDestination.HttpSync }
        val ankiConnectRow = sections.flatMap { it.rows }.single { it.destination == AdvancedDestination.AnkiConnect }
        val backupRow = sections.flatMap { it.rows }.single { it.destination == AdvancedDestination.Backup }

        assertEquals(AdvancedSettingsIcon.Cloud, httpSyncRow.icon)
        assertEquals(AdvancedSettingsIcon.AnkiConnect, ankiConnectRow.icon)
        assertEquals(AdvancedSettingsIcon.ExternalDrive, backupRow.icon)
        assertFalse(backupRow.icon == AdvancedSettingsIcon.Cloud)
        assertTrue(sections.indexOfFirst { it.rows.any { row -> row.destination == AdvancedDestination.HttpSync } } !=
            sections.indexOfFirst { it.rows.any { row -> row.destination == AdvancedDestination.Backup } })
    }
}
