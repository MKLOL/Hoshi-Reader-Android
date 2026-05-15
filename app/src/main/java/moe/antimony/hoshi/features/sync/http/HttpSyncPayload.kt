package moe.antimony.hoshi.features.sync.http

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.io.File
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
 * via their own key path. Including them in the payload would either:
 *
 *  - corrupt cross-device convergence (`metadata.json` has a per-device `id` UUID and a
 *    `lastAccess` timestamp that gets rewritten every time the user opens the book, so
 *    the payload zip would mutate on every open and re-upload churn forever — this was
 *    the bug the user reported as "scrolled a bit, pressed Sync, took forever and said
 *    book payload up");
 *  - waste bytes on state that already syncs through its own key (`bookmark.json` and
 *    `ai_chat_log.json` flow through `books/{syncId}/bookmark` and `…/chat/…`);
 *  - feed the staleness check noise that isn't real content change
 *    (`.payload.sha256.cache` is the cache itself).
 *
 * The receiving device generates its own [moe.antimony.hoshi.epub.BookMetadata] via
 * [HttpSyncReconciler.importRemoteOnlyBook] after unpacking, so excluding it loses
 * nothing across-device.
 */
internal val PAYLOAD_EXCLUDED_FILES: Set<String> = setOf(
    "bookmark.json",
    "ai_chat_log.json",
    "metadata.json",
    PAYLOAD_SHA_CACHE_FILENAME,
)

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
 * Memory: the zip is buffered in a `ByteArrayOutputStream`. For typical mokuro volumes
 * (30–100 MB) this is fine on a phone with 4 GB RAM. For 500 MB book payloads we'd want
 * to spool to a temp file; that's a future iteration.
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
    suspend fun uploadIfChanged(
        transport: HttpSyncKvTransport,
        syncId: String,
        bookRoot: File,
        originalName: String,
        format: HttpSyncContentType,
    ): Boolean = withContext(ioDispatcher) {
        val cacheFile = bookRoot.resolve(PAYLOAD_SHA_CACHE_FILENAME)
        val cachedSha = readCachedShaIfFresh(bookRoot, cacheFile)

        // Cheapest possible check: ask the server what it has, compare to our cached sha.
        // If they agree, we don't even open the zip path.
        val remoteManifest = fetchManifest(transport, syncId)
        if (cachedSha != null && remoteManifest != null && remoteManifest.sha256 == cachedSha) {
            return@withContext false
        }

        // We have to zip. This is the multi-second path on big books.
        val (zipBytes, sha) = zipDirectory(bookRoot)
        val localSize = zipBytes.size.toLong()
        writeCachedSha(cacheFile, sha)

        // The cache may have been stale (mtimes outdated) but the actual content might
        // still match the server. Re-check with the fresh sha before committing the upload.
        if (remoteManifest != null && remoteManifest.sha256 == sha && remoteManifest.sizeBytes == localSize) {
            return@withContext false
        }

        // PUT zip first so the manifest never points at a missing or stale blob.
        transport.put(
            key = payloadZipKey(syncId),
            contentType = "application/zip",
            body = zipBytes,
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
                file.lastModified() > cacheMtime
        }
        if (anyContentNewerThanCache) return null
        val raw = runCatching { cacheFile.readText().trim() }.getOrNull() ?: return null
        return raw.takeIf { it.startsWith("sha256:") }
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
                    .filter { it.isFile && it.name !in PAYLOAD_EXCLUDED_FILES }
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
    ): HttpSyncPayloadManifest = withContext(ioDispatcher) {
        val manifest = fetchManifest(transport, syncId)
            ?: throw HttpSyncException("No payload manifest for $syncId.")
        val fetched = transport.get(payloadZipKey(syncId))
            ?: throw HttpSyncException("Payload zip missing for $syncId (manifest existed).")
        // Validate sha256 before unpacking — a corrupted zip should fail loud, not produce a
        // half-imported book directory.
        val actualSha = sha256Hex(fetched.body)
        if (actualSha != manifest.sha256) {
            throw HttpSyncException(
                "Payload zip for $syncId failed sha256 check (expected ${manifest.sha256}, got $actualSha).",
            )
        }
        unzipInto(fetched.body, targetDir)
        manifest
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

    /**
     * Streams every regular file under [root] (except [PAYLOAD_EXCLUDED_FILES]) into a zip,
     * also feeding bytes through a SHA-256 digest as they're written. Returns the zip bytes
     * and the lowercase hex `sha256:<hex>` digest.
     */
    internal fun zipDirectory(root: File): Pair<ByteArray, String> {
        require(root.isDirectory) { "Book root is not a directory: $root" }
        val buffer = ByteArrayOutputStream()
        val digest = MessageDigest.getInstance("SHA-256")
        ZipOutputStream(buffer).use { zip ->
            zipRecursive(root, root, zip, digest)
        }
        // The zip's central directory is part of the bytes too, so we re-digest the final
        // buffer instead of trying to digest entry-by-entry. Cheap; just one more pass over
        // the same bytes we already have.
        val finalBytes = buffer.toByteArray()
        val finalDigest = MessageDigest.getInstance("SHA-256").digest(finalBytes)
        return finalBytes to "sha256:" + finalDigest.joinToString("") { "%02x".format(it) }
    }

    private fun zipRecursive(rootDir: File, current: File, zip: ZipOutputStream, digest: MessageDigest) {
        val children = current.listFiles()?.sortedBy { it.name } ?: return
        for (child in children) {
            if (child.isDirectory) {
                zipRecursive(rootDir, child, zip, digest)
                continue
            }
            if (child.name in PAYLOAD_EXCLUDED_FILES) continue
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
                    digest.update(buf, 0, read)
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
        targetDir.mkdirs()
        val canonicalTarget = targetDir.canonicalFile
        try {
            ZipInputStream(bytes.inputStream()).use { zin ->
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
}
