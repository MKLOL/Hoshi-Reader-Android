package moe.antimony.hoshi.features.sync.v3

import kotlinx.coroutines.runBlocking
import moe.antimony.hoshi.features.sync.http.deriveSyncId
import moe.antimony.hoshi.features.sync.http.metadataKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Stress tests for pagination + multipart upload against the real [StubKvServer].
 *
 * Coverage:
 *  - 200-book library: import 200 syncIds × {metadata, manifest, zip}, sync a fresh
 *    device, every book lands locally.
 *  - Multipart payload: lower the multipart threshold; upload + download verifies
 *    every byte (sha256 enforced by [HttpSyncPayloadCodec.downloadAndUnpack]).
 *  - LIST page boundary: seed > stub's default `limit=500` keys and confirm both
 *    pages walked.
 *  - LIST stable across re-syncs: same library synced twice produces identical
 *    server keys.
 */
class V3SyncLargeLibraryTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun twoHundredBookLibraryRoundTrips() = runBlocking {
        StubKvServer().use { server ->
            server.start()
            val a = freshDevice(server, "A", tempFolder.newFolder("a-root"))
            val b = freshDevice(server, "B", tempFolder.newFolder("b-root"))
            val n = 200
            for (i in 1..n) a.importMokuro("Library Vol %03d".format(i))

            val pushed = a.sync()
            assertEquals("Sync errors on big push: ${pushed.errors}", 0, pushed.errors.size)
            val pulled = b.sync()
            assertEquals("Sync errors on big pull: ${pulled.errors}", 0, pulled.errors.size)

            val bTitles = b.repo.loadBookEntries().mapNotNull { it.metadata.title }.toSet()
            for (i in 1..n) {
                assertTrue("Missing Library Vol %03d".format(i), "Library Vol %03d".format(i) in bTitles)
            }
        }
    }

    @Test
    fun listPaginationWalksAllPages() = runBlocking {
        StubKvServer().use { server ->
            server.start()
            // Stub default limit is 500 — seed > 500 keys so we cross the page boundary.
            // Each book contributes 3 keys (metadata + manifest + zip), so 180 books =
            // 540 keys, comfortably over the boundary.
            val a = freshDevice(server, "A", tempFolder.newFolder("a-root"))
            val b = freshDevice(server, "B", tempFolder.newFolder("b-root"))
            val n = 180
            for (i in 1..n) a.importMokuro("Page Vol %03d".format(i))
            val pushed = a.sync()
            assertEquals(0, pushed.errors.size)
            assertTrue("Should produce more than 500 keys, got ${server.keys().size}",
                server.keys().size > 500)

            val pulled = b.sync()
            assertEquals(0, pulled.errors.size)
            val bTitles = b.repo.loadBookEntries().mapNotNull { it.metadata.title }.toSet()
            assertEquals(n, bTitles.size)
        }
    }

    @Test
    fun resyncDoesNotChurnServerKeys() = runBlocking {
        StubKvServer().use { server ->
            server.start()
            val a = freshDevice(server, "A", tempFolder.newFolder("a-root"))
            val n = 25
            for (i in 1..n) a.importMokuro("Stable Vol %03d".format(i))
            val first = a.sync()
            assertEquals(0, first.errors.size)

            val firstKeys = server.keys().toSet()
            val firstBytes = firstKeys.associateWith { server.bytesAt(it)?.toList() }

            // Re-sync — no local edits — should produce identical server state.
            for (round in 2..4) {
                val r = a.sync()
                assertEquals("round $round errors: ${r.errors}", 0, r.errors.size)
                assertEquals("round $round keys changed", firstKeys, server.keys().toSet())
                for ((k, v) in firstBytes) {
                    assertEquals("round $round bytes changed at $k", v, server.bytesAt(k)?.toList())
                }
            }
        }
    }

    @Test
    fun multipartPayloadRoundTripsLargeBytesSha256Verified() = runBlocking {
        // Force the multipart threshold down to 32 KiB so anything over 32 KiB uses the
        // multipart upload codepath. Build a 256 KiB book payload (so ~8 parts) and verify
        // the bytes round-trip intact.
        StubKvServer().use { server ->
            server.start()
            val a = freshDevice(
                server, "A", tempFolder.newFolder("a-root"),
                multipartThresholdBytes = 32L * 1024L,
                multipartPartSizeBytes = 32L * 1024L,
            )
            val b = freshDevice(
                server, "B", tempFolder.newFolder("b-root"),
                multipartThresholdBytes = 32L * 1024L,
                multipartPartSizeBytes = 32L * 1024L,
            )
            val title = "Multipart Vol"
            val payload = ByteArray(256 * 1024).also { bytes ->
                // Deterministic non-trivial pattern: easier to spot a single-byte corruption.
                for (i in bytes.indices) bytes[i] = ((i * 31 + 7) % 251).toByte()
            }
            a.importMokuro(title, contentBytes = payload)

            val pushed = a.sync()
            assertEquals(0, pushed.errors.size)
            assertTrue("Server should have a payload.zip key: ${server.keys()}",
                server.keys().any { it.endsWith("payload.zip") })

            val pulled = b.sync()
            assertEquals(0, pulled.errors.size)
            val rootB = b.repo.loadBookEntries().single { it.metadata.title == title }.root
            val downloaded = File(rootB, "pages/p1.png").readBytes()
            assertEquals(payload.size, downloaded.size)
            for (i in payload.indices) {
                if (payload[i] != downloaded[i]) {
                    throw AssertionError("Byte mismatch at offset $i: " +
                        "expected=${payload[i].toInt() and 0xff} actual=${downloaded[i].toInt() and 0xff}")
                }
            }
        }
    }

    @Test
    fun pendingRemoteOnlyBooksReportedWhenManifestMissing() = runBlocking {
        // Force the server to have a metadata key but no manifest — the engine should
        // count it as a pending remote-only book without crashing.
        StubKvServer().use { server ->
            server.start()
            val a = freshDevice(server, "A", tempFolder.newFolder("a-root"))
            val b = freshDevice(server, "B", tempFolder.newFolder("b-root"))
            a.importMokuro("Pending Vol")
            a.sync()

            // Delete just the manifest key — simulates a half-finished upload.
            val syncId = deriveSyncId("Pending Vol")!!
            // The stub server doesn't expose a direct mutation API beyond setBehavior, but
            // we can intercept the manifest GET and return 404 so it looks absent.
            server.setBehavior { method, path ->
                if (method == "GET" && path.endsWith("/payload.manifest")) {
                    BehaviorAction.FailWith(404, "")
                } else BehaviorAction.Passthrough
            }
            val pulled = b.sync()
            // The metadata blob is still on the server, so V3RemoteState observes the
            // book and registers it under pendingRemoteOnlyBooks (manifest GET returns
            // 404, so the importer can't run). Concretely: nothing landed locally,
            // and remoteOnlyBooks counts the un-importable book.
            assertTrue(
                "metadata for the pending book should still be on the server: ${server.keys()}",
                metadataKey(syncId) in server.keys(),
            )
            assertFalse(
                "B must not have imported the book locally — manifest is 404",
                b.repo.loadBookEntries().any { it.metadata.title == "Pending Vol" },
            )
            assertTrue(
                "B should report at least one pending remote-only book: ${pulled.remoteOnlyBooks}",
                pulled.remoteOnlyBooks >= 1,
            )
        }
    }
}
