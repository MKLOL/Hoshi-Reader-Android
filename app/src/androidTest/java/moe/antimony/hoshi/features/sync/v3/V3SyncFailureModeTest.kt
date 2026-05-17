package moe.antimony.hoshi.features.sync.v3

/**
 * Failure-mode tests for [V3SyncEngine] against a controllably-flaky
 * [StubKvServer]. Uses [StubKvServer.setBehavior] to inject 5xx / dropped
 * connections / delays.
 *
 * Required coverage:
 *  - GET 503 on one book's metadata → that book's sync surfaces an error,
 *    other books complete.
 *  - PUT 503 on one chat entry → that chat is unpushed and reported, sync continues.
 *  - LIST 503 mid-pagination → fatal: engine throws, no partial state advancement.
 *  - 401 on LIST → fatal: engine throws, user-facing error message mentions auth.
 *  - Malformed JSON body returned by server for a metadata key → per-book error,
 *    other books fine.
 *  - Dropped connection mid-payload download → retry on next sync succeeds.
 *  - Slow server (1-second delay per request) — sync completes, no spurious timeouts.
 */
class V3SyncFailureModeTest {
    // Implementation agent: populate this file with @Test methods.
}
