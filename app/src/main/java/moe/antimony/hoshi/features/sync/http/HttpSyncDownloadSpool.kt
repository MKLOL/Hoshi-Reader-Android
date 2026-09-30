package moe.antimony.hoshi.features.sync.http

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Resumable payload archives live beside the Books folder, one directory per account and remote
 * key, so a restarted process finds the same bytes. A directory is removed once its archive
 * unpacks; [pruneAfterSync] clears the ones no sync came back for.
 */
internal object HttpSyncDownloadSpool {
    const val DIRECTORY = ".http-sync-downloads"
    const val IMPORT_STAGING_PREFIX = ".http-sync-import-"

    /** Nobody retried for a week: the book was deleted elsewhere or the account was replaced. */
    val ABANDONED_DOWNLOAD_MILLIS = TimeUnit.DAYS.toMillis(7)

    /** Import staging is never resumed, so a day-old folder was stranded by a killed process. */
    val ABANDONED_STAGING_MILLIS = TimeUnit.DAYS.toMillis(1)

    private val locks = Array(32) { Mutex() }

    /** Held while a download reads or writes the directory named [identity]. */
    fun lockFor(identity: String): Mutex = locks[(identity.hashCode() and Int.MAX_VALUE) % locks.size]

    /**
     * Runs after a sync pass that finished without errors. Every archive the pass still needed was
     * just retried, which restarts its clock, so a returning user resumes rather than re-downloads.
     */
    suspend fun pruneAfterSync(booksDirectory: File) = withContext(Dispatchers.IO) {
        pruneAbandoned(booksDirectory.parentFile ?: booksDirectory)
    }

    /** Removes abandoned archives and import staging under [spoolDir]; a held download is kept. */
    fun pruneAbandoned(spoolDir: File, now: Long = System.currentTimeMillis()) {
        spoolDir.resolve(DIRECTORY).listFiles()?.forEach { directory ->
            if (now - directory.lastActivityMillis() < ABANDONED_DOWNLOAD_MILLIS) return@forEach
            val lock = lockFor(directory.name)
            if (!lock.tryLock()) return@forEach
            try {
                if (now - directory.lastActivityMillis() >= ABANDONED_DOWNLOAD_MILLIS) directory.deleteRecursively()
            } finally {
                lock.unlock()
            }
        }
        spoolDir.listFiles { file -> file.name.startsWith(IMPORT_STAGING_PREFIX) }?.forEach { directory ->
            if (now - directory.lastActivityMillis() >= ABANDONED_STAGING_MILLIS) directory.deleteRecursively()
        }
    }
}

/** The newest modification anywhere in this file or directory tree. */
internal fun File.lastActivityMillis(): Long =
    walkTopDown().maxOfOrNull { it.lastModified() } ?: lastModified()
