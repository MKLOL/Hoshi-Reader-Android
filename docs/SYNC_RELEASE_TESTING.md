# Sync release testing

Both supported publication paths (`release.py` and `.github/workflows/release-apk.yml`)
require the same command:

```bash
python3 tools/verify_release.py
```

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
5. Before publication, the candidate must update the previous APK's package/signature,
   increase its version code, and match its tag. Upload uses a private verified copy.
   The draft's downloaded APK must match its SHA-256 before it becomes public/latest.
   Existing releases/assets are never overwritten; competing publishers fail safely.

The local release script also rejects source/HEAD changes during verification or build.
GitHub runs on the checked-out release tag. Reports and emulator logs are kept under
`build/reports/release-gate/`; workflows upload them even on failure. A successful full
run writes `summary.json`. A failed run removes any previous success summary.

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
| Interrupted or partial uploads damage books | Server contract and Android failure/race suites cover multipart ordering/retry/cancel, failures, preservation of old payloads, manifests, and later convergence. |
| Filesystem optimization hides a change | Cache tests cover replacement with equal size/time, deletion/restoration, corruption, concurrent changes, bounded eviction, and real Android file identity. |
| Other important data paths regress | Full JVM coverage includes backup traversal/CRC/cancellation, stale cleanup previews, atomic sidecars, import routing, Anki validation/media, and reader state. Android also checks EPUB import, iOS ZIP64 payloads, Unicode lookup, settings typing, and bookshelf sync success/error UI. |

Request counts are assertions; wall-clock benchmark numbers are diagnostic only. Races
use explicit rendezvous/deferred responses instead of timing-dependent sleeps.

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
