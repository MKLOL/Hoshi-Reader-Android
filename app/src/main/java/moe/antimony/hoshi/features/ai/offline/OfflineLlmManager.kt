package moe.antimony.hoshi.features.ai.offline

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
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

    /** Long-lived scope for downloads; survives individual screens. */
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

    /** Tracks the in-flight download so it can be cancelled. */
    private var downloadJob: Job? = null

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
     * Starts downloading [model] on the internal scope. No-op if a download is already running.
     *
     * Streams to a `<fileName>.part` temp file, emits [ModelDownloadState.Downloading] updates
     * (throttled), then atomically renames `.part` → final on success. On any error the temp
     * file is removed and [ModelDownloadState.Failed] is emitted with a user-facing message.
     */
    fun startDownload(appContext: Context, model: LlmModel) {
        // Ignore if a download is already in flight.
        if (downloadJob?.isActive == true) return
        val context = appContext.applicationContext
        downloadJob = scope.launch {
            val dir = modelsDir(context).apply { mkdirs() }
            val partFile = File(dir, model.fileName + PART_SUFFIX)
            val finalFile = File(dir, model.fileName)
            try {
                _downloadState.value = ModelDownloadState.Downloading(model, 0L, model.approxSizeBytes)
                downloadTo(model, partFile)
                // Atomic-ish publish: only a fully-streamed file is ever given the real name.
                if (finalFile.exists()) finalFile.delete()
                if (!partFile.renameTo(finalFile)) {
                    throw IllegalStateException("Could not finalize the downloaded file.")
                }
                _downloadState.value = ModelDownloadState.Completed(model)
                _downloadedRevision.value++
            } catch (e: Exception) {
                partFile.delete()
                if (coroutineContext.isActive) {
                    _downloadState.value = ModelDownloadState.Failed(model, friendlyMessage(e))
                } else {
                    // Cancelled via cancelDownload(): leave state as that call set it (Idle).
                    _downloadState.value = ModelDownloadState.Idle
                }
            }
        }
    }

    /**
     * Cancels the in-flight download and resets to [ModelDownloadState.Idle]. The actual `.part`
     * file is removed by the download coroutine's `catch` block when the cancellation propagates.
     */
    fun cancelDownload() {
        downloadJob?.cancel()
        downloadJob = null
        _downloadState.value = ModelDownloadState.Idle
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
        maxTokens: Int = 256,
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

    private fun modelsDir(appContext: Context): File =
        File(appContext.applicationContext.filesDir, MODELS_DIR)

    /**
     * Streams [model] to [partFile] via `HttpURLConnection`, following redirects to the CDN and
     * emitting throttled progress. Honors coroutine cancellation between chunks.
     */
    private suspend fun downloadTo(model: LlmModel, partFile: File) {
        val connection = (URL(model.downloadUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            instanceFollowRedirects = true
            connectTimeout = 30_000
            readTimeout = 60_000
        }
        try {
            val code = connection.responseCode
            if (code !in 200..299) {
                throw IllegalStateException("Download failed (HTTP $code).")
            }
            // Content-Length may be absent or -1 on a chunked/redirected response; fall back
            // to the catalog's advertised size so the progress bar still has a denominator.
            val reported = connection.contentLengthLong
            val totalBytes = if (reported > 0L) reported else model.approxSizeBytes

            var downloaded = 0L
            var lastEmitBytes = 0L
            var lastEmitTime = System.currentTimeMillis()
            connection.inputStream.use { input ->
                partFile.outputStream().use { output ->
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
                            lastEmitBytes = downloaded
                            lastEmitTime = now
                        }
                    }
                }
            }
            // Final progress tick so the UI lands on 100% before Completed.
            _downloadState.value = ModelDownloadState.Downloading(model, downloaded, totalBytes)
        } finally {
            connection.disconnect()
        }
    }

    /** Maps a thrown exception to a short, user-facing message. */
    private fun friendlyMessage(e: Exception): String =
        e.message?.takeIf { it.isNotBlank() } ?: "Download failed. Check your connection and retry."
}
