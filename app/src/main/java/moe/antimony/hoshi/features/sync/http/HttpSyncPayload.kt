package moe.antimony.hoshi.features.sync.http

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.DigestOutputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Book-payload (the .epub file or the mokuro pages/json) round-trip over the v2 KV sync.
 *
 * Wire layout, per book:
 *  - `books/{syncId}/payload.zip`      — `application/zip`, the actual content bytes
 *  - `books/{syncId}/payload.manifest` — JSON `{sha256, sizeBytes, originalName, format}`,
 *                                        used by clients to decide whether to fetch the zip
 *
 * The manifest is the change-detector. We compute the local zip's sha256 and compare to the
 * remote manifest; if they match, the zip upload is skipped. The first sync of a book is
 * therefore expensive (~50 MB zip) and subsequent ones cost two HTTP round-trips (GET
 * manifest, then nothing).
 *
 * The bookmark / chat-log sidecars are **excluded** from the zip on the way up — they sync
 * through the per-blob path (`bookmark`, `chat/{...}` keys). This keeps the payload zip
 * stable across page-turns so the sha256 doesn't churn.
 */

@Serializable
data class HttpSyncPayloadManifest(
    /** `sha256:<hex>` of the zip bytes — same shape the server stamps on PUT etags. */
    val sha256: String,
    val sizeBytes: Long,
    /** The local folder name used at import time. Receiving devices reuse this. */
    val originalName: String,
    val format: HttpSyncContentType,
)

/**
 * Files inside a book directory that should NOT be zipped — they're per-device or synced
 * via their own key path. Including ANY of these in the payload would:
 *
 *  - corrupt cross-device convergence (the file has a per-device UUID, a wallclock
 *    timestamp, or otherwise mutates as the user reads), or
 *  - waste bytes on state that already syncs through its own key, or
 *  - feed the staleness check noise that isn't real content change.
 *
 * **If you add a new per-book sidecar to Hoshi, add its filename here.** Otherwise
 * its mutations will invalidate the payload-sha cache on every Sync now, forcing a
 * multi-second re-zip + multi-MB re-upload of unchanged content.
 *
 * Concretely, each excluded file is here because:
 *  - `bookmark.json`   — per-device, rewritten on every page turn. Synced as `…/bookmark`.
 *  - `ai_chat_log.json` — per-device, appended per ChatGPT reply. Synced as `…/chat/…`.
 *  - `metadata.json`   — per-device `id` UUID + `lastAccess` bumped on every book open.
 *                        Receiving device makes its own via `importRemoteOnlyBook`.
 *  - `statistics.json` — per-book reading stats updated AS THE USER SCROLLS. **This was
 *                        the bug the user hit as "I scrolled a bit, pressed sync, said
 *                        book payload up" even after metadata.json was excluded.**
 *  - `sasayaki_match.json` / `sasayaki_playback.json` — per-device audiobook alignment
 *                        and playhead, mutate while listening.
 *  - `.payload.sha256.cache` — the cache itself; including it would be circular.
 */
internal val PAYLOAD_EXCLUDED_FILES: Set<String> = setOf(
    "bookmark.json",
    "ai_chat_log.json",
    "metadata.json",
    "statistics.json",
    "sasayaki_match.json",
    "sasayaki_playback.json",
    PAYLOAD_SHA_CACHE_FILENAME,
)

/**
 * Directories under a book root that are excluded from the payload, by directory name.
 * Currently used for `Sasayaki/` — the per-device audiobook audio file lives under there
 * with a filename that can vary per device (copy vs. linked import), and the file itself
 * is large and stable post-import, so re-syncing it just to satisfy sha consistency would
 * be wasteful. The audio sync (when we add it) gets its own key path.
 */
internal val PAYLOAD_EXCLUDED_DIRS: Set<String> = setOf("Sasayaki")

/**
 * Sidecar that caches the last-computed payload sha so subsequent syncs of an unchanged
 * book don't have to re-zip and re-hash the entire directory. Living alongside the
 * bookmark / chat sidecars is fine — like them, it never travels in the zip itself.
 */
internal const val PAYLOAD_SHA_CACHE_FILENAME: String = ".payload.sha256.cache"

internal fun payloadZipKey(syncId: String): String = "books/$syncId/payload.zip"
internal fun payloadManifestKey(syncId: String): String = "books/$syncId/payload.manifest"

/**
 * Zips a book directory, computes sha256 of the resulting bytes, uploads zip + manifest
 * to the KV server iff the server's manifest sha256 differs from local.
 *
 * Large book payloads are spooled to temp files. Mokuro page bundles can exceed Android's
 * per-process heap when held as one `ByteArray`, so the production upload/download path
 * never materializes `payload.zip` in memory.
 */
class HttpSyncPayloadCodec(
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /**
     * Outbound: returns `true` if a zip+manifest was uploaded, `false` if the server's
     * manifest already matched (no-op fast path). Throws [HttpSyncException] on network /
     * IO failure.
     *
     * **Fast path (the common case):** fetch the remote manifest first; if we have a valid
     * cached local sha256 that matches, return without re-zipping. A 50 MB mokuro volume
     * therefore costs one HTTPS GET (a few hundred bytes) on every sync after the first,
     * instead of multi-second zip + sha256 work.
     *
     * **Slow path:** zip + hash + compare; upload only if the sha actually differs from
     * what's on the server. Cache the freshly-computed sha so the next sync is fast.
     */
    /**
     * **Manifest-existence policy.** If the server already has a manifest for this
     * book, we **never** re-upload from this device. The user explicitly asked for this
     * after the codec re-uploaded their 50-100 MB book three times in a row from per-
     * device sidecar mtime drift (`metadata.json`, then `statistics.json`, then a fourth
     * unknown write site we never nailed down). Mokuro/EPUB content is immutable post-
     * import; everything that mutates as you read (bookmark, chat log, statistics, cover
     * regeneration, etc.) already syncs through its own key or is per-device-by-design.
     *
     * Cost: each `Sync now` for an already-synced book is exactly one HTTPS GET (the
     * manifest), no zip work, no hash work, no upload. Fast and predictable.
     *
     * Escape hatch: if the user genuinely modifies a book's content and wants to push
     * the new version, they `curl -X DELETE
     * https://<server>/v1/kv/books/{syncId}/payload.manifest` and run sync again. We
     * could surface a "re-upload book" button in settings later.
     *
     * First-upload-from-this-device path: still works. If no manifest exists, the zip is
     * built and PUT'd, the manifest is written, and the cache sidecar is updated so
     * future syncs hit the (also fast) "manifest exists" return.
     */
    suspend fun uploadIfChanged(
        transport: HttpSyncKvTransport,
        syncId: String,
        bookRoot: File,
        originalName: String,
        format: HttpSyncContentType,
        onByteProgress: ((bytesTransferred: Long, totalBytes: Long) -> Unit)? = null,
    ): Boolean = withContext(ioDispatcher) {
        val remoteManifest = fetchManifest(transport, syncId)
        if (remoteManifest != null) {
            // Server already has this book — refuse to re-upload. No matter what mtime
            // changes have happened locally (and they will happen, all the time — opening
            // a book, scrolling, chatting, etc.), the manifest's existence is the
            // authoritative signal that the user has already shipped a copy. We trust
            // that and stay quiet.
            return@withContext false
        }

        val spoolDir = bookRoot.parentFile ?: bookRoot
        val zipFile = File.createTempFile("hoshi-sync-upload-", ".zip", spoolDir)
        try {
            // First upload of this book from this device. This is the only path that does the
            // multi-second zip + hash.
            val sha = zipDirectoryToFile(bookRoot, zipFile)
            val localSize = zipFile.length()
            writeCachedSha(bookRoot.resolve(PAYLOAD_SHA_CACHE_FILENAME), sha)

            // PUT zip first so the manifest never points at a missing or stale blob.
            transport.putFile(
                key = payloadZipKey(syncId),
                contentType = "application/zip",
                file = zipFile,
                onByteProgress = onByteProgress,
            )
            val manifest = HttpSyncPayloadManifest(
                sha256 = sha,
                sizeBytes = localSize,
                originalName = originalName,
                format = format,
            )
            transport.put(
                key = payloadManifestKey(syncId),
                contentType = "application/json; charset=utf-8",
                body = json.encodeToString(HttpSyncPayloadManifest.serializer(), manifest).toByteArray(),
            )
            true
        } finally {
            zipFile.delete()
        }
    }

    /**
     * Returns the cached payload sha iff the cache sidecar exists AND no file under the
     * book root (excluding the per-key sidecars [PAYLOAD_EXCLUDED_FILES] which legitimately
     * change every page turn) has a `lastModified` newer than the cache itself.
     */
    private fun readCachedShaIfFresh(bookRoot: File, cacheFile: File): String? {
        if (!cacheFile.exists()) return null
        val cacheMtime = cacheFile.lastModified()
        val anyContentNewerThanCache = bookRoot.walkTopDown().any { file ->
            file.isFile &&
                file.name !in PAYLOAD_EXCLUDED_FILES &&
                !file.isInsideExcludedDir(bookRoot) &&
                file.lastModified() > cacheMtime
        }
        if (anyContentNewerThanCache) return null
        val raw = runCatching { cacheFile.readText().trim() }.getOrNull() ?: return null
        return raw.takeIf { it.startsWith("sha256:") }
    }

    /**
     * Returns `true` iff every non-excluded file under [bookRoot] has an mtime less than
     * or equal to [cacheFile]'s. When [cacheFile] doesn't exist, returns `false` because
     * we have no baseline to compare against. Used as the "extra-careful" gate before
     * a payload re-upload when the server already has the book.
     */
    private fun noContentFileNewerThan(bookRoot: File, cacheFile: File): Boolean {
        if (!cacheFile.exists()) return false
        val cacheMtime = cacheFile.lastModified()
        return bookRoot.walkTopDown().none { file ->
            file.isFile &&
                file.name !in PAYLOAD_EXCLUDED_FILES &&
                !file.isInsideExcludedDir(bookRoot) &&
                file.lastModified() > cacheMtime
        }
    }

    /**
     * Walks the parent chain up to (but not including) [bookRoot]; returns `true` iff any
     * intermediate directory's name appears in [PAYLOAD_EXCLUDED_DIRS]. Robust to nesting
     * (e.g., `Sasayaki/Subdir/audio.m4b` still counts as inside `Sasayaki`).
     */
    private fun File.isInsideExcludedDir(bookRoot: File): Boolean {
        var current: File? = this.parentFile
        while (current != null && current != bookRoot) {
            if (current.name in PAYLOAD_EXCLUDED_DIRS) return true
            current = current.parentFile
        }
        return false
    }

    private fun writeCachedSha(cacheFile: File, sha: String) {
        runCatching {
            cacheFile.writeText(sha)
            // Defend against filesystems with coarse mtime resolution (FAT32 / sdcard:
            // 2-second granularity). If a content file's mtime ends up identical to
            // the cache's, our strict `>` check would treat the cache as fresh forever.
            // Bump the cache file's mtime to one second past the latest content file we
            // care about so the next staleness check has a meaningful baseline.
            val parent = cacheFile.parentFile
            if (parent != null && parent.isDirectory) {
                val maxContentMtime = parent.walkTopDown()
                    .filter {
                        it.isFile &&
                            it.name !in PAYLOAD_EXCLUDED_FILES &&
                            !it.isInsideExcludedDir(parent)
                    }
                    .maxOfOrNull { it.lastModified() }
                    ?: 0L
                cacheFile.setLastModified(maxContentMtime + 1_000L)
            }
        }
        // Failure here just means subsequent syncs will recompute. Not fatal.
    }

    /**
     * Inbound: fetches and unpacks the zip into [targetDir]. Caller is responsible for
     * creating the target directory and registering the book in [moe.antimony.hoshi.epub.BookRepository].
     * Returns the manifest so the caller can use `originalName` / `format` for the
     * post-unpack registration.
     */
    suspend fun downloadAndUnpack(
        transport: HttpSyncKvTransport,
        syncId: String,
        targetDir: File,
        onByteProgress: ((bytesTransferred: Long, totalBytes: Long) -> Unit)? = null,
    ): HttpSyncPayloadManifest = withContext(ioDispatcher) {
        val manifest = fetchManifest(transport, syncId)
            ?: throw HttpSyncException("No payload manifest for $syncId.")
        val spoolDir = targetDir.parentFile ?: targetDir
        val zipFile = File.createTempFile("hoshi-sync-download-", ".zip", spoolDir)
        try {
            transport.downloadToFile(payloadZipKey(syncId), zipFile, onByteProgress)
                ?: throw HttpSyncException("Payload zip missing for $syncId (manifest existed).")
            // Validate sha256 before unpacking — a corrupted zip should fail loud, not produce a
            // half-imported book directory.
            val actualSha = sha256Hex(zipFile)
            if (actualSha != manifest.sha256) {
                throw HttpSyncException(
                    "Payload zip for $syncId failed sha256 check (expected ${manifest.sha256}, got $actualSha).",
                )
            }
            unzipInto(zipFile, targetDir)
            manifest
        } finally {
            zipFile.delete()
        }
    }

    /**
     * Fetches just the remote manifest (no payload). Returns `null` if the server has no
     * manifest for this book. Throws on network / 5xx errors.
     */
    suspend fun fetchManifest(
        transport: HttpSyncKvTransport,
        syncId: String,
    ): HttpSyncPayloadManifest? {
        val fetched = transport.get(payloadManifestKey(syncId)) ?: return null
        return runCatching {
            json.decodeFromString(
                HttpSyncPayloadManifest.serializer(),
                fetched.body.toString(Charsets.UTF_8),
            )
        }.getOrElse { error ->
            throw HttpSyncException("Manifest for $syncId: malformed JSON (${error.message})")
        }
    }

    // ----- Zip helpers (internal so tests can target them) -------------------------------

    /** Test helper for small fixtures; production upload uses [zipDirectoryToFile]. */
    internal fun zipDirectory(root: File): Pair<ByteArray, String> {
        require(root.isDirectory) { "Book root is not a directory: $root" }
        val buffer = ByteArrayOutputStream()
        ZipOutputStream(buffer).use { zip ->
            zipRecursive(root, root, zip)
        }
        val finalBytes = buffer.toByteArray()
        return finalBytes to sha256Hex(finalBytes)
    }

    /**
     * Streams every regular file under [root] (except [PAYLOAD_EXCLUDED_FILES]) into
     * [targetZip], hashing the exact zip bytes as they are written. Returns the lowercase
     * hex `sha256:<hex>` digest.
     */
    internal fun zipDirectoryToFile(root: File, targetZip: File): String {
        require(root.isDirectory) { "Book root is not a directory: $root" }
        targetZip.parentFile?.mkdirs()
        val digest = MessageDigest.getInstance("SHA-256")
        targetZip.outputStream().buffered(STREAM_BUFFER_SIZE).use { fileOut ->
            DigestOutputStream(fileOut, digest).use { digestOut ->
                ZipOutputStream(digestOut).use { zip ->
                    zipRecursive(root, root, zip)
                }
            }
        }
        return "sha256:" + digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun zipRecursive(rootDir: File, current: File, zip: ZipOutputStream) {
        val children = current.listFiles()?.sortedBy { it.name } ?: return
        for (child in children) {
            if (child.isDirectory) {
                if (child.name in PAYLOAD_EXCLUDED_DIRS) continue
                zipRecursive(rootDir, child, zip)
                continue
            }
            if (child.name in PAYLOAD_EXCLUDED_FILES) continue
            if (child.isInsideExcludedDir(rootDir)) continue
            // Path inside the zip is the file's relative path under the book root, with
            // forward-slash separators so non-Android extractors decode it correctly.
            val entryPath = child.relativeTo(rootDir).path.replace(File.separatorChar, '/')
            zip.putNextEntry(ZipEntry(entryPath))
            child.inputStream().use { input ->
                val buf = ByteArray(8 * 1024)
                while (true) {
                    val read = input.read(buf)
                    if (read <= 0) break
                    zip.write(buf, 0, read)
                }
            }
            zip.closeEntry()
        }
    }

    /**
     * Unzips [bytes] into [targetDir]. Refuses entries that would escape the directory
     * (zip-slip defense). Throws [HttpSyncException] on malformed zip or escape attempts.
     */
    internal fun unzipInto(bytes: ByteArray, targetDir: File) {
        unzipStream({ bytes.inputStream() }, targetDir)
    }

    internal fun unzipInto(zipFile: File, targetDir: File) {
        unzipStream({ zipFile.inputStream().buffered(STREAM_BUFFER_SIZE) }, targetDir)
    }

    private fun unzipStream(openInput: () -> java.io.InputStream, targetDir: File) {
        targetDir.mkdirs()
        val canonicalTarget = targetDir.canonicalFile
        try {
            ZipInputStream(openInput()).use { zin ->
                while (true) {
                    val entry = zin.nextEntry ?: break
                    val outFile = canonicalTarget.resolve(entry.name).canonicalFile
                    if (!outFile.path.startsWith(canonicalTarget.path + File.separator) &&
                        outFile.path != canonicalTarget.path) {
                        throw HttpSyncException("Refused to unpack zip-slip entry: ${entry.name}")
                    }
                    if (entry.isDirectory) {
                        outFile.mkdirs()
                    } else {
                        outFile.parentFile?.mkdirs()
                        outFile.outputStream().use { out -> zin.copyTo(out) }
                    }
                    zin.closeEntry()
                }
            }
        } catch (e: HttpSyncException) {
            throw e
        } catch (e: Exception) {
            throw HttpSyncException("Failed to unpack payload zip: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private fun sha256Hex(bytes: ByteArray): String {
        val d = MessageDigest.getInstance("SHA-256").digest(bytes)
        return "sha256:" + d.joinToString("") { "%02x".format(it) }
    }

    private fun sha256Hex(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered(STREAM_BUFFER_SIZE).use { input ->
            val buffer = ByteArray(STREAM_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return "sha256:" + digest.digest().joinToString("") { "%02x".format(it) }
    }
}

private const val STREAM_BUFFER_SIZE = 64 * 1024
