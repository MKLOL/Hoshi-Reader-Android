package moe.antimony.hoshi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

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

    @Test
    fun freshInstallsOfferBothStepsWithoutLookingForDictionaries() {
        val seed = onboardingSeed(isFreshInstall = true, restored = false, hasToken = false, hasTermDictionary = ::unexpected)
        assertEquals(OnboardingSeed(tokenCompleted = false, dictionariesCompleted = false), seed)
    }

    @Test
    fun upgradesWithoutARestoreMarkerOfferNeitherStep() {
        val seed = onboardingSeed(isFreshInstall = false, restored = false, hasToken = false, hasTermDictionary = ::unexpected)
        assertEquals(OnboardingSeed(tokenCompleted = true, dictionariesCompleted = true), seed)
    }

    @Test
    fun aRestoreThatBroughtEverythingOffersNeitherStep() {
        val seed = onboardingSeed(isFreshInstall = false, restored = true, hasToken = true, hasTermDictionary = { true })
        assertEquals(OnboardingSeed(tokenCompleted = true, dictionariesCompleted = true), seed)
    }

    @Test
    fun aRestoreWithoutATokenOffersOnlyTheTokenStep() {
        val seed = onboardingSeed(isFreshInstall = false, restored = true, hasToken = false, hasTermDictionary = { true })
        assertEquals(OnboardingSeed(tokenCompleted = false, dictionariesCompleted = true), seed)
    }

    @Test
    fun aRestoreWithoutTermDictionariesOffersOnlyTheDictionaryStep() {
        val seed = onboardingSeed(isFreshInstall = false, restored = true, hasToken = true, hasTermDictionary = { false })
        assertEquals(OnboardingSeed(tokenCompleted = true, dictionariesCompleted = false), seed)
    }

    @Test
    fun aRestoreMissingBothOffersBothSteps() {
        // A backup taken before any book or dictionary existed restores no prior-use evidence either.
        listOf(false, true).forEach { isFreshInstall ->
            val seed = onboardingSeed(isFreshInstall, restored = true, hasToken = false, hasTermDictionary = { false })
            assertEquals(OnboardingSeed(tokenCompleted = false, dictionariesCompleted = false), seed)
        }
    }

    @Test
    fun anyRestoredTermDictionaryCountsButOtherKindsDoNot() {
        val filesDir = temp.newFolder("restored-files")
        assertFalse(hasInstalledTermDictionary(filesDir))
        filesDir.writeDictionary("Frequency", "Jiten")
        assertFalse(hasInstalledTermDictionary(filesDir))
        filesDir.writeDictionary("Term", "JMdict")
        filesDir.resolve("Dictionaries/config.json").writeText(
            """{"termDictionaries":[{"fileName":"JMdict","isEnabled":false,"order":0}],"frequencyDictionaries":[],"pitchDictionaries":[]}""",
        )
        assertTrue(hasInstalledTermDictionary(filesDir))
    }

    @Test
    fun aRestoreMarkerIsRecordedOnceAndConsumed() {
        val noBackup = temp.newFolder("no_backup")
        val marker = RestoredInstallMarker.inDirectory(noBackup)
        assertFalse(marker.isPresent)
        marker.record()
        marker.record()
        assertTrue(RestoredInstallMarker.inDirectory(noBackup).isPresent)
        marker.consume()
        assertFalse(RestoredInstallMarker.inDirectory(noBackup).isPresent)
    }

    @Test
    fun aRestoreHookThatCannotWriteItsNoteDoesNotFailTheRestore() {
        // Without a framework context there is no no-backup directory to write to.
        HoshiBackupAgent().onRestoreFinished()
    }

    private fun unexpected(): Boolean = error("Only a restore looks for restored dictionaries")

    private fun File.writeDictionary(type: String, name: String) {
        resolve("Dictionaries/$type/$name").apply { mkdirs() }
            .resolve("index.json").writeText("""{"title":"$name","format":3,"revision":"1"}""")
    }
}
