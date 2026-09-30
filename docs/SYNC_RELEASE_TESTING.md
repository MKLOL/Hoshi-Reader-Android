# Sync release testing

Releases are published by `./release.py`. It runs the gate below before changing any
version file:

```bash
python3 tools/verify_release.py
```

`.github/workflows/release-apk.yml` is manual (`workflow_dispatch`) only. It is meant for
keystore-signed releases and runs the same gate and release APK smoke test. Tag pushes do
not start it, so a local release never races a second publisher.

Use Python 3.11+ and the normal JDK/SDK/NDK/Rust build environment from `bootstrap.sh`.
Install `emulator` and `system-images;android-36;google_apis;arm64-v8a` on Apple Silicon,
or `system-images;android-36;google_apis;x86_64` on x86 hosts. The gate creates an AVD
in its own temporary directory, checks its identity before installation, and removes
it afterward. It never selects an existing connected phone, tablet, or emulator.
`HOSHI_TEST_SYSTEM_IMAGE` can select another installed, compatible system image.

## What blocks a release

1. Python tests verify the KV simulator contract and the release gate/publisher itself.
2. `./gradlew test lint assembleDebug assembleDebugAndroidTest` checks the whole JVM
   suite and builds the actual app and instrumentation APKs. Python simulator changes
   are Gradle test inputs, so changing the simulated protocol invalidates cached results.
3. The gate removes old JVM reports before running Gradle, then rejects missing required
   classes, failures, inconsistent reports, and unexpected skips. Valid Gradle cache
   results may be reused for unchanged inputs.
4. Android runs the real HTTP client/engines, ZIP implementation, filesystem checks,
   and selected Compose/WebView interactions on the disposable emulator. Every selected
   class must run; skipped tests, crashes, incomplete output, and zero-test success
   messages are failures, even when `adb` itself exits successfully.
5. The gate runs the debug build. After the version bump, `release.py` builds the minified,
   resource-shrunk, arm64-only release APK and snapshots it. It runs
   `tools/verify_release.py --smoke-apk <snapshot>`, which boots another disposable AVD.
   That check installs the exact bytes that will be published, launches the launcher
   activity, waits for it to hold window focus, sends key presses, and watches it for
   20 seconds. It covers startup and first-screen breakage (R8 or resource shrinking on
   the launch path); code the launch never runs is not exercised.
   - Any of these fails the release: a crash or native crash (including secondary `:name`
     processes), an ANR, the process restarting or dying, a failed launch, or the activity
     never taking focus or losing it to an error dialog after input. A launch that only
     timed out waiting for the first frame passes if the activity is focused within 60 s.
   - An emulator that cannot execute the APK's ABIs fails loudly instead of skipping. Apple
     Silicon hosts use an arm64 image. An x86_64 image must report `arm64-v8a` (ARM
     translation) in `ro.product.cpu.abilist`.
   - Logs are the `release-smoke*` files in the reports directory.
   - Nothing is committed until this passes and its recorded SHA-256 matches the candidate.
6. Before publication, the candidate must meet three conditions:
   - it can update the previous APK's package and signature;
   - its version code is higher;
   - it matches its tag.

   The same private snapshot is used for the smoke test, commit, push, and upload. The
   draft's downloaded APK must match its SHA-256 before it becomes public/latest. Published
   releases and assets are never overwritten. Competing publishers fail safely.

The local release script also rejects source/HEAD changes during verification or build.
The manual GitHub workflow runs on the checked-out release tag. Reports and emulator logs are kept under
`build/reports/release-gate/`; workflows upload them even on failure. A successful full
run writes `summary.json`. A failed run removes any previous success summary.

## Recovering an interrupted release

Where the failure happens decides the fix. `release.py` prints the matching instruction.

- **Before the release commit** (gate, build, smoke test, upgrade check, commit hook).
  Only `app/build.gradle.kts` and `docs/CHANGELOG.md` changed. Undo them with
  `git checkout HEAD -- app/build.gradle.kts docs/CHANGELOG.md` and rerun `./release.py`.
  - If someone else edited those two files during the build, the script says so and does
    not suggest that command, which would discard their edits.
  - A draft that already exists for the new tag stops the run before the commit; delete it
    with the printed `gh release delete` command.
- **After the release commit** (tagging, push, upload, checksum, publication). Do not undo
  the commit. Fix the cause (network, `gh auth`, a stuck upload) and run
  `./release.py --resume` on the release branch.
  - A rejected branch push means `origin` gained commits during the gate. Merge them with
    `git pull --no-rebase origin <branch>` (never rebase: that rewrites the release commit),
    then resume. Resume accepts any HEAD on the release branch that contains the release
    commit, pushes the branch, and keeps the tag on the release commit.
  - The verified snapshot, notes, and a receipt stay in `build/release-candidates/<tag>/`.
    The receipt records the SHA-256, the release commit and branch, and that the smoke
    test passed. `./release.py` refuses to start another release while one is pending.
  - Resume refuses to continue in any of these cases:
    - HEAD is detached, on another branch, or no longer contains the release commit;
    - the tag points elsewhere;
    - the snapshot bytes changed;
    - the smoke test never passed.
  - If the tag is missing, resume recreates it on the recorded commit. It then pushes
    missing refs (already-pushed refs are no-ops).
  - It deletes only a single unpublished draft that this tooling created. Drafts it creates
    carry a hidden `<!-- sui-release-tool -->` marker; the title must be `Sui Manga Reader
    <tag>` and the assets only the APK and `LICENSE`. It re-reads the draft by id and deletes
    that id, so a release published meanwhile is never removed. The tag stays.
  - It then uploads the same snapshot (checked against the receipt's SHA-256), checks its
    downloaded SHA-256, and publishes.
  - If publication already happened but the response was lost, a published release whose
    APK matches the receipt counts as done. A published release with other or missing
    bytes is never changed. Several drafts or an unmarked, renamed, or extra-asset draft stop
    the run for manual review.
  - The directory is removed after a successful publish. If it is gone (for example,
    `build/` was deleted) resume cannot finish the tag, because only verified bytes are
    published; publish that tag with the manual workflow or cut the next patch release.
- `tools/release_artifacts.py --resume` applies the same draft rule when publishing by hand.

## Regression coverage

| Failure | Required evidence |
| --- | --- |
| Tablet shows zero totals, streak, or trends despite installed books and acknowledged maps | `SyncReleaseRegressionTest` removes the local history, with and without exchange markers; production UI aggregates must recover through actual HTTP without book transfers. |
| Failed history GET is incorrectly acknowledged | Inject HTTP 500, then retry the same remote ETag without another phone write; history and visible change notifications must recover. |
| Reader push is missed or upload fails | Reading remains durable and the next manual pass uploads it; a second install receives it. |
| Legacy history doubles or changes attribution | Real installation identities and a live statistics observer verify unattributed rows, optimistic streaks, and totals before/after restart. |
| Two devices study concurrently | Deterministic overlapping HTTP responses preserve both devices' contributions across V2/V3 and mixed engine combinations. |
| Idle sync work grows with an already installed library | `SyncReleasePerformanceTest` checks one versus 40 mixed EPUB/manga books with daily/hourly history: repeated polls and restart each require exactly one delta LIST. The metadata unit suite also covers 10,001 keys. |
| One history edit triggers book downloads | Exactly two history GETs for a changed manga's time/OCR records, at most seven total requests, and no payload transfer. |
| Manual sync joins an outdated background pass | Freeze a real response, publish newer phone history, cancel the original waiter, and verify the manual tap still performs a fresh pass. |
| Screen sleep stops a book transfer | `HttpSyncSleepTransferTest` turns the disposable emulator screen off during throttled real book downloads through both UIDT and foreground WorkManager, verifying continued bytes, completion, and identical installed content. |
| Transfer speed or ETA misleads after resume, stalls, or file changes | `HttpSyncTransferProgressTest` uses a controlled monotonic clock for windowed speed, resume baselines, resets, unknown sizes, large files, and cancellation; `HttpSyncTransferProgressInstrumentedTest` checks both engines against throttled HTTP downloads and verifies localized UI/notification text. |
| An interrupted download starts again from zero or appends the wrong file | `HttpSyncDownloadTest`, `RealServerResumeIntegrationTest`, and `HttpSyncDownloadInstrumentedTest` check durable partial archives, Range/If-Range, ignored ranges, changed validators/manifests, malformed responses, cancellation, and final SHA verification. Android requires exactly two GETs after a truncated response, with no prefix bytes sent twice. |
| Shelf badges misreport offline translations | Availability tests reject empty, corrupt, unsupported, and wrong-book sidecars, exercise cache updates/deletion, and Compose checks the badge accessibility state. |
| Interrupted or partial uploads damage books | Server contract and Android failure/race suites cover multipart ordering/retry/cancel, failures, preservation of old payloads, manifests, and later convergence. |
| Filesystem optimization hides a change | Cache tests cover replacement with equal size/time, deletion/restoration, corruption, concurrent changes, bounded eviction, and real Android file identity. |
| Minification or resource shrinking breaks the shipped APK's launch path | The release APK smoke test installs the exact published bytes on a fresh AVD, requires the activity to hold focus before and after key presses, and fails on crash, ANR, native crash (including secondary processes), process death/restart, failed launch, or an emulator that cannot execute the APK's ABI. Code the launch never reaches is not covered. |
| An interrupted publish leaves a stuck draft, pushed tag, or rejected branch push | `tools/release_tests` cover undo hints before the commit, snapshot retention after it, the pending-release refusal, `--resume` guards (branch, ancestry, tag, bytes, smoke receipt), resume after merging a rejected push, idempotent resume after a lost publish response, and marker-only draft deletion by id that never touches published or foreign releases. |
| Other important data paths regress | Full JVM coverage includes backup traversal/CRC/cancellation, stale cleanup previews, atomic sidecars, import routing, Anki validation/media, and reader state. Android also checks EPUB import, iOS ZIP64 payloads, Unicode lookup, settings typing, and bookshelf sync success/error UI. |

Request counts are assertions; wall-clock benchmark numbers are diagnostic only. Races
use explicit rendezvous/deferred responses instead of timing-dependent sleeps.

## Transfer ownership

Manual sync uses [user-initiated data transfer jobs](https://developer.android.com/develop/background-work/background-tasks/uidt) on Android 14+, with a foreground WorkManager worker on older versions. Android manages wakefulness. The application shares one sync container across activities and jobs; system cancellation stops the shared transfer and keeps its partial archive. Partial files live outside published books, keyed by account, remote key, and manifest, and are removed after successful extraction. A server must honor byte ranges to avoid transferring the saved prefix; servers that return 200 are handled safely by replacing the partial file.

## External tests and limits

The mandatory integration tests launch an isolated Python KV server and use the production
HTTP client and orchestration. Android also runs an in-process HTTP server whose contract
tests are included. No test credentials or production service are required. The gate forces
`HOSHI_ENABLE_LIVE_SYNC_TESTS=0` and removes external KV credentials from its environment;
the six optional deployment smoke tests are the only allowed skips. A private credentials
file alone cannot enable them. To test an explicitly selected deployment separately, set
`HOSHI_ENABLE_LIVE_SYNC_TESTS=1` and run `HttpSyncLiveServerSmokeTest` with its documented
credentials. That test writes remote data; it is not part of offline release verification.

These gates cover the documented protocol and the reproduced client failures. They cannot
prove the availability or behavior of an independently changed production deployment, every
OEM/Android version, or external Anki services. The iOS simulator regression suite remains a
separate cross-platform check when changing the shared wire contract.

Known architectural follow-up: whole-library backup replacement needs coordination with
active sync/import/reader writers and invalidation of stale operations. Per-book locks alone
cannot cover newly imported roots or writers holding paths to the replaced library. Current
backup tests cover corruption, cancellation, and rollback, not that cross-feature barrier.
