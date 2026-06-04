package moe.antimony.hoshi.features.ai.offline

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.coroutineContext
import kotlin.math.min

/**
 * App-wide controller for on-device translation: downloads GGUF models, tracks download
 * progress, and owns a single cached [LlamaInference] that it lazily (re)loads when the
 * active model changes.
 *
 * It is an `object` because the loaded native model is a heavyweight, process-global resource
 * — only one should ever be resident. Downloads run on an internal IO-bound scope so they
 * survive screen rotations and navigation; progress is observed via [downloadState].
 */
object OfflineLlmManager {
    /** Subdirectory under `filesDir` that holds the downloaded `.gguf` files. */
    private const val MODELS_DIR = "offline-llm"

    /** Suffix for the in-progress temp file, renamed to the final name on success. */
    private const val PART_SUFFIX = ".part"

    /** A downloaded file must be at least this fraction of its advertised size to count. */
    private const val MIN_COMPLETE_FRACTION = 0.90

    /** Progress-emit throttle: emit at most this often, or every [PROGRESS_BYTES] bytes. */
    private const val PROGRESS_INTERVAL_MS = 250L
    private const val PROGRESS_BYTES = 1_024L * 1_024L // ~1 MB

    /** HTTP 416 — a resume range past the end of the file means it's already fully downloaded. */
    private const val HTTP_RANGE_NOT_SATISFIABLE = 416

    /** Long-lived scope for short housekeeping coroutines (model delete). */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _downloadState = MutableStateFlow<ModelDownloadState>(ModelDownloadState.Idle)
    val downloadState: StateFlow<ModelDownloadState> = _downloadState.asStateFlow()

    /**
     * Bumped whenever the set of on-disk models changes (download completed / model deleted).
     * The settings UI keys its `isDownloaded` disk checks on this so they re-run even when
     * [downloadState] lands back on the same value (e.g. Idle → Idle after a delete).
     */
    private val _downloadedRevision = MutableStateFlow(0)
    val downloadedRevision: StateFlow<Int> = _downloadedRevision.asStateFlow()

    /** The single resident model and its id, guarded by [inferenceMutex]. */
    private val inferenceMutex = Mutex()
    private var loaded: LlamaInference? = null
    private var loadedModelId: String? = null

    /** Progress / outcome of the current (or most recent) download. */
    sealed interface ModelDownloadState {
        object Idle : ModelDownloadState
        data class Downloading(
            val model: LlmModel,
            val downloadedBytes: Long,
            val totalBytes: Long,
        ) : ModelDownloadState

        data class Completed(val model: LlmModel) : ModelDownloadState
        data class Failed(val model: LlmModel, val message: String) : ModelDownloadState
    }

    /** Final on-disk path for [model] (whether or not it exists yet). */
    fun modelFile(appContext: Context, model: LlmModel): File =
        File(modelsDir(appContext), model.fileName)

    /**
     * Whether [model] is fully downloaded. Requires the file to exist *and* be at least
     * [MIN_COMPLETE_FRACTION] of its advertised size, so a truncated or partial download
     * (e.g. an interrupted transfer that left a short final file) is not mistaken for done.
     */
    fun isDownloaded(appContext: Context, model: LlmModel): Boolean {
        val file = modelFile(appContext, model)
        return file.exists() &&
            file.length() >= (model.approxSizeBytes * MIN_COMPLETE_FRACTION).toLong()
    }

    /** All catalog models that are currently downloaded on this device. */
    fun downloadedModels(appContext: Context): List<LlmModel> =
        LlmModelCatalog.ALL.filter { isDownloaded(appContext, it) }

    /**
     * One-time reclaim after the models dir moved from internal [Context.getFilesDir] to external
     * app-specific storage. A model an older build downloaded into `filesDir/offline-llm` is now
     * dead weight (the new code never looks there). For each legacy file: delete it if the active
     * (external) dir already has that model — reclaiming the orphaned copy — otherwise migrate it
     * across so the user doesn't have to re-download. No-op when external storage is unavailable
     * (active dir == legacy dir) so it never deletes the in-use model. Safe to call on every launch.
     */
    fun cleanupLegacyInternalModels(appContext: Context) {
        val context = appContext.applicationContext
        val legacyDir = File(context.filesDir, MODELS_DIR)
        if (!legacyDir.isDirectory) return
        val activeDir = modelsDir(context)
        // External unavailable → modelsDir fell back to internal, so legacy IS the active dir.
        if (legacyDir.canonicalPath == activeDir.canonicalPath) return
        legacyDir.listFiles()?.forEach { legacyFile ->
            if (!legacyFile.isFile) {
                legacyFile.deleteRecursively()
                return@forEach
            }
            activeDir.mkdirs()
            val target = File(activeDir, legacyFile.name)
            val activePart = File(activeDir, legacyFile.name + PART_SUFFIX)
            when {
                // A download for this model is in progress in the active dir — don't touch it.
                activePart.exists() -> Unit
                // Already present in the new location (e.g. re-downloaded) → drop the orphan.
                target.exists() -> legacyFile.delete()
                // Same-filesystem move (won't happen internal→external, but cheap to try).
                legacyFile.renameTo(target) -> Unit
                // Cross-filesystem: copy to a temp file, then atomically rename into place, so a
                // crash mid-copy can never leave a truncated file at the real model name (which a
                // later launch would mistake for complete and delete the good legacy copy behind).
                else -> {
                    val tmp = File(activeDir, legacyFile.name + ".migrating")
                    tmp.delete()
                    val copied = runCatching {
                        legacyFile.inputStream().use { input ->
                            tmp.outputStream().use { output -> input.copyTo(output) }
                        }
                    }.isSuccess
                    if (copied && tmp.renameTo(target)) {
                        legacyFile.delete()
                    } else {
                        tmp.delete() // keep the legacy file; retry on a future launch
                    }
                }
            }
        }
        legacyDir.delete() // remove the now-empty legacy dir (no-op if anything remains)
    }

    /**
     * Starts (or resumes) downloading [model] via [ModelDownloadService] — a foreground service,
     * so the multi-GB transfer keeps running with the screen off and the app backgrounded. No-op
     * if a download is already in flight.
     */
    fun startDownload(appContext: Context, model: LlmModel) {
        if (_downloadState.value is ModelDownloadState.Downloading) return
        ModelDownloadService.start(appContext.applicationContext, model.id)
    }

    /**
     * Stops the in-flight download. The `.part` file is intentionally **kept** so the next
     * [startDownload] resumes from where it left off instead of restarting.
     */
    fun cancelDownload(appContext: Context) {
        ModelDownloadService.cancel(appContext.applicationContext)
        _downloadState.value = ModelDownloadState.Idle
    }

    /**
     * The actual download work, driven by [ModelDownloadService] (which owns the foreground
     * lifecycle + wake lock). Streams [model] to its `.part` file with range-resume, reporting
     * progress through [onProgress] (notification) and [downloadState] (UI), then atomically
     * renames to the final file on success. On error or cancellation the `.part` file is **kept**
     * so a later attempt resumes; a network/IO error surfaces as [ModelDownloadState.Failed].
     */
    suspend fun runDownload(appContext: Context, model: LlmModel, onProgress: (Long, Long) -> Unit) {
        val context = appContext.applicationContext
        val dir = modelsDir(context).apply { mkdirs() }
        val partFile = File(dir, model.fileName + PART_SUFFIX)
        val finalFile = File(dir, model.fileName)
        try {
            val resumeFrom = if (partFile.exists()) partFile.length() else 0L
            _downloadState.value =
                ModelDownloadState.Downloading(model, resumeFrom, model.approxSizeBytes)
            downloadTo(model, partFile, onProgress)
            // Only a fully-streamed file is ever given the real name.
            if (finalFile.exists()) finalFile.delete()
            if (!partFile.renameTo(finalFile)) {
                throw IllegalStateException("Could not finalize the downloaded file.")
            }
            _downloadState.value = ModelDownloadState.Completed(model)
            _downloadedRevision.value++
        } catch (e: CancellationException) {
            // Stopped by the user: keep the .part file so the next start resumes.
            _downloadState.value = ModelDownloadState.Idle
            throw e
        } catch (e: Exception) {
            // Network/IO failure: keep the .part file so Retry resumes from here.
            _downloadState.value = ModelDownloadState.Failed(model, friendlyMessage(e))
        }
    }

    /**
     * Deletes [model] from disk. If that model is the one currently loaded for inference, it is
     * closed first so the native handle (and its mmap of the file) is released before the delete.
     */
    fun deleteDownloadedModel(appContext: Context, model: LlmModel) {
        scope.launch {
            inferenceMutex.withLock {
                if (loadedModelId == model.id) {
                    loaded?.close()
                    loaded = null
                    loadedModelId = null
                }
            }
            modelFile(appContext, model).delete()
            _downloadedRevision.value++
            _downloadState.value = ModelDownloadState.Idle
        }
    }

    /**
     * Translates [japaneseText] with the active on-device model, loading it on first use.
     *
     * Model selection: the settings' `activeModelId`, falling back to the first downloaded model
     * if that one isn't present, and throwing [LlamaModelException] if nothing is downloaded.
     *
     * The user content mirrors [moe.antimony.hoshi.features.ai.OpenAiChatClient.buildRequestBody]:
     * `japaneseText` alone when [instruction] is blank, otherwise `"<instruction>\n\n<text>"`.
     */
    suspend fun translate(
        appContext: Context,
        instruction: String,
        japaneseText: String,
        // Headroom for an explanation (translation + grammar/vocab breakdown). A translate-only
        // model still stops at EOG after the short translation, so this doesn't slow it down.
        maxTokens: Int = 512,
    ): OfflineTranslationResult {
        val context = appContext.applicationContext
        val model = resolveActiveModel(context)
        val userContent = if (instruction.isBlank()) {
            japaneseText
        } else {
            "${instruction.trim()}\n\n$japaneseText"
        }
        // Hold [inferenceMutex] across BOTH the (re)load and the generation, so a concurrent
        // deleteDownloadedModel()/model-swap cannot free the native handle mid-translation.
        return inferenceMutex.withLock {
            if (loadedModelId != model.id || loaded == null) {
                // Active model changed (or first load): swap the resident model.
                loaded?.close()
                loaded = LlamaInference.load(
                    modelPath = modelFile(context, model).path,
                    modelId = model.id,
                    nThreads = Runtime.getRuntime().availableProcessors().coerceIn(2, 8),
                    nCtx = min(model.contextLength, 2048),
                )
                loadedModelId = model.id
            }
            loaded!!.translate(userContent, maxTokens)
        }
    }

    /**
     * Resolves which model to translate with: the settings choice if downloaded, else the first
     * downloaded catalog model.
     *
     * @throws LlamaModelException if no model is downloaded.
     */
    private suspend fun resolveActiveModel(context: Context): LlmModel {
        val activeId = context.offlineTranslationSettingsRepository().settings.first().activeModelId
        val preferred = LlmModelCatalog.byId(activeId)
        if (preferred != null && isDownloaded(context, preferred)) return preferred
        return downloadedModels(context).firstOrNull()
            ?: throw LlamaModelException(
                "No on-device model downloaded yet. Download one in Settings → ChatGPT.",
            )
    }

    private fun modelsDir(appContext: Context): File {
        val context = appContext.applicationContext
        // Multi-GB model files live in app-specific EXTERNAL storage (getExternalFilesDir): no
        // runtime permission needed, removed on uninstall, and it doesn't eat into the limited
        // internal data partition. Falls back to internal storage if external is unavailable.
        val base = context.getExternalFilesDir(null) ?: context.filesDir
        return File(base, MODELS_DIR)
    }

    /**
     * Streams [model] to [partFile], **resuming** from any existing bytes via an HTTP `Range`
     * request: 206 → append to the partial file; 200 → server ignored the range, so restart;
     * 416 → the partial file already holds everything, so it's complete. Follows redirects to the
     * CDN, emits throttled progress (UI + [onProgress]), and honors cancellation between chunks.
     */
    private suspend fun downloadTo(model: LlmModel, partFile: File, onProgress: (Long, Long) -> Unit) {
        val existing = if (partFile.exists()) partFile.length() else 0L
        val connection = (URL(model.downloadUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            instanceFollowRedirects = true
            connectTimeout = 30_000
            readTimeout = 60_000
            if (existing > 0L) setRequestProperty("Range", "bytes=$existing-")
        }
        try {
            val code = connection.responseCode
            // The partial file is already the whole thing — nothing left to fetch.
            if (existing > 0L && code == HTTP_RANGE_NOT_SATISFIABLE) {
                onProgress(existing, existing)
                return
            }
            val resuming = code == HttpURLConnection.HTTP_PARTIAL
            if (code != HttpURLConnection.HTTP_OK && !resuming) {
                throw IllegalStateException("Download failed (HTTP $code).")
            }
            // Content-Length is the *remaining* bytes on a 206, the full size on a 200, or absent
            // (-1) on a chunked response; fall back to the catalog's advertised size for a total.
            val reported = connection.contentLengthLong
            val totalBytes = when {
                resuming && reported > 0L -> existing + reported
                reported > 0L -> reported
                else -> model.approxSizeBytes
            }

            var downloaded = if (resuming) existing else 0L
            var lastEmitBytes = downloaded
            var lastEmitTime = System.currentTimeMillis()
            _downloadState.value = ModelDownloadState.Downloading(model, downloaded, totalBytes)
            onProgress(downloaded, totalBytes)
            connection.inputStream.use { input ->
                // Append when resuming (206); overwrite when the server ignored the range (200).
                FileOutputStream(partFile, /* append = */ resuming).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        coroutineContext.ensureActive() // honor cancellation between chunks
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        downloaded += read

                        val now = System.currentTimeMillis()
                        if (downloaded - lastEmitBytes >= PROGRESS_BYTES ||
                            now - lastEmitTime >= PROGRESS_INTERVAL_MS
                        ) {
                            _downloadState.value =
                                ModelDownloadState.Downloading(model, downloaded, totalBytes)
                            onProgress(downloaded, totalBytes)
                            lastEmitBytes = downloaded
                            lastEmitTime = now
                        }
                    }
                }
            }
            // Final progress tick so the UI lands on 100% before Completed.
            _downloadState.value = ModelDownloadState.Downloading(model, downloaded, totalBytes)
            onProgress(downloaded, totalBytes)
        } finally {
            connection.disconnect()
        }
    }

    /** Maps a thrown exception to a short, user-facing message. */
    private fun friendlyMessage(e: Exception): String =
        e.message?.takeIf { it.isNotBlank() } ?: "Download failed. Check your connection and retry."
}
