package moe.antimony.hoshi.features.sync.v3

/**
 * End-to-end engine tests using the existing `FakeKvTransport` from `HttpSyncTest`.
 *
 * Required coverage — implementation agent must add at least:
 *  - parity with v2's existing happy-path scenarios:
 *      * fresh-device cold start (Mokuro),
 *      * fresh-device cold start (EPUB),  -- v3 widens this
 *      * outbound push of a local-only book,
 *      * round-trip bookmark, chat, ai-settings,
 *  - tombstone application + push,
 *  - error reporting: per-book error doesn't abort the sync (cf. spec scenario 12),
 *  - idempotency: running syncOnce twice in a row leaves the server unchanged on the second run,
 *  - progress callback fires for each phase in the documented order.
 */
class V3SyncEngineTest {
    // Implementation agent: populate this file with @Test methods.
}
