package moe.antimony.hoshi.features.sync.v3

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import moe.antimony.hoshi.features.sync.http.AI_CHAT_SETTINGS_KEY
import moe.antimony.hoshi.features.sync.http.bookmarkKey
import moe.antimony.hoshi.features.sync.http.chatPrefixForBook
import moe.antimony.hoshi.features.sync.http.deriveSyncId
import moe.antimony.hoshi.features.sync.http.metadataKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Single-device round-trip tests for [V3SyncEngine] against a real [StubKvServer].
 *
 * Coverage:
 *  - Fresh device imports a Mokuro book, syncs, server has metadata + payload.zip + manifest.
 *  - Same for EPUB (proves v3 widens the gate).
 *  - Two BookRepositorys against the same stub server: A imports + syncs, B syncs - B has the book.
 *  - Bookmark round-trip: A.saveBookmark, A.sync, B.sync, B.loadBookmark matches.
 *  - Chat round-trip: A.append, A.sync, B.sync, B's history contains the entry.
 *  - ai_chat_settings round-trip.
 *  - Tombstone round-trip: A.delete, A.sync, B.sync, B's local book is gone.
 *  - Repeat the same syncOnce 3x - no growth in server state, no errors.
 */
class V3SyncIntegrationTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun freshMokuroBookUploadsMetadataManifestAndZip() = runBlocking {
        StubKvServer().use { server ->
            server.start()
            val a = freshDevice(server, "A", tempFolder.newFolder("a-root"))
            val title = "Yotsubato 01"
            a.importMokuro(title)

            val result = a.sync()

            assertTrue("Sync errors: ${result.errors}", result.errors.isEmpty())
            val syncId = deriveSyncId(title)!!
            val keys = server.keys().toSet()
            assertTrue("metadata key missing: $keys", "books/$syncId/metadata" in keys)
            assertTrue("manifest key missing: $keys", "books/$syncId/payload.manifest" in keys)
            assertTrue("payload.zip key missing: $keys", "books/$syncId/payload.zip" in keys)
            assertTrue(
                "expected at least 1 payload upload, got ${result.pushed.payloads}",
                result.pushed.payloads >= 1,
            )
        }
    }

    @Test
    fun freshEpubBookUploadsMetadataManifestAndZip() = runBlocking {
        StubKvServer().use { server ->
            server.start()
            val a = freshDevice(server, "A", tempFolder.newFolder("a-root"))
            val title = "Kafka on the Shore"
            a.importEpub(title)

            val result = a.sync()

            assertTrue("Sync errors: ${result.errors}", result.errors.isEmpty())
            val syncId = deriveSyncId(title)!!
            val keys = server.keys().toSet()
            // v3 widens the payload gate to EPUB too.
            assertTrue("EPUB manifest missing: $keys", "books/$syncId/epub.manifest" in keys)
            assertTrue("EPUB payload missing: $keys", "books/$syncId/epub.zip" in keys)
        }
    }

    @Test
    fun bookImportSyncedToSecondDeviceMaterializesBook() = runBlocking {
        StubKvServer().use { server ->
            server.start()
            val a = freshDevice(server, "A", tempFolder.newFolder("a-root"))
            val b = freshDevice(server, "B", tempFolder.newFolder("b-root"))
            val title = "Sync Sample"
            a.importMokuro(title)

            val pushed = a.sync()
            assertTrue("A push errors: ${pushed.errors}", pushed.errors.isEmpty())
            val pulled = b.sync()
            assertTrue("B pull errors: ${pulled.errors}", pulled.errors.isEmpty())

            val bTitles = b.repo.loadBookEntries().map { it.metadata.title }
            assertTrue("B missing $title, has $bTitles", title in bTitles)
            assertConvergent(a, b)
        }
    }

    @Test
    fun bookmarkRoundTripsAcrossDevices() = runBlocking {
        StubKvServer().use { server ->
            server.start()
            val a = freshDevice(server, "A", tempFolder.newFolder("a-root"))
            val b = freshDevice(server, "B", tempFolder.newFolder("b-root"))
            val title = "Bookmark Roundtrip"
            val bookA = a.importMokuro(title)
            a.turnPage(bookA, toCharacter = 42)

            val pushed = a.sync()
            assertTrue("A errors: ${pushed.errors}", pushed.errors.isEmpty())
            val pulled = b.sync()
            assertTrue("B errors: ${pulled.errors}", pulled.errors.isEmpty())

            val bookB = b.repo.loadBookEntries().single { it.metadata.title == title }.root
            val bookmarkB = b.repo.loadBookmark(bookB)
            assertNotNull("B did not get a bookmark", bookmarkB)
            assertEquals(42, bookmarkB!!.characterCount)
        }
    }

    @Test
    fun chatEntryRoundTripsAcrossDevices() = runBlocking {
        StubKvServer().use { server ->
            server.start()
            val a = freshDevice(server, "A", tempFolder.newFolder("a-root"))
            val b = freshDevice(server, "B", tempFolder.newFolder("b-root"))
            val title = "Chat Roundtrip"
            val bookA = a.importMokuro(title)
            val pushedEntry = a.chat(bookA, bubbleText = "こんにちは", response = "Hello")

            a.sync()
            b.sync()

            val bookB = b.repo.loadBookEntries().single { it.metadata.title == title }.root
            val historyB = b.history.load(bookB).entries
            assertTrue(
                "B's history did not contain the entry: ${historyB.map { it.bubbleText }}",
                historyB.any { it.bubbleText == pushedEntry.bubbleText && it.response == pushedEntry.response },
            )
        }
    }

    @Test
    fun aiChatSettingsRoundTrip() = runBlocking {
        StubKvServer().use { server ->
            server.start()
            val a = freshDevice(server, "A", tempFolder.newFolder("a-root"))
            val b = freshDevice(server, "B", tempFolder.newFolder("b-root"))
            a.aiRepo.update { it.copy(model = "gpt-test-9", promptText = "Translate this", apiKey = "secret-A") }

            a.sync()
            b.sync()

            val bSettings = b.aiRepo.settings.first()
            assertEquals("gpt-test-9", bSettings.model)
            assertEquals("Translate this", bSettings.promptText)
            // API key is per-device — must not have leaked.
            assertFalse("API key leaked to B: ${bSettings.apiKey}", bSettings.apiKey == "secret-A")
            assertTrue("Server has no app/ai_chat_settings: ${server.keys()}", AI_CHAT_SETTINGS_KEY in server.keys())
        }
    }

    @Test
    fun aiChatSettingsLwwPicksNewerSide() = runBlocking {
        StubKvServer().use { server ->
            server.start()
            val a = freshDevice(server, "A", tempFolder.newFolder("a-root"))
            val b = freshDevice(server, "B", tempFolder.newFolder("b-root"))
            a.aiRepo.update { it.copy(model = "alpha") }
            a.sync()
            b.sync()
            // B edits after pulling — B should now be authoritative.
            b.aiRepo.update { it.copy(model = "beta") }
            b.sync()
            a.sync()

            val final = a.aiRepo.settings.first()
            assertEquals("beta", final.model)
        }
    }

    @Test
    fun tombstoneRoundTripRemovesBookOnOtherDevice() = runBlocking {
        StubKvServer().use { server ->
            server.start()
            val a = freshDevice(server, "A", tempFolder.newFolder("a-root"))
            val b = freshDevice(server, "B", tempFolder.newFolder("b-root"))
            val title = "Tombstoned Volume"
            val bookA = a.importMokuro(title)
            a.sync()
            b.sync()
            assertTrue(b.repo.loadBookEntries().any { it.metadata.title == title })

            // Delete on A (also drops a tombstone sidecar).
            stageTombstoneFor(a, title)
            a.delete(bookA)
            a.sync()
            b.sync()

            val bTitles = b.repo.loadBookEntries().map { it.metadata.title }
            assertFalse("B still has $title: $bTitles", title in bTitles)
        }
    }

    @Test
    fun threeIdenticalSyncsAreIdempotent() = runBlocking {
        StubKvServer().use { server ->
            server.start()
            val a = freshDevice(server, "A", tempFolder.newFolder("a-root"))
            val title = "Idempotent Volume"
            val book = a.importMokuro(title)
            a.turnPage(book, toCharacter = 10)
            a.chat(book, bubbleText = "Bubble1", response = "Response1")

            val first = a.sync()
            assertTrue("First sync errors: ${first.errors}", first.errors.isEmpty())
            assertTrue("First sync had no pushes: $first", first.pushed.total > 0)

            val keysAfterFirst = server.keys().toSet()
            val byteSnapshotAfterFirst = keysAfterFirst.associateWith { server.bytesAt(it)?.toList() }

            // Two more syncs with no local edits — server state must not grow, no errors.
            for (i in 2..3) {
                val r = a.sync()
                assertTrue("Sync #$i errors: ${r.errors}", r.errors.isEmpty())
                assertEquals(
                    "Sync #$i added/removed keys",
                    keysAfterFirst,
                    server.keys().toSet(),
                )
                for ((k, expected) in byteSnapshotAfterFirst) {
                    val actual = server.bytesAt(k)?.toList()
                    assertEquals("Sync #$i mutated bytes at $k", expected, actual)
                }
            }
        }
    }

    @Test
    fun serverStateHasExpectedKeysForOneMokuroBook() = runBlocking {
        StubKvServer().use { server ->
            server.start()
            val a = freshDevice(server, "A", tempFolder.newFolder("a-root"))
            val title = "Key Shape Volume"
            val book = a.importMokuro(title)
            a.turnPage(book, toCharacter = 3)
            a.chat(book, bubbleText = "test bubble", response = "test response")
            a.sync()

            val syncId = deriveSyncId(title)!!
            val keys = server.keys().toSet()
            assertTrue("metadata: $keys", metadataKey(syncId) in keys)
            assertTrue("bookmark: $keys", bookmarkKey(syncId) in keys)
            assertTrue("chat prefix present: $keys", keys.any { it.startsWith(chatPrefixForBook(syncId)) })
            assertTrue("payload.manifest: $keys", "books/$syncId/payload.manifest" in keys)
            assertTrue("payload.zip: $keys", "books/$syncId/payload.zip" in keys)
        }
    }

    @Test
    fun staleSyncedAtCursorIsIgnored() = runBlocking {
        // v3 explicitly does no `since=` filtering — even garbage cursors should not block a pull.
        StubKvServer().use { server ->
            server.start()
            val a = freshDevice(server, "A", tempFolder.newFolder("a-root"))
            val b = freshDevice(server, "B", tempFolder.newFolder("b-root"))
            val title = "Stale Cursor Volume"
            a.importMokuro(title)
            a.sync()

            // Hand B a malformed lastSyncedAt — sync must still produce the book locally.
            val pulled = b.engine.syncOnce(b.settings.copy(lastSyncedAt = "not-a-real-timestamp"))
            assertTrue("B pull with garbage cursor errored: ${pulled.errors}", pulled.errors.isEmpty())
            assertTrue(b.repo.loadBookEntries().any { it.metadata.title == title })
        }
    }
}
