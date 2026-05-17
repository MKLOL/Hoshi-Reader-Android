# Hoshi Sync v3 — implementation spec

**Status:** active. Implementation lives in
`app/src/main/java/moe/antimony/hoshi/features/sync/v3/` and is intentionally **not
hooked into the app** — `HttpSyncSettingsView`, `HoshiAppContainer`, and the reader
hooks still call the v2 reconciler. v3 ships as parallel code with its own tests, and
the user will tell us when to flip the switch.

This spec is the contract that all implementation and review agents work against. If
you find ambiguity, fix the spec first, then the code.

## Why a v3

See `docs/SYNC_REDESIGN.md` for the full motivation. Quick summary of what v2 gets
structurally wrong:

1. Five-pass reconciler with cross-pass invariants that drift bug-by-bug.
2. Persisted `lastSyncedAt` cursor that has been wrong every time a new key kind was
   added; the v0.7.18 hotfix drops `since=` filtering entirely on manual sync.
3. Manifest-existence gate that refuses re-upload — the user has to `curl -X DELETE`
   to re-push a book.
4. Per-device sidecar churn worked around with a hardcoded exclusion list.
5. Bookmark fetch-lock logic duplicated between `HttpSyncPusher` and
   `HttpSyncReconciler`.
6. Chat backfill loop that re-lists every Mokuro book's chat prefix every manual sync.
7. Mokuro-only payload sync; EPUBs never round-trip the content.
8. No A↔B integration tests; every race condition is found by users.

## Goals (v3.0)

1. **Single-pass reconciliation.** Read local state, read remote state, compute a
   plan, execute the plan. No buffered passes that have to agree.
2. **No client cursor.** Manual `Sync now` always full-lists `books/`. The wire
   protocol still supports `since=` for a future incremental mode, but v3.0 doesn't
   use it. (Out of scope: auto-poll.)
3. **Wire-protocol-compatible with v2.** Same key layout, same blob shapes, same KV
   server (`docs/HTTP_SYNC_KV.md`). The server is data storage; no server changes
   for v3.0.
4. **Single push primitive** shared by the reconciler and the reader hooks (later —
   v3.0 keeps the reader on v2 since we don't hook anything up).
5. **EPUB payload sync.** Same `payload.zip` + `payload.manifest` keys, just widen
   the gate that the v2 codec hides behind.
6. **Real integration tests.** Embedded HTTP server (`StubKvServer`) implements the
   v2 KV protocol in-process. Android instrumentation tests run two simulated
   devices against one server.

## Non-goals (v3.0)

- Server-side conditional writes (`If-Match` / 412). The v2 server doesn't honour
  them and we promised no backend changes. LWW per key stays.
- Real-time push / websockets / auto-poll.
- Migration tooling. v3 reads and writes the same keys as v2; an existing user's
  server state stays valid.
- Hooking v3 into the app. That ships in a later, deliberate commit, gated on the
  user's word.

## Wire protocol (unchanged from v2)

Verbatim from `docs/HTTP_SYNC_KV.md`. Re-stated here only so implementers and
reviewers don't have to cross-reference.

Routes:
- `PUT /v1/kv/{key}` — upsert opaque bytes. Returns `{key, lastModified, etag, size, contentType}`.
- `GET /v1/kv/{key}` — fetch bytes + headers. `404` on absent.
- `GET /v1/kv?prefix=...&since=...&cursor=...&limit=...` — list metadata only. Paginated; `truncated=true` + `nextCursor` for more.
- `DELETE /v1/kv/{key}` — `204` on delete, `404` is no-op success.
- Multipart: `POST /v1/kv-multipart/start`, `PUT /v1/kv-multipart/{uploadId}/{partNumber}`, `POST /v1/kv-multipart/{uploadId}/complete`, `DELETE /v1/kv-multipart/{uploadId}`.

Keys, per book at `syncId = deriveSyncId(title)`:

| Key | Mutability |
|---|---|
| `books/{syncId}/metadata` | overwrite — `{title, contentType, shelfName?, shelfUpdatedAt?, importedAt?, deletedAt?}` |
| `books/{syncId}/bookmark` | overwrite per page-turn batch — `{chapterIndex, progress, characterCount, lastModified}` |
| `books/{syncId}/chat/{ts}-{nonce}` | write-once — chat entry |
| `books/{syncId}/payload.zip` | rare overwrite — zipped book directory bytes |
| `books/{syncId}/payload.manifest` | rare overwrite — `{sha256, sizeBytes, originalName, format}` |
| `app/ai_chat_settings` | overwrite — global ChatGPT settings (model + prompts) |

v3 reuses the existing blob types from `HttpSyncBlobs.kt` verbatim
(`HttpSyncMetadataBlob`, `HttpSyncBookmarkBlob`, `HttpSyncChatEntryBlob`,
`HttpSyncPayloadManifest`, `HttpSyncAiChatSettingsBlob`). It also reuses the
`HttpSyncKvTransport` interface and `HttpSyncKvClient` implementation. **Do not
duplicate or rename these.**

## Package layout

```
app/src/main/java/moe/antimony/hoshi/features/sync/v3/
├── V3SyncEngine.kt          — public entry point, top-level orchestration
├── V3Models.kt              — data classes: V3SyncResult, V3Progress, V3Plan, V3Action, V3LocalBook, V3RemoteBook
├── V3LocalState.kt          — reads local state into V3LocalBook[]
├── V3RemoteState.kt         — paginated list + group remote keys into V3RemoteBook[]
├── V3Planner.kt              — computes V3Plan from local + remote
├── V3Executor.kt             — applies V3Plan against the transport
├── V3PushOps.kt              — single fetch-then-PUT primitives (bookmark, metadata, chat, payload)
└── README.md                 — one-pager intro pointing at this spec
```

```
app/src/test/java/moe/antimony/hoshi/features/sync/v3/
├── V3SyncEngineTest.kt       — full-engine fakes-based tests (mirrors HttpSyncTest)
├── V3PlannerTest.kt          — planner in isolation
├── V3RaceTest.kt             — A↔B scripted races against shared FakeKvTransport
├── V3EdgeCaseTest.kt         — pathological inputs, schema drift, malformed blobs
└── V3PushOpsTest.kt          — push primitives, conditional bookmark logic
```

```
app/src/androidTest/java/moe/antimony/hoshi/features/sync/v3/
├── StubKvServer.kt                — NanoHTTPD-backed in-process KV server, full v2 protocol incl. multipart
├── StubKvServerTest.kt            — protocol-level sanity for the stub
├── V3SyncIntegrationTest.kt        — single-device round-trip against stub server
├── V3SyncRaceIntegrationTest.kt    — two simulated devices vs one stub server
├── V3SyncLargeLibraryTest.kt       — pagination, multipart payload, hundreds of books
└── V3SyncFailureModeTest.kt        — network hiccups, 5xx, dropped connections, partial uploads
```

## Architecture: the four-step algorithm

```kotlin
suspend fun syncOnce(settings, onProgress): V3SyncResult {
    // Step 1: snapshot local state.
    val local: V3LocalSnapshot = localState.read()
    onProgress(...)

    // Step 2: snapshot remote state by paginated list + grouping.
    val remote: V3RemoteSnapshot = remoteState.list(transport, onProgress)
    onProgress(...)

    // Step 3: pure function from (local, remote) -> Plan.
    val plan: V3Plan = planner.compute(local, remote)
    onProgress(...)

    // Step 4: execute plan, collecting per-action results + errors.
    return executor.run(plan, transport, onProgress)
}
```

Each step is a class so each can be unit-tested in isolation. Step 3 is pure and
deterministic — given the same `(local, remote)`, it always produces the same plan.

### Step 1 — V3LocalState

Reads from `BookRepository`, `AiChatHistoryStore`, `AiChatSettingsRepository`:

```kotlin
data class V3LocalBook(
    val bookId: String,
    val syncId: String,
    val title: String,
    val root: File,
    val contentType: ContentType,
    val shelfName: String?,
    val shelfUpdatedAt: String?,   // from the existing shelf state sidecar
    val bookmark: Bookmark?,
    val chatEntries: List<AiChatEntry>,  // empty for non-Mokuro books
    val pendingDeletion: HttpSyncDeletedBookRecord?,  // from .http_sync_deleted_books.json
)

data class V3LocalSnapshot(
    val books: List<V3LocalBook>,
    val aiSettings: AiChatSettings?,
    /** Captured at read time so concurrent local edits during sync don't poison the snapshot. */
    val readAt: Instant,
)
```

Importantly: V3LocalState reads the SAME existing sidecars as v2
(`.http_sync_shelf_state.json`, `.http_sync_deleted_books.json`). No new sidecar
files in v3.0.

### Step 2 — V3RemoteState

Lists `books/` and `app/` paginated, fetches each non-list body it needs, groups by
syncId:

```kotlin
data class V3RemoteBook(
    val syncId: String,
    /** Present iff the server has a metadata blob. */
    val metadata: HttpSyncMetadataBlob? = null,
    val metadataLastModified: String? = null,
    val manifest: HttpSyncPayloadManifest? = null,
    val manifestLastModified: String? = null,
    val bookmark: HttpSyncBookmarkBlob? = null,
    val bookmarkLastModified: String? = null,
    /** Keys present on the server, NOT bodies. Bodies fetched lazily in executor. */
    val chatKeys: Set<String> = emptySet(),
)

data class V3RemoteSnapshot(
    val books: Map<String, V3RemoteBook>,
    val aiSettings: HttpSyncAiChatSettingsBlob? = null,
    val aiSettingsLastModified: String? = null,
)
```

Bodies for metadata, manifest, bookmark, and ai_chat_settings ARE fetched here
because (a) planner needs them to decide, (b) they're tiny.

Chat entry BODIES are not fetched here. The planner just sees the set of present
keys; the executor fetches the bodies for keys it needs to import.

Payload zip bodies are obviously not fetched here.

### Step 3 — V3Planner (pure)

```kotlin
sealed interface V3Action {
    data class DeleteLocalBook(val root: File, val syncId: String) : V3Action
    data class ImportRemoteBook(val syncId: String, val manifest: HttpSyncPayloadManifest) : V3Action
    data class ApplyRemoteBookmark(val root: File, val syncId: String, val blob: HttpSyncBookmarkBlob) : V3Action
    data class ApplyRemoteMetadata(val root: File, val syncId: String, val blob: HttpSyncMetadataBlob) : V3Action
    data class ImportChat(val root: File, val syncId: String, val key: String) : V3Action
    data class PushBookmark(val root: File, val syncId: String, val bookmark: Bookmark, val expectedRemote: HttpSyncBookmarkBlob?) : V3Action
    data class PushMetadata(val syncId: String, val blob: HttpSyncMetadataBlob) : V3Action
    data class PushChat(val root: File, val syncId: String, val entry: AiChatEntry, val key: String) : V3Action
    data class PushPayload(val root: File, val syncId: String, val title: String, val format: HttpSyncContentType) : V3Action
    data class PushTombstone(val syncId: String, val record: HttpSyncDeletedBookRecord) : V3Action
    data class PushAiSettings(val local: AiChatSettings) : V3Action
    data class ApplyAiSettings(val remote: HttpSyncAiChatSettingsBlob) : V3Action
}

data class V3Plan(
    val actions: List<V3Action>,
    /** Books that exist on the server but cannot be imported yet (e.g. no manifest). */
    val pendingRemoteOnlyBooks: Set<String>,
)
```

Planner rules (deterministic):

- **Tombstones win.** If `remote.metadata.deletedAt != null`, the only action for
  that syncId is `DeleteLocalBook` (if local exists). Skip everything else for it.
- **Local pending-deletion wins.** If local has a pending tombstone, action is
  `PushTombstone` (even if local book still exists on disk because deletion is
  staged but not pushed). Skip other actions for that syncId.
- **Import remote-only books.** If `remote.manifest != null` and no local root for
  that syncId, action is `ImportRemoteBook`. Subsequent actions referencing the
  same syncId are deferred until executor materializes the local root.
- **Bookmark LWW.** Compare `remote.bookmark.lastModified` to local
  `bookmark.lastModified` (via `compareRfc3339`). Newer wins. Tie → no action.
- **Metadata shelf merge.** Uses the existing `shouldApplyRemoteShelfPlacement`
  rule from v2 verbatim. Reason: existing user state must keep merging the same
  way; don't introduce a behavioral fork in the same release as the architecture
  change.
- **Chat set-union.** For every server chat key not in local, action is `ImportChat`.
  For every local chat not on server (computed from content-addressable key shape),
  action is `PushChat`.
- **Payload push.** For every Mokuro OR EPUB local book where remote has no
  manifest, action is `PushPayload`. (Widened gate: EPUB included in v3.)
- **Payload re-push policy.** If remote has a manifest, v3.0 does NOT re-upload.
  Same conservative gate as v2. (Re-pushing on hash mismatch can land in v3.1.)
- **App settings.** LWW on `lastModified`. Tie → no action. Single global key.

Output is the action list in a determined order:

1. PushTombstone (so server learns about deletes before we look at metadata).
2. ApplyRemoteMetadata (apply deletions and shelf moves from server).
3. DeleteLocalBook (executes server-side tombstones we just learned about).
4. ImportRemoteBook (download payloads for new books).
5. ApplyRemoteBookmark / ImportChat / ApplyAiSettings (apply remote state).
6. PushBookmark / PushChat / PushPayload / PushMetadata / PushAiSettings (push
   local-newer state).

Within each bucket, sort by syncId for determinism.

### Step 4 — V3Executor

Walks the plan, runs each action, collects results in a `V3SyncResult`. Per-action
errors are caught and reported per syncId; one bad book doesn't abort the rest.

```kotlin
data class V3SyncResult(
    val applied: V3AppliedCounts,
    val pushed: V3PushedCounts,
    val remoteOnlyBooks: Int,
    val errors: List<V3Error>,
)

data class V3AppliedCounts(
    val bookmarks: Int = 0,
    val chatEntries: Int = 0,
    val payloads: Int = 0,
    val metadataDeletes: Int = 0,
    val shelfPlacements: Int = 0,
    val aiSettings: Int = 0,
)

data class V3PushedCounts(
    val bookmarks: Int = 0,
    val chatEntries: Int = 0,
    val metadata: Int = 0,
    val payloads: Int = 0,
    val tombstones: Int = 0,
    val aiSettings: Int = 0,
)

data class V3Error(
    val syncId: String?,        // null for app-settings errors
    val action: String,         // class name of the V3Action that failed
    val message: String,
)
```

Bookmark / chat / metadata / payload push paths all go through `V3PushOps`. The
executor never calls `transport.put` directly — it always goes through a push op
that handles fetch-then-PUT correctly.

## V3PushOps — the single push primitive

```kotlin
class V3PushOps(
    private val transport: HttpSyncKvTransport,
    private val locks: V3BookLocks,            // reused from HttpSyncBookLocks
    private val bookRepository: BookRepository,
    private val aiHistoryStore: AiChatHistoryStore,
    private val payloadCodec: HttpSyncPayloadCodec,  // reused verbatim
) {
    /**
     * Conditional bookmark PUT. Fetches the remote, compares lastModified to local,
     * and either:
     *  - pushes local if local is strictly newer,
     *  - applies remote locally if remote is strictly newer (under the per-book lock),
     *  - no-op on tie.
     *
     * Returns the action taken so the executor can count it correctly.
     */
    suspend fun pushBookmarkConditional(...): PushBookmarkOutcome

    suspend fun pushChat(...): Boolean
    suspend fun pushMetadata(...): HttpSyncKvWriteResponse
    suspend fun pushPayload(...): Boolean    // returns true if uploaded, false if skipped
    suspend fun pushTombstone(...): HttpSyncKvWriteResponse
    suspend fun pushAiSettings(...): HttpSyncKvWriteResponse
}
```

This single class is the ONLY thing that writes to the transport in v3. The reader
hook will later be migrated to call `V3PushOps.pushBookmarkConditional` and
`pushChat` directly, with no per-call sync engine.

## Concurrency / locking

- Per-book mutex (`V3BookLocks`, identical shape to `HttpSyncBookLocks`) wrapped
  around any read-then-write sequence on a single book's local state (bookmark,
  chat append, metadata save).
- The engine runs one action at a time. No parallel action execution in v3.0; it
  simplifies error handling and the test surface, and the wallclock cost is
  dominated by payload uploads which are sequential anyway.
- The transport itself is thread-safe (each request opens its own connection).
- Read-then-write LWW on bookmarks holds the per-book lock for the full
  fetch-compare-write sequence. Same pattern as v2's `pushBookmarkIfLocalNewer`.

## Error handling

- Per-action errors caught in the executor → `V3Error` in the result.
- The engine itself only throws on:
  - `IllegalStateException("HTTP sync is not configured")` if settings.isConfigured is false.
  - Fatal transport errors that prevent the entire sync (e.g. 401, DNS failure on
    the LIST). Per-book GET/PUT failures are non-fatal.
- The executor never silently swallows. Every caught exception is added to
  `result.errors`.

## Progress reporting

```kotlin
data class V3Progress(
    val phase: V3Phase,
    val message: String,
    val detail: String? = null,
    val completed: Int? = null,
    val total: Int? = null,
)

enum class V3Phase {
    ReadingLocal,
    ListingRemote,
    Planning,
    PushingTombstones,
    ApplyingMetadata,
    DeletingBooks,
    ImportingPayloads,
    ApplyingRemoteState,
    PushingLocalState,
    SyncingAppSettings,
    Done,
}
```

The settings view's UI maps phase + counters to a human-friendly status line, same
shape as v2's `HttpSyncProgress`.

## Test strategy

### Unit tests (JVM, no Android)

`V3PlannerTest` — feed scripted local + remote snapshots, assert exact action lists.
Cover: empty/empty, local-only, remote-only, tombstone (both sides), tie-breakers,
shelf merge, chat set-union, deeply nested edge cases.

`V3SyncEngineTest` — full engine against a `FakeKvTransport` (reuse v2's). Cover
the user-visible scenarios from `HttpSyncTest` (fresh device, incremental, cross-
device race, etc.) but using the v3 entry point. Tests prove parity with v2 for
the existing scenarios.

`V3RaceTest` — script device A and device B against the SAME `FakeKvTransport`:
both push bookmark, both move shelf, both delete + reimport, etc. Assert convergence.

`V3EdgeCaseTest` — malformed blobs, missing manifest with present zip, present
manifest with missing zip, syncId collisions across content types, blank titles,
non-ASCII titles, partial outbound writes.

`V3PushOpsTest` — focused tests for the push primitive.

### Integration tests (Android device, real HTTP)

`StubKvServer` — a NanoHTTPD-backed in-process HTTP server implementing the full
v2 KV protocol (paginated list, multipart upload, etc). Persists state in memory
during the test. Bound to localhost on an ephemeral port.

`V3SyncIntegrationTest` — fresh device imports a Mokuro book, syncs, asserts
server state via direct stub-server inspection. Then a second `BookRepository`
syncs and asserts it gets the book.

`V3SyncRaceIntegrationTest` — two `BookRepository` instances against the same stub
server. Scripted interleavings.

`V3SyncLargeLibraryTest` — 200 books, ensure pagination across multiple list pages
works end-to-end. Multipart payload upload (force the threshold low) round-trips.

`V3SyncFailureModeTest` — stub server returns 5xx on specific keys, drops
connections mid-stream, returns malformed JSON. Engine surfaces per-book errors
without aborting the whole sync.

### Mandatory edge cases

The test files MUST cover at minimum these scenarios. Reviewers verify each:

1. **Fresh-device cold start.** Empty local. Server has N books. Sync → all books local.
2. **Reverse cold start.** Empty server. Local has N books. Sync → all on server.
3. **Bidirectional new content.** Each side has books the other doesn't. Sync → both converge.
4. **Bookmark LWW symmetric.** A pushes bookmark at T1, B pushes at T2 > T1.
   After both sync, both end up at T2's bookmark.
5. **Bookmark tie.** Same `lastModified` on both sides; no action either way.
6. **Chat set-union.** A has [x,y], B has [y,z]. Sync → both end with [x,y,z].
7. **Tombstone round-trip.** A deletes book; B picks up the tombstone; B's local copy is removed.
8. **Re-import after delete.** A deletes; tombstone is on server; A re-imports the
   same title — local re-import survives, doesn't get wiped by its own tombstone.
9. **Shelf placement merge.** A moves to "Reading"; B is on default; older
   `shelfUpdatedAt` doesn't beat newer one.
10. **EPUB payload sync.** Confirms the v3 gate widening — EPUBs round-trip.
11. **Pagination.** Server has > 500 keys; both list pages traversed.
12. **Multipart upload.** Payload over the multipart threshold uploads + downloads
    intact, sha256 verified.
13. **Stale cursor / no cursor.** v3 never reads `lastSyncedAt`; integration test
    sets it to garbage and confirms full pull still works.
14. **Manifest without zip.** Server has manifest, no payload.zip. Import surfaces
    error per-book without crashing the sync.
15. **Zip without manifest.** Server has zip but no manifest. Engine ignores
    (manifest is the change-detector).
16. **Malformed JSON.** Bookmark/metadata/chat blob has corrupt body. Engine
    reports per-key error, other books proceed.
17. **401/403 mid-sync.** Auth fails mid-pagination → engine throws fatal, caller
    handles.
18. **Concurrent reader push.** Reader pushes bookmark while sync runs. Per-book
    lock prevents corruption.
19. **Large library.** 200 books × 5 keys = 1000 keys end-to-end.
20. **App settings LWW.** ChatGPT model/prompt round-trips bidirectionally.

### Scripted multi-device integration scenarios (mandatory)

The instrumented `V3Sync*IntegrationTest` files MUST script the following whole-
session scenarios. Each scenario runs against ONE `StubKvServer` shared by
multiple in-process `BookRepository`s (each "device" is a fresh repo). Devices
sync in scripted order; assertions check final convergence and per-step counts.

Each device acts via a tiny harness:

```kotlin
class SimDevice(val name: String, val repo: BookRepository, val history: AiChatHistoryStore, val engine: V3SyncEngine) {
    suspend fun importMokuro(title: String, pages: Int = 1): File
    suspend fun importEpub(title: String): File
    suspend fun turnPage(book: File, toCharacter: Int)
    suspend fun chat(book: File, bubbleText: String, response: String)
    suspend fun moveToShelf(book: File, shelfName: String?)
    suspend fun delete(book: File)
    suspend fun sync(): V3SyncResult
}
```

Scenarios:

1. **Three-device fanout.** Device A imports 5 books. A syncs. Devices B and C
   sync. Both B and C end up with all 5 books. Then C imports a 6th, syncs. A and
   B sync. All three devices have 6 identical libraries.

2. **Read-the-same-book race.** A and B both have book X. A turns pages 1→2→3,
   syncs. B turns pages 1→2, syncs (its bookmark older). Final: B picks up A's
   page-3 bookmark. Then B turns to page 4, syncs. A syncs. A is now on page 4.

3. **Concurrent local writes.** A imports book X, page-turns to 10, syncs. B
   imports book Y, page-turns to 20, syncs. Then A and B both sync. A ends with
   both X and Y at the correct page; B ends with both books at the correct page.

4. **Tombstone + reimport.** A imports X, syncs. B imports X via sync. A deletes
   X (tombstone goes to server), syncs. B syncs — B's X is removed. Then A
   re-imports X with the same title. A syncs. B syncs. Both have X back with no
   tombstone.

5. **Shelf reorganization across three devices.** All three have 10 books on the
   default shelf. A puts {1, 3, 5} on "Reading". B puts {2, 4} on "Done". A and
   B sync. C syncs. C now has {1, 3, 5} on "Reading", {2, 4} on "Done", rest on
   default.

6. **Mid-page-turn sync.** Reader on device A has been turning pages and a sync
   fires while a page-turn is mid-write. Per-book lock ensures the synced
   bookmark matches the latest fully-persisted local one (not torn state).

7. **Chat-heavy book.** A and B both read book X. A produces 50 chat entries
   (different `bubbleText`s), interleaved with 30 page turns. B produces 50
   different chat entries. After both sync (in either order), both devices have
   all 100 chat entries, deduped (same key → single entry).

8. **Same-chat collision.** A and B both produce a chat with identical
   `bubbleText`, `response`, and `timestampSeconds` (e.g., system-generated reply
   to the same line). Server has one entry, both devices have one entry, no
   duplicate.

9. **EPUB sync round-trip.** A imports an EPUB, syncs. B syncs. B has the EPUB
   payload with bytes intact (the v3-widened gate).

10. **Large-payload multipart.** A imports a >64 MiB Mokuro volume (forced via
    test config to lower the threshold). Upload uses multipart, download via stub
    server reconstructs identical bytes (sha256 verified).

11. **Hundreds of books pagination.** Seed the stub server with 200 books (just
    metadata + manifest + small zip each). A sync against it must traverse every
    list page; no book is missed.

12. **Network flapping.** Stub server returns 503 on every other GET for a
    specific syncId. The sync surfaces per-book errors but completes for every
    other book.

13. **Stub server crash recovery.** Mid-sync the stub server's `kv` map is
    replaced wholesale (simulating server restart with snapshot restore). The
    next sync converges.

14. **Hostile clock skew.** Stub server stamps `lastModified` artificially in the
    past (or future). Engine handles bookmark LWW correctly because the COMPARE
    uses the stored timestamps, not local wallclock.

15. **Long-running session.** A device does a 5-minute scripted session:
    imports book, page-turns 100 times, chats 10 times, moves to shelf, syncs
    midway, page-turns more, syncs again, deletes book, syncs. Final server
    state matches the script's expectations exactly.

16. **Two devices, opposite operations.** A is busy upload-heavy (imports 10
    books, syncs). B is busy delete-heavy (had 10 books, deletes 5, syncs).
    Server state and both devices converge correctly.

17. **Repeated sync idempotency.** Run the same sync 5 times in a row. After the
    first, every subsequent sync produces zero pushes / zero downloads (modulo
    counters), no errors.

18. **Cross-content-type collision.** A imports a Mokuro book titled "X". B
    imports an EPUB titled "X". Both have the same `syncId`. The first to sync
    wins the payload; the second's sync surfaces a per-book error rather than
    silently overwriting. (Real bug we want to catch.)

19. **Reader hook interleave with sync.** While the reader hook is pushing a
    bookmark for book X, a full sync starts. The bookmark push lands, the sync
    sees it via list, no race-induced double-write or rollback.

20. **All-features stress.** Two devices simultaneously do: import + delete +
    shelf move + page-turn + chat + sync, in a scripted but realistic
    interleave. Run for 20 rounds. Assert final convergence and no errors.

These are not optional. Reviewers MUST flag any missing scenario.

## Backward compatibility / hookup plan

When the user is ready to flip the switch, the swap is:

1. In `HoshiAppContainer.kt`, replace the construction of `HttpSyncReconciler`
   with `V3SyncEngine` (same constructor inputs available — they share the
   `BookRepository`, `HttpSyncPayloadCodec`, etc).
2. In `HttpSyncSettingsView.kt`, change the call site from
   `reconciler.syncOnce(settings) { progress -> … }` to
   `engine.syncOnce(settings) { progress -> … }`. The progress callback shape is
   different (`V3Progress` vs `HttpSyncProgress`) — adapt the UI status mapping
   accordingly. Plan to use a thin adapter so the swap is one import + one type
   rename.
3. The reader hooks stay on `HttpSyncPusher` for now. Future PR migrates them to
   `V3PushOps`.

The KV server state, local sidecars, and DataStore-backed `HttpSyncSettings` are
**unchanged**, so a device that runs v2 yesterday and v3 today picks up where it
left off with no migration step.

## Out-of-scope items (do NOT do)

- Don't touch `HttpSyncReconciler`, `HttpSyncPusher`, `HttpSyncReaderHooks`,
  `HttpSyncSettingsView`, or `HoshiAppContainer` in this PR.
- Don't change the wire protocol or any server behavior.
- Don't change `HttpSyncBlobs.kt` types (reuse them verbatim).
- Don't change `HttpSyncPayloadCodec` — v3 reuses it.
- Don't add a feature flag — v3 lives in its own package, unwired.
- Don't cut a release. Don't add a changelog entry.

## What "done" looks like

- All files under the package layout above exist and compile.
- All unit tests pass with `./gradlew testDebugUnitTest`.
- All instrumentation tests compile with `./gradlew assembleDebugAndroidTest`. (We
  can't run them without a device in CI; the user will run them manually.)
- All 20 mandatory edge cases above are covered by an explicit test (unit or
  integration; some are easier as one or the other).
- `docs/SYNC_V3_SPEC.md` (this doc) and `docs/SYNC_REDESIGN.md` reference each other.
- `app/src/main/java/moe/antimony/hoshi/features/sync/v3/README.md` exists and
  links to this spec.
- The commit message is "feat: v3 sync engine (not yet hooked up)" or similar.
