# Hoshi Sync v2 — KVS-style protocol (design doc)

**Status:** proposed; **author:** Dragos + Claude (hoshi-android side).
**Server target:** `/Users/dragosristache/game-collection/` — meant for the other Claude
working in that repo. This doc is the brief; the server side just needs to implement the
endpoints below, the client (this repo) handles all schema and reconciliation.

This replaces the v1 blob-per-book protocol in `docs/HTTP_SYNC.md`. The motivation: v1
sends the **whole** record (bookmark + entire ChatGPT history + metadata) on every push,
so a 250-byte page-turn update re-uploads a 25 KB chat history. We want **incremental**:
one page turn = one small write, one new chat message = one small write, and book
payloads (.epub / mokuro folder) round-trip too so a new device gets the actual files,
not just the position.

The fix: the server stops knowing about books at all. It's a generic key/value blob
store. All schema is client-side.

## Goals

- **Server is dumb storage.** It learns nothing about bookmarks, chat, manga, EPUB.
  New client features need zero server changes.
- **Incremental writes.** Page turns cost ~250 B. New chat entries cost ~500 B–2 KB.
  Nothing is ever re-uploaded just because something else changed.
- **Book payloads sync too.** Importing a book on phone A and reading it on phone B is
  one zip blob + a manifest entry; no per-file API.
- **Append-only where we can get away with it.** Chat entries are write-once at unique
  keys; merging is set-union with no logic.
- **Single user.** One bearer token, one namespace. Multi-user is out of scope for v2.

## Non-goals

- Real-time push / websockets. Polling on app resume + the existing every-N-page-turn
  hook is enough.
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
| `books/{syncId}/metadata` | `application/json` | `{title, contentType, importedAt, deletedAt?}` | overwrite | ~200 B |
| `books/{syncId}/bookmark` | `application/json` | `{chapterIndex, progress, characterCount, lastModified}` | overwrite (every page turn batch) | ~250 B |
| `books/{syncId}/chat/{ts}-{nonce}` | `application/json` | `{bubbleText, prompt, model, response, timestampSeconds}` | **write-once** | ~500 B – 2 KB |
| `books/{syncId}/payload.zip` | `application/zip` | zip of the original book directory | overwrite (rare; effectively immutable) | 10 MB – 200 MB |
| `books/{syncId}/payload.manifest` | `application/json` | `{sha256, sizeBytes, originalName, format: "mokuro" \| "epub"}` | overwrite | ~150 B |

- `syncId` = `deriveSyncId(title)` (the lowercase-alphanumeric sanitizer the v1 client
  already uses, see `HttpSync.kt:131`). Two devices with the same titled book converge.
- `{ts}` in chat keys is the entry's RFC 3339 UTC timestamp; `{nonce}` is a short
  random suffix so two chats produced at the same second on different devices don't
  collide.
- A book that's been deleted on one device writes `deletedAt: <ts>` into its `metadata`
  blob. Other devices, on the next poll, see the tombstone and remove the local copy.
  Real `DELETE` is only used when the user wants to scrub server storage.

## Sync algorithm (client-side, also informative)

The client tracks one local cursor: `lastSyncedAt = highest lastModified it has
applied`. Two flows:

### Outbound (writes)

- Page turn → after the existing debounce, `PUT books/{syncId}/bookmark` with the
  current bookmark JSON. Coalesced so a burst of turns ends in one PUT.
- New chat reply persisted → `PUT books/{syncId}/chat/{ts}-{nonce}` with that one
  entry. Never re-uploaded.
- Book import → `PUT books/{syncId}/payload.zip` once, then
  `PUT books/{syncId}/payload.manifest`, then `PUT books/{syncId}/metadata`.
- Book delete → overwrite `books/{syncId}/metadata` with `deletedAt` set.

### Inbound (reads)

On app resume / periodic timer:

1. `GET /v1/kv?prefix=books/&since={lastSyncedAt}` (paginate via `cursor`).
2. For each returned key:
   - `bookmark` → if newer than local `lastModified`, fetch and overwrite local
     bookmark.
   - `chat/{ts}-{nonce}` → if the local chat log doesn't have that exact key, fetch
     and append. Order in-memory by `timestampSeconds`.
   - `metadata` with `deletedAt` set → if local copy exists and we haven't already
     processed this tombstone, delete locally.
   - `payload.manifest` → if the manifest's `sha256` differs from local (or there is
     no local book), schedule a payload download.
   - `payload.zip` → only fetched when the manifest says we need it.
3. Advance `lastSyncedAt` to the max `lastModified` seen.

Conflicts are last-write-wins per key, which is the right granularity because:
- A bookmark conflict is "one user, two phones, both reading the same book at the
  same time" — extremely rare; just take the newer one.
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
- **Streaming:** `payload.zip` can be tens to hundreds of MB. Use chunked transfer
  encoding on both PUT and GET — don't `request.get_data()` the whole thing into RAM
  for the big ones. Flask + Werkzeug supports `request.stream`.
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
