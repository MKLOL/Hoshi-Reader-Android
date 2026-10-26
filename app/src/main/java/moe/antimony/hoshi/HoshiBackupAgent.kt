package moe.antimony.hoshi

import android.app.backup.BackupAgent
import android.app.backup.BackupDataInput
import android.app.backup.BackupDataOutput
import android.os.ParcelFileDescriptor
import android.util.Log

/**
 * Auto Backup exactly as the platform's default agent runs it (`android:fullBackupOnly`: the base
 * class's file backup and restore with the include/exclude rules), plus one note after a restore
 * or device transfer: setup state and the sync token are never backed up, so the next launch
 * re-offers what did not arrive (see [onboardingSeed]).
 *
 * Android runs this in its restricted backup mode (base `Application`, no content providers), so
 * it only writes a file and never casts the application.
 */
class HoshiBackupAgent : BackupAgent() {
    // Key/value backup is unused: the platform requires these, and they back up nothing.
    override fun onBackup(oldState: ParcelFileDescriptor?, data: BackupDataOutput?, newState: ParcelFileDescriptor?) = Unit

    override fun onRestore(data: BackupDataInput?, appVersionCode: Int, newState: ParcelFileDescriptor?) = Unit

    override fun onRestoreFinished() {
        try {
            super.onRestoreFinished()
            RestoredInstallMarker.inDirectory(noBackupFilesDir).record()
        } catch (error: Exception) {
            // The restored data is already in place; losing the note only skips the re-offer.
            Log.w(TAG, "Could not note the finished restore", error)
        }
    }

    private companion object {
        const val TAG = "HoshiBackupAgent"
    }
}
