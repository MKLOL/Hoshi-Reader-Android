package moe.antimony.hoshi

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AppFirstLaunchTest {
    @get:Rule val temp = TemporaryFolder()

    @Test
    fun installingOrUpdatingAnUnopenedAppDoesNotCountAsPriorUse() {
        val filesDir = temp.newFolder("files")
        assertFalse(hasPriorAppUse(filesDir))
        filesDir.resolve("datastore").mkdirs()
        assertFalse(hasPriorAppUse(filesDir))
    }

    @Test
    fun openingTheAppWithoutImportingOrChangingSettingsCountsAsPriorUse() {
        val filesDir = temp.newFolder("files")
        filesDir.resolve("datastore/reader-settings.preferences_pb").apply {
            parentFile!!.mkdirs()
            writeBytes(byteArrayOf(1))
        }
        assertTrue(hasPriorAppUse(filesDir))
    }

    @Test
    fun olderSettingsAndAnExistingLibraryCountAsPriorUse() {
        val dataDir = temp.newFolder("legacy")
        val filesDir = dataDir.resolve("files").apply { mkdirs() }
        dataDir.resolve("shared_prefs/reader-settings.xml").apply {
            parentFile!!.mkdirs()
            writeText("<map />")
        }
        assertTrue(hasPriorAppUse(filesDir))
        dataDir.resolve("shared_prefs/reader-settings.xml").delete()
        filesDir.resolve("Books").mkdirs()
        assertTrue(hasPriorAppUse(filesDir))
    }
}
