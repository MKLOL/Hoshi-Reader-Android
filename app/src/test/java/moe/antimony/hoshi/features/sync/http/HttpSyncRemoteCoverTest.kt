package moe.antimony.hoshi.features.sync.http

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.random.Random

/** A [FakeKvTransport] that also answers byte ranges, counting what it served. */
internal class RangeFakeKvTransport(
    val delegate: FakeKvTransport = FakeKvTransport(),
) : HttpSyncKvTransport by delegate {
    var rangedBytes = 0L
        private set
    var rangeRequests = 0
        private set

    override suspend fun getRange(key: String, start: Long?, length: Int): HttpSyncKvRange? {
        val body = delegate.kv[key]?.body ?: return null
        val from = start ?: maxOf(0L, body.size.toLong() - length)
        if (from >= body.size) return null
        val to = minOf(body.size.toLong(), from + length)
        rangeRequests += 1
        rangedBytes += to - from
        return HttpSyncKvRange(body.copyOfRange(from.toInt(), to.toInt()), from, body.size.toLong())
    }
}

class HttpSyncRemoteCoverTest {
    private val page1 = Random(1).nextBytes(40_000)
    private val page2 = Random(2).nextBytes(30_000)
    private val page10 = Random(10).nextBytes(20_000)

    private fun zip(vararg entries: Pair<String, ByteArray>, stored: Set<String> = emptySet()): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            for ((name, bytes) in entries) {
                val entry = ZipEntry(name)
                if (name in stored) {
                    entry.method = ZipEntry.STORED
                    entry.size = bytes.size.toLong()
                    entry.compressedSize = bytes.size.toLong()
                    entry.crc = CRC32().apply { update(bytes) }.value
                }
                zip.putNextEntry(entry)
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun mokuro(vararg pages: String): ByteArray =
        pages.joinToString(prefix = """{"title":"T","pages":[""", postfix = "]}") { path ->
            """{"img_path":"$path","img_width":10,"img_height":10,"blocks":[{"box":[1,2,3,4],"lines":["あ"]}]}"""
        }.toByteArray()

    private fun serve(archive: ByteArray): RangeFakeKvTransport = RangeFakeKvTransport().apply {
        runBlocking { put("books/m/payload.zip", "application/zip", archive) }
    }

    @Test
    fun readsTheFirstMokuroPageWithoutDownloadingTheArchive() = runBlocking {
        // The sidecar names page 2 first; only it may be the cover, whatever the file names say.
        val padding = Random(3).nextBytes(2_000_000)
        val archive = zip(
            "images/0001.jpg" to page1,
            "images/0002.jpg" to page2,
            "images/padding.bin" to padding,
            "mokuro.json" to mokuro("images/0002.jpg", "images/0001.jpg"),
            stored = setOf("images/0001.jpg", "images/0002.jpg", "images/padding.bin"),
        )
        val transport = serve(archive)

        val cover = fetchRemoteMangaCover(transport, "books/m/payload.zip", archive.size.toLong())

        assertArrayEquals(page2, cover)
        assertTrue("read ${transport.rangedBytes} of ${archive.size} bytes", transport.rangedBytes < archive.size / 10)
    }

    @Test
    fun inflatesDeflatedCoverEntries() = runBlocking {
        val compressible = ByteArray(50_000) { (it % 7).toByte() }
        val archive = zip("mokuro.json" to mokuro("images/0001.jpg"), "images/0001.jpg" to compressible)

        val cover = fetchRemoteMangaCover(serve(archive), "books/m/payload.zip", archive.size.toLong())

        assertArrayEquals(compressible, cover)
    }

    @Test
    fun withoutAUsableSidecarTheFirstPageInNaturalOrderIsTheCover() = runBlocking {
        val archive = zip(
            "mokuro.json" to "not json".toByteArray(),
            "images/page10.jpg" to page10,
            "images/page2.jpg" to page2,
            "notes.txt" to "x".toByteArray(),
        )

        val cover = fetchRemoteMangaCover(serve(archive), "books/m/payload.zip", archive.size.toLong())

        assertArrayEquals(page2, cover)
    }

    @Test
    fun aServerWithoutRangesOrAChangedArchiveGivesNoCover() = runBlocking {
        val archive = zip("mokuro.json" to mokuro("images/0001.jpg"), "images/0001.jpg" to page1)
        val noRanges = FakeKvTransport().apply { put("books/m/payload.zip", "application/zip", archive) }

        assertNull(fetchRemoteMangaCover(noRanges, "books/m/payload.zip", archive.size.toLong()))
        // The manifest describes a different archive than the one stored now.
        assertNull(fetchRemoteMangaCover(serve(archive), "books/m/payload.zip", archive.size + 1L))
        assertNull(fetchRemoteMangaCover(serve(archive), "books/missing/payload.zip", archive.size.toLong()))
    }

    @Test
    fun aCorruptedEntryIsRejectedByItsChecksum() = runBlocking {
        val archive = zip(
            "mokuro.json" to mokuro("images/0001.jpg"),
            "images/0001.jpg" to page1,
            stored = setOf("images/0001.jpg"),
        )
        // Flip one byte inside the stored page data (after its 30-byte header and name).
        val start = indexOf(archive, page1.copyOfRange(0, 64))
        archive[start + 100] = (archive[start + 100].toInt() xor 0xFF).toByte()

        assertNull(fetchRemoteMangaCover(serve(archive), "books/m/payload.zip", archive.size.toLong()))
    }

    @Test
    fun zip64ExtraFieldsCarryTheRealSizesAndOffset() {
        val name = "images/0001.jpg".toByteArray()
        val extra = ByteBuffer.allocate(4 + 24).order(ByteOrder.LITTLE_ENDIAN)
            .putShort(1).putShort(24).putLong(70_000L).putLong(60_000L).putLong(5_000_000_000L).array()
        val header = ByteBuffer.allocate(46).order(ByteOrder.LITTLE_ENDIAN).apply {
            putInt(0x02014b50)
            putShort(45); putShort(45); putShort(0); putShort(8)
            putShort(0); putShort(0)
            putInt(1234)
            putInt(-1); putInt(-1)
            putShort(name.size.toShort()); putShort(extra.size.toShort()); putShort(0)
            putShort(0); putShort(0); putInt(0)
            putInt(-1)
        }.array()

        val entry = parseCentralDirectory(header + name + extra)!!.single()

        assertEquals("images/0001.jpg", entry.name)
        assertEquals(70_000L, entry.size)
        assertEquals(60_000L, entry.compressedSize)
        assertEquals(5_000_000_000L, entry.localHeaderOffset)
        assertEquals(1234L, entry.crc)
    }

    @Test
    fun naturalOrderComparesDigitRunsByValue() {
        val names = listOf("p10.jpg", "p2.jpg", "P1.jpg", "p02a.jpg", "cover.jpg")
        assertEquals(listOf("cover.jpg", "P1.jpg", "p2.jpg", "p02a.jpg", "p10.jpg"), names.sortedWith { a, b -> compareNatural(a, b) })
    }

    @Test
    fun firstMokuroPagePathIsNormalizedToAnEntryName() {
        assertEquals("images/a.jpg", firstMokuroPagePath(mokuro("./images\\\\a.jpg")))
        assertNull(firstMokuroPagePath("""{"pages":[]}""".toByteArray()))
        assertNull(firstMokuroPagePath("[".toByteArray()))
    }

    private fun indexOf(haystack: ByteArray, needle: ByteArray): Int {
        outer@ for (i in 0..haystack.size - needle.size) {
            for (j in needle.indices) if (haystack[i + j] != needle[j]) continue@outer
            return i
        }
        error("not found")
    }
}
