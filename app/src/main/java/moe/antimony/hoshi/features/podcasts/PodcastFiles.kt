package moe.antimony.hoshi.features.podcasts

import android.content.Context
import java.io.File
import androidx.core.util.AtomicFile
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal class PodcastFiles(
    private val root: File,
    private val atomicFile: (File) -> AtomicFile = ::AtomicFile,
) {
    constructor(context: Context) : this(File(context.noBackupFilesDir, "podcasts"))
    private val json = Json { ignoreUnknownKeys = true }

    companion object {
        // AtomicFile has no locking of its own. The repository and workers create separate
        // PodcastFiles instances, so readers, writers and pruning must share this monitor.
        private val storageLock = Any()
    }

    fun loadCatalogue(account: String): PodcastCatalogue? = synchronized(storageLock) {
        require(validPodcastId(account))
        val cached = read(account, "catalogue.json")
        val library = read(account, "downloads.json")
        if (cached == null && library == null) return@synchronized null
        val saved = library ?: PodcastCatalogue(emptyList())
        (cached ?: PodcastCatalogue(emptyList()))
            .withSavedDownloads(saved, downloadedIds(account, saved.episodes))
            .copy(worker = null, generating = null)
    }

    /** Persist the fresh listing without losing metadata for lessons already on this device. */
    fun saveCatalogue(account: String, catalogue: PodcastCatalogue): PodcastCatalogue = synchronized(storageLock) {
        require(validPodcastId(account))
        val cached = read(account, "catalogue.json")
        val library = read(account, "downloads.json") ?: PodcastCatalogue(emptyList())
        // Existing installations have only catalogue.json. Include its downloaded rows on the
        // first refresh so they migrate even when this response has already omitted the show.
        val previous = PodcastCatalogue(
            episodes = (cached?.episodes.orEmpty() + library.episodes).distinctBy { it.id },
            shows = (cached?.shows.orEmpty() + library.shows).distinctBy { it.id },
        )
        val allEpisodes = catalogue.episodes + previous.episodes
        val downloaded = downloadedIds(account, allEpisodes)
        val merged = catalogue.withSavedDownloads(previous, downloaded)
        val saved = PodcastCatalogue(
            episodes = (merged.episodes.filter { it.id in downloaded } + library.episodes).distinctBy { it.id },
            shows = (merged.shows + library.shows).distinctBy { it.id },
        ).forDownloadLibrary()
        if (saved.episodes.isNotEmpty()) write(account, "downloads.json", saved)
        // A disk snapshot cannot establish that a worker is still alive or progressing.
        write(account, "catalogue.json", merged.copy(worker = null, generating = null))
        merged
    }

    /** Remember metadata before enqueueing: a show may be archived while the transfer runs. */
    fun rememberDownload(account: String, episode: PodcastEpisode) = synchronized(storageLock) {
        require(validPodcastId(account) && validPodcastId(episode.id))
        val library = read(account, "downloads.json") ?: PodcastCatalogue(emptyList())
        val cached = read(account, "catalogue.json")
        write(account, "downloads.json", PodcastCatalogue(
            episodes = (listOf(episode) + library.episodes).distinctBy { it.id },
            shows = (cached?.shows.orEmpty() + library.shows).distinctBy { it.id },
        ).forDownloadLibrary())
    }

    private fun PodcastCatalogue.forDownloadLibrary(): PodcastCatalogue = copy(
        shows = shows.filter { show -> episodes.any { it.show == show.id } }.map { it.copy(feedStale = false) },
    )

    private fun downloadedIds(account: String, episodes: List<PodcastEpisode>): Set<String> =
        episodes.filter { validPodcastId(it.id) && audio(account, it.id).isFile }.map { it.id }.toSet()

    private fun read(account: String, name: String): PodcastCatalogue? = runCatching {
        json.decodeFromString<PodcastCatalogue>(atomicFile(File(File(root, account), name)).readFully().toString(Charsets.UTF_8))
    }.getOrNull()

    private fun write(account: String, name: String, catalogue: PodcastCatalogue) {
        val directory = File(root, account).apply { mkdirs() }
        val file = atomicFile(File(directory, name))
        val output = file.startWrite()
        try {
            output.write(json.encodeToString(catalogue).toByteArray())
            file.finishWrite(output)
        } catch (error: Exception) {
            file.failWrite(output)
            throw error
        }
    }
    fun audio(account: String, id: String): File {
        require(validPodcastId(account) && validPodcastId(id))
        return File(File(root, account), "$id.mp3")
    }
    /** Removes every account's lessons, catalogue and partial downloads except [keep]'s. */
    fun pruneExcept(keep: String?) = synchronized(storageLock) {
        root.listFiles()?.filter { it.isDirectory && it.name != keep }?.forEach { it.deleteRecursively() }
    }
}

/** The server owns current rows; omitted downloaded rows and their show names remain local. */
internal fun PodcastCatalogue.withSavedDownloads(saved: PodcastCatalogue, downloaded: Set<String>): PodcastCatalogue {
    val current = episodes.filter { validPodcastId(it.id) }.distinctBy { it.id }
    val currentIds = current.map { it.id }.toSet()
    val retained = saved.episodes.filter { it.id in downloaded && it.id !in currentIds }.distinctBy { it.id }
    val retainedShows = retained.map { it.show }.toSet()
    return copy(
        episodes = (current + retained).sortedByDescending { it.publishedAt },
        shows = (shows + saved.shows.filter { it.id in retainedShows }.map { it.copy(feedStale = false) }).distinctBy { it.id },
    )
}
