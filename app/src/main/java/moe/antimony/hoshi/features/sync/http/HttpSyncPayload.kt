package moe.antimony.hoshi.features.sync.http

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import moe.antimony.hoshi.epub.GENERATED_COVER_FILENAME
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.DigestOutputStream
import java.security.MessageDigest
import java.text.Normalizer
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Book-payload (the .epub file or the mokuro pages/json) round-trip over the v2 KV sync.
 *
 * Wire layout, per book:
 *  - Mokuro: `books/{syncId}/payload.zip` + `payload.manifest`
 *  - EPUB: `books/{syncId}/epub.zip` + `epub.manifest`
 *
 * EPUB uses the separate key pair established by the iOS client. Downloaders still accept the
 * legacy Android `payload.*` EPUB shape so books uploaded by Android 0.11 remain recoverable.
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
    /** Cross-platform hash of sorted static paths + bytes (independent of ZIP metadata). */
    val contentSha256: String? = null,
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
    "manga_statistics.json",
    "sasayaki_match.json",
    "sasayaki_playback.json",
    "bookinfo.json",
    "highlights.json",
    "pretranslations.json",     // synced as …/pretranslations
    "sentence_translations.json", // synced as …/sentences
    // Cover materialized by a sync receiver for a payload that shipped none (both platforms);
    // it must stay out of the cross-platform content hash.
    GENERATED_COVER_FILENAME,
    PAYLOAD_SHA_CACHE_FILENAME,
    PAYLOAD_ZIP_SHA_CACHE_FILENAME,
    LEGACY_PAYLOAD_SHA_CACHE_FILENAME,
    PAYLOAD_LOCAL_DIRTY_FILENAME,
    PAYLOAD_REPLACEMENT_TARGET_FILENAME,
)

/**
 * Directories under a book root that are excluded from the payload, by directory name.
 * Currently used for `Sasayaki/` — the per-device audiobook audio file lives under there
 * with a filename that can vary per device (copy vs. linked import), and the file itself
 * is large and stable post-import, so re-syncing it just to satisfy sha consistency would
 * be wasteful. The audio sync (when we add it) gets its own key path.
 */
internal val PAYLOAD_EXCLUDED_DIRS: Set<String> = setOf("Sasayaki")

// Derived from static EPUB bytes. It remains excluded from uploads, but a replacement must
// regenerate it rather than carry chapter offsets from the old payload forward.
private val PAYLOAD_REPLACEMENT_PRESERVED_FILES = PAYLOAD_EXCLUDED_FILES - setOf(
    "bookinfo.json",
    PAYLOAD_SHA_CACHE_FILENAME,
    PAYLOAD_ZIP_SHA_CACHE_FILENAME,
    PAYLOAD_REPLACEMENT_TARGET_FILENAME,
)

/**
 * Sidecar that caches the last-computed payload sha so subsequent syncs of an unchanged
 * book don't have to re-zip and re-hash the entire directory. Living alongside the
 * bookmark / chat sidecars is fine — like them, it never travels in the zip itself.
 */
internal const val PAYLOAD_SHA_CACHE_FILENAME: String = ".payload.content.sha256.cache"

/**
 * Sidecar recording the sha256 of the archive this install last uploaded or installed. While
 * the server's manifest still points at that exact archive, a content-hash disagreement can only
 * be a derivation difference between clients, never new content, so no download is owed.
 */
internal const val PAYLOAD_ZIP_SHA_CACHE_FILENAME: String = ".payload.zip.sha256.cache"
private const val LEGACY_PAYLOAD_SHA_CACHE_FILENAME: String = ".payload.sha256.cache"
private const val PAYLOAD_LOCAL_DIRTY_FILENAME: String = ".payload.content.local_dirty"
private const val PAYLOAD_REPLACEMENT_TARGET_FILENAME = ".payload.replacement.target"
private const val PAYLOAD_REPLACEMENT_BACKUP_PREFIX = ".hoshi-sync-backup-"

internal fun payloadZipKey(syncId: String): String = "books/$syncId/payload.zip"
internal fun payloadManifestKey(syncId: String): String = "books/$syncId/payload.manifest"
internal fun epubZipKey(syncId: String): String = "books/$syncId/epub.zip"
internal fun epubManifestKey(syncId: String): String = "books/$syncId/epub.manifest"

data class HttpSyncPayloadKeys(
    val zip: String,
    val manifest: String,
) {
    companion object {
        fun forFormat(format: HttpSyncContentType, syncId: String): HttpSyncPayloadKeys =
            when (format) {
                HttpSyncContentType.Mokuro -> HttpSyncPayloadKeys(
                    zip = payloadZipKey(syncId),
                    manifest = payloadManifestKey(syncId),
                )
                HttpSyncContentType.Epub -> HttpSyncPayloadKeys(
                    zip = epubZipKey(syncId),
                    manifest = epubManifestKey(syncId),
                )
            }

        /** Android 0.11 wrote EPUBs to the original shared Mokuro key pair. */
        fun legacy(syncId: String): HttpSyncPayloadKeys = HttpSyncPayloadKeys(
            zip = payloadZipKey(syncId),
            manifest = payloadManifestKey(syncId),
        )
    }
}

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

    /** The cross-platform static-content hash remembered beside an already-materialized book. */
    internal fun cachedPayloadSha(bookRoot: File): String? = runCatching {
        bookRoot.resolve(PAYLOAD_SHA_CACHE_FILENAME).readText().trim()
    }.getOrNull()?.takeIf { SHA256_VALUE.matches(it) }

    /** sha256 of the archive this install last uploaded or installed for the book, if known. */
    internal fun cachedZipSha(bookRoot: File): String? = runCatching {
        bookRoot.resolve(PAYLOAD_ZIP_SHA_CACHE_FILENAME).readText().trim()
    }.getOrNull()?.takeIf { SHA256_VALUE.matches(it) }

    /**
     * Records the archive [bookRoot]'s content is known to equal. Uploads and installs write it
     * beside the unpacked book; callers that prove an already-held book matches the server's
     * archive without installing anything (a verify-only download, or a local re-hash that
     * agrees with the manifest) must record it too, or a book that predates this sidecar never
     * gains the baseline that stops cross-platform hash disagreements from re-downloading it.
     */
    internal fun rememberZipSha(bookRoot: File, sha: String) {
        runCatching { writeSidecarAtomically(bookRoot.resolve(PAYLOAD_ZIP_SHA_CACHE_FILENAME), sha) }
    }

    /**
     * Writes a verified content-hash baseline after an import/download or one-time upgrade hash.
     */
    internal fun rememberPayloadSha(bookRoot: File, sha: String) {
        require(SHA256_VALUE.matches(sha)) { "Invalid payload sha256: $sha" }
        writeCachedSha(bookRoot.resolve(PAYLOAD_SHA_CACHE_FILENAME), sha)
    }

    /** One-time/import-time content hash; later fast syncs only read the sidecar. */
    internal suspend fun ensurePayloadContentSha(bookRoot: File): String = withContext(ioDispatcher) {
        cachedPayloadSha(bookRoot) ?: computePayloadContentSha(bookRoot).also {
            writeCachedSha(bookRoot.resolve(PAYLOAD_SHA_CACHE_FILENAME), it)
        }
    }

    /**
     * Recomputes the content hash from the files on disk, ignoring the cache sidecar, and
     * rewrites the sidecar. Callers use it before replacing a book whose *cached* hash
     * disagrees with the server: a stale or mis-derived sidecar must never cost a
     * multi-hundred-MB download that installs identical bytes.
     */
    internal suspend fun refreshPayloadContentSha(bookRoot: File): String = withContext(ioDispatcher) {
        computePayloadContentSha(bookRoot).also {
            writeCachedSha(bookRoot.resolve(PAYLOAD_SHA_CACHE_FILENAME), it)
        }
    }

    internal fun computePayloadContentSha(bookRoot: File, excludedRootFiles: Set<String> = emptySet()): String {
        require(bookRoot.isDirectory) { "Book root is not a directory: $bookRoot" }
        val digest = MessageDigest.getInstance("SHA-256")
        val files = bookRoot.walkTopDown().filter { file ->
            file.isFile && file.name !in PAYLOAD_EXCLUDED_FILES &&
                !file.isInsideExcludedDir(bookRoot) &&
                // Root-level candidates only, compared in the same NFC form the hash uses.
                !(file.parentFile == bookRoot && nfc(file.name) in excludedRootFiles)
        }.map { file ->
            file to nfc(file.relativeTo(bookRoot).invariantSeparatorsPath).toByteArray(Charsets.UTF_8)
        }
            // Order by the UTF-8 bytes of the NFC path. Kotlin's String order is UTF-16 code
            // units, which disagrees with byte order for characters outside the BMP (a title
            // mixing ｜ and 𠀀 sorted differently here than on iOS); Swift orders by code point,
            // which is byte order.
            .sortedWith { a, b -> compareUnsignedBytes(a.second, b.second) }
            .toList()
        for ((file, path) in files) {
            digest.update(ByteBuffer.allocate(4).putInt(path.size).array())
            digest.update(path)
            digest.update(ByteBuffer.allocate(8).putLong(file.length()).array())
            file.inputStream().buffered(STREAM_BUFFER_SIZE).use { input ->
                val buffer = ByteArray(STREAM_BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    digest.update(buffer, 0, read)
                }
            }
        }
        return "sha256:" + digest.digest().joinToString("") { "%02x".format(it) }
    }

    internal fun markPayloadContentDirty(bookRoot: File) {
        writeSidecarAtomically(bookRoot.resolve(PAYLOAD_LOCAL_DIRTY_FILENAME), "1")
    }

    internal fun hasPayloadContentDirty(bookRoot: File): Boolean =
        bookRoot.resolve(PAYLOAD_LOCAL_DIRTY_FILENAME).isFile

    /**
     * Atomically installs a downloaded static payload while retaining mutable/per-device files.
     * The old directory remains as a rollback backup until every preserved sidecar has moved.
     */
    internal fun installReplacement(bookRoot: File, stagingRoot: File, sha: String) {
        require(bookRoot.isDirectory) { "Book directory disappeared during payload replacement." }
        require(stagingRoot.isDirectory) { "Downloaded payload staging directory is missing." }
        val parent = bookRoot.parentFile ?: throw HttpSyncException("Book directory has no parent.")
        recoverInterruptedReplacements(parent)
        val backup = File(parent, "$PAYLOAD_REPLACEMENT_BACKUP_PREFIX${UUID.randomUUID()}")
        writeSidecarAtomically(bookRoot.resolve(PAYLOAD_REPLACEMENT_TARGET_FILENAME), bookRoot.name)
        try {
            moveDirectory(bookRoot, backup)
        } catch (error: Exception) {
            bookRoot.resolve(PAYLOAD_REPLACEMENT_TARGET_FILENAME).delete()
            throw HttpSyncException("Could not stage changed payload: ${error.message}")
        }
        val movedSidecars = mutableListOf<String>()
        try {
            moveDirectory(stagingRoot, bookRoot)
            val preserved = replacementSidecars(backup)
            for (child in preserved) {
                val destination = bookRoot.resolve(child.name)
                if (destination.exists()) destination.deleteRecursively()
                Files.move(child.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
                movedSidecars += child.name
            }
            rememberPayloadSha(bookRoot, sha)
            backup.deleteRecursively()
        } catch (error: Exception) {
            // Put moved sidecars back into the backup before restoring the original directory.
            for (name in movedSidecars.asReversed()) {
                val source = bookRoot.resolve(name)
                if (source.exists()) {
                    runCatching {
                        Files.move(source.toPath(), backup.resolve(name).toPath(), StandardCopyOption.REPLACE_EXISTING)
                    }
                }
            }
            bookRoot.deleteRecursively()
            runCatching {
                moveDirectory(backup, bookRoot)
                bookRoot.resolve(PAYLOAD_REPLACEMENT_TARGET_FILENAME).delete()
            }
            throw HttpSyncException("Could not install changed payload: ${error.message}")
        }
    }

    /** Repairs a process death at any point in the directory swap before books are listed. */
    internal fun recoverInterruptedReplacements(booksDirectory: File) {
        for (backup in booksDirectory.listFiles().orEmpty().filter {
            it.isDirectory && it.name.startsWith(PAYLOAD_REPLACEMENT_BACKUP_PREFIX)
        }) {
            val targetName = runCatching {
                backup.resolve(PAYLOAD_REPLACEMENT_TARGET_FILENAME).readText().trim()
            }.getOrNull() ?: continue
            if (targetName.isBlank() || targetName.startsWith(".") || File(targetName).name != targetName) continue
            val target = booksDirectory.resolve(targetName)
            if (target.exists()) {
                for (child in replacementSidecars(backup)) {
                    val destination = target.resolve(child.name)
                    if (destination.exists()) destination.deleteRecursively()
                    Files.move(child.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
                }
                backup.deleteRecursively()
            } else {
                moveDirectory(backup, target)
                target.resolve(PAYLOAD_REPLACEMENT_TARGET_FILENAME).delete()
            }
        }
    }

    private fun replacementSidecars(root: File): List<File> = root.listFiles().orEmpty().filter { child ->
        (child.isDirectory && child.name in PAYLOAD_EXCLUDED_DIRS) ||
            (child.isFile && child.name in PAYLOAD_REPLACEMENT_PRESERVED_FILES)
    }

    private fun moveDirectory(source: File, destination: File) {
        try {
            Files.move(source.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } catch (_: Exception) {
            Files.move(source.toPath(), destination.toPath())
        }
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
        val keys = HttpSyncPayloadKeys.forFormat(format, syncId)
        val remoteManifest = fetchManifest(transport, syncId, keys)
        if (remoteManifest != null) {
            // Server already has this book — refuse to re-upload. No matter what mtime
            // changes have happened locally (and they will happen, all the time — opening
            // a book, scrolling, chatting, etc.), the manifest's existence is the
            // authoritative signal that the user has already shipped a copy. We trust
            // that and stay quiet.
            // The server copy is authoritative for static book bytes. Persist its verified
            // content hash so map checks never re-zip/re-hash this already-downloaded book.
            val contentSha = ensurePayloadContentSha(bookRoot)
            val forceReplacement = hasPayloadContentDirty(bookRoot) &&
                remoteManifest.contentSha256 != contentSha
            if (hasPayloadContentDirty(bookRoot) && remoteManifest.contentSha256 == contentSha) {
                bookRoot.resolve(PAYLOAD_LOCAL_DIRTY_FILENAME).delete()
            }
            if (!forceReplacement) return@withContext false
        }

        val spoolDir = bookRoot.parentFile ?: bookRoot
        val zipFile = File.createTempFile("hoshi-sync-upload-", ".zip", spoolDir)
        try {
            // First upload of this book from this device. This is the only path that does the
            // multi-second zip + hash.
            val sha = zipDirectoryToFile(bookRoot, zipFile)
            val localSize = zipFile.length()
            val contentSha = computePayloadContentSha(bookRoot)
            writeCachedSha(bookRoot.resolve(PAYLOAD_SHA_CACHE_FILENAME), contentSha)

            // PUT zip first so the manifest never points at a missing or stale blob.
            transport.putFile(
                key = keys.zip,
                contentType = "application/zip",
                file = zipFile,
                onByteProgress = onByteProgress,
            )
            val manifest = HttpSyncPayloadManifest(
                sha256 = sha,
                sizeBytes = localSize,
                originalName = originalName,
                format = format,
                contentSha256 = contentSha,
            )
            transport.put(
                key = keys.manifest,
                contentType = "application/json; charset=utf-8",
                body = json.encodeToString(HttpSyncPayloadManifest.serializer(), manifest).toByteArray(),
            )
            rememberZipSha(bookRoot, sha)
            bookRoot.resolve(PAYLOAD_LOCAL_DIRTY_FILENAME).delete()
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

    /**
     * Pre-[GENERATED_COVER_FILENAME] builds materialized a receiver-side cover straight into the
     * book root (mokuro and EPUB alike), where it poisons the content hash against the origin's
     * manifest forever. If ignoring exactly one candidate root file makes the hash match the
     * manifest, the local content then provably equals the origin's: rename that file to the
     * excluded name and cache the now-matching sha instead of re-downloading the entire
     * archive. The caller repoints the book's metadata cover path.
     */
    internal suspend fun migrateLegacyGeneratedCover(
        bookRoot: File,
        expectedSha: String,
        candidateNames: List<String>,
    ): Boolean = withContext(ioDispatcher) {
        for (name in candidateNames) {
            if (name.isEmpty() || name == GENERATED_COVER_FILENAME || name.contains('/')) continue
            val legacy = bookRoot.resolve(name)
            if (!legacy.isFile) continue
            val shaWithoutCover = runCatching {
                computePayloadContentSha(bookRoot, excludedRootFiles = setOf(nfc(name)))
            }.getOrNull() ?: continue
            if (shaWithoutCover != expectedSha) continue
            val target = bookRoot.resolve(GENERATED_COVER_FILENAME)
            try {
                Files.move(legacy.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            } catch (_: Exception) {
                return@withContext false
            }
            writeCachedSha(bookRoot.resolve(PAYLOAD_SHA_CACHE_FILENAME), expectedSha)
            return@withContext true
        }
        false
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

    private companion object {
        val SHA256_VALUE = Regex("^sha256:[0-9a-f]{64}$")
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
        keys: HttpSyncPayloadKeys = HttpSyncPayloadKeys.legacy(syncId),
        expectedFormat: HttpSyncContentType? = null,
    ): HttpSyncPayloadManifest = withContext(ioDispatcher) {
        val fetchedManifest = transport.get(keys.manifest)
            ?: throw HttpSyncException("No payload manifest for $syncId.")
        val manifest = decodeManifest(syncId, fetchedManifest)
        if (expectedFormat != null && manifest.format != expectedFormat) {
            throw HttpSyncException(
                "Payload manifest for $syncId declares ${manifest.format}, " +
                    "but ${keys.manifest} requires $expectedFormat.",
            )
        }
        val spoolDir = targetDir.parentFile ?: targetDir
        val zipFile = File.createTempFile("hoshi-sync-download-", ".zip", spoolDir)
        try {
            transport.downloadToFile(keys.zip, zipFile, onByteProgress)
                ?: throw HttpSyncException("Payload zip missing for $syncId (manifest existed).")
            if (zipFile.length() != manifest.sizeBytes) {
                throw HttpSyncException(
                    "Payload zip for $syncId has ${zipFile.length()} bytes; manifest declares ${manifest.sizeBytes}.",
                )
            }
            // Validate sha256 before unpacking — a corrupted zip should fail loud, not produce a
            // half-imported book directory.
            val actualSha = sha256Hex(zipFile)
            if (actualSha != manifest.sha256) {
                throw HttpSyncException(
                    "Payload zip for $syncId failed sha256 check (expected ${manifest.sha256}, got $actualSha).",
                )
            }
            unzipInto(zipFile, targetDir)
            // The archive sha256 above is the integrity check; the content hash is a change
            // detector derived from those verified bytes. A manifest that disagrees was written
            // by a client whose derivation differed (iOS builds through 0.11.3 did), so it is
            // corrected rather than treated as corruption.
            val contentSha = computePayloadContentSha(targetDir)
            val verified = manifest.copy(contentSha256 = contentSha)
            if (manifest.contentSha256 != contentSha) {
                repairManifest(transport, keys, verifiedAgainst = fetchedManifest.body, verified = verified)
            }
            // Keep both hashes next to the unpacked book so later syncs are sidecar reads.
            writeCachedSha(targetDir.resolve(PAYLOAD_SHA_CACHE_FILENAME), contentSha)
            rememberZipSha(targetDir, manifest.sha256)
            verified
        } finally {
            zipFile.delete()
        }
    }

    /**
     * Republishes [verified] only while the server still serves byte-for-byte the manifest the
     * archive was checked against ([verifiedAgainst], its raw body). Another device may have
     * published a new archive during this download; overwriting its manifest with the old sha256
     * and size would break the book for every device until that publisher syncs again. The raw
     * bytes are compared rather than decoded fields so a rewrite this client cannot represent
     * (an unknown field, a different note) is never clobbered either. The KV API has no
     * conditional PUT, so this re-fetch is the narrowest window available. A failed repair just
     * repeats on the next sync.
     */
    private suspend fun repairManifest(
        transport: HttpSyncKvTransport,
        keys: HttpSyncPayloadKeys,
        verifiedAgainst: ByteArray,
        verified: HttpSyncPayloadManifest,
    ) {
        try {
            val current = transport.get(keys.manifest) ?: return
            if (!current.body.contentEquals(verifiedAgainst)) return
            publishVerifiedContentSha(transport, keys, verified)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // The archive is verified and unpacked; only the server-side note is stale.
        }
    }

    /**
     * Fetches just the remote manifest (no payload). Returns `null` if the server has no
     * manifest for this book. Throws on network / 5xx errors.
     */
    suspend fun fetchManifest(
        transport: HttpSyncKvTransport,
        syncId: String,
        keys: HttpSyncPayloadKeys = HttpSyncPayloadKeys.legacy(syncId),
    ): HttpSyncPayloadManifest? {
        val fetched = transport.get(keys.manifest) ?: return null
        return decodeManifest(syncId, fetched)
    }

    private fun decodeManifest(syncId: String, fetched: HttpSyncKvFetched): HttpSyncPayloadManifest =
        runCatching {
            json.decodeFromString(
                HttpSyncPayloadManifest.serializer(),
                fetched.body.toString(Charsets.UTF_8),
            )
        }.getOrElse { error ->
            throw HttpSyncException("Manifest for $syncId: malformed JSON (${error.message})")
        }

    /** Publish a content hash only after it was computed from the verified remote ZIP. */
    internal suspend fun publishVerifiedContentSha(
        transport: HttpSyncKvTransport,
        keys: HttpSyncPayloadKeys,
        manifest: HttpSyncPayloadManifest,
    ) {
        require(manifest.contentSha256 != null)
        transport.put(
            key = keys.manifest,
            contentType = "application/json; charset=utf-8",
            body = json.encodeToString(HttpSyncPayloadManifest.serializer(), manifest).toByteArray(),
        )
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

private fun nfc(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFC)

/** Unsigned lexicographic byte order — the cross-platform order of hashed payload paths. */
private fun compareUnsignedBytes(a: ByteArray, b: ByteArray): Int {
    val shared = minOf(a.size, b.size)
    for (i in 0 until shared) {
        val d = (a[i].toInt() and 0xff) - (b[i].toInt() and 0xff)
        if (d != 0) return d
    }
    return a.size - b.size
}
