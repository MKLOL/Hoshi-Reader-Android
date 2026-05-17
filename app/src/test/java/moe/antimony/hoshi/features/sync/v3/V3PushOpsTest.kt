package moe.antimony.hoshi.features.sync.v3

/**
 * Focused tests for the [V3PushOps] primitive set.
 *
 * Required coverage — implementation agent must add at least:
 *  - `pushBookmarkConditional` matrix: server-empty + push, server-newer + apply,
 *    local-newer + push, tie + no-op,
 *  - per-book lock is held for the full read-then-write sequence (verifiable by
 *    interleaving two concurrent calls),
 *  - `pushChat` is idempotent (re-push writes same bytes),
 *  - `pushPayload` skips when remote manifest exists,
 *  - `pushPayload` uploads when remote manifest absent (Mokuro AND Epub),
 *  - `pushTombstone` writes a metadata blob with deletedAt set,
 *  - `applyAiSettings` returns false when a concurrent local edit raced.
 */
class V3PushOpsTest {
    // Implementation agent: populate this file with @Test methods.
}
