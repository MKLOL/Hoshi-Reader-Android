package moe.antimony.hoshi.features.sasayaki

import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** State/source capture stays on the caller; encoding and rewriting never block the UI. */
internal suspend fun dispatchSasayakiCueAudioExport(export: () -> File?): File? =
    withContext(Dispatchers.IO) { export() }
