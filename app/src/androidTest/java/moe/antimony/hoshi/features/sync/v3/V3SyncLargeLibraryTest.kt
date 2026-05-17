package moe.antimony.hoshi.features.sync.v3

/**
 * Stress tests that exercise pagination + multipart upload against the real
 * [StubKvServer].
 *
 * Required coverage:
 *  - 200-book library: seed the stub with 200 syncIds × {metadata, manifest, zip},
 *    sync a fresh device, every book lands locally,
 *  - multipart payload: construct a Mokuro folder larger than the configured
 *    multipart threshold (force the threshold low in test config), upload,
 *    download, verify sha256 matches end-to-end.
 *  - LIST page boundary: stub server's default limit is 500; seed 501 keys and
 *    confirm both pages are walked.
 *  - LIST stable across re-syncs: same library synced twice produces identical
 *    server keys (no churn).
 */
class V3SyncLargeLibraryTest {
    // Implementation agent: populate this file with @Test methods.
}
