package moe.antimony.hoshi.epub

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import moe.antimony.hoshi.features.sync.http.deriveSyncId
import moe.antimony.hoshi.features.sync.http.syncIdForMetadata
import moe.antimony.hoshi.storage.writeSidecarAtomically
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs

@Serializable
data class ReadingPosition(val chapterIndex: Int, val progress: Double, val characterCount: Int) {
    fun bookmark(stamp: Double) = Bookmark(chapterIndex, progress, characterCount, stamp)
    fun samePlace(other: ReadingPosition) = chapterIndex == other.chapterIndex &&
        kotlin.math.abs(progress - other.progress) < 0.00000001
    fun isValid() = chapterIndex >= 0 && progress.isFinite() && progress in 0.0..1.0 && characterCount >= 0
}

fun Bookmark.readingPosition() = ReadingPosition(chapterIndex, progress, characterCount)

@Serializable
data class ReadingPositionEvent(
    val id: String,
    val deviceId: String,
    val deviceName: String,
    val originId: String,
    val sequence: Long,
    val sessionId: String,
    val action: String,
    val from: ReadingPosition?,
    val position: ReadingPosition,
    /** Actual action time; never refreshed by disk writes, retries or sync application. */
    val occurredAt: Double,
    /** Monotonic compatibility timestamp for released timestamp-only clients. */
    val compatibilityAt: Double,
    val basedOn: String? = null,
    val seen: Map<String, Long> = emptyMap(),
    val missingTimestamp: Boolean = false,
    val bookId: String = "",
) {
    fun bookmark(): Bookmark = position.bookmark(compatibilityAt).let {
        if (missingTimestamp) it.copy(lastModified = null) else it
    }
    fun observes(other: ReadingPositionEvent) =
        (originId == other.originId && sequence > other.sequence) || (seen[other.originId] ?: 0) >= other.sequence
    fun isValid() = id.isNotBlank() && id.length <= 128 && deviceId.isNotBlank() &&
        originId.isNotBlank() && originId.length <= 128 && sequence > 0 &&
        position.isValid() && (from?.isValid() != false) && abs(occurredAt) <= MAX_POSITION_SECONDS &&
        compatibilityAt <= MAX_POSITION_SECONDS && compatibilityAt >= occurredAt && seen.size <= 256 &&
        seen.all { it.key.length in 1..128 && it.value >= 0 }
}

/** Own-writer wire format, stored separately from all released bookmark formats. */
@Serializable
data class ReadingPositionJournal(
    val version: Int = 1,
    val bookId: String,
    val deviceId: String,
    val events: List<ReadingPositionEvent>,
    val heads: List<ReadingPositionEvent>,
)

@Serializable
data class ReadingPositionState(
    val bookId: String,
    val heads: Map<String, ReadingPositionEvent> = emptyMap(),
    val events: List<ReadingPositionEvent> = emptyList(),
    val pending: Set<String> = emptySet(),
) {
    fun frontier(): Map<String, Long> = buildMap {
        for (head in heads.values) {
            for ((origin, sequence) in head.seen) {
                // Superseded synthetic legacy heads are never exported. Their exact IDs
                // need not accumulate forever in future sessions' causal frontiers.
                if (!origin.startsWith("legacy-") || origin in heads) put(origin, maxOf(get(origin) ?: 0, sequence))
            }
            put(head.originId, maxOf(get(head.originId) ?: 0, head.sequence))
        }
    }

    fun winner(): ReadingPositionEvent? {
        val candidates = heads.values.filter { candidate -> heads.values.none {
            it.id != candidate.id && it.observes(candidate)
        } }
        // Truly concurrent offline navigation has no objectively known real-time order.
        // Keep all heads/history; use this stable default for the displayed position.
        return candidates.maxWithOrNull(compareBy<ReadingPositionEvent> { it.compatibilityAt }
            .thenBy { it.occurredAt }.thenBy { it.id })
    }

    fun merging(incoming: Collection<ReadingPositionEvent>, ownPending: Set<String> = emptySet()): ReadingPositionState {
        val mergedHeads = heads.toMutableMap()
        for (event in incoming) {
            require(event.isValid()) { "Invalid reading position event" }
            val previous = mergedHeads[event.originId]
            if (previous == null || compareValuesBy(event, previous, { it.sequence }, { it.id }) > 0) {
                mergedHeads[event.originId] = event
            }
        }
        val all = (events + incoming).associateBy { it.id }.values
        // Receivers keep 50 events per device, so an older unsent event could never arrive:
        // the outbox shares that window and stays bounded while sync is off or offline.
        // Heads are kept apart and always exported, so positions still converge.
        val retained = all.groupBy { it.deviceId }.values.flatMap { deviceEvents ->
            deviceEvents.sortedWith(compareBy<ReadingPositionEvent> { it.compatibilityAt }.thenBy { it.id }).takeLast(50)
        }.sortedWith(compareBy<ReadingPositionEvent> { it.compatibilityAt }.thenBy { it.id })
        // A head is exported either way, so it stays unsent even outside the window: a move the
        // window drops at once (odd clocks, concurrent sessions) must still be published.
        val unsent = (pending + ownPending).intersect(retained.mapTo(HashSet()) { it.id } + mergedHeads.values.map { it.id })
        return copy(heads = mergedHeads, events = retained, pending = unsent)
    }
}

/** Frozen observed context: an old reader cannot claim it saw a later remote bookmark. */
class ReadingPositionSession internal constructor(
    val initialBookmark: Bookmark?,
    private val device: DeviceIdentity,
    private val originId: String,
    private val sequence: AtomicLong,
    initialState: ReadingPositionState,
) {
    private val sessionId = UUID.randomUUID().toString()
    private var previous = initialBookmark?.readingPosition() ?: ReadingPosition(0, 0.0, 0)
    private var parent = initialState.winner()?.id
    private var stamp = initialBookmark?.lastModified ?: 0.0
    private var seen = initialState.frontier().toMutableMap()
    private val bookId = initialState.bookId

    @Synchronized
    fun navigate(bookmark: Bookmark, action: String = "pageTurn"): ReadingPositionEvent? {
        val position = bookmark.readingPosition()
        require(position.isValid())
        if (position.samePlace(previous)) return null
        val time = requireNotNull(bookmark.lastModified).also { require(it.isFinite()) }
        val number = sequence.incrementAndGet()
        // Old clients still order by this timestamp. Causal backwards movement must remain
        // newer even if the device's wall clock went backwards; occurredAt remains factual.
        val nextStamp = nextCompatibilityTimestamp(time, stamp)
        val event = ReadingPositionEvent(UUID.randomUUID().toString(), device.id, device.name,
            originId, number, sessionId, action, previous, position, time, nextStamp, parent, seen.toMap(), bookId = bookId)
        previous = position
        parent = event.id
        stamp = nextStamp
        seen[originId] = number
        return event
    }
}

/**
 * Owns bookmark intent/history and the durable event outbox. bookmark.json remains the exact
 * released projection. Journal writes precede projection writes, so reopening recovers a crash
 * between the two. Every local/remote projection writer uses the same per-book mutex here.
 */
class ReadingPositionRepository(
    val directory: File,
    private val storage: BookSidecarDataSource,
    private val suppliedDevice: DeviceIdentity? = null,
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val locks = ConcurrentHashMap<String, Mutex>()
    private val identityLock = Mutex()
    private var origin: String? = null
    private var device: DeviceIdentity? = null
    private val sequence = AtomicLong()
    /** Per book with this device's history: its unsent event ids. Polls read this, not every journal. */
    private val outbox = ConcurrentHashMap<String, Set<String>>()
    private val scanLock = Mutex()
    @Volatile private var scanned = false
    private val syncIndexFile = directory.resolve("sync-index.json")
    private val syncIndexLock = Mutex()
    private var syncIndexGeneration = 0L

    private suspend fun identity(): Pair<DeviceIdentity, String> = identityLock.withLock {
        if (origin == null) {
            val file = directory.resolve("origin")
            val stored = file.takeIf { it.exists() }
                ?.let { runCatching { UUID.fromString(it.readText().trim()).toString() }.getOrNull() }
            // An unreadable origin is replaced: a fresh origin never collides with old sequences.
            val id = stored ?: UUID.randomUUID().toString().also {
                directory.mkdirs()
                writeSidecarAtomically(file, it)
            }
            origin = id
            device = suppliedDevice ?: DeviceIdentity(id, "Android")
        }
        requireNotNull(device) to requireNotNull(origin)
    }

    /** Starts a new causal counter, for when a lost journal hides how far the old one got. */
    private suspend fun rotateOrigin() = identityLock.withLock {
        directory.mkdirs()
        writeSidecarAtomically(directory.resolve("origin"), UUID.randomUUID().toString())
        origin = null
        device = null
    }

    suspend fun deviceId(): String = withContext(Dispatchers.IO) { identity().first.id }

    suspend fun bookId(root: File): String = storage.loadMetadata(root)?.let(::syncIdForMetadata)
        ?: requireNotNull(deriveSyncId(root.name))

    suspend fun open(root: File): ReadingPositionSession = withContext(Dispatchers.IO) {
        val id = bookId(root)
        locks.getOrPut(id) { Mutex() }.withLock {
            val state = observedProjection(id, root, read(id))
            // After the read: setting aside an unreadable journal starts a new origin.
            val (device, origin) = identity()
            val winner = state.winner()
            if (winner != null) persist(state, root)
            sequence.updateAndGet { maxOf(it, state.heads[origin]?.sequence ?: 0) }
            ReadingPositionSession(winner?.bookmark() ?: storage.loadBookmark(root),
                device, origin, sequence, state)
        }
    }

    suspend fun record(root: File, event: ReadingPositionEvent) = withContext(Dispatchers.IO + NonCancellable) {
        val id = event.bookId.ifBlank { bookId(root) }
        locks.getOrPut(id) { Mutex() }.withLock {
            val state = observedProjection(id, root, read(id)).merging(listOf(event), setOf(event.id))
            persist(state, root)
        }
    }

    /**
     * Existing clients/importers supply a projection; importing it never assigns a new time.
     * @return whether it was adopted (it is not older than the current position).
     */
    suspend fun saveLegacy(root: File, bookmark: Bookmark): Boolean = withContext(Dispatchers.IO + NonCancellable) {
        val id = bookId(root)
        locks.getOrPut(id) { Mutex() }.withLock {
            val state = observedProjection(id, root, read(id))
            val current = state.winner()
            val time = bookmark.lastModified ?: 0.0
            if (current == null || positionWireMillis(time) > positionWireMillis(current.compatibilityAt) ||
                (positionWireMillis(time) == positionWireMillis(current.compatibilityAt) && bookmark != current.bookmark())) {
                persist(importLegacy(state, bookmark), root)
                true
            } else {
                persist(state, root)
                false
            }
        }
    }

    suspend fun merge(journal: ReadingPositionJournal, root: File?): Boolean = withContext(Dispatchers.IO + NonCancellable) {
        require(journal.version == 1 && journal.bookId.isNotBlank())
        val incoming = (journal.events + journal.heads).distinctBy { it.id }
        require(incoming.size <= 512 && incoming.all { it.deviceId == journal.deviceId &&
            it.bookId == journal.bookId && it.isValid() })
        locks.getOrPut(journal.bookId) { Mutex() }.withLock {
            val before = root?.let { observedProjection(journal.bookId, it, read(journal.bookId)) } ?: read(journal.bookId)
            val old = root?.let { storage.loadBookmark(it) }?.readingPosition()
            val state = before.merging(incoming)
            persist(state, root)
            val winner = state.winner()?.position
            winner != null && (old == null || !winner.samePlace(old)) && root != null
        }
    }

    suspend fun export(bookId: String): ReadingPositionJournal? = withContext(Dispatchers.IO) {
        locks.getOrPut(bookId) { Mutex() }.withLock {
            val own = identity().first.id
            val state = read(bookId)
            val heads = state.heads.values.filter { it.deviceId == own }
            if (heads.isEmpty()) return@withLock null
            val events = state.events.filter { it.deviceId == own }
            // Sorted, so the body published before acknowledgement hashes the same after it.
            val batch = (events.filter { it.id in state.pending }.take(200) + events.takeLast(50)).distinctBy { it.id }
                .sortedWith(compareBy<ReadingPositionEvent> { it.compatibilityAt }.thenBy { it.id })
            ReadingPositionJournal(bookId = bookId, deviceId = own, events = batch, heads = heads)
        }
    }

    suspend fun acknowledge(bookId: String, ids: Set<String>) = withContext(Dispatchers.IO + NonCancellable) {
        locks.getOrPut(bookId) { Mutex() }.withLock {
            val state = read(bookId)
            persist(state.copy(pending = state.pending - ids).merging(emptyList()), null)
        }
    }

    /**
     * Every book with a history written by this device, with its unsent event ids. The
     * directory is read once; later calls come from memory, kept current by every write.
     */
    suspend fun ownHistories(): Map<String, Set<String>> = withContext(Dispatchers.IO) {
        if (!scanned) scanLock.withLock {
            if (!scanned) {
                val own = identity().first.id
                for (file in directory.listFiles().orEmpty()) {
                    if (!file.name.startsWith("book-") || !file.name.endsWith(".json")) continue
                    // An unreadable journal is set aside when its book is next read.
                    val state = decode(file) ?: continue
                    // A write during the scan already recorded a newer state.
                    if (state.heads.values.any { it.deviceId == own }) outbox.putIfAbsent(state.bookId, state.pending)
                }
                scanned = true
            }
        }
        outbox.toMap()
    }

    /** The position sync's acknowledgements, kept beside the journals they describe. */
    internal suspend fun loadSyncIndex(): Pair<String?, Long> = withContext(Dispatchers.IO) {
        syncIndexLock.withLock {
            syncIndexFile.takeIf { it.exists() }?.let { runCatching { it.readText() }.getOrNull() } to syncIndexGeneration
        }
    }

    /** Dropped when a journal was set aside since [generation]: it describes the lost journal. */
    internal suspend fun saveSyncIndex(text: String, generation: Long) = withContext(Dispatchers.IO + NonCancellable) {
        syncIndexLock.withLock {
            if (generation != syncIndexGeneration) return@withLock
            directory.mkdirs()
            if (!syncIndexFile.exists() || syncIndexFile.readText() != text) writeSidecarAtomically(syncIndexFile, text)
        }
    }

    suspend fun snapshot(root: File): ReadingPositionState = withContext(Dispatchers.IO) {
        val id = bookId(root)
        locks.getOrPut(id) { Mutex() }.withLock { read(id) }
    }

    private suspend fun observedProjection(id: String, root: File, state: ReadingPositionState): ReadingPositionState {
        val bookmark = storage.loadBookmark(root) ?: return state
        val winner = state.winner()
        val time = bookmark.lastModified ?: 0.0
        // This file is a projection. At an equal timestamp the durable journal wins,
        // including a crash after journal commit but before its projection was replaced.
        // Protocol-resolved equal-time changes enter explicitly through saveLegacy().
        return if (winner == null || positionWireMillis(time) > positionWireMillis(winner.compatibilityAt)) {
            importLegacy(state, bookmark)
        } else state
    }

    private fun importLegacy(state: ReadingPositionState, projected: Bookmark): ReadingPositionState {
        // Older clients and other devices' maps can hold values navigation never produces.
        // Clamp them, as the readers do, instead of making the book unopenable or a sync fail.
        val bookmark = projected.copy(
            chapterIndex = projected.chapterIndex.coerceAtLeast(0),
            progress = projected.progress.takeIf { it.isFinite() }?.coerceIn(0.0, 1.0) ?: 0.0,
            characterCount = projected.characterCount.coerceAtLeast(0),
            lastModified = projected.lastModified?.takeIf { abs(it) <= MAX_POSITION_SECONDS },
        )
        val time = bookmark.lastModified ?: 0.0
        val id = "legacy-${digest(json.encodeToString(bookmark))}"
        // Legacy callers have already resolved timestamp/revision/ETag ties. Each exact
        // projection has a stable origin, so two devices never mistake different imported
        // positions for the same causal counter. Only its current synthetic head is needed.
        val event = ReadingPositionEvent(id, "legacy", "Legacy client", id,
            1, "legacy", "legacy", null, bookmark.readingPosition(), time, time,
            seen = state.frontier().filterKeys { it != id },
            missingTimestamp = bookmark.lastModified == null, bookId = state.bookId)
        return state.copy(heads = state.heads.filterValues { it.action != "legacy" })
            .merging(listOf(event))
    }

    private suspend fun read(id: String): ReadingPositionState {
        val file = fileFor(id)
        if (!file.exists()) return ReadingPositionState(id)
        decode(file)?.takeIf { it.bookId == id }?.let { return it }
        // One unreadable journal must not make its book unopenable or stop every sync.
        // bookmark.json still holds the position; the next sync restores this device's
        // published history. A new origin keeps new events ahead of the lost sequence.
        file.renameTo(file.resolveSibling("${file.name}.corrupt"))
        outbox.remove(id)
        // The sync's acknowledgements cover the published history that must now be read back.
        syncIndexLock.withLock {
            syncIndexGeneration++
            syncIndexFile.delete()
        }
        rotateOrigin()
        return ReadingPositionState(id)
    }

    private fun decode(file: File): ReadingPositionState? =
        runCatching { json.decodeFromString<ReadingPositionState>(file.readText()) }.getOrNull()

    private suspend fun persist(state: ReadingPositionState, root: File?) {
        directory.mkdirs()
        val file = fileFor(state.bookId)
        val text = json.encodeToString(state)
        if (!file.exists() || file.readText() != text) writeSidecarAtomically(file, text)
        val own = identity().first.id
        if (state.heads.values.any { it.deviceId == own }) outbox[state.bookId] = state.pending
        else outbox.remove(state.bookId)
        // Never resurrect a book deleted while a reader's final IO was still queued.
        if (root?.isDirectory == true) state.winner()?.let { winner ->
            val bookmark = winner.bookmark()
            if (storage.loadBookmark(root) != bookmark) storage.saveBookmark(root, bookmark)
        }
    }

    private fun fileFor(id: String) = directory.resolve("book-${digest(id)}.json")
    private fun digest(value: String) = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
}

/** Far beyond any real clock, and far below where wire milliseconds would overflow a Long. */
internal const val MAX_POSITION_SECONDS = 1e12

/** Advance at least one representable wire millisecond, including after wall-clock correction. */
internal fun nextCompatibilityTimestamp(actionTime: Double, previous: Double): Double {
    val priorMillis = positionWireMillis(previous)
    require(priorMillis < Long.MAX_VALUE - 1) { "Reading position timestamp is out of range" }
    var next = maxOf(actionTime, (priorMillis + 1) / 1000.0 - 978_307_200L)
    while (positionWireMillis(next) <= priorMillis) next = Math.nextUp(next)
    return next
}

private fun positionWireMillis(time: Double) = ((time + 978_307_200L) * 1000.0).toLong()
