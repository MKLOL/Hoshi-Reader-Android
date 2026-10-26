package moe.antimony.hoshi

import moe.antimony.hoshi.dictionary.DictionaryStorageDataSource
import moe.antimony.hoshi.dictionary.DictionaryType
import java.io.File

/** Capture before startup creates settings: package updates do not mean the app was opened. */
internal fun hasPriorAppUse(filesDir: File): Boolean =
    listOfNotNull(
        // Reading settings migrate and persist defaults before the first screen is shown,
        // including for people who never imported books or changed a setting.
        filesDir.resolve("datastore/reader-settings.preferences_pb"),
        filesDir.resolve("datastore/dictionary-settings.preferences_pb"),
        filesDir.resolve("Books"),
        filesDir.resolve("Dictionaries/config.json"),
        // Preserve installs from before the settings migrated to DataStore.
        filesDir.parentFile?.resolve("shared_prefs/reader-settings.xml"),
        filesDir.parentFile?.resolve("shared_prefs/dictionary-settings.xml"),
    ).any(File::exists)

/**
 * Left by [HoshiBackupAgent] after Android restores or transfers this app's data. It lives in
 * `noBackupFilesDir`, which is never backed up, so it can only mean this installation's data came
 * from another one. It stays until both re-offered setup steps are completed or skipped.
 */
class RestoredInstallMarker(private val file: File) {
    val isPresent: Boolean get() = file.exists()

    fun record() {
        file.parentFile?.mkdirs()
        file.writeText("restored\n")
    }

    fun consume() {
        file.delete()
    }

    companion object {
        fun inDirectory(noBackupFilesDir: File) = RestoredInstallMarker(noBackupFilesDir.resolve("restored-install"))
    }
}

/** Any term dictionary, enabled or not, as the dictionary settings list them. */
internal fun hasInstalledTermDictionary(filesDir: File): Boolean =
    DictionaryStorageDataSource(filesDir).loadDictionaries(DictionaryType.Term).isNotEmpty()

/** Whether each first-launch step starts out completed, for a step this installation never stored. */
internal data class OnboardingSeed(val tokenCompleted: Boolean, val dictionariesCompleted: Boolean)

/**
 * Fresh installs offer both steps; upgrades and other returning installs offer neither. A restore
 * or device transfer brings settings and libraries but never the setup state or the sync token, so
 * it re-offers only what did not arrive: the token step without a token, and the dictionary step
 * without any term dictionary.
 */
internal fun onboardingSeed(
    isFreshInstall: Boolean,
    restored: Boolean,
    hasToken: Boolean,
    hasTermDictionary: () -> Boolean,
): OnboardingSeed =
    if (restored) {
        OnboardingSeed(tokenCompleted = hasToken, dictionariesCompleted = hasTermDictionary())
    } else {
        OnboardingSeed(tokenCompleted = !isFreshInstall, dictionariesCompleted = !isFreshInstall)
    }
