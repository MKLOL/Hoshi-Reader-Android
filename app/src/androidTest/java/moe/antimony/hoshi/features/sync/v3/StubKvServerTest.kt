package moe.antimony.hoshi.features.sync.v3

/**
 * Protocol-level sanity tests for [StubKvServer]. These tests prove the server
 * implements the v2 KV protocol correctly enough for the v3 engine tests above
 * to be trustworthy. If any of these fail, all other v3 integration tests are
 * suspect.
 *
 * Required coverage:
 *  - PUT then GET round-trips bytes + headers,
 *  - GET on missing key returns 404,
 *  - DELETE then GET returns 404,
 *  - LIST with prefix filters correctly,
 *  - LIST pagination: insert > limit keys, walk via cursor, every key seen exactly once,
 *  - LIST since: filter by lastModified > since,
 *  - Multipart upload: start, parts, complete; final body bytes match a single PUT
 *    of the same content,
 *  - Multipart delete cancels in-flight upload,
 *  - Auth: missing token → 401; wrong token → 401; correct token → 200,
 *  - Bytes for [bytesAt] match what GET returns.
 */
class StubKvServerTest {
    // Implementation agent: populate this file with @Test methods.
}
