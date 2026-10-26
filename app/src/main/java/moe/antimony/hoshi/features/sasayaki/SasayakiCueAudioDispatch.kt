package moe.antimony.hoshi.features.sasayaki

import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** State/source capture stays on the caller; encoding and rewriting never block the UI. */
internal suspend fun dispatchSasayakiCueAudioExport(export: () -> File?): File? =
    withContext(Dispatchers.IO) { export() }

/**
 * Mines one popup entry, first exporting its sentence's audio clip when [exportCueAudio] is
 * given. [scope] must outlive the reader: the export can take up to 30 s, and leaving the reader
 * meanwhile must still add the note. A clip that cannot be made mines without it (null path);
 * an export that throws answers false without mining. [onResult] runs wherever [mine] reports,
 * so a caller that replies to reader UI checks that its reader is still shown.
 */
internal fun launchSasayakiMining(
    scope: CoroutineScope,
    exportCueAudio: (suspend () -> File?)?,
    mine: (sasayakiAudioPath: String?, onResult: (Boolean) -> Unit) -> Unit,
    onResult: (Boolean) -> Unit,
): Job = scope.launch {
    val audioPath = try {
        exportCueAudio?.invoke()?.absolutePath
    } catch (error: Exception) {
        if (error is CancellationException) throw error
        onResult(false)
        return@launch
    }
    mine(audioPath, onResult)
}
