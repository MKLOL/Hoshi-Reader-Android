package moe.antimony.hoshi.features.update

/**
 * Splits update work at app start into the part the first composition needs and the part that
 * must stay off the main thread.
 *
 * [snapshot] only reads the persisted record; [DownloadedUpdatePrompt] uses it to decide which
 * "update available" prompt belongs to this launch. [reconcile] talks to DownloadManager,
 * parses APKs and hashes a completed download, which can take seconds on slow storage, so
 * `Application.onCreate` must never block on it. Prompts and About read the live record flow
 * and pick up its results when they land.
 */
internal class UpdateStartup(
    private val store: UpdateDownloadStore,
    private val currentVersionName: String,
    private val discardInstalledUpdate: suspend (currentVersionName: String) -> Unit,
    private val deleteCurrentVersionApks: suspend () -> Unit,
    private val refresh: suspend () -> Unit,
) {
    /**
     * The persisted record and nothing else: no DownloadManager query, no file access. A
     * corrupt preferences file yields no snapshot rather than a crash at launch.
     */
    suspend fun snapshot(): UpdateDownloadRecord? = runCatching { store.load() }.getOrNull()

    /** Reconciles the persisted record with the system download; each step is best effort. */
    suspend fun reconcile() {
        runCatching { discardInstalledUpdate(currentVersionName) }
        runCatching { deleteCurrentVersionApks() }
        runCatching { refresh() }
    }
}
