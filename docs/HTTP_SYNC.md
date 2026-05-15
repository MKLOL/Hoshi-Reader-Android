# Hoshi HTTP Sync (Android-only)

A small REST-over-HTTPS sync that lives alongside the existing Google Drive sync.
Unlike Drive sync — which mirrors iOS's `progress_*.json` schema and excludes
manga — this is shaped for **both EPUB and mokuro manga** and stores the per-book
reading state on a server you control.

This is the **v1 spec** and ships **state-only**: bookmarks + per-manga ChatGPT
history. The wire format is designed so the manga / EPUB payload can be added as
a `payload-files` extension later without breaking v1 clients.

## Configuration on the device

Reader → ⚙ Settings → Advanced → **HTTP Sync**:

| Field | Example |
|---|---|
| Base URL | `https://sync.example.com/hoshi` |
| Bearer token | `<long random string>` |
| Enabled | toggle |

Plus a manual **Sync now** button. Auto-sync on bookmark save is planned for v2.

All requests:
- Carry `Authorization: Bearer <token>`
- Accept and return `application/json; charset=utf-8`
- Use `Date` headers in **RFC 3339 UTC** (`2026-05-15T12:34:56.789Z`)

## Endpoints

### `GET /v1/books`
List every book the server knows about for this token.

```json
{
  "books": [
    { "syncId": "001_jp_yotsubato", "clientModified": "2026-05-15T01:18:00Z", "serverModified": "2026-05-15T01:18:01Z" },
    { "syncId": "kafka_on_the_shore", "clientModified": "2026-05-14T19:02:11Z", "serverModified": "2026-05-14T19:02:12Z" }
  ]
}
```

`syncId` = `<sanitized title>`. The sanitizer is:
lowercase → replace any non-ASCII-alphanumeric character with `_` → collapse runs
of `_` → strip leading/trailing `_`. Both devices that imported the same titled
book will compute the same `syncId` and therefore converge.

### `GET /v1/books/{syncId}`
The full sync record for one book. **404** if unknown.

```json
{
  "syncId": "001_jp_yotsubato",
  "title": "001 [JP] Yotsubato",
  "contentType": "mokuro",
  "bookmark": {
    "chapterIndex": 17,
    "progress": 0.0,
    "characterCount": 18,
    "lastModified": "2026-05-15T01:18:00Z"
  },
  "aiChatLog": {
    "entries": [
      {
        "bubbleText": "もうすぐだ",
        "prompt": "You are a helpful Japanese reading tutor...",
        "model": "gpt-5.5",
        "response": "Almost there! ...",
        "timestampSeconds": 800000000.0
      }
    ]
  },
  "clientModified": "2026-05-15T01:18:00Z",
  "serverModified": "2026-05-15T01:18:01Z"
}
```

Field rules:

- `contentType` is `"mokuro"` or `"epub"`.
- `bookmark` mirrors the on-device `bookmark.json` shape. For manga,
  `chapterIndex` is the page index; for EPUB it is the chapter index, with
  `progress` carrying the in-chapter fraction.
- `aiChatLog` is only present for manga (matches `ai_chat_log.json`). For EPUB
  the field is omitted (or `null`).
- `clientModified` is the latest local sidecar `lastModified` for that book.
  This is the **last-write-wins** key — the client compares its local
  `clientModified` with the server's, newer wins.
- `serverModified` is set by the server on every successful `PUT` and returned
  on `GET`. The client only uses it to surface a "synced at" timestamp.

### `PUT /v1/books/{syncId}`
Upsert one book's record.

Request body has every field of the `GET` response **except** `serverModified`.
The server stamps `serverModified` on successful write and returns it:

```json
{ "serverModified": "2026-05-15T01:18:01Z" }
```

**Conflict policy:** the server overwrites unconditionally. Client-side
last-write-wins by comparing `clientModified` decides whether the client PUTs
or GETs first; the server is dumb storage.

### `DELETE /v1/books/{syncId}`
Optional. Used when the user deletes a book locally and wants to scrub the
server too. Returns `204`. **404** is a no-op success from the client's view.

## Payload sync (v2, NOT in v1)

The same `syncId` namespace will host file-level routes:

```
GET  /v1/books/{syncId}/payload          → list of files: [{ "path", "size", "sha256" }, ...]
GET  /v1/books/{syncId}/payload/{path}   → binary file
PUT  /v1/books/{syncId}/payload/{path}   → binary file
```

This is forward-compatible: v1 servers can return `404` from these routes; v1
clients never hit them.

## Auth & errors

- Missing or wrong bearer token → `401`. The Android client surfaces a
  "Check your token" toast and stops.
- Server-side persistence error → `5xx` with `{ "error": "<message>" }`. The
  client treats it as transient and lets the user retry.
- Bad JSON / missing fields → `400` with `{ "error": "<message>" }`.

## Reference server (Python / Flask, ~40 lines)

Save as `sync_server.py`, set `HOSHI_TOKEN` in the env, run with
`flask --app sync_server run --host 0.0.0.0 --port 8080`. Persists every book
record as a JSON file under `./data/`.

```python
import json, os, time, re
from pathlib import Path
from flask import Flask, request, jsonify, abort

DATA = Path(os.environ.get("HOSHI_DATA", "./data"))
TOKEN = os.environ["HOSHI_TOKEN"]
DATA.mkdir(parents=True, exist_ok=True)

app = Flask(__name__)
SAFE = re.compile(r"^[a-z0-9_]+$")

def authed():
    h = request.headers.get("Authorization", "")
    return h == f"Bearer {TOKEN}"

def now():
    return time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())

def book_path(sync_id):
    if not SAFE.match(sync_id):
        abort(400, "invalid syncId")
    return DATA / f"{sync_id}.json"

@app.before_request
def _auth():
    if not authed():
        abort(401, "missing or invalid bearer token")

@app.get("/v1/books")
def list_books():
    out = []
    for p in DATA.glob("*.json"):
        record = json.loads(p.read_text())
        out.append({
            "syncId": p.stem,
            "clientModified": record.get("clientModified"),
            "serverModified": record.get("serverModified"),
        })
    return jsonify(books=out)

@app.get("/v1/books/<sync_id>")
def get_book(sync_id):
    p = book_path(sync_id)
    if not p.exists():
        abort(404)
    return p.read_text(), 200, {"Content-Type": "application/json"}

@app.put("/v1/books/<sync_id>")
def put_book(sync_id):
    p = book_path(sync_id)
    record = request.get_json(force=True)
    record["serverModified"] = now()
    record["syncId"] = sync_id  # defensive: ignore body syncId mismatch
    p.write_text(json.dumps(record))
    return jsonify(serverModified=record["serverModified"])

@app.delete("/v1/books/<sync_id>")
def delete_book(sync_id):
    p = book_path(sync_id)
    if p.exists():
        p.unlink()
    return "", 204
```

Node / Go / nginx-with-WebDAV equivalents are straightforward — any HTTP stack
that can read a bearer header and write a file is enough.

## Versioning

The `/v1/` prefix is part of the contract. Future incompatible changes will
ship under `/v2/`; the client picks the highest version it knows the server
supports, falling back to `/v1/` if `/v2/books` returns `404` or `405`.
