package moe.antimony.hoshi.features.podcasts

import android.content.Context
import java.io.File
import android.util.AtomicFile
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal class PodcastFiles(context: Context) {
    private val json = Json { ignoreUnknownKeys = true }
    private val root = File(context.noBackupFilesDir, "podcasts")
    fun loadCatalogue(account: String): PodcastCatalogue? {
        require(validPodcastId(account))
        return runCatching {
            json.decodeFromString<PodcastCatalogue>(AtomicFile(File(File(root, account), "catalogue.json")).readFully().toString(Charsets.UTF_8))
        }.getOrNull()
    }
    fun saveCatalogue(account: String, catalogue: PodcastCatalogue) {
        require(validPodcastId(account))
        val directory = File(root, account).apply { mkdirs() }
        val file = AtomicFile(File(directory, "catalogue.json"))
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
    fun pruneExcept(keep: String?) {
        root.listFiles()?.filter { it.isDirectory && it.name != keep }?.forEach { it.deleteRecursively() }
    }
    fun deleteAccount(account: String) {
        require(validPodcastId(account))
        File(root, account).deleteRecursively()
    }
}
