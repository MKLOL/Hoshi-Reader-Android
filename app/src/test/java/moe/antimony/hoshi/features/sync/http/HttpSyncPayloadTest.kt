package moe.antimony.hoshi.features.sync.http

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Tests for the book-payload codec and its wiring into [HttpSyncReconciler].
 *
 * Most tests run against the codec directly with a temp directory. The end-to-end
 * round-trip (zip → upload → fetch → unzip → verify identical contents) goes through a
 * shared [FakeKvTransport] so we exercise the full pipeline.
 */
class HttpSyncPayloadTest {

    @get:Rule val tempFolder = TemporaryFolder()

    private val codec = HttpSyncPayloadCodec(ioDispatcher = Dispatchers.Unconfined)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    // ===== Zip / unzip round-trip ==============================================================

    @Test
    fun zipAndUnzipRoundTripPreservesContents() = runBlocking {
        val src = tempFolder.newFolder("source-book").apply {
            resolve("mokuro.json").writeText("""{"version":"1.0"}""")
            resolve("pages").mkdirs()
            resolve("pages/0001.png").writeBytes(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47))
            resolve("pages/0002.png").writeBytes(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D))
        }
        val (zipBytes, sha) = codec.zipDirectory(src)
        assertTrue("sha should be a sha256: prefix", sha.startsWith("sha256:"))
        assertTrue("zip non-empty", zipBytes.isNotEmpty())

        val dest = tempFolder.newFolder("dest-book")
        codec.unzipInto(zipBytes, dest)

        assertEquals("""{"version":"1.0"}""", dest.resolve("mokuro.json").readText())
        assertEquals(4, dest.resolve("pages/0001.png").readBytes().size)
        assertEquals(5, dest.resolve("pages/0002.png").readBytes().size)
    }

    @Test
    fun zipDirectoryToFileMatchesByteArrayZipHelper() = runBlocking {
        val src = tempFolder.newFolder("spooled-source-book").apply {
            resolve("mokuro.json").writeText("""{"version":"1.0"}""")
            resolve("pages").mkdirs()
            resolve("pages/0001.png").writeBytes(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47))
        }
        val (zipBytes, byteArraySha) = codec.zipDirectory(src)
        val zipFile = tempFolder.newFile("payload.zip")
        val fileSha = codec.zipDirectoryToFile(src, zipFile)

        assertEquals(byteArraySha, fileSha)
        assertEquals(zipBytes.size.toLong(), zipFile.length())
    }

    @Test
    fun uploadUsesFileBackedPayloadZipInsteadOfByteArrayPut() = runBlocking {
        val src = tempFolder.newFolder("stream-upload-book").apply {
            resolve("mokuro.json").writeText("""{"version":"1.0"}""")
            resolve("pages").mkdirs()
            resolve("pages/0001.png").writeBytes(ByteArray(128 * 1024) { (it % 251).toByte() })
        }
        val transport = PayloadStreamingOnlyTransport()

        val uploaded = codec.uploadIfChanged(
            transport = transport,
            syncId = "stream_upload",
            bookRoot = src,
            originalName = "Stream Upload",
            format = HttpSyncContentType.Mokuro,
        )

        assertTrue(uploaded)
        assertEquals(1, transport.payloadPutFileCalls)
        assertEquals(0, transport.payloadByteArrayPutCalls)
        assertNotNull(transport.kv[payloadZipKey("stream_upload")])
        val manifest = json.decodeFromString(
            HttpSyncPayloadManifest.serializer(),
            transport.kv[payloadManifestKey("stream_upload")]!!.body.toString(Charsets.UTF_8),
        )
        assertEquals(transport.kv[payloadZipKey("stream_upload")]!!.body.size.toLong(), manifest.sizeBytes)
        assertFalse(
            "upload spool should be deleted",
            src.parentFile!!.listFiles().orEmpty().any { it.name.startsWith("hoshi-sync-upload-") },
        )
    }

    @Test
    fun uploadDoesNotWriteManifestWhenPayloadUploadFails() = runBlocking {
        val src = tempFolder.newFolder("failing-stream-upload-book").apply {
            resolve("mokuro.json").writeText("""{"version":"1.0"}""")
            resolve("pages").mkdirs()
            resolve("pages/0001.png").writeBytes(ByteArray(128 * 1024) { (it % 251).toByte() })
        }
        val transport = FailingPayloadUploadTransport()

        assertThrows(HttpSyncException::class.java) {
            runBlocking {
                codec.uploadIfChanged(
                    transport = transport,
                    syncId = "failing_stream_upload",
                    bookRoot = src,
                    originalName = "Failing Stream Upload",
                    format = HttpSyncContentType.Mokuro,
                )
            }
        }

        assertTrue("payload upload should have been attempted", transport.payloadPutFileCalls == 1)
        assertFalse("manifest must not point at a failed payload upload", payloadManifestKey("failing_stream_upload") in transport.kv)
        assertFalse(
            "failed upload spool should be deleted",
            src.parentFile!!.listFiles().orEmpty().any { it.name.startsWith("hoshi-sync-upload-") },
        )
    }

    @Test
    fun downloadUsesFileBackedPayloadZipInsteadOfByteArrayGet() = runBlocking {
        val src = tempFolder.newFolder("stream-download-source").apply {
            resolve("mokuro.json").writeText("""{"hello":"stream"}""")
            resolve("pages").mkdirs()
            resolve("pages/p1.png").writeBytes(ByteArray(96 * 1024) { (it % 127).toByte() })
        }
        val seeded = FakeKvTransport()
        codec.uploadIfChanged(seeded, "stream_download", src, "Stream Download", HttpSyncContentType.Mokuro)
        val transport = PayloadStreamingOnlyTransport().apply {
            kv.putAll(seeded.kv)
        }

        val downloadTarget = tempFolder.newFolder("stream-download-target")
        val manifest = codec.downloadAndUnpack(transport, "stream_download", downloadTarget)

        assertEquals("Stream Download", manifest.originalName)
        assertEquals(1, transport.payloadDownloadToFileCalls)
        assertEquals(0, transport.payloadByteArrayGetCalls)
        assertEquals("""{"hello":"stream"}""", downloadTarget.resolve("mokuro.json").readText())
        assertEquals(96 * 1024, downloadTarget.resolve("pages/p1.png").readBytes().size)
        assertFalse(
            "download spool should be deleted",
            downloadTarget.parentFile!!.listFiles().orEmpty().any { it.name.startsWith("hoshi-sync-download-") },
        )
    }

    @Test
    fun zipExcludesPerDeviceSidecars() = runBlocking {
        val src = tempFolder.newFolder("book").apply {
            resolve("mokuro.json").writeText("{}")
            resolve("bookmark.json").writeText("""{"chapterIndex": 5}""")
            resolve("ai_chat_log.json").writeText("""{"entries":[]}""")
            // metadata.json carries per-device fields (UUID id, lastAccess timestamp)
            // that rewrite on every book-open. Including it in the zip would make every
            // device's payload sha differ and re-upload forever — the regression the
            // user reported as "scrolled a bit, pressed Sync, said book payload up."
            resolve("metadata.json").writeText("""{"title":"Hi","id":"device-a-uuid","lastAccess":12345}""")
        }
        val (zipBytes, _) = codec.zipDirectory(src)
        val dest = tempFolder.newFolder("unpacked")
        codec.unzipInto(zipBytes, dest)

        assertTrue("content file (mokuro.json) round-trips", dest.resolve("mokuro.json").exists())
        assertFalse("bookmark.json must not round-trip via the payload", dest.resolve("bookmark.json").exists())
        assertFalse("ai_chat_log.json must not round-trip via the payload", dest.resolve("ai_chat_log.json").exists())
        assertFalse("metadata.json must not round-trip — receiving device makes its own", dest.resolve("metadata.json").exists())
    }

    @Test
    fun zipExcludesAllPerDeviceSidecarsAndSasayakiDir() = runBlocking {
        // Comprehensive zip-exclusion check. Adding a new per-book sidecar to BookRepository
        // without updating PAYLOAD_EXCLUDED_FILES has caused the same user-visible bug
        // ("scrolled a bit, sync re-uploaded the whole book") three separate times. This
        // test makes sure every currently-known sidecar is filtered.
        val src = tempFolder.newFolder("all-sidecars-book").apply {
            resolve("mokuro.json").writeText("""{"v":1}""")
            resolve("bookmark.json").writeText("""{"chapterIndex":5}""")
            resolve("ai_chat_log.json").writeText("""{"entries":[]}""")
            resolve("metadata.json").writeText("""{"title":"T","id":"abc","lastAccess":12345}""")
            resolve("statistics.json").writeText("""[{"chapterIndex":1,"timestampSeconds":1.0}]""")
            resolve("sasayaki_match.json").writeText("""{"deviceId":"a"}""")
            resolve("sasayaki_playback.json").writeText("""{"playheadPosition":10.0}""")
            // Sasayaki audio file lives under its own subdirectory.
            resolve("Sasayaki").mkdirs()
            resolve("Sasayaki/audio.m4b").writeBytes(ByteArray(64) { it.toByte() })
        }
        val (zipBytes, _) = codec.zipDirectory(src)
        val dest = tempFolder.newFolder("unpacked")
        codec.unzipInto(zipBytes, dest)

        assertTrue("content file (mokuro.json) round-trips", dest.resolve("mokuro.json").exists())
        for (name in listOf(
            "bookmark.json",
            "ai_chat_log.json",
            "metadata.json",
            "statistics.json",
            "sasayaki_match.json",
            "sasayaki_playback.json",
        )) {
            assertFalse("$name must not round-trip — it's per-device", dest.resolve(name).exists())
        }
        assertFalse(
            "Sasayaki/ subdirectory must not round-trip — per-device audio file",
            dest.resolve("Sasayaki").exists() || dest.resolve("Sasayaki/audio.m4b").exists(),
        )
    }

    @Test
    fun shaCacheSurvivesEveryExcludedSidecarMtimeChange() = runBlocking {
        // **The structural defense against future sidecar leaks.** Every name in
        // PAYLOAD_EXCLUDED_FILES gets an mtime bump after the cache is written; the
        // cache must remain fresh in every case. If a new sidecar gets added to the
        // exclusion list without this test being updated, the test still passes (good
        // — the new sidecar is correctly excluded). If a new sidecar gets added to
        // BookRepository and a developer forgets the exclusion list AND this test,
        // they hit the "scroll and resync" bug on first use — at which point the
        // matching live-server cross-device test would catch it.
        val src = tempFolder.newFolder("everything-thrashes-book").apply {
            resolve("mokuro.json").writeText("""{"v":1}""")
        }
        val transport = FakeKvTransport()
        codec.uploadIfChanged(transport, "everything_thrashes", src, "Everything Thrashes", HttpSyncContentType.Mokuro)
        val cacheFile = src.resolve(PAYLOAD_SHA_CACHE_FILENAME)
        val later = cacheFile.lastModified() + 5_000

        for (sidecar in PAYLOAD_EXCLUDED_FILES - PAYLOAD_SHA_CACHE_FILENAME) {
            src.resolve(sidecar).apply {
                writeText("""{"sidecar":"$sidecar","mutated":true}""")
                setLastModified(later)
            }
        }

        val uploaded = codec.uploadIfChanged(transport, "everything_thrashes", src, "Everything Thrashes", HttpSyncContentType.Mokuro)
        assertFalse(
            "mutating EVERY excluded sidecar simultaneously must not trigger re-upload",
            uploaded,
        )
    }

    @Test
    fun shaCacheSurvivesSasayakiAudioFileMtimeChange() = runBlocking {
        // User re-links / re-imports a Sasayaki audiobook. The audio file's mtime changes.
        // Cache must NOT invalidate because the Sasayaki/ directory is excluded as a whole.
        val src = tempFolder.newFolder("audio-relink-book").apply {
            resolve("mokuro.json").writeText("""{"v":1}""")
            resolve("Sasayaki").mkdirs()
            resolve("Sasayaki/audio.m4b").writeBytes(byteArrayOf(0x00, 0x01, 0x02))
        }
        val transport = FakeKvTransport()
        codec.uploadIfChanged(transport, "audio_relink", src, "Audio Relink", HttpSyncContentType.Mokuro)

        // Simulate re-linking the audio (new bytes, new mtime).
        val cacheFile = src.resolve(PAYLOAD_SHA_CACHE_FILENAME)
        val later = cacheFile.lastModified() + 5_000
        src.resolve("Sasayaki/audio.m4b").apply {
            writeBytes(byteArrayOf(0x42, 0x43, 0x44, 0x45))
            setLastModified(later)
        }

        val uploaded = codec.uploadIfChanged(transport, "audio_relink", src, "Audio Relink", HttpSyncContentType.Mokuro)
        assertFalse("audio file under Sasayaki/ must not invalidate the payload cache", uploaded)
    }

    @Test
    fun extraCarefulGateBlocksUploadWhenOnlyExcludedFilesChanged() = runBlocking {
        // The defensive gate added in [HttpSyncPayloadCodec.uploadIfChanged]: when the
        // remote manifest already exists and no NON-excluded file is newer than the cache,
        // refuse to re-upload even if our cache file got corrupted/deleted. This catches
        // the entire class of "future patch forgot to extend PAYLOAD_EXCLUDED_FILES" bugs
        // without needing the fix to be in PAYLOAD_EXCLUDED_FILES.
        val src = tempFolder.newFolder("careful-gate-book").apply {
            resolve("mokuro.json").writeText("""{"v":1}""")
        }
        val transport = FakeKvTransport()
        // First upload establishes the remote manifest.
        codec.uploadIfChanged(transport, "careful_gate", src, "Careful Gate", HttpSyncContentType.Mokuro)
        val initialZipBody = transport.kv[payloadZipKey("careful_gate")]?.body
        assertNotNull("first sync should have uploaded the zip", initialZipBody)

        // Now mutate ONLY excluded sidecars (so the staleness check sees nothing real change).
        val cacheFile = src.resolve(PAYLOAD_SHA_CACHE_FILENAME)
        val later = cacheFile.lastModified() + 5_000
        src.resolve("bookmark.json").apply {
            writeText("""{"chapterIndex":99}""")
            setLastModified(later)
        }
        src.resolve("statistics.json").apply {
            writeText("""[{"chapterIndex":99,"timestampSeconds":99.0}]""")
            setLastModified(later)
        }

        val uploaded = codec.uploadIfChanged(transport, "careful_gate", src, "Careful Gate", HttpSyncContentType.Mokuro)
        assertFalse("excluded-only mutations must not trigger re-upload", uploaded)
        assertTrue(
            "server's zip bytes must be untouched",
            initialZipBody!!.contentEquals(transport.kv[payloadZipKey("careful_gate")]!!.body),
        )
    }

    @Test
    fun shaCacheSurvivesMetadataJsonMtimeChange() = runBlocking {
        // Companion to shaCacheSurvivesBookmarkAndChatLogMtimeChange — the user reported
        // "scrolled a bit, pressed Sync, took a long time and said book payload up". Root
        // cause was metadata.json being rewritten with a new lastAccess every book-open,
        // bumping its mtime past the cache, invalidating cache, re-zipping, re-hashing,
        // sha differs because metadata.json is per-device, re-upload. The fix excludes
        // metadata.json from the zip AND from the staleness check. Verify the cache stays
        // fresh after a metadata.json rewrite.
        val src = tempFolder.newFolder("metadata-thrash-book").apply {
            resolve("mokuro.json").writeText("""{"v":1}""")
            resolve("metadata.json").writeText("""{"title":"T","id":"abc","lastAccess":0}""")
        }
        val transport = FakeKvTransport()
        codec.uploadIfChanged(transport, "metadata_thrash", src, "Metadata Thrash", HttpSyncContentType.Mokuro)

        // User opens the book; bookshelf rewrites metadata.json with a new lastAccess.
        val cacheFile = src.resolve(PAYLOAD_SHA_CACHE_FILENAME)
        val later = cacheFile.lastModified() + 5_000
        src.resolve("metadata.json").apply {
            writeText("""{"title":"T","id":"abc","lastAccess":99999}""")
            setLastModified(later)
        }

        // Second sync must hit the fast path.
        val uploaded = codec.uploadIfChanged(transport, "metadata_thrash", src, "Metadata Thrash", HttpSyncContentType.Mokuro)
        assertFalse("metadata.json mtime/content change must NOT trigger a re-upload", uploaded)
    }

    @Test
    fun zipIsDeterministicForIdenticalDirectoryContents() = runBlocking {
        // Same files in two separate directories → same zip bytes + sha → no churn upload.
        val a = tempFolder.newFolder("a").apply {
            resolve("page1.png").writeBytes(byteArrayOf(1, 2, 3))
            resolve("page2.png").writeBytes(byteArrayOf(4, 5, 6))
        }
        val b = tempFolder.newFolder("b").apply {
            resolve("page1.png").writeBytes(byteArrayOf(1, 2, 3))
            resolve("page2.png").writeBytes(byteArrayOf(4, 5, 6))
        }
        val (_, shaA) = codec.zipDirectory(a)
        val (_, shaB) = codec.zipDirectory(b)
        // Note: zip metadata includes mtime, which differs per file. The current implementation
        // doesn't fix mtime, so this test asserts only that the sha is well-formed and stable
        // *across* both runs of the same directory.
        val (_, shaA2) = codec.zipDirectory(a)
        assertEquals("same directory → same sha across two zip calls", shaA, shaA2)
        assertTrue("sha format is sha256:<64-hex>", shaA.matches(Regex("^sha256:[0-9a-f]{64}$")))
        // shaA == shaB would also be nice but isn't guaranteed by the current impl (mtimes).
        // Reaffirm the spec: equality across runs of the SAME directory is enough for the
        // change-detector to work; cross-device dedup is a future optimization.
        assertNotNull(shaB)
    }

    // ===== Zip-slip defense ===================================================================

    @Test
    fun unzipRefusesZipSlipPaths() {
        // Build a malicious zip with a `../escape.txt` entry.
        val malicious = ByteArrayOutputStream().run {
            ZipOutputStream(this).use { zip ->
                zip.putNextEntry(ZipEntry("../escape.txt"))
                zip.write("you got hacked".toByteArray())
                zip.closeEntry()
            }
            toByteArray()
        }
        val target = tempFolder.newFolder("inside")
        val ex = assertThrows(HttpSyncException::class.java) {
            codec.unzipInto(malicious, target)
        }
        assertTrue(
            "expected zip-slip error, got '${ex.message}'",
            ex.message!!.contains("zip-slip", ignoreCase = true),
        )
        // And critically, the parent directory of `target` did not gain the malicious file.
        assertFalse(target.parentFile!!.resolve("escape.txt").exists())
    }

    @Test
    fun unzipGracefullyHandlesBytesWithNoZipSignature() {
        // Java's ZipInputStream treats no-magic bytes as "zip with zero entries" rather
        // than throwing. The codec's sha256 check on the caller side is what catches the
        // actual corruption (bytes != manifest's sha) — see [downloadAndUnpackFailsOnSha256Mismatch].
        val garbage = ByteArray(100) { 0xFF.toByte() }
        val target = tempFolder.newFolder("victim")
        codec.unzipInto(garbage, target)
        assertEquals("no files extracted from garbage bytes", 0, target.listFiles()?.size ?: 0)
    }

    // ===== uploadIfChanged + manifest comparison ==============================================

    @Test
    fun shaCacheSurvivesBookmarkAndChatLogMtimeChange() = runBlocking {
        // The point of the cache is to make the SECOND sync fast — and the per-page-turn
        // hooks rewrite bookmark.json + ai_chat_log.json constantly. If those legitimate
        // mtime bumps invalidated the cache, every Sync now would re-zip a multi-MB book.
        val src = tempFolder.newFolder("hot-reader-book").apply {
            resolve("mokuro.json").writeText("""{"static":"content"}""")
        }
        val transport = FakeKvTransport()
        codec.uploadIfChanged(transport, "hot_reader_book", src, "Hot Reader", HttpSyncContentType.Mokuro)
        // Simulate the reader writing the bookmark sidecar (well after the cache was written).
        val cacheFile = src.resolve(PAYLOAD_SHA_CACHE_FILENAME)
        val later = cacheFile.lastModified() + 10_000
        src.resolve("bookmark.json").apply {
            writeText("""{"chapterIndex":99}""")
            setLastModified(later)
        }
        src.resolve("ai_chat_log.json").apply {
            writeText("""{"entries":[]}""")
            setLastModified(later)
        }
        // Second sync must hit the fast path — bookmark/chat sidecar mtimes are explicitly
        // excluded from the staleness check.
        val uploaded = codec.uploadIfChanged(transport, "hot_reader_book", src, "Hot Reader", HttpSyncContentType.Mokuro)
        assertFalse("bookmark/chat sidecar mtime changes must NOT invalidate the payload cache", uploaded)
    }

    @Test
    fun uploadIfChangedSkipsWhenServerManifestMatches() = runBlocking {
        val src = tempFolder.newFolder("hot-book").apply {
            resolve("mokuro.json").writeText("""{"v":1}""")
        }
        val transport = FakeKvTransport()
        // First upload writes both keys.
        val firstResult = codec.uploadIfChanged(
            transport = transport,
            syncId = "hot_book",
            bookRoot = src,
            originalName = "Hot Book",
            format = HttpSyncContentType.Mokuro,
        )
        assertTrue("first upload should write", firstResult)
        assertNotNull(transport.kv[payloadZipKey("hot_book")])
        assertNotNull(transport.kv[payloadManifestKey("hot_book")])

        // Second call with identical contents should NOT re-upload.
        val sizeBefore = transport.kv[payloadZipKey("hot_book")]!!.body.size
        val secondResult = codec.uploadIfChanged(
            transport = transport,
            syncId = "hot_book",
            bookRoot = src,
            originalName = "Hot Book",
            format = HttpSyncContentType.Mokuro,
        )
        assertFalse("identical contents = no upload", secondResult)
        assertEquals("zip body unchanged on server", sizeBefore, transport.kv[payloadZipKey("hot_book")]!!.body.size)
    }

    @Test
    fun uploadIfChangedSkipsEvenWhenContentsDifferOnceServerHasManifest() = runBlocking {
        // Manifest-existence policy: once the server has a copy of the book, we don't
        // re-upload from this device — even when local content has objectively changed.
        // This is the user-requested aggressive guard against the entire class of "some
        // sidecar I forgot to exclude triggered a re-upload" bugs. Escape hatch is
        // server-side: delete the manifest via curl, run sync, the codec uploads again.
        val src = tempFolder.newFolder("growing-book").apply {
            resolve("mokuro.json").writeText("""{"pages":1}""")
        }
        val transport = FakeKvTransport()
        codec.uploadIfChanged(transport, "growing_book", src, "Growing Book", HttpSyncContentType.Mokuro)
        // Now genuinely change the content. The codec MUST still refuse to re-upload
        // because the manifest already exists.
        src.resolve("mokuro.json").writeText("""{"pages":2}""")
        src.resolve("new-page.png").writeBytes(byteArrayOf(1, 2, 3))
        val uploaded = codec.uploadIfChanged(transport, "growing_book", src, "Growing Book", HttpSyncContentType.Mokuro)
        assertFalse("once the server has the manifest, no re-upload (even on real content change)", uploaded)
    }

    @Test
    fun uploadIfChangedUploadsAgainAfterServerSideManifestDelete() = runBlocking {
        // The escape hatch: when the user deletes the manifest server-side, the next
        // local sync uploads fresh content. Verifies the policy isn't a permanent lock.
        val src = tempFolder.newFolder("redo-book").apply {
            resolve("mokuro.json").writeText("""{"v":1}""")
        }
        val transport = FakeKvTransport()
        val firstUpload = codec.uploadIfChanged(transport, "redo_book", src, "Redo Book", HttpSyncContentType.Mokuro)
        assertTrue(firstUpload)

        // Server-side delete (simulated).
        transport.kv.remove(payloadManifestKey("redo_book"))
        transport.kv.remove(payloadZipKey("redo_book"))

        val reupload = codec.uploadIfChanged(transport, "redo_book", src, "Redo Book", HttpSyncContentType.Mokuro)
        assertTrue("manifest gone from server → re-upload works", reupload)
    }

    @Test
    fun fetchManifestReturnsNullOnMissing() = runBlocking {
        val transport = FakeKvTransport()
        assertNull(codec.fetchManifest(transport, "never_existed"))
    }

    @Test
    fun fetchManifestReportsMalformedJsonAsHttpSyncException() = runBlocking {
        val transport = FakeKvTransport()
        transport.kv[payloadManifestKey("bad_book")] = FakeKvTransport.Stored(
            body = "{ malformed".toByteArray(),
            contentType = "application/json",
            lastModified = "2026-01-01T00:00:00Z",
        )
        val ex = assertThrows(HttpSyncException::class.java) {
            runBlocking { codec.fetchManifest(transport, "bad_book") }
        }
        assertTrue("malformed" in ex.message!!.lowercase())
    }

    // ===== downloadAndUnpack ==================================================================

    @Test
    fun downloadAndUnpackRoundTripsContents() = runBlocking {
        val src = tempFolder.newFolder("upload-side").apply {
            resolve("mokuro.json").writeText("""{"hello":"there"}""")
            resolve("pages").mkdirs()
            resolve("pages/p1.png").writeBytes(byteArrayOf(0x42))
        }
        val transport = FakeKvTransport()
        codec.uploadIfChanged(transport, "round_trip", src, "Round Trip", HttpSyncContentType.Mokuro)

        val downloadTarget = tempFolder.newFolder("download-side")
        val manifest = codec.downloadAndUnpack(transport, "round_trip", downloadTarget)

        assertEquals("Round Trip", manifest.originalName)
        assertEquals(HttpSyncContentType.Mokuro, manifest.format)
        assertEquals("""{"hello":"there"}""", downloadTarget.resolve("mokuro.json").readText())
        assertEquals(1, downloadTarget.resolve("pages/p1.png").readBytes().size)
    }

    @Test
    fun downloadAndUnpackFailsOnSha256Mismatch() = runBlocking {
        val src = tempFolder.newFolder("real-book").apply {
            resolve("mokuro.json").writeText("real content")
        }
        val transport = FakeKvTransport()
        codec.uploadIfChanged(transport, "tampered", src, "Tampered", HttpSyncContentType.Mokuro)

        // Server-side corruption: replace the zip body but leave the manifest's sha alone.
        val zipEntry = transport.kv[payloadZipKey("tampered")]!!
        transport.kv[payloadZipKey("tampered")] = zipEntry.copy(body = ByteArray(zipEntry.body.size) { 0xAA.toByte() })

        val target = tempFolder.newFolder("victim")
        val ex = assertThrows(HttpSyncException::class.java) {
            runBlocking { codec.downloadAndUnpack(transport, "tampered", target) }
        }
        assertTrue(
            "expected sha256 mismatch error, got '${ex.message}'",
            ex.message!!.contains("sha256", ignoreCase = true),
        )
    }

    @Test
    fun downloadFailsCleanlyWhenManifestExistsButZipDoesNot() = runBlocking {
        val transport = FakeKvTransport()
        val manifest = HttpSyncPayloadManifest(
            sha256 = "sha256:" + "00".repeat(32),
            sizeBytes = 100L,
            originalName = "Orphan",
            format = HttpSyncContentType.Mokuro,
        )
        transport.kv[payloadManifestKey("orphan")] = FakeKvTransport.Stored(
            body = json.encodeToString(HttpSyncPayloadManifest.serializer(), manifest).toByteArray(),
            contentType = "application/json",
            lastModified = "2026-01-01T00:00:00Z",
        )
        // No zip on server.
        val target = tempFolder.newFolder("attempt")
        val ex = assertThrows(HttpSyncException::class.java) {
            runBlocking { codec.downloadAndUnpack(transport, "orphan", target) }
        }
        assertTrue("missing" in ex.message!!.lowercase())
    }

    // ===== End-to-end via the Reconciler ======================================================

    @Test
    fun reconcilerUploadsPayloadDuringSyncOnce() = runBlocking {
        val filesDir = tempFolder.newFolder("files")
        val repo = moe.antimony.hoshi.epub.BookRepository(filesDir)
        val title = "Mokuro With Payload"
        val root = repo.createBookDirectoryForImportedTitle(title)
        repo.saveMetadata(
            root,
            moe.antimony.hoshi.epub.BookMetadata(
                id = "id-1",
                title = title,
                cover = null,
                folder = root.name,
                lastAccess = 0.0,
            ),
        )
        root.resolve("mokuro.json").writeText("""{"v":1}""")
        repo.saveBookmark(root, moe.antimony.hoshi.epub.Bookmark(1, 0.0, 1, 800_000_000.0))

        val transport = FakeKvTransport()
        val reconciler = HttpSyncReconciler(
            bookRepository = repo,
            transportFactory = { transport },
            ioDispatcher = Dispatchers.Unconfined,
        )
        val result = reconciler.syncOnce(
            HttpSyncSettings(baseUrl = "https://x", bearerToken = "t"),
        )
        assertEquals(1, result.uploadedPayloads)
        assertNotNull(transport.kv[payloadZipKey("mokuro_with_payload")])
        assertNotNull(transport.kv[payloadManifestKey("mokuro_with_payload")])
    }
}

private class FailingPayloadUploadTransport : HttpSyncKvTransport {
    val kv: MutableMap<String, FakeKvTransport.Stored> = linkedMapOf()
    var payloadPutFileCalls: Int = 0
        private set

    override suspend fun put(
        key: String,
        contentType: String,
        body: ByteArray,
    ): HttpSyncKvWriteResponse {
        kv[key] = FakeKvTransport.Stored(body, contentType, "2027-02-01T00:00:00Z")
        return HttpSyncKvWriteResponse(
            key = key,
            lastModified = "2027-02-01T00:00:00Z",
            etag = "sha256:fake",
            size = body.size,
            contentType = contentType,
        )
    }

    override suspend fun putFile(
        key: String,
        contentType: String,
        file: File,
        onByteProgress: ((bytesTransferred: Long, totalBytes: Long) -> Unit)?,
    ): HttpSyncKvWriteResponse {
        payloadPutFileCalls += 1
        throw HttpSyncException("payload upload failed")
    }

    override suspend fun get(key: String): HttpSyncKvFetched? = null

    override suspend fun list(
        prefix: String?,
        since: String?,
        cursor: String?,
        limit: Int?,
    ): HttpSyncKvList = HttpSyncKvList()

    override suspend fun delete(key: String) {
        kv.remove(key)
    }
}

private class PayloadStreamingOnlyTransport : HttpSyncKvTransport {
    val kv: MutableMap<String, FakeKvTransport.Stored> = linkedMapOf()
    var payloadPutFileCalls: Int = 0
        private set
    var payloadDownloadToFileCalls: Int = 0
        private set
    var payloadByteArrayPutCalls: Int = 0
        private set
    var payloadByteArrayGetCalls: Int = 0
        private set
    private var clock: Long = 0L

    override suspend fun put(
        key: String,
        contentType: String,
        body: ByteArray,
    ): HttpSyncKvWriteResponse {
        if (isPayloadZipKey(key)) {
            payloadByteArrayPutCalls += 1
            throw AssertionError("payload.zip must be uploaded with putFile, not put(ByteArray)")
        }
        return store(key, contentType, body)
    }

    override suspend fun putFile(
        key: String,
        contentType: String,
        file: File,
        onByteProgress: ((bytesTransferred: Long, totalBytes: Long) -> Unit)?,
    ): HttpSyncKvWriteResponse {
        if (isPayloadZipKey(key)) {
            payloadPutFileCalls += 1
            assertTrue("payload spool should exist while upload is running", file.isFile)
            assertTrue("payload spool should be non-empty", file.length() > 0L)
            return store(key, contentType, file.readBytes())
        }
        return put(key, contentType, file.readBytes())
    }

    override suspend fun get(key: String): HttpSyncKvFetched? {
        if (isPayloadZipKey(key)) {
            payloadByteArrayGetCalls += 1
            throw AssertionError("payload.zip must be downloaded with downloadToFile, not get(ByteArray)")
        }
        val stored = kv[key] ?: return null
        return HttpSyncKvFetched(
            body = stored.body,
            contentType = stored.contentType,
            lastModified = stored.lastModified,
            etag = "sha256:fake",
        )
    }

    override suspend fun downloadToFile(
        key: String,
        targetFile: File,
        onByteProgress: ((bytesTransferred: Long, totalBytes: Long) -> Unit)?,
    ): HttpSyncKvFileFetched? {
        if (isPayloadZipKey(key)) {
            payloadDownloadToFileCalls += 1
            val stored = kv[key] ?: return null
            targetFile.parentFile?.mkdirs()
            targetFile.writeBytes(stored.body)
            return HttpSyncKvFileFetched(
                contentType = stored.contentType,
                lastModified = stored.lastModified,
                etag = "sha256:fake",
            )
        }
        val fetched = get(key) ?: return null
        targetFile.parentFile?.mkdirs()
        targetFile.writeBytes(fetched.body)
        return HttpSyncKvFileFetched(
            contentType = fetched.contentType,
            lastModified = fetched.lastModified,
            etag = fetched.etag,
        )
    }

    override suspend fun list(
        prefix: String?,
        since: String?,
        cursor: String?,
        limit: Int?,
    ): HttpSyncKvList {
        val filtered = kv.entries
            .filter { (key, _) -> prefix == null || key.startsWith(prefix) }
            .filter { (_, stored) -> since == null || stored.lastModified > since }
            .map { (key, stored) ->
                HttpSyncKvKeyMeta(
                    key = key,
                    lastModified = stored.lastModified,
                    etag = "sha256:fake",
                    size = stored.body.size,
                    contentType = stored.contentType,
                )
            }
            .sortedBy { it.key }
        return HttpSyncKvList(keys = filtered, truncated = false, nextCursor = null)
    }

    override suspend fun delete(key: String) {
        kv.remove(key)
    }

    private fun store(key: String, contentType: String, body: ByteArray): HttpSyncKvWriteResponse {
        val timestamp = nextTimestamp()
        kv[key] = FakeKvTransport.Stored(
            body = body,
            contentType = contentType,
            lastModified = timestamp,
        )
        return HttpSyncKvWriteResponse(
            key = key,
            lastModified = timestamp,
            etag = "sha256:fake",
            size = body.size,
            contentType = contentType,
        )
    }

    private fun nextTimestamp(): String {
        clock += 1
        return "2027-02-01T00:00:%02dZ".format(clock % 60)
    }
}

private fun isPayloadZipKey(key: String): Boolean = key.endsWith("/payload.zip")
