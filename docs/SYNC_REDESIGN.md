# HTTP Sync v3 — re-engineering plan

**Status:** draft, May 2026. Written after the v0.7.18 hotfix dropped the `since=`
incremental filter on manual `Sync now`. The hotfix is robust but only because v2 is
fragile enough that ignoring the cursor was easier than trusting it.

This doc is the prep work for replacing v2 with a sync layer that is less ad-hoc, has
fewer edges, and can survive cross-device races without per-bug patches.

## What v2 gets wrong

The current architecture grew bug-by-bug. Symptoms:

1. **Multi-pass reconciler** (`HttpSyncReconciler.pullChangedKeys`, ~300 lines): five
   sequential passes over the same key listing — buffer, tombstones, payload import,
   metadata-shelf, bookmarks-chats, chat backfill. Each pass can partially fail. Cursor
   advancement (`safeNewCursor`) tries to be conservative, but every new key type adds
   a new pass and a new way for the cursor to lie.
2. **Two cursor stories**. The persisted `lastSyncedAt` is supposed to make sync cheap,
   but every commit since v0.7.13 has been about cases where the cursor advanced past
   keys that hadn't actually converged. The hotfix gives up on it entirely for manual
   sync. Auto-sync via the reader doesn't even use it — it only pushes, never pulls.
3. **Manifest-existence gate** (`HttpSyncPayloadCodec.uploadIfChanged`): once the
   server has any manifest for a syncId, this device refuses to push a new one. The
   user has to `curl -X DELETE` to re-upload. This was a workaround for sidecar mtime
   drift (`metadata.json`, `statistics.json`) churning the SHA every sync.
4. **Per-device sidecar churn**. `PAYLOAD_EXCLUDED_FILES` is a hardcoded list of
   "files we lie about being part of the payload" — bookmark, chat log, statistics,
   sasayaki playback, cover regeneration. Each new sidecar requires a code change in
   the codec or the cache invalidates forever.
5. **Bookmark fetch-lock logic duplicated** between `HttpSyncPusher.pushBookmark`
   and `HttpSyncReconciler.pushBookmarkIfLocalNewer`. Two near-identical implementations
   of conditional PUT, each with its own ordering bugs.
6. **Chat backfill loop** (Reconciler.kt:566–625): runs every manual sync, re-lists
   `books/{syncId}/chat/` for every Mokuro book, ad-hoc pagination, in case an older
   build advanced the cursor past chat keys that hadn't been applied. This is a
   permanent compensating loop for a transient older bug.
7. **No If-Match / etag conditional writes**. LWW per key means two devices that
   both push a bookmark on the same RFC 3339 second tie, and the second-writer wins
   regardless of which is actually newer. Fine for bookmarks; bad for metadata blobs
   (shelf placement, tombstones) where the second device's value can clobber a deletion.
8. **Mokuro-only payload sync.** EPUBs get metadata + bookmark + chat synced but no
   content. A fresh device with HTTP sync alone never sees an EPUB the user imported
   on another device.
9. **Tests are single-device fakes.** `HttpSyncTest.kt` covers ~95% of the fake
   transport's surface but doesn't exercise A↔B race scenarios, partial-failure mid-sync,
   payload sha mismatch, or cursor poisoning. Each bug above was caught by users.

## Design goals for v3

- **One reconciliation algorithm**, not five passes that have to agree.
- **Server is the source of truth.** No client-side cursor. Convergence comes from
  comparing what the server has against what we have, every sync.
- **Content-immutable payloads.** Once a book is uploaded, the only post-import
  mutations should be in per-key sidecars (bookmark, chat, position) that don't
  affect the payload hash. No exclusion list.
- **Conditional writes for mutable blobs.** Use `If-Match: <etag>` on metadata PUTs
  so a stale local copy can't clobber a tombstone or shelf move from another device.
- **Single push surface.** One class, one method, one fetch-then-PUT primitive that
  both the reader hooks and the reconciler call.
- **Symmetric A↔B test harness.** Two repos, one transport, scripted interleavings,
  assert convergence.
- **Stretch:** EPUB payload sync. The protocol already allows it; only the gate
  needs to be widened.

## Proposed architecture (v3 sketch)

### One algorithm

The reconciler becomes a single pass:

```
1. List all keys under `books/`.
2. Group by syncId:
   {syncId → {metadata?, manifest?, zip?, bookmark?, chats: [...]}}.
3. For each syncId in the union of (local books) and (remote keys):
   a. If remote tombstone (metadata.deletedAt != null):
      - delete locally if present.
   b. Else if remote has manifest but no local root:
      - download payload, unpack, save metadata sidecar.
   c. If remote has bookmark newer than local → apply.
      If local has bookmark newer than remote → PUT with If-Match.
   d. Set-union chats (both directions).
   e. Reconcile metadata blob (shelf placement, importedAt) via timestamp + If-Match.
4. For each LOCAL syncId not in remote:
   - PUT metadata + payload manifest + zip (or skip if local-only and never synced).
```

That's it. No "Pass 2 before Pass 3 because of ordering". Read everything, build a
plan, execute the plan. Errors per-syncId, surfaced in the result.

### No client cursor

Drop `lastSyncedAt`. The list is paginated, but every manual sync starts from page 1.
The per-key GET cost is bounded by the LWW check — bookmarks and chats are O(KB)
per entry, never re-downloaded, never re-applied. Saves the persistence problem
entirely. (If auto-poll ever lands, it can re-add a cursor as an optimization, but
must NEVER be the only signal for "is this key new".)

### Sidecar-stable payloads

Drop the `PAYLOAD_EXCLUDED_FILES` list and the `.payload.sha256.cache` mtime walk.
Replace with: payload SHA is computed at **import time** from the original imported
archive bytes (the user-supplied `.zip`/`.cbz`/`.epub`), stored in
`payload.original.sha256` next to the book root. Subsequent syncs read that file
verbatim — no walk, no exclusions, no cache invalidation. If the user wants to
re-upload (rare), expose a "Re-export to sync" button that recomputes.

This kills three classes of bug:
- mtime drift after open/close
- new sidecar files invalidating the cache
- coarse FAT/sdcard mtime resolution

### One push surface

Collapse `HttpSyncPusher.pushBookmark` and `pushBookmarkIfLocalNewer` into one
helper that takes the local blob, fetches the remote, compares, and PUTs with the
fetched etag as `If-Match`. Reader hook and reconciler both call this single helper.

### Etag/If-Match for metadata

Two devices, same syncId, both move it to different shelves at the same second:
under v2 the second writer silently wins. Under v3:
1. Device A GETs metadata → etag E1, PUTs `If-Match: E1` → success.
2. Device B GETs metadata → etag E1 (cached) → PUTs `If-Match: E1` → 412.
3. Device B re-fetches → etag E2 with A's new shelf → reapply if its move was
   strictly newer (by `shelfUpdatedAt`), otherwise drop.

Server change: honour `If-Match` (server pseudocode in `docs/HTTP_SYNC_KV.md` already
lists this as a possible extension; v3 commits to it). Returns 412 on mismatch.

### Symmetric test harness

```
class SyncSimulation {
    val transport: FakeKvTransport
    val deviceA: SyncFixture
    val deviceB: SyncFixture
    fun step(actor: Device, action: Action)
    fun assertConverged()
}
```

Scripted scenarios:
- Concurrent bookmark pushes
- Concurrent shelf moves
- Re-import after delete (current bug — outbound deletes the local re-import)
- Network partition mid-payload upload
- Cursor poisoning (verify it no longer matters — cursor gone)
- Payload sha drift detection

Every cross-device commit must add one scenario to this harness.

## Phasing

| Phase | What | Risk |
|---|---|---|
| 1 | Replace multi-pass reconciler with one-shot algorithm. Drop `lastSyncedAt`. | Med — touches the hot path; needs the test harness first. |
| 2 | Original-archive SHA. Stop walking the book tree. | Low — additive sidecar; old payloads still work via fallback. |
| 3 | Collapse Pusher / Reconciler push paths. | Low. |
| 4 | If-Match on metadata writes. Server-side support too. | Med — needs server change in `dragos.games`. |
| 5 | EPUB payload sync. | Low once the gate widens. |

Each phase ships behind no flag; old protocol stays wire-compatible since the
server is dumb. Old devices can keep using v2 against the same KV store as v3 devices
land.

## Open questions

1. **Server change cost.** If-Match support is small but not free; do we own the
   server enough to push that? Yes — same repo author.
2. **Bookmark conflict UX.** Today a conflict silently drops the older edit. v3 with
   If-Match still drops it (re-fetch + re-apply if newer); is "newer wins by
   wallclock" still good enough? Probably yes; the alternative is showing a UI for
   conflicts and that's not worth the complexity for a single user.
3. **Migration**. Old payloads on the server were uploaded under the per-walk SHA.
   v3 wants the original-archive SHA. Either: (a) v3 computes both during the
   transition window and trusts whichever matches, or (b) v3 always re-uploads on
   first run. (a) is cheaper; (b) is simpler. Pick (a).

## What this doc is NOT

A v3 spec. The wire format ideally stays the same as `docs/HTTP_SYNC_KV.md` — server
is still dumb storage. This doc is about the CLIENT-side reconciliation and where
the current implementation is structurally bad. The actual implementation will land
in a series of PRs, each one of which will reference back to this doc.
