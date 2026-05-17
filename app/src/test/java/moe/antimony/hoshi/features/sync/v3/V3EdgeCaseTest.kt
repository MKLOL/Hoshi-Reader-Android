package moe.antimony.hoshi.features.sync.v3

/**
 * Pathological inputs and recovery behavior.
 *
 * Required coverage — implementation agent must add at least:
 *  - malformed JSON in remote metadata / bookmark / chat / manifest blobs,
 *  - manifest without zip on server,
 *  - zip without manifest on server,
 *  - blank-title local book → planner emits no actions for it (deriveSyncId returns null),
 *  - non-ASCII title with hash fallback syncId round-trips,
 *  - same syncId across content types (Mokuro vs EPUB collision),
 *  - book directory missing key files,
 *  - chat-key shape from a future client that contains characters we ignore.
 */
class V3EdgeCaseTest {
    // Implementation agent: populate this file with @Test methods.
}
