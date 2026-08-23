package moe.antimony.hoshi.features.sync.http

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import moe.antimony.hoshi.epub.Bookmark
import moe.antimony.hoshi.epub.BookRepository
import moe.antimony.hoshi.features.ai.AiChatEntry
import java.io.File

/**
 * Reader-hot, fire-and-forget single-PUT hooks. One class with one job: push the local
 * state for **one** book up to the server. Nothing reads from the server here; nothing
 * paginates; nothing reconciles. Cursor advancement is the [HttpSyncReconciler]'s job.
 *
 * Both methods throw [HttpSyncException] on failure. Callers (the reader hooks) wrap
 * each call in `runCatching { }` so a 401 / offline / 5xx never affects the reader UI.
 *
 * Why split from [HttpSyncReconciler]: the reader's failure surface shrinks to one class,
 * and the reconciler's listing/cursor logic gets to be the heavy one. See the audit at
 * commit 42b2f17 for the rationale.
 */
class HttpSyncPusher(
    private val bookRepository: BookRepository,
    private val bookLocks: HttpSyncBookLocks = HttpSyncBookLocks(),
    private val transportFactory: (HttpSyncSettings) -> HttpSyncKvTransport = { settings ->
        HttpSyncKvClient(settings.baseUrl, settings.bearerToken)
    },
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private val revisionStore = HttpSyncRevisionStore(json)

    /**
     * PUTs the current local bookmark for [bookRoot] under `books/{syncId}/bookmark` — but
     * **only** if the local edit out-revisions what the server already has.
     *
     * Each call registers ONE deliberate local edit batch (the reader hooks coalesce 5 page
     * turns into one call), so the bookmark key's rev is bumped exactly once per call via
     * [HttpSyncRevisionStore.bumpForLocalEdit] before the network round-trip. Mirrors iOS
     * `HttpSyncManager.onPageTurnPersisted` + `pushBookmark`.
     *
     * Overwrite protection: device A reading page 50 while device B is at page 100 used to
     * clobber B's progress when A's reader hook fired. Now we fetch the remote bookmark
     * first; if its edit chain is deeper (rev — timestamps only break rev ties), we apply
     * it to the local file and return without pushing. Otherwise we push our local copy.
     */
    suspend fun pushBookmark(bookRoot: File, title: String, settings: HttpSyncSettings) =
        pushBookmark(bookRoot, title, settings, persistedSyncId = null)

    suspend fun pushBookmark(
        bookRoot: File,
        title: String,
        settings: HttpSyncSettings,
        persistedSyncId: String?,
    ) {
        require(settings.isConfigured) { "HTTP sync is not configured." }
        val syncId = persistedSyncId ?: deriveSyncId(title)
            ?: throw HttpSyncException("Book '$title' has no title to derive a syncId from.")
        val transport = transportFactory(settings)
        val key = bookmarkKey(syncId)
        val booksRoot = bookRepository.booksDirectory
        // Hold the per-book mutex for the entire fetch + compare + write/PUT cycle so the
        // reconciler can't race us on this same key. Without it the second writer's stale
        // local copy could clobber the first's freshly-pulled-or-pushed value.
        bookLocks.withBookLock(bookRoot) {
            withContext(ioDispatcher) {
                // Missing local bookmark = nothing to push; quiet success (mirrors iOS).
                // Do this before bumping: a spurious push on an unopened book must not
                // create a local rev that later blocks a legacy remote bookmark.
                val local = bookRepository.loadBookmark(bookRoot) ?: return@withContext
                // One persisted edit batch = one rev bump. Persist before any network IO
                // so the bump survives even when the push itself fails (Lamport monotonicity).
                val localRev = revisionStore.bumpForLocalEdit(booksRoot, key)
                // Fetch + compare. The extra GET adds ~50ms to a typical push, well worth
                // it to never lose a reading position because of a stale push.
                val remote = transport.get(key)
                if (remote != null) {
                    val remoteBlob = runCatching {
                        json.decodeFromString(
                            HttpSyncBookmarkBlob.serializer(),
                            remote.body.toString(Charsets.UTF_8),
                        )
                    }.getOrNull()
                    if (remoteBlob != null) {
                        val localStamp = local.lastModified?.let(::appleSecondsToRfc3339)
                        if (compareRevisioned(
                                localRev = localRev,
                                remoteRev = remoteBlob.rev,
                                localStamp = localStamp,
                                remoteStamp = remoteBlob.lastModified,
                            ) == SyncComparison.REMOTE_WINS
                        ) {
                            // Server's edit chain is deeper; pull it into the local file and bail
                            // on the push. Caller treats success as "the local state is now in
                            // sync with the server", which is true either way.
                            bookRepository.saveBookmark(
                                bookRoot,
                                Bookmark(
                                    chapterIndex = remoteBlob.chapterIndex,
                                    progress = remoteBlob.progress,
                                    characterCount = remoteBlob.characterCount,
                                    lastModified = rfc3339ToAppleSeconds(remoteBlob.lastModified),
                                ),
                            )
                            revisionStore.noteRemote(booksRoot, key, remoteBlob.rev, appliedLocally = true)
                            return@withContext
                        }
                    }
                }
                // A newer local edit may have superseded this push while the GET was in
                // flight — let its own push deliver the deeper rev instead of racing it
                // with stale content. Mirrors iOS HttpSyncManager.pushBookmark.
                if (revisionStore.current(booksRoot, key).localRev != localRev) return@withContext
                transport.put(
                    key = key,
                    contentType = JSON_CONTENT_TYPE,
                    body = json.encodeToString(
                        HttpSyncBookmarkBlob.serializer(),
                        local.toBlob().copy(rev = localRev),
                    ).toByteArray(),
                )
                revisionStore.noteRemote(booksRoot, key, localRev, appliedLocally = true)
            }
        }
    }

    /**
     * Fire-and-forget metadata PUT with the same merge rules as the reconciler's outbound
     * pass: honour a winning remote tombstone, keep a fresher remote shelf placement,
     * advance importedAt — then push only if the local edit out-revisions the remote blob.
     * Mirrors iOS `HttpSyncManager.pushMetadata` exactly.
     *
     * The hook that constructs [upload] has already bumped the key's local rev; the changed
     * field's freshness (e.g. shelfUpdatedAt = now) makes the shelf-LWW merge below keep the
     * local change while preserving fresher remote fields.
     *
     * Throws [HttpSyncException] / IO errors on failure; a "remote out-revisions us, push
     * correctly skipped" outcome is a normal (successful) return.
     */
    suspend fun pushMetadata(upload: HttpSyncMetadataUpload, settings: HttpSyncSettings) {
        require(settings.isConfigured) { "HTTP sync is not configured." }
        val transport = transportFactory(settings)
        val key = metadataKey(upload.syncId)
        val booksRoot = bookRepository.booksDirectory
        withContext(ioDispatcher) {
            bookLocks.withKeyLock(key) {
                val remoteBlob: HttpSyncMetadataBlob? = transport.get(key)?.let { fetched ->
                    runCatching {
                        json.decodeFromString(
                            HttpSyncMetadataBlob.serializer(),
                            fetched.body.toString(Charsets.UTF_8),
                        )
                    }.getOrNull()
                }
                if (remoteBlob != null) {
                    val comparison = compareRevisioned(
                        localRev = upload.localRev,
                        remoteRev = remoteBlob.rev,
                        localStamp = upload.shelfUpdatedAt ?: upload.deletedAt,
                        remoteStamp = remoteBlob.shelfUpdatedAt ?: remoteBlob.deletedAt,
                    )
                    if (comparison == SyncComparison.REMOTE_WINS) {
                        // Deeper remote edit chain: do not clobber. The next manual sync applies it.
                        revisionStore.noteRemote(booksRoot, key, remoteBlob.rev, appliedLocally = false)
                        return@withKeyLock
                    }
                }
                // Merge: keep the fresher shelf placement, advance importedAt, honour tombstones.
                var shelfName = upload.shelfName
                var shelfUpdatedAt = upload.shelfUpdatedAt
                if (remoteBlob != null && upload.deletedAt == null &&
                    shouldApplyRemoteShelfPlacement(
                        remoteShelfUpdatedAt = remoteBlob.shelfUpdatedAt,
                        localShelvesUpdatedAt = shelfUpdatedAt,
                    )
                ) {
                    shelfName = remoteBlob.shelfName
                    shelfUpdatedAt = remoteBlob.shelfUpdatedAt
                }
                // A remote tombstone the local re-import has overridden must NOT be carried —
                // re-publishing it at a deeper rev would delete the book on peers. Mirrors iOS
                // HttpSyncManager.pushMetadata's `carriedDeletedAt`.
                var carriedDeletedAt = upload.deletedAt ?: remoteBlob?.deletedAt
                if (upload.deletedAt == null &&
                    localImportedAtOverridesRemoteDeletion(
                        localImportedAt = upload.importedAt,
                        remoteDeletedAt = remoteBlob?.deletedAt,
                    )
                ) {
                    carriedDeletedAt = null
                }
                val blob = HttpSyncMetadataBlob(
                    title = upload.title,
                    contentType = upload.contentType,
                    shelfName = shelfName,
                    shelfUpdatedAt = shelfUpdatedAt,
                    importedAt = maxRfc(remoteBlob?.importedAt, upload.importedAt),
                    deletedAt = carriedDeletedAt,
                    rev = upload.localRev,
                )
                if (remoteBlob != null && remoteBlob.copy(rev = blob.rev) == blob) {
                    // Content identical — skip the PUT (and the rev-inflation it would cause).
                    revisionStore.noteRemote(booksRoot, key, remoteBlob.rev, appliedLocally = true)
                    return@withKeyLock
                }
                // A newer local edit or observed remote write may have superseded this push
                // while the GET was in flight; skip so the newer push/sync delivers it.
                val current = revisionStore.current(booksRoot, key)
                if (current.localRev != upload.localRev || current.baseRev > upload.localRev) {
                    return@withKeyLock
                }
                transport.put(
                    key = key,
                    contentType = JSON_CONTENT_TYPE,
                    body = json.encodeToString(HttpSyncMetadataBlob.serializer(), blob).toByteArray(),
                )
                revisionStore.noteRemote(booksRoot, key, upload.localRev, appliedLocally = true)
            }
        }
    }

    /**
     * PUTs a single chat entry at `books/{syncId}/chat/{ts}-{contenthash}`. The key is
     * content-addressable, so re-pushes are idempotent on the server.
     */
    suspend fun pushChatEntry(title: String, entry: AiChatEntry, settings: HttpSyncSettings) =
        pushChatEntry(title, entry, settings, persistedSyncId = null)

    suspend fun pushChatEntry(
        title: String,
        entry: AiChatEntry,
        settings: HttpSyncSettings,
        persistedSyncId: String?,
    ) {
        require(settings.isConfigured) { "HTTP sync is not configured." }
        val syncId = persistedSyncId ?: deriveSyncId(title)
            ?: throw HttpSyncException("Book '$title' has no title to derive a syncId from.")
        val blob = entry.toBlob()
        val suffix = chatEntryKeySuffix(entry.timestampSeconds, entry.bubbleText, entry.response)
        withContext(ioDispatcher) {
            transportFactory(settings).put(
                key = chatKey(syncId, suffix),
                contentType = JSON_CONTENT_TYPE,
                body = json.encodeToString(HttpSyncChatEntryBlob.serializer(), blob).toByteArray(),
            )
        }
    }

    internal companion object {
        const val JSON_CONTENT_TYPE = "application/json; charset=utf-8"
    }
}

/**
 * One fire-and-forget metadata upload for [HttpSyncPusher.pushMetadata]. The hook that
 * constructs it ([HttpSyncAutoPush]) has already bumped the metadata key's local rev and
 * passes it as [localRev]. Mirrors iOS `HttpSyncManager.MetadataUpload`.
 */
data class HttpSyncMetadataUpload(
    val syncId: String,
    val title: String,
    val contentType: HttpSyncContentType,
    val shelfName: String?,
    val shelfUpdatedAt: String?,
    val importedAt: String?,
    val deletedAt: String?,
    val localRev: Int,
)
