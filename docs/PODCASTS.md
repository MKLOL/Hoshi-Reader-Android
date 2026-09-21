# NHK listening lessons

Podcasts appears after validating the existing HTTP book-sync token. No separate login
or OpenAI/Google key is needed in Android. A previously validated, unchanged account
can use saved listings and downloads offline. A rejected or removed token hides the
section and stops playback/downloads. Changing server/token isolates its local files.
Validation is checked on startup and every minute while the app process is alive.

Browse recent NHK news and filter by **original** duration (≤10 min, 10–20 min, >20 min).
Prepare queues a shared server lesson; progress continues even if Android closes.
When ready, Download saves it in app-private storage. Play uses Media3, including seek,
15-second skips, playback speed, background audio and lock-screen controls. Completed
or failed playback can be restarted with Play. Playback position is saved periodically.

Lesson recipe: Japanese sentence → English → vocabulary (Japanese → English meaning
→ Japanese replay, short pause) → original Japanese replay → loud beep → one-second
pause. Male Kokoro voices; repeated vocabulary is retained.

The new feature has no corresponding iOS podcast screen; the user's requested workflow
is the interaction reference. Media3 and WorkManager use Android's recommended service
and foreground-worker patterns rather than copying an iOS implementation.

## Implementation boundaries

- `PodcastRepository`: process-owned validated account and download scheduling; credentials
  come from the existing settings repository. It stores only an account fingerprint as
  the successful validation marker, never another copy of the bearer token. Once a new
  server/token pair validates, every other account's lessons and catalogue are removed; a
  rejected or edited-but-unvalidated token keeps its files, since a rejection can be transient.
- `PodcastApi`: typed server responses, HTTPS (debug loopback exception), no redirects,
  bounded JSON, cancellable requests. Tokens are headers and never URL parameters.
- `PodcastViewModel`: immutable screen state, account-scoped observers, polling only while
  the Podcasts screen is started. Settings changes cancel the previous session observers.
- WorkManager handles constrained, foreground downloads and bounded retries. Files publish
  atomically after a successful bounded transfer; stopped transfers leave no playable partial file.
- `PodcastPlaybackService`: MediaSessionService owns the player. It only resolves validated
  account/episode IDs to its own downloaded files, ignoring caller-provided media URLs.
- `PodcastFiles`: private MP3 and atomic catalogue storage, excluded from OS backup.

API and deployment: sibling `game-collection/docs/api/book_sync.md` and
`game-collection/docs/podcasts.md`. The production server needs its updated web dependency
installation and separate podcast worker. The emulator checks below used an isolated fixture
server; they do not imply production deployment or a new paid generation run.

## Verification

Run `./gradlew test assembleDebug lint` with the Android SDK/NDK configured. Focused tests
cover original-duration boundaries, navigation gating, account IDs, JSON contract, auth
headers, redirect rejection, auth cancellation and screen/account observer cancellation.
The original integration was reviewed in-session without retained artifacts. On 2026-09-21
three independent Android reviews and three server reviews of the merged branch were run;
their should-fix findings are addressed in the follow-up commits, and the remaining known
limits are listed below.

### What the screen says when something fails

- A preparation failure shows the server's one-sentence reason (which stage failed and why:
  a missing dependency, no OpenAI key, the NHK download, an OpenAI auth/rate-limit/outage, or
  ffmpeg) and how many attempts remain before an administrator has to reset the episode; the
  Prepare button disappears once they are used up.
- When the server's worker is down or recorded start-up problems (for example "ffmpeg is not
  on PATH"), the header says so, with the worker's own words and when it last reported in.
- A request error shows the server's `error` sentence when it sent one, otherwise the HTTP
  status or the failure type; a failed download shows its reason (HTTP status, content type,
  size, incomplete transfer, account change); a playback error shows Media3's error code.

### Known limits

- Playback speed is not remembered between sessions.
- The lesson recipe (sentence, translation, vocabulary order and voices) is defined by the
  server; the app only requests preparation.
- Downloads are only removed when another server/token pair validates; there is no
  per-episode delete or size cap yet.

### Completed checks (2026-09-21)

- `./gradlew test assembleDebug lint`: passed. 1,579 JVM tests, zero failures/errors,
  six skipped. Lint: zero errors; warnings remain.
- Updated the `hoshi_test` emulator with `adb install -r`; preserved its books,
  dictionaries and other existing app data. Used an isolated Flask fixture server
  and the previously generated real preview MP3, without new paid API calls.
- Confirmed Podcasts hidden without a token, visible after valid validation, and hidden
  again after server-side token revocation.
- Confirmed original-duration filtering (9:57 included, 25:00 excluded by ≤10 min).
- Confirmed Prepare → queued → Download → Play, completed download retained across restart.
- Confirmed active MediaSession playback, background continuation, return to Podcasts,
  15-second seeking (position advanced to 15,000 ms), time bar and speed settings.
- Confirmed cached listings and downloaded playback after a cold app restart with the
  fixture server unavailable. Temporary fixture credentials/data were removed afterward.
- Emulator screenshots exposed and verified fixes for status-bar overlap and native
  Media3 compact mode hiding controls. The screen scrolls on smaller viewports.
