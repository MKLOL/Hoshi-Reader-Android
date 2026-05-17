package moe.antimony.hoshi.features.sync.v3

/**
 * Two simulated devices against ONE shared `FakeKvTransport`. Scripted interleavings,
 * assert convergence.
 *
 * Required coverage — implementation agent must add scenarios from
 * `docs/SYNC_V3_SPEC.md` § Scripted multi-device integration scenarios:
 *  - bookmark LWW across A and B,
 *  - shelf reorganization,
 *  - tombstone + reimport,
 *  - chat-heavy book with overlapping + non-overlapping entries,
 *  - same-chat collision,
 *  - reader-hook push interleaved with full sync.
 *
 * For unit-test purposes "scripted" means: A.sync, B.sync, A.import, A.sync, B.sync, ...
 * with assertions after each step. Real Android-side instrumentation tests (under
 * `androidTest/`) run the same harness against a real HTTP stub server.
 */
class V3RaceTest {
    // Implementation agent: populate this file with @Test methods.
}
