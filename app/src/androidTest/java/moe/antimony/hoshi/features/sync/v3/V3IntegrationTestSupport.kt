package moe.antimony.hoshi.features.sync.v3

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import moe.antimony.hoshi.epub.BookMetadata
import moe.antimony.hoshi.epub.BookRepository
import moe.antimony.hoshi.epub.BookShelf
import moe.antimony.hoshi.epub.Bookmark
import moe.antimony.hoshi.epub.MOKURO_SIDECAR_FILE
import moe.antimony.hoshi.features.ai.AiChatEntry
import moe.antimony.hoshi.features.ai.AiChatHistoryStore
import moe.antimony.hoshi.features.ai.AiChatSettings
import moe.antimony.hoshi.features.ai.AiChatSettingsRepository
import moe.antimony.hoshi.features.sync.http.HttpSyncBookLocks
import moe.antimony.hoshi.features.sync.http.HttpSyncKvClient
import moe.antimony.hoshi.features.sync.http.HttpSyncPayloadCodec
import moe.antimony.hoshi.features.sync.http.HttpSyncSettings
import java.io.File
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/**
 * Shared support for v3 instrumented integration tests:
 *
 *  - [SimDevice]: a single in-process device wrapping its own [BookRepository], history
 *    store, AI settings store, and [V3SyncEngine] — all pointed at the same shared
 *    [StubKvServer].
 *  - [tempBooksDir]: creates a fresh root for a new device under the test's temp space.
 *  - [freshDevice]: factory that produces a wired [SimDevice] from a server + a name.
 *  - [stubSettings]: builds the [HttpSyncSettings] handed to [V3SyncEngine.syncOnce].
 *  - [assertConvergent]: convergence post-condition shared across scenarios.
 *
 * Kept in a single file because every public type here is glue specifically for the v3
 * instrumented tests — splitting them up would just add ceremony.
 */

internal class SimDevice(
    val name: String,
    val repo: BookRepository,
    val history: AiChatHistoryStore,
    val aiRepo: AiChatSettingsRepository,
    val engine: V3SyncEngine,
    val settings: HttpSyncSettings,
) {
    /**
     * Creates a Mokuro book directory (with a `mokuro.json` sidecar so
     * [moe.antimony.hoshi.epub.bookContentType] picks Mokuro) and a metadata sidecar
     * carrying the given title. Optional [contentBytes] are written as `pages/p1.png`
     * — when omitted, a tiny deterministic placeholder is used.
     */
    suspend fun importMokuro(title: String, contentBytes: ByteArray? = null): File {
        val root = repo.createBookDirectoryForImportedTitle(title)
        File(root, MOKURO_SIDECAR_FILE).writeText("""{"v":1,"title":${jsonStr(title)}}""")
        val pages = File(root, "pages").apply { mkdirs() }
        File(pages, "p1.png").writeBytes(contentBytes ?: PNG_PLACEHOLDER)
        repo.saveMetadata(
            root,
            BookMetadata(
                id = UUID.randomUUID().toString(),
                title = title,
                cover = null,
                folder = root.name,
                lastAccess = appleNow(),
                // Match AndroidBookshelfRepository: a new import is newer than any
                // prior deletion of the same title, so it can revive that sync identity.
                importedAt = Instant.now().toString(),
            ),
        )
        return root
    }

    /**
     * Creates an EPUB book directory (no mokuro sidecar, so
     * [moe.antimony.hoshi.epub.bookContentType] returns Epub by default) plus its
     * metadata.
     */
    suspend fun importEpub(title: String): File {
        val root = repo.createBookDirectoryForImportedTitle(title)
        File(root, "META-INF").mkdirs()
        File(root, "OEBPS").mkdirs()
        File(root, "META-INF/container.xml").writeText(
            """<?xml version="1.0"?>
            <container xmlns="urn:oasis:names:tc:opendocument:xmlns:container" version="1.0">
              <rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles>
            </container>""".trimIndent(),
        )
        File(root, "OEBPS/content.opf").writeText(
            """<?xml version="1.0" encoding="UTF-8"?>
            <package xmlns="http://www.idpf.org/2007/opf" unique-identifier="book-id" version="3.0">
              <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                <dc:identifier id="book-id">${jsonStr(title)}</dc:identifier>
                <dc:title>$title</dc:title><dc:language>ja</dc:language>
              </metadata>
              <manifest><item id="chapter" href="chapter.xhtml" media-type="application/xhtml+xml"/></manifest>
              <spine><itemref idref="chapter"/></spine>
            </package>""".trimIndent(),
        )
        File(root, "OEBPS/chapter.xhtml").writeText(
            """<html xmlns="http://www.w3.org/1999/xhtml"><body><p>食べる。</p></body></html>""",
        )
        repo.saveMetadata(
            root,
            BookMetadata(
                id = UUID.randomUUID().toString(),
                title = title,
                cover = null,
                folder = root.name,
                lastAccess = appleNow(),
                importedAt = Instant.now().toString(),
            ),
        )
        return root
    }

    /**
     * Saves a fresh bookmark with [toCharacter] characters read in chapter [chapterIndex].
     * Each call bumps the monotonic Apple-seconds clock so LWW comparisons always pick
     * the most recent write, even when two `turnPage` calls happen within the same JVM
     * wall-clock millisecond.
     */
    suspend fun turnPage(book: File, toCharacter: Int, chapterIndex: Int = 0) {
        val nowSeconds = appleNow()
        repo.saveBookmark(
            book,
            Bookmark(
                chapterIndex = chapterIndex,
                progress = toCharacter.toDouble().coerceAtLeast(0.0),
                characterCount = toCharacter,
                lastModified = nowSeconds,
            ),
        )
    }

    /** Appends a chat entry to [book]'s history at the current monotonic Apple-seconds clock. */
    suspend fun chat(book: File, bubbleText: String, response: String): AiChatEntry {
        val entry = AiChatEntry(
            bubbleText = bubbleText,
            prompt = "Translate this bubble.",
            model = "gpt-test",
            response = response,
            timestampSeconds = appleNow(),
        )
        history.append(book, entry)
        return entry
    }

    /**
     * Moves [book] onto [shelfName], or onto the default (no) shelf when null.
     * Mirrors the bookshelf UI: rewrites `shelves.json` so [book]'s id sits only on
     * the target shelf.
     */
    suspend fun moveToShelf(book: File, shelfName: String?) {
        val bookId = repo.loadMetadata(book)?.id ?: error("Book ${book.name} missing metadata")
        val current = repo.loadShelves()
        val withoutBook = current.map { shelf ->
            shelf.copy(bookIds = shelf.bookIds.filterNot { it == bookId })
        }
        val updated = when {
            shelfName == null -> withoutBook
            withoutBook.any { it.name == shelfName } -> withoutBook.map { shelf ->
                if (shelf.name == shelfName) shelf.copy(bookIds = (shelf.bookIds + bookId).distinct())
                else shelf
            }
            else -> withoutBook + BookShelf(shelfName, listOf(bookId))
        }
        repo.saveShelves(updated)
    }

    /** Deletes [book] from local storage. Real prod also stages a tombstone — see kdoc. */
    suspend fun delete(book: File) {
        repo.deleteBook(book)
    }

    /** Runs one [V3SyncEngine.syncOnce] against the stub server. */
    suspend fun sync(): V3SyncResult = engine.syncOnce(settings)

    companion object {
        // Tiny PNG header so writing real content into `pages/p1.png` looks plausible
        // to anything that wants to sniff it. Most code-paths never look at the bytes.
        internal val PNG_PLACEHOLDER: ByteArray = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
        )
    }
}

/**
 * Apple-reference epoch (2001-01-01T00:00:00Z) in unix seconds. Mirrors
 * `BookSidecarDataSource.APPLE_REFERENCE_EPOCH_SECONDS` (private there).
 */
internal const val APPLE_REFERENCE_EPOCH_SECONDS: Double = 978_307_200.0

/**
 * Monotonic Apple-reference-seconds clock. Every call returns a value strictly greater
 * than the previous one (millisecond bumps when wall-clock didn't advance), so scripted
 * page-turn sequences are always linearizable by their lastModified ordering — which is
 * what LWW compares.
 */
private val monotonicMillis = AtomicLong(0L)

internal fun appleNow(): Double {
    val now = Instant.now()
    val nowMillis = now.toEpochMilli()
    val candidate = monotonicMillis.updateAndGet { prev -> if (prev >= nowMillis) prev + 1 else nowMillis }
    return candidate.toDouble() / 1000.0 - APPLE_REFERENCE_EPOCH_SECONDS
}

private fun jsonStr(s: String): String = buildString {
    append('"')
    for (ch in s) when (ch) {
        '"' -> append("\\\"")
        '\\' -> append("\\\\")
        '\n' -> append("\\n")
        '\r' -> append("\\r")
        '\t' -> append("\\t")
        else -> if (ch.code < 0x20) append("\\u%04x".format(ch.code)) else append(ch)
    }
    append('"')
}

/**
 * Creates a fresh on-disk `BookRepository` root under [parent]/[name]/files. Each
 * `SimDevice` needs its own root so the devices share nothing but the [StubKvServer].
 */
internal fun tempBooksDir(parent: File, name: String): File {
    val filesDir = File(parent, "$name/files").apply { mkdirs() }
    return filesDir
}

/**
 * Builds a fully-wired [SimDevice] pointing at [server] with an isolated repo. Reuses
 * a single shared [HttpSyncBookLocks] map per device so per-book mutexes serialize
 * concurrent writes within ONE device (the v3 reader-hook interleave scenario relies
 * on this).
 *
 * The [HttpSyncKvClient] is constructed explicitly so callers can override multipart
 * thresholds for the large-payload tests.
 */
internal fun freshDevice(
    server: StubKvServer,
    name: String,
    parent: File,
    multipartThresholdBytes: Long = 64L * 1024L * 1024L,
    multipartPartSizeBytes: Long = 64L * 1024L * 1024L,
): SimDevice {
    val filesDir = tempBooksDir(parent, name)
    val repo = BookRepository(filesDir)
    val history = AiChatHistoryStore()
    val aiRepo = AiChatSettingsRepository(InMemoryPreferencesDataStore())
    val locks = HttpSyncBookLocks()
    val codec = HttpSyncPayloadCodec()
    val engine = V3SyncEngine(
        bookRepository = repo,
        aiHistoryStore = history,
        aiSettingsRepository = aiRepo,
        payloadCodec = codec,
        bookLocks = locks,
        transportFactory = { settings ->
            HttpSyncKvClient(
                baseUrl = settings.baseUrl,
                bearerToken = settings.bearerToken,
                multipartPartSizeBytes = multipartPartSizeBytes,
                multipartThresholdBytes = multipartThresholdBytes,
            )
        },
    )
    val settings = stubSettings(server)
    return SimDevice(
        name = name,
        repo = repo,
        history = history,
        aiRepo = aiRepo,
        engine = engine,
        settings = settings,
    )
}

/** Settings tuple that points at the stub server with its hardcoded bearer token. */
internal fun stubSettings(server: StubKvServer): HttpSyncSettings = HttpSyncSettings(
    baseUrl = server.baseUrl,
    bearerToken = server.token,
)

/**
 * Asserts every device in [devices] reports identical local state. Convergence is the
 * core post-condition for all multi-device scenarios.
 *
 *  - Same set of book titles on the bookshelf.
 *  - Same bookmark per book (compared by `(chapterIndex, characterCount, progress)`).
 *  - Same chat entry set per book (compared by `matchesEntry` semantics — content-equal
 *    entries collide on the server, so locally-deduped state is the right ground truth).
 *  - Same shelf placement per book.
 */
internal suspend fun assertConvergent(vararg devices: SimDevice) {
    require(devices.size >= 2) { "Need at least two devices to compare for convergence." }
    val snapshots = devices.map { deviceSnapshot(it) }
    val reference = snapshots.first()
    for (i in 1 until snapshots.size) {
        val other = snapshots[i]
        if (other != reference) {
            val diff = buildString {
                appendLine("Devices diverged.")
                appendLine("${devices[0].name}: $reference")
                appendLine("${devices[i].name}: $other")
            }
            throw AssertionError(diff)
        }
    }
}

/** Plain-data snapshot for diffing two devices in [assertConvergent]. */
internal data class DeviceSnapshot(
    val titles: Set<String>,
    val bookmarks: Map<String, Triple<Int, Int, Double>>,
    val chats: Map<String, Set<Triple<String, String, Double>>>,
    val shelves: Map<String, String>,
)

internal suspend fun deviceSnapshot(device: SimDevice): DeviceSnapshot {
    val entries = device.repo.loadBookEntries()
    val titles = entries.mapNotNull { it.metadata.title }.toSet()
    val bookmarks = entries.associate { entry ->
        val title = entry.metadata.title.orEmpty()
        val bm = device.repo.loadBookmark(entry.root)
        title to Triple(
            bm?.chapterIndex ?: -1,
            bm?.characterCount ?: -1,
            bm?.progress ?: -1.0,
        )
    }
    val chats = entries.associate { entry ->
        val title = entry.metadata.title.orEmpty()
        val log = device.history.load(entry.root)
        title to log.entries.map {
            Triple(it.bubbleText, it.response, it.timestampSeconds)
        }.toSet()
    }
    val shelfMap = mutableMapOf<String, String>()
    val shelves = device.repo.loadShelves()
    val idsToTitle: Map<String, String> = entries.associate { entry ->
        entry.metadata.id to entry.metadata.title.orEmpty()
    }
    for (shelf in shelves) {
        for (id in shelf.bookIds) {
            idsToTitle[id]?.let { title -> shelfMap[title] = shelf.name }
        }
    }
    return DeviceSnapshot(
        titles = titles,
        bookmarks = bookmarks,
        chats = chats,
        shelves = shelfMap,
    )
}

/**
 * Minimal in-memory [DataStore] of [Preferences] so [AiChatSettingsRepository] can be
 * built inside tests without an Android `Context`-backed DataStore.
 */
internal class InMemoryPreferencesDataStore : DataStore<Preferences> {
    private val state = MutableStateFlow<Preferences>(mutablePreferencesOf())
    override val data: Flow<Preferences> get() = state
    override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
        val next = transform(state.value)
        state.update { next }
        return next
    }
}

/** Convenience to make `AiChatSettings.copy(...)` calls less verbose in tests. */
internal fun aiSettings(
    apiKey: String = "",
    promptText: String = AiChatSettings.DEFAULT_PROMPT,
    imagePromptText: String = AiChatSettings.DEFAULT_IMAGE_PROMPT,
    model: String = AiChatSettings.DEFAULT_MODEL,
    lastEditedAt: String? = null,
): AiChatSettings = AiChatSettings(
    apiKey = apiKey,
    promptText = promptText,
    imagePromptText = imagePromptText,
    model = model,
    lastEditedAt = lastEditedAt,
)
