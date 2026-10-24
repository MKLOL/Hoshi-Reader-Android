package moe.antimony.hoshi

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
