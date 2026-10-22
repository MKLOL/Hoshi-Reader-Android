package moe.antimony.hoshi.features.sync.v3

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import moe.antimony.hoshi.epub.BookRepository
import moe.antimony.hoshi.features.sync.http.HttpSyncCloudBooks
import moe.antimony.hoshi.features.sync.http.HttpSyncContentType
import moe.antimony.hoshi.features.sync.http.HttpSyncKvClient
import moe.antimony.hoshi.features.sync.http.HttpSyncMetadataBlob
import moe.antimony.hoshi.features.sync.http.HttpSyncPayloadCodec
import moe.antimony.hoshi.features.sync.http.HttpSyncPayloadManifest
import moe.antimony.hoshi.features.sync.http.HttpSyncSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream

/** Real HTTP and Android image decoding: the shelf cover must never touch the ZIP. */
@RunWith(AndroidJUnit4::class)
class V3CloudCoverInstrumentedTest {
    @get:Rule val temp = TemporaryFolder()

    @Test(timeout = 30_000)
    fun aCloudMangaUsesItsStoredThumbnailWithoutDownloadingTheArchive() = runBlocking {
        StubKvServer().use { server ->
            server.start()
            val client = HttpSyncKvClient(server.baseUrl, server.token)
            val json = Json { encodeDefaults = true }
            val hash = "a".repeat(64)
            val coverKey = "books/cloud_manga/cover/$hash"
            val bitmap = Bitmap.createBitmap(160, 240, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(Color.RED)
            val cover = ByteArrayOutputStream().use { output ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 85, output)
                output.toByteArray()
            }
            bitmap.recycle()
            client.put("books/cloud_manga/metadata", "application/json", json.encodeToString(
                HttpSyncMetadataBlob.serializer(), HttpSyncMetadataBlob("Cloud manga", HttpSyncContentType.Mokuro),
            ).toByteArray())
            client.put("books/cloud_manga/payload.manifest", "application/json", json.encodeToString(
                HttpSyncPayloadManifest.serializer(),
                HttpSyncPayloadManifest("sha256:$hash", 200_000_000, "Cloud manga", HttpSyncContentType.Mokuro),
            ).toByteArray())
            client.put(coverKey, "image/jpeg", cover)
            // Deliberately no payload.zip: a cover lookup cannot accidentally fall back to it.
            val repo = BookRepository(temp.newFolder("receiver"))
            val cloud = HttpSyncCloudBooks(repo.booksDirectory)
            val engine = V3SyncEngine(
                bookRepository = repo,
                payloadCodec = HttpSyncPayloadCodec(Dispatchers.IO),
                transportFactory = { client },
                cloudBooks = cloud,
            )
            val result = engine.syncOnce(HttpSyncSettings(server.baseUrl, server.token))

            assertTrue("sync errors: ${result.errors}", result.errors.isEmpty())
            val record = cloud.records().single()
            val file = cloud.coverFile(record)
            assertNotNull(file)
            val decoded = BitmapFactory.decodeFile(file!!.path)
            assertNotNull(decoded)
            assertEquals(160, decoded.width)
            assertEquals(240, decoded.height)
            assertTrue(Color.red(decoded.getPixel(0, 0)) > 240)
            decoded.recycle()
            assertEquals(1, server.requests().count { it.method == "GET" && it.path == "/v1/kv/$coverKey" })
            assertTrue(server.requests().none { it.range != null || it.path.endsWith("payload.zip") })
        }
    }
}
