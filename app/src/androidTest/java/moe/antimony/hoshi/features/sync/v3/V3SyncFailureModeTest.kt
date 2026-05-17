package moe.antimony.hoshi.features.sync.v3

import kotlinx.coroutines.runBlocking
import moe.antimony.hoshi.features.sync.http.HttpSyncException
import moe.antimony.hoshi.features.sync.http.bookmarkKey
import moe.antimony.hoshi.features.sync.http.deriveSyncId
import moe.antimony.hoshi.features.sync.http.metadataKey
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.atomic.AtomicInteger

/**
 * Failure-mode tests for [V3SyncEngine] against a controllably-flaky [StubKvServer].
 * Behavior is injected via [StubKvServer.setBehavior].
 *
 * Coverage:
 *  - GET 503 on one book's metadata → that book's sync surfaces an error,
 *    other books complete.
 *  - PUT 503 on a chat entry → that chat is unpushed and reported.
 *  - LIST 503 → fatal: engine throws.
 *  - 401 on LIST → fatal: engine throws with auth-mentioning message.
 *  - Malformed JSON body returned for metadata → per-book error.
 *  - Dropped connection mid-payload → retry on next sync succeeds.
 *  - Slow server (delayed responses) — sync completes, no spurious timeouts.
 */
class V3SyncFailureModeTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun perBookMetadataGetFailureDoesNotPoisonOtherBooks() = runBlocking {
        StubKvServer().use { server ->
            server.start()
            val a = freshDevice(server, "A", tempFolder.newFolder("a-root"))
            val flaky = "Flaky Book"
            val healthy = "Healthy Book"
            a.importMokuro(flaky)
            a.importMokuro(healthy)
            a.sync()

            val flakySyncId = deriveSyncId(flaky)!!
            val healthySyncId = deriveSyncId(healthy)!!
            val flakyKey = metadataKey(flakySyncId)
            server.setBehavior { method, path ->
                if (method == "GET" && path.endsWith(flakyKey)) BehaviorAction.FailWith(503, "down")
                else BehaviorAction.Passthrough
            }
            val b = freshDevice(server, "B", tempFolder.newFolder("b-root"))
            val result = b.sync()

            assertTrue(
                "Healthy book must arrive: titles=${b.repo.loadBookEntries().mapNotNull { it.metadata.title }}",
                b.repo.loadBookEntries().any { it.metadata.title == healthy },
            )
            assertTrue(
                "Per-book error must surface for the flaky book: ${result.errors}",
                result.errors.any { e ->
                    (e.syncId == flakySyncId) ||
                        e.message.contains("503", ignoreCase = true) ||
                        e.message.contains("down", ignoreCase = true) ||
                        e.message.contains(flakySyncId)
                },
            )
        }
    }

    @Test
    fun chatPutFailureSurfacesAsPerBookErrorAndOtherBooksProceed() = runBlocking {
        StubKvServer().use { server ->
            server.start()
            val a = freshDevice(server, "A", tempFolder.newFolder("a-root"))
            val flaky = "Flaky Chat Book"
            val healthy = "Healthy Chat Book"
            val flakyBook = a.importMokuro(flaky)
            val healthyBook = a.importMokuro(healthy)
            a.chat(flakyBook, "fb1", "fr1")
            a.chat(healthyBook, "hb1", "hr1")

            val flakySyncId = deriveSyncId(flaky)!!
            server.setBehavior { method, path ->
                if (method == "PUT" && path.contains("/books/$flakySyncId/chat/")) {
                    BehaviorAction.FailWith(503, "chat-down")
                } else BehaviorAction.Passthrough
            }
            val result = a.sync()
            // Either it threw, or it captured the error per-action. We accept either.
            assertTrue(
                "Expected at least one error in result.errors: ${result.errors}",
                result.errors.isNotEmpty(),
            )
            // Other books proceed: at least a metadata or manifest for the healthy book on the server.
            val healthyId = deriveSyncId(healthy)!!
            assertTrue(
                "Healthy book's metadata should be reachable: ${server.keys()}",
                metadataKey(healthyId) in server.keys(),
            )
        }
    }

    @Test
    fun listFiveHundredThreeIsFatal() = runBlocking {
        StubKvServer().use { server ->
            server.start()
            val a = freshDevice(server, "A", tempFolder.newFolder("a-root"))
            a.importMokuro("Book")
            server.setBehavior { method, path ->
                if (method == "GET" && path.startsWith("/v1/kv?")) BehaviorAction.FailWith(503, "boom")
                else BehaviorAction.Passthrough
            }
            try {
                a.sync()
                fail("Expected the sync to throw on LIST 503")
            } catch (_: HttpSyncException) {
                // Expected — fatal transport error during pagination.
            } catch (_: Exception) {
                // Some implementations may wrap differently; any exception is acceptable.
            }
        }
    }

    @Test
    fun fourOhOneOnListMentionsAuthInMessage() = runBlocking {
        StubKvServer().use { server ->
            server.start()
            val a = freshDevice(server, "A", tempFolder.newFolder("a-root"))
            server.setBehavior { method, path ->
                if (method == "GET" && path.startsWith("/v1/kv?")) BehaviorAction.FailWith(401, "no auth")
                else BehaviorAction.Passthrough
            }
            try {
                a.sync()
                fail("Expected 401 to be fatal")
            } catch (e: HttpSyncException) {
                assertTrue(
                    "Auth failure message should mention auth/401/token: ${e.message}",
                    listOf("auth", "401", "token", "bearer").any { it in (e.message ?: "").lowercase() },
                )
            } catch (e: Exception) {
                assertTrue(
                    "Auth failure should mention auth: ${e.message}",
                    listOf("auth", "401", "token", "bearer").any { it in (e.message ?: "").lowercase() },
                )
            }
        }
    }

    @Test
    fun malformedMetadataJsonProducesPerBookErrorNotCrash() = runBlocking {
        StubKvServer().use { server ->
            server.start()
            val a = freshDevice(server, "A", tempFolder.newFolder("a-root"))
            val target = "Malformed Volume"
            val other = "OK Volume"
            a.importMokuro(target)
            a.importMokuro(other)
            a.sync()

            // Intercept the metadata GET for `target` and return garbage JSON.
            val targetSyncId = deriveSyncId(target)!!
            val key = metadataKey(targetSyncId)
            server.setBehavior { method, path ->
                if (method == "GET" && path.endsWith(key)) BehaviorAction.FailWith(200, "not-valid-json")
                else BehaviorAction.Passthrough
            }
            val b = freshDevice(server, "B", tempFolder.newFolder("b-root"))
            val result = b.sync()

            assertTrue(
                "Other book must still arrive locally: ${b.repo.loadBookEntries().mapNotNull { it.metadata.title }}",
                b.repo.loadBookEntries().any { it.metadata.title == other },
            )
            assertTrue(
                "Expected an error mentioning $targetSyncId / parse / malformed: ${result.errors}",
                result.errors.any {
                    (it.syncId == targetSyncId) ||
                        it.message.contains("malformed", ignoreCase = true) ||
                        it.message.contains("json", ignoreCase = true) ||
                        it.message.contains("parse", ignoreCase = true) ||
                        it.message.contains(targetSyncId)
                },
            )
        }
    }

    @Test
    fun droppedConnectionMidPayloadRetriesNextSync() = runBlocking {
        StubKvServer().use { server ->
            server.start()
            val a = freshDevice(server, "A", tempFolder.newFolder("a-root"))
            val title = "Drop Volume"
            a.importMokuro(title)
            a.sync()

            val syncId = deriveSyncId(title)!!
            val dropCounter = AtomicInteger(0)
            server.setBehavior { method, path ->
                if (method == "GET" && path.endsWith("/books/$syncId/payload.zip")) {
                    if (dropCounter.getAndIncrement() == 0) BehaviorAction.DropConnection
                    else BehaviorAction.Passthrough
                } else BehaviorAction.Passthrough
            }
            val b = freshDevice(server, "B", tempFolder.newFolder("b-root"))
            // First B sync: payload fetch fails. Engine surfaces an error per-book, then we
            // restore connectivity (the behavior auto-recovers after the first DropConnection)
            // and the next sync completes.
            val first = b.sync()
            // Either the book imported despite the drop (unlikely; manifest needed first), OR
            // it surfaced as an error. Both are acceptable; we then retry.
            val firstHasBook = b.repo.loadBookEntries().any { it.metadata.title == title }
            if (!firstHasBook) {
                assertTrue(
                    "First sync should have at least one error from the dropped payload: ${first.errors}",
                    first.errors.isNotEmpty(),
                )
            }
            val second = b.sync()
            assertTrue("Second sync still errored: ${second.errors}", second.errors.isEmpty())
            assertTrue(
                "After retry, B should have the book: ${b.repo.loadBookEntries().mapNotNull { it.metadata.title }}",
                b.repo.loadBookEntries().any { it.metadata.title == title },
            )
        }
    }

    @Test
    fun slowServerDoesNotProduceSpuriousTimeouts() = runBlocking {
        StubKvServer().use { server ->
            server.start()
            val a = freshDevice(server, "A", tempFolder.newFolder("a-root"))
            a.importMokuro("Slow Volume")
            a.sync()
            // Delay every request by ~200 ms for the next sync. Real timeouts in
            // HttpSyncKvClient are 15s/30s; 200ms shouldn't be near them.
            server.setBehavior { _, _ -> BehaviorAction.Delay(200L) }
            val b = freshDevice(server, "B", tempFolder.newFolder("b-root"))
            val result = b.sync()
            assertTrue("Slow server errored unexpectedly: ${result.errors}", result.errors.isEmpty())
            assertNotNull(b.repo.loadBookEntries().firstOrNull { it.metadata.title == "Slow Volume" })
        }
    }

    @Test
    fun perBookBookmarkFetchErrorDoesNotAbortOtherBooks() = runBlocking {
        StubKvServer().use { server ->
            server.start()
            val a = freshDevice(server, "A", tempFolder.newFolder("a-root"))
            val flaky = "Flaky Bookmark"
            val healthy = "Healthy Bookmark"
            val flakyBook = a.importMokuro(flaky)
            val healthyBook = a.importMokuro(healthy)
            a.turnPage(flakyBook, 5)
            a.turnPage(healthyBook, 9)
            a.sync()

            val flakyId = deriveSyncId(flaky)!!
            val healthyId = deriveSyncId(healthy)!!
            val bmKey = bookmarkKey(flakyId)
            server.setBehavior { method, path ->
                if (method == "GET" && path.endsWith(bmKey)) BehaviorAction.FailWith(503, "bm-down")
                else BehaviorAction.Passthrough
            }
            val b = freshDevice(server, "B", tempFolder.newFolder("b-root"))
            val result = b.sync()
            // Healthy book's bookmark should still apply.
            val healthyRoot = b.repo.loadBookEntries().firstOrNull { it.metadata.title == healthy }?.root
            assertNotNull("Healthy book missing on B", healthyRoot)
            // Errors should mention the flaky book OR a 503.
            assertTrue(
                "Per-book error must surface for flaky bookmark: ${result.errors}",
                result.errors.any { it.message.contains("503") || (it.syncId == flakyId) || it.message.contains(flakyId) },
            )
        }
    }
}
