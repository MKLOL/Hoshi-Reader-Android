# Hoshi Sync — existing-KV map protocol

**Status:** implemented by the Android and iOS clients. No sync-specific backend endpoint
or backend deployment is required; the clients use the existing generic `/v1/kv` API.

The fast path adds two logical opaque per-user maps to the original per-book KV layout:
`BookID -> static-content SHA256` and `BookID -> bookmark state`. The bookmark map is
physically split into one small shard per installation because generic KV PUT has no CAS;
each client only overwrites its own shard, then merges all shards by event timestamp. Book
payloads (.epub / mokuro folder) still use their existing binary keys.

The fix: the server stops knowing about books at all. It's a generic key/value blob
store. All schema is client-side.

## Goals

- **Server is dumb storage.** It learns nothing about bookmarks, chat, manga, EPUB.
  New client features need zero server changes.
- **Batched bookmark writes.** Every dirty EPUB/manga position is merged into one small
  per-install bookmark-shard PUT at most five seconds after local persistence.
- **Book payloads sync too.** Importing a book on phone A and reading it on phone B is
  one zip blob + a manifest entry; no per-file API.
- **Append-only where we can get away with it.** Chat entries are write-once at unique
  keys; merging is set-union with no logic.
- **Single user.** One bearer token, one namespace. Multi-user is out of scope for v2.

## Non-goals

- Real-time push / websockets. One cheap metadata poll every five seconds while the app is active
  is enough.
- Server-side conflict resolution. Last-write-wins per key, client decides.
- Server-side enforcement of hoshi schemas. The server only validates "is this a valid
  key, is the body within size limits, is the bearer token correct."

## API

Four routes. That's it. All paths support nested keys via `path:` segments — the slash
is part of the key, not a directory.

All requests carry `Authorization: Bearer <token>`. All responses are
`application/json; charset=utf-8` except blob `GET` (which returns the stored content
type) and blob `PUT` (which is the request body's content type).

### `PUT /v1/kv/{key}`

Upsert a value at `key`. Body is opaque bytes. Server stamps a server-side
timestamp and content hash, returns them.

Headers:
- `Content-Type` — preserved and returned on subsequent `GET`. (Hoshi uses
  `application/json` for everything except `payload.zip`, which is
  `application/zip`.)
- `Content-Length` — required.

Response:
```json
{
  "key": "books/yotsubato_01/bookmark",
  "lastModified": "2026-05-15T12:34:56.789Z",
  "etag": "sha256:9af1...c0",
  "size": 246,
  "contentType": "application/json"
}
```

Small `payload.zip` uploads may still use this route. Large payloads should use the
multipart routes below so every individual request stays below Cloudflare's body cap.

### `POST /v1/kv-multipart/start`

Start an upload for a large value. The server creates an opaque upload id and stores
parts temporarily until `complete` or `cancel`.

Request:
```json
{
  "key": "books/yotsubato_01/payload.zip",
  "contentType": "application/zip"
}
```

Response:
```json
{
  "uploadId": "opaque-upload-id"
}
```

### `PUT /v1/kv-multipart/{uploadId}/{partNumber}`

Upload one raw byte part. `partNumber` is 1-based. Android currently sends parts up to
64 MiB so each request remains comfortably below Cloudflare's 100 MB limit.

### `POST /v1/kv-multipart/{uploadId}/complete`

Finish a multipart upload. The server concatenates the listed parts in order, upserts
the finished value into KV, and deletes the temporary upload rows/files.

Request:
```json
{
  "parts": [1, 2, 3]
}
```

Response:
```json
{
  "key": "books/yotsubato_01/payload.zip",
  "lastModified": "2026-05-15T12:34:56.789Z",
  "etag": "sha256:9af1...c0",
  "size": 104857601
}
```

### `DELETE /v1/kv-multipart/{uploadId}`

Cancel an unfinished multipart upload and delete all temporary parts. Android calls this
best-effort if a part or complete request fails.

### `GET /v1/kv/{key}`

Return the stored bytes. Returns the original `Content-Type`, plus `Last-Modified`
(RFC 3339 UTC) and `ETag` (sha256). **404** if the key does not exist.

### `DELETE /v1/kv/{key}`

Remove the key. Returns `204`. **404** is a no-op success from the client's view.

(Tombstone semantics — "deleted on phone A, phone B should also delete" — are handled
client-side by writing a `metadata` blob with `deletedAt`, see § Conventions below.
Hard `DELETE` is just for scrubbing the server.)

### `GET /v1/kv?prefix={prefix}&since={iso8601}&cursor={c}&limit={n}`

List keys with metadata, optionally filtered by a write-time floor. **Does not**
return values. The client uses this to find changes since the last poll and then
fetches what it wants.

Query params:
- `prefix` (optional) — restrict to keys starting with this string.
- `since` (optional) — return only keys with `lastModified > since` (RFC 3339 UTC).
- `cursor` (optional) — pagination cursor from a previous truncated response.
- `limit` (optional, default 500, max 2000) — page size.

Response:
```json
{
  "keys": [
    {
      "key": "books/yotsubato_01/bookmark",
      "lastModified": "2026-05-15T12:34:56.789Z",
      "etag": "sha256:9af1...c0",
      "size": 246,
      "contentType": "application/json"
    },
    { "key": "books/yotsubato_01/chat/2026-05-15T12:33:11.220Z-9c2e", "lastModified": "...", "etag": "...", "size": 612, "contentType": "application/json" }
  ],
  "truncated": false,
  "nextCursor": null
}
```

`truncated: true` + a `nextCursor` means there are more results; pass it back in the
next request. Cursor is server-defined; opaque to the client.

## Auth & errors

- Missing or wrong bearer token → `401` with `{ "error": "..." }`.
- Body exceeds the per-PUT cap → `413`. Recommended cap **200 MB** so a manga zip
  fits; document the exact value in the response.
- Key fails validation (see § Key naming) → `400`.
- Server-side persistence error → `5xx` with `{ "error": "..." }`. Client retries.

## Key naming

The server validates a strict key grammar so accidental garbage doesn't end up in the
namespace:

```
key       = segment ( "/" segment )*
segment   = 1*64 ( ALPHA / DIGIT / "_" / "-" / "." )
total length ≤ 512 bytes
```

Anything else → `400`. The server doesn't care what the segments **mean** — but
keeping the grammar tight makes nginx logs readable and protects against `..` shenanigans.

## Client-side conventions (informative; server doesn't enforce)

The Android client uses this layout under one shared root prefix `books/`:

| Key | Content-Type | Schema | Mutability | Approx size |
|---|---|---|---|---|
| `sync/maps/books.json` | `application/json` | `{BookID: "sha256:..."}` | overwrite after book reconcile | O(number of books) |
| `sync/maps/bookmarks/{deviceId}.json` | `application/json` | `{BookID: {etag, lastModified, value}}` | that installation's five-second batches | O(books read on device) |
| `books/{syncId}/metadata` | `application/json` | `{title, contentType, shelfName?, shelfUpdatedAt?, importedAt, deletedAt?}` | overwrite | ~250 B |
| `books/{syncId}/bookmark` | `application/json` | legacy bookmark read during migration | old clients only | ~250 B |
| `books/{syncId}/chat/{ts}-{nonce}` | `application/json` | `{bubbleText, prompt, model, response, timestampSeconds, screenshotImage?}` | **write-once** | ~500 B – 2 KB text-only; screenshot entries include the cropped PNG as base64 |
| `books/{syncId}/payload.zip` | `application/zip` | zip of a Mokuro book directory | overwrite (rare; effectively immutable) | 10 MB – 200 MB |
| `books/{syncId}/payload.manifest` | `application/json` | `{sha256, sizeBytes, originalName, format: "mokuro"}` | overwrite | ~150 B |
| `books/{syncId}/epub.zip` | `application/zip` | zip of an extracted EPUB directory | overwrite (rare; effectively immutable) | 1 MB – 200 MB |
| `books/{syncId}/epub.manifest` | `application/json` | `{sha256, sizeBytes, originalName, format: "epub"}` | overwrite | ~150 B |
| `books/{syncId}/sentences` | `application/json` | validated EPUB sentence translations | download-only | 1 MB – 5 MB typical |

- `syncId` is persisted in `metadata.json`. New imports derive it from title plus a folder
  hash only when duplicate-title folder uniquification requires one; legacy records backfill
  the same way. A remote import keeps the key's exact identity.
- `shelfName` syncs the book's bookshelf shelf/folder placement by visible shelf name.
  Missing `shelfName` means an older client wrote the metadata and the receiver should
  leave local shelf placement alone; explicit `null` means intentionally unshelved.
- `shelfUpdatedAt` is an RFC 3339 UTC per-book placement timestamp retained in local
  sync state. Receivers apply remote shelf placement only when this value is at least
  as fresh as that book's local shelf placement, so moving one book cannot make stale
  folder data for another book win.
- `{ts}` in chat keys is the entry's RFC 3339 UTC timestamp; `{nonce}` is a short
  random suffix so two chats produced at the same second on different devices don't
  collide.
- A book that's been deleted on one device writes `deletedAt: <ts>` into its `metadata`
  blob. Other devices, on the next poll, see the tombstone and remove the local copy.
  Real `DELETE` is only used when the user wants to scrub server storage.

## Sync algorithm (client-side, also informative)

The client persists the last map ETags and decoded map bodies. The legacy full
reconciler retains `lastSyncedAt` for compatibility, but runs only for bootstrap, a
book-map mismatch, or a changed non-map metadata ETag.

### Request budget

- Nothing changed: one `GET /v1/kv`; matching ETags end the sync. Listing all metadata also
  catches older per-book clients and changed chats/settings without fetching unchanged bodies.
- Any number of local bookmark changes: the list above plus one
  `PUT /v1/kv/sync/maps/bookmarks/{deviceId}.json` containing the device shard.
- Remote bookmark shard changed: the list plus one GET for that changed shard. Concurrent
  devices never overwrite each other because their physical keys differ.
- Book map changed: the small preflight returns immediately to the UI and launches the
  existing binary/metadata reconcile in the background.

### Outbound (writes)

- Page turn → after the local save, replace that BookID's durable outbox entry.
  At most five seconds later, merge every dirty book into one bookmark-shard PUT. The
  outbox removes only the exact mutation acknowledged, so a page turn during the PUT
  remains queued.
- New chat reply persisted → `PUT books/{syncId}/chat/{ts}-{nonce}` with that one
  entry. Never re-uploaded.
- Book import → upload the format-specific zip once (Mokuro `payload.*`, EPUB `epub.*`),
  then its manifest, then `PUT books/{syncId}/metadata`.
- Book shelf/folder move → next manual sync overwrites each local book metadata blob
  with the current `shelfName`/`shelfUpdatedAt`; the payload zip is not re-uploaded.
- Book delete → record a local tombstone outside the deleted book folder; the next
  manual sync overwrites `books/{syncId}/metadata` with `deletedAt` set, then clears
  the pending tombstone once the server write succeeds.

### Inbound (reads)

On app resume, reader open, manual sync, and every five seconds while the app is active:

1. List all KV metadata once and compare the returned ETags with the local cache.
2. GET only a map whose ETag changed. Merge bookmark entries by event timestamp, using
   revision and content hash only as deterministic tie-breakers, and apply newer remote
   positions to the active EPUB or manga reader.
3. PUT this installation's bookmark shard once if any number of local positions need publishing.
4. If the books map or any non-map ETag changed, run the existing per-book reconcile:
   - `chat/{ts}-{nonce}` → if the local chat log doesn't have that exact key, fetch
     and append. Order in-memory by `timestampSeconds`.
   - `metadata` with `deletedAt` set → if local copy exists, delete it locally and do
     not upload replacement book state over the tombstone. An open reader defers that
     deletion without acknowledging it, then retries after close. If no local copy exists,
     treat the tombstone as handled so it does not pin the incremental cursor.
   - `metadata` with `shelfName` present → apply the book's shelf/folder placement if
     `shelfUpdatedAt` is not older than that book's local shelf placement.
   - `payload.manifest` / `epub.manifest` → resolve the format-specific key family and
     download a missing book. For an existing book, a later server SHA replaces only the
     static payload while retaining bookmark, metadata, chat, statistics, and device audio.
     A legacy Android EPUB stored in `payload.*` remains readable when its manifest declares
     `epub`.
   - `sentences` → after its EPUB exists locally, validate the sync id, EPUB spine count,
     normalized sentence addresses and hashes, then atomically install
     `sentence_translations.json`.
5. Advance `lastSyncedAt` and publish the authoritative maps after the full reconcile succeeds.

### Upgrade and hash-cache safety

Pre-map clients can already have every book downloaded but no
`.payload.content.sha256.cache` sidecars. Their first map sync performs the old bidirectional
reconcile once and computes a cross-platform hash of sorted static paths and bytes. Identical
existing downloads therefore remain untouched; a real mismatch is not falsely marked current.
Downloads and imports write the sidecar immediately. Later fast syncs only read it.

Direct per-book bookmark keys from released clients are imported during bootstrap and any later
edit to one triggers the legacy reconciler. Live two-way coexistence with an old app is not
supported: all devices that should exchange new page turns must run the map-capable release. This
is what keeps any number of bookmark changes to one shard PUT instead of N compatibility PUTs.

Conflicts are resolved client-side:
- Bookmark shards merge per BookID by event timestamp, with revision and content hash as
  deterministic tie-breakers. No whole-map writer can erase another device's position.
- Chat keys never conflict (unique nonce).
- Payload conflicts are essentially "re-imported the same book on two devices at the
  same time" — even rarer; the second writer wins, both copies are effectively
  identical.

If a real conflict-resolution story is needed later, it goes in client code and
takes the form of `If-Match: "<etag>"` on PUT (server returns `412 Precondition Failed`
if the etag doesn't match). The server should plumb this through but the v2 client
doesn't have to use it.

## Server implementation sketch

The whole server is ~80 lines of Flask + SQLite. Recommended schema:

```sql
CREATE TABLE kv (
  key            TEXT PRIMARY KEY,
  body           BLOB NOT NULL,
  content_type   TEXT NOT NULL,
  last_modified  TEXT NOT NULL,    -- RFC 3339 UTC, indexed
  etag           TEXT NOT NULL,    -- sha256:<hex>
  size           INTEGER NOT NULL
);
CREATE INDEX kv_last_modified ON kv(last_modified);
```

Pseudocode for the four routes:

```python
@app.before_request
def _auth():
    if request.headers.get("Authorization") != f"Bearer {TOKEN}":
        abort(401, "invalid bearer token")

KEY_RE = re.compile(r"^([A-Za-z0-9_\-.]{1,64})(/[A-Za-z0-9_\-.]{1,64})*$")
MAX_BODY = 200 * 1024 * 1024  # 200 MB

@app.put("/v1/kv/<path:key>")
def put_kv(key):
    if not KEY_RE.fullmatch(key) or len(key) > 512:
        abort(400, "invalid key")
    body = request.get_data()
    if len(body) > MAX_BODY:
        abort(413, f"body exceeds {MAX_BODY} bytes")
    content_type = request.content_type or "application/octet-stream"
    etag = "sha256:" + hashlib.sha256(body).hexdigest()
    last_modified = datetime.utcnow().isoformat(timespec="milliseconds") + "Z"
    db.execute(
        "INSERT OR REPLACE INTO kv (key, body, content_type, last_modified, etag, size) "
        "VALUES (?, ?, ?, ?, ?, ?)",
        (key, body, content_type, last_modified, etag, len(body)),
    )
    db.commit()
    return jsonify(key=key, lastModified=last_modified, etag=etag, size=len(body), contentType=content_type)

@app.get("/v1/kv/<path:key>")
def get_kv(key):
    row = db.execute("SELECT body, content_type, last_modified, etag FROM kv WHERE key = ?", (key,)).fetchone()
    if row is None:
        abort(404)
    body, content_type, last_modified, etag = row
    return Response(body, headers={
        "Content-Type": content_type,
        "Last-Modified": last_modified,
        "ETag": etag,
    })

@app.delete("/v1/kv/<path:key>")
def delete_kv(key):
    db.execute("DELETE FROM kv WHERE key = ?", (key,))
    db.commit()
    return "", 204

@app.get("/v1/kv")
def list_kv():
    prefix = request.args.get("prefix", "")
    since = request.args.get("since")
    cursor = request.args.get("cursor")  # opaque: just the last key returned, ordered
    limit = min(int(request.args.get("limit", 500)), 2000)
    sql = "SELECT key, last_modified, etag, size, content_type FROM kv WHERE key LIKE ? || '%'"
    args = [prefix]
    if since:
        sql += " AND last_modified > ?"; args.append(since)
    if cursor:
        sql += " AND key > ?"; args.append(cursor)
    sql += " ORDER BY key LIMIT ?"; args.append(limit + 1)
    rows = db.execute(sql, args).fetchall()
    truncated = len(rows) > limit
    rows = rows[:limit]
    keys = [
        dict(key=r[0], lastModified=r[1], etag=r[2], size=r[3], contentType=r[4])
        for r in rows
    ]
    next_cursor = keys[-1]["key"] if truncated and keys else None
    return jsonify(keys=keys, truncated=truncated, nextCursor=next_cursor)
```

Notes for the server implementer:

- **Persistence:** SQLite's `BLOB` column is fine up to ~1 GB total; if the user has more
  than ~50 manga (~5 GB of payloads), switch the body storage to filesystem files
  keyed by etag and keep only metadata in SQLite. Same API, different backing.
- **Streaming / multipart:** `payload.zip` can be tens to hundreds of MB. For single
  PUT and GET, don't `request.get_data()` the whole thing into RAM for the big ones.
  Multipart uploads split the body into <=64 MiB requests; complete should concatenate
  parts without materializing the entire finished blob in memory.
- **Concurrency:** SQLite's serialized mode is fine for one user; switch to WAL mode
  (`PRAGMA journal_mode=WAL`) so reads don't block writes during a big payload PUT.
- **Don't validate hoshi schemas.** If the client uploads a malformed bookmark, that
  is the client's bug, not the server's. The server is content-blind.
- **CORS:** not needed if this is Android-only. Skip the headers.
- **Mount path:** the existing v1 server uses `/api/book_sync/v1/books/...`. Suggested
  mount for v2: `/api/book_sync/v1/kv/...` so the two protocols can cohabit during
  migration. The repo CLAUDE.md should say how to register a new blueprint.

## Migration

The Android client is the only client. v2 client ships, on first launch:

1. Reads the existing local `bookmark.json` / `ai_chat_log.json` files for each book.
2. For each book, writes `metadata`, `bookmark`, and one `chat/...` blob per existing
   entry to the new v2 server.
3. v1 endpoints (`/v1/books/...`) can stay up indefinitely as read-only fallback or
   can just be deleted once everything's migrated. The client doesn't talk to them
   anymore.

No data migration on the server is needed — the v2 KV table is fresh.

## Open questions for the server side

1. **Authentication:** is the bearer token good enough, or do we want per-device
   credentials? (Recommend: one global token until there's a reason for more.)
2. **Storage backend:** SQLite blob column for v2.0, switch to filesystem when total
   storage > some threshold? Or just always filesystem?
3. **Backup:** does the server take periodic snapshots of the kv table? Or is that
   the user's problem? (Recommend: nightly `sqlite3 .backup`, kept for 7 days.)
4. **TLS termination:** we already assume HTTPS in front (nginx / Caddy / Cloudflare).
   Worth documenting so the Flask process never listens on a public port directly.
5. **Per-PUT body cap:** 200 MB feels right for manga zips. Confirm or pick a number.

That's the brief — if any of this looks wrong from the server side, push back before
implementing and we'll revise the doc rather than the wire format after the fact.
