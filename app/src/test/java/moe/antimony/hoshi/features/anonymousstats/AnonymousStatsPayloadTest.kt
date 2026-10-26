package moe.antimony.hoshi.features.anonymousstats

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import moe.antimony.hoshi.epub.BookMetadata
import moe.antimony.hoshi.epub.BookRepository
import moe.antimony.hoshi.epub.DeviceIdentity
import moe.antimony.hoshi.epub.MOKURO_SIDECAR_FILE
import moe.antimony.hoshi.features.reader.ReaderSettings
import moe.antimony.hoshi.features.sync.SyncSettings
import moe.antimony.hoshi.features.sync.http.HttpSyncCloudBookListing
import moe.antimony.hoshi.features.sync.http.HttpSyncCloudBooks
import moe.antimony.hoshi.features.sync.http.HttpSyncContentType
import moe.antimony.hoshi.features.sync.http.HttpSyncSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Locale
import java.util.UUID

class AnonymousStatsPayloadTest {
    @get:Rule val temp = TemporaryFolder()

    @Test
    fun aCompleteReportCarriesExactlyTheContractKeys() {
        val json = parse(anonymousStatsPayload(INSTALL_ID, fullDevice(), fullLibrary(), fullFlags()).toJson())

        assertEquals(CONTRACT_KEYS, json.keys)
        assertEquals("android", json.text("platform"))
        assertEquals(INSTALL_ID, json.text("installId"))
        assertEquals("zh-CN", json.text("appLanguage"))
    }

    @Test
    fun theScreenWidthIsAlwaysTheShorterSide() {
        val landscape = anonymousStatsPayload(INSTALL_ID, AnonymousStatsDevice(screenWidthPx = 2400, screenHeightPx = 1080), AnonymousStatsLibrary(), AnonymousStatsFlags())
        val portrait = anonymousStatsPayload(INSTALL_ID, AnonymousStatsDevice(screenWidthPx = 1080, screenHeightPx = 2400), AnonymousStatsLibrary(), AnonymousStatsFlags())
        val square = anonymousStatsPayload(INSTALL_ID, AnonymousStatsDevice(screenWidthPx = 1800, screenHeightPx = 1800), AnonymousStatsLibrary(), AnonymousStatsFlags())

        assertEquals(1080 to 2400, landscape.screenWidthPx to landscape.screenHeightPx)
        assertEquals(1080 to 2400, portrait.screenWidthPx to portrait.screenHeightPx)
        assertEquals(1800 to 1800, square.screenWidthPx to square.screenHeightPx)
        assertTrue(listOf(landscape, portrait, square).all { it.screenWidthPx!! <= it.screenHeightPx!! })
    }

    @Test
    fun factsThatCouldNotBeReadAreLeftOut() {
        val halfScreen = anonymousStatsPayload(INSTALL_ID, AnonymousStatsDevice(screenWidthPx = 1080), AnonymousStatsLibrary(), AnonymousStatsFlags())
        val json = parse(anonymousStatsPayload(INSTALL_ID, AnonymousStatsDevice(), AnonymousStatsLibrary(), AnonymousStatsFlags()).toJson())

        assertNull(halfScreen.screenWidthPx)
        assertNull(halfScreen.screenHeightPx)
        // Only the required fields: an unreadable app language is not reported as "system".
        assertEquals(setOf("installId", "platform"), json.keys)
    }

    @Test
    fun anAppFollowingTheSystemLanguageSaysSo() {
        val payload = anonymousStatsPayload(INSTALL_ID, AnonymousStatsDevice(appLanguage = APP_LANGUAGE_SYSTEM), AnonymousStatsLibrary(), AnonymousStatsFlags())

        assertEquals("system", parse(payload.toJson()).text("appLanguage"))
    }

    @Test
    fun languageTagsDropRegionalPreferenceExtensions() {
        assertEquals("en-US", languageTag(Locale.forLanguageTag("en-US-u-mu-celsius")))
        assertEquals("zh-Hans-CN", languageTag(Locale.forLanguageTag("zh-Hans-CN-u-fw-mon-mu-fahrenhe")))
        assertEquals("ja-JP", languageTag(Locale.forLanguageTag("ja-JP-u-ca-japanese-x-private")))
        assertEquals("de", languageTag(Locale.GERMAN))
        assertNull(languageTag(Locale.ROOT))
        assertNull(languageTag(null))
    }

    @Test
    fun theReportNeverCarriesNamesTokensServerOrTitles() = runBlocking {
        val filesDir = temp.newFolder("files")
        val books = BookRepository(filesDir, deviceIdentity = DeviceIdentity(SYNC_DEVICE_ID, DEVICE_NAME))
        val epub = book(books, "Secret Epub Title", "secret_epub")
        val manga = book(books, "Secret Manga Title", "secret_manga").apply { resolve(MOKURO_SIDECAR_FILE).writeText("{}") }
        val cloud = cloudCatalogue(books, "secret_cloud", "secret_manga")
        val collector = collector(
            books,
            cloud,
            HttpSyncSettings(baseUrl = PRIVATE_SERVER, bearerToken = SYNC_TOKEN),
        )

        val body = collector.collect(INSTALL_ID).toJson()
        val json = parse(body)

        assertEquals(CONTRACT_KEYS, json.keys)
        assertEquals("1", json.raw("epubCount"))
        assertEquals("1", json.raw("mangaCount"))
        // The installed manga is listed by the server too, but the shelf shows only the other one.
        assertEquals("1", json.raw("cloudOnlyCount"))
        assertEquals("3", json.raw("dictionaryCount"))
        assertEquals("true", json.raw("httpSyncEnabled"))
        assertEquals("true", json.raw("driveSyncEnabled"))
        assertEquals("true", json.raw("readerContinuous"))
        assertEquals("false", json.raw("readerVertical"))
        listOf(
            "Secret", "secret_", epub.name, manga.name, "Cloud ",
            SYNC_TOKEN, PRIVATE_SERVER, "private-sync.example.invalid", SYNC_DEVICE_ID, DEVICE_NAME,
        ).forEach { forbidden -> assertFalse("report contains $forbidden: $body", body.contains(forbidden)) }
    }

    @Test
    fun cloudOnlyBooksCountOnlyWhereTheShelfShowsThem() = runBlocking {
        val books = BookRepository(temp.newFolder("files"))
        val cloud = cloudCatalogue(books, "cloud_a", "cloud_b")

        suspend fun count(settings: HttpSyncSettings) = collector(books, cloud, settings).collect(INSTALL_ID).cloudOnlyCount

        assertEquals(2, count(HttpSyncSettings(bearerToken = SYNC_TOKEN)))
        // No server to download them from, and the v2 rollback engine keeps no catalogue.
        assertEquals(0, count(HttpSyncSettings(bearerToken = "")))
        assertEquals(0, count(HttpSyncSettings(bearerToken = SYNC_TOKEN, useV3Sync = false)))
    }

    @Test
    fun onlyEmulatorBuildsLookLikeEmulators() {
        assertTrue(looksLikeEmulator("google/sdk_gphone64_arm64/emu64a:14/UE1A/1:userdebug/dev-keys", "ranchu", "sdk_gphone64_arm64", "sdk_gphone64_arm64", "Google"))
        assertTrue(looksLikeEmulator("generic_x86/sdk_x86/generic_x86:9/PSR1/1:userdebug/test-keys", "goldfish", "sdk_x86", "Android SDK built for x86", "unknown"))
        assertTrue(looksLikeEmulator("genymotion/vbox86p/vbox86p:11/RQ1A/1:user/release-keys", "vbox86", "vbox86p", "Pixel 3", "Genymotion"))
        assertFalse(looksLikeEmulator("google/panther/panther:14/AP2A/1:user/release-keys", "panther", "panther", "Pixel 7", "Google"))
        assertFalse(looksLikeEmulator("samsung/dm3qxeea/dm3q:14/UP1A/1:user/release-keys", "qcom", "dm3qxeea", "SM-S918B", "samsung"))
        assertFalse(looksLikeEmulator("Onyx/NoteAir3C/NoteAir3C:12/2023/1:user/release-keys", "qcom", "NoteAir3C", "NoteAir3C", "ONYX"))
        assertFalse(looksLikeEmulator(null, null, null, null, null))
    }

    private suspend fun book(books: BookRepository, title: String, syncId: String): File {
        val root = books.createBookDirectoryForImportedTitle(title)
        books.saveMetadata(
            root,
            BookMetadata(id = UUID.randomUUID().toString(), title = title, cover = null, folder = root.name, lastAccess = 0.0, syncId = syncId),
        )
        return root
    }

    private fun cloudCatalogue(books: BookRepository, vararg syncIds: String) =
        HttpSyncCloudBooks(books.booksDirectory, coverThumbnail = { it }, ioDispatcher = Dispatchers.Unconfined).apply {
            recordListed(
                syncIds.map { syncId ->
                    HttpSyncCloudBookListing(syncId, "Cloud $syncId", HttpSyncContentType.Mokuro, 100, "sha256:" + "1".repeat(64), null, null)
                },
            )
        }

    private fun collector(books: BookRepository, cloud: HttpSyncCloudBooks, httpSync: HttpSyncSettings) =
        AnonymousStatsCollector(
            bookRepository = books,
            cloudBooks = cloud,
            dictionaryCount = { 3 },
            httpSyncSettings = flowOf(httpSync),
            driveSyncSettings = flowOf(SyncSettings(enabled = true)),
            readerSettings = flowOf(ReaderSettings(continuousMode = true, verticalWriting = false)),
            device = ::fullDevice,
        )

    private fun parse(body: String): JsonObject = Json.parseToJsonElement(body).jsonObject

    private fun JsonObject.text(key: String): String = getValue(key).jsonPrimitive.content

    private fun JsonObject.raw(key: String): String = getValue(key).jsonPrimitive.toString()

    private companion object {
        const val INSTALL_ID = "0b6a8a43-3c1e-4c55-9d7e-5f3b2f1a9c11"
        const val SYNC_DEVICE_ID = "3f2c7d0e-sync-installation-id"
        const val DEVICE_NAME = "Dragos's Reading Tablet"
        const val SYNC_TOKEN = "fixture-secret-token"
        const val PRIVATE_SERVER = "https://private-sync.example.invalid/api/book_sync"
    }
}
