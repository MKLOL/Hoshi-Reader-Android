package moe.antimony.hoshi.features.sync.http

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.random.Random

/**
 * Answers ranges the way the production server (`kv_routes._download_range`) does, behind the
 * exact preconditions of [HttpSyncKvClient.getRange]:
 *  - `require(length > 0)` and `require(start == null || start >= 0)` (IllegalArgumentException),
 *  - a header whose end overflowed (`bytes=X--Y`) does not match the server's regex: the server
 *    ignores it and answers 200, which the client turns into null,
 *  - a start at or past the end is 416 (null); an end past the end is clamped (shorter body).
 */
internal class ProductionRangeTransport(
    private val read: (start: Long, length: Int) -> ByteArray,
    private val size: Long,
) : HttpSyncKvTransport by FakeKvTransport() {
    constructor(archive: ByteArray) : this(
        { start, length -> archive.copyOfRange(start.toInt(), (start + length).toInt()) },
        archive.size.toLong(),
    )

    var requests = 0
        private set
    var bytes = 0L
        private set

    override suspend fun getRange(key: String, start: Long?, length: Int): HttpSyncKvRange? {
        require(length > 0) { "Range length must be positive." }
        require(start == null || start >= 0) { "Range start must not be negative." }
        requests += 1
        if (start != null && start + length - 1 < start) return null // malformed header: 200
        val from = start ?: maxOf(0L, size - length)
        if (from >= size) return null // 416
        val to = minOf(size, from + length)
        bytes += to - from
        return HttpSyncKvRange(read(from, (to - from).toInt()), from, size)
    }

    companion object {
        fun of(file: File): ProductionRangeTransport {
            val raf = RandomAccessFile(file, "r")
            return ProductionRangeTransport({ start, length ->
                ByteArray(length).also { raf.seek(start); raf.readFully(it) }
            }, file.length())
        }
    }
}

/**
 * A ZIP writer that emits exactly the records a test asks for, so ZIP64 end records, partial
 * ZIP64 extras and hostile field values can be produced on purpose.
 */
internal class RawZip {
    class Entry(
        var name: ByteArray,
        var method: Int,
        var crc: Long,
        var compressedSize: Long,
        var size: Long,
        var localOffset: Long,
        var extra: ByteArray = ByteArray(0),
    )

    private val body = ByteArrayOutputStream()
    val entries = mutableListOf<Entry>()

    fun add(name: String, data: ByteArray, deflate: Boolean = false, nameBytes: ByteArray = name.toByteArray()): Entry {
        val payload = if (deflate) rawDeflate(data) else data
        val crc = CRC32().apply { update(data) }.value
        val entry = Entry(nameBytes, if (deflate) 8 else 0, crc, payload.size.toLong(), data.size.toLong(), body.size().toLong())
        body.u32(0x04034b50); body.u16(20); body.u16(0); body.u16(entry.method); body.u16(0); body.u16(0)
        body.u32(crc); body.u32(payload.size.toLong()); body.u32(data.size.toLong())
        body.u16(nameBytes.size); body.u16(0); body.write(nameBytes); body.write(payload)
        entries += entry
        return entry
    }

    class Zip64End(var recordOffset: Long? = null, var directoryOffset: Long? = null, var directorySize: Long? = null)

    fun finish(comment: ByteArray = ByteArray(0), zip64: Zip64End? = null): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(body.toByteArray())
        val directoryOffset = out.size().toLong()
        for (e in entries) {
            out.u32(0x02014b50); out.u16(45); out.u16(45); out.u16(0); out.u16(e.method); out.u16(0); out.u16(0)
            out.u32(e.crc); out.u32(e.compressedSize); out.u32(e.size)
            out.u16(e.name.size); out.u16(e.extra.size); out.u16(0); out.u16(0); out.u16(0); out.u32(0)
            out.u32(e.localOffset); out.write(e.name); out.write(e.extra)
        }
        val directorySize = out.size() - directoryOffset
        if (zip64 != null) {
            val recordOffset = out.size().toLong()
            out.u32(0x06064b50); out.u64(44); out.u16(45); out.u16(45); out.u32(0); out.u32(0)
            out.u64(entries.size.toLong()); out.u64(entries.size.toLong())
            out.u64(zip64.directorySize ?: directorySize); out.u64(zip64.directoryOffset ?: directoryOffset)
            out.u32(0x07064b50); out.u32(0); out.u64(zip64.recordOffset ?: recordOffset); out.u32(1)
            out.u32(0x06054b50); out.u16(0); out.u16(0); out.u16(0xFFFF); out.u16(0xFFFF)
            out.u32(0xFFFFFFFFL); out.u32(0xFFFFFFFFL); out.u16(comment.size)
        } else {
            out.u32(0x06054b50); out.u16(0); out.u16(0); out.u16(entries.size); out.u16(entries.size)
            out.u32(directorySize); out.u32(directoryOffset); out.u16(comment.size)
        }
        out.write(comment)
        return out.toByteArray()
    }

    companion object {
        fun zip64Extra(vararg values: Long): ByteArray = ByteArrayOutputStream().apply {
            u16(1); u16(values.size * 8); values.forEach { u64(it) }
        }.toByteArray()

        fun rawDeflate(data: ByteArray): ByteArray {
            val deflater = Deflater(Deflater.DEFAULT_COMPRESSION, true)
            deflater.setInput(data); deflater.finish()
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(64 * 1024)
            while (!deflater.finished()) out.write(buffer, 0, deflater.deflate(buffer))
            deflater.end()
            return out.toByteArray()
        }

        private fun ByteArrayOutputStream.u16(v: Int) { write(v and 0xFF); write((v ushr 8) and 0xFF) }
        private fun ByteArrayOutputStream.u32(v: Long) { u16((v and 0xFFFF).toInt()); u16(((v ushr 16) and 0xFFFF).toInt()) }
        private fun ByteArrayOutputStream.u32(v: Int) = u32(v.toLong() and 0xFFFFFFFFL)
        private fun ByteArrayOutputStream.u64(v: Long) { u32(v and 0xFFFFFFFFL); u32(v ushr 32) }
    }
}

class HttpSyncRemoteCoverArchiveTest {
    @get:Rule val temp = TemporaryFolder()

    private val key = "books/m/payload.zip"
    private val page1 = Random(1).nextBytes(40_000)
    private val page2 = Random(2).nextBytes(30_000)

    private fun mokuro(vararg pages: String): ByteArray =
        pages.joinToString(prefix = """{"title":"T","pages":[""", postfix = "]}") { """{"img_path":"$it"}""" }.toByteArray()

    private suspend fun cover(archive: ByteArray): ByteArray? =
        fetchRemoteMangaCover(ProductionRangeTransport(archive), key, archive.size.toLong())

    /** The result, or the exception that is not a network error (HttpSyncException). */
    private suspend fun outcome(archive: ByteArray): Result<ByteArray?> = try {
        Result.success(withTimeout(10_000) { cover(archive) })
    } catch (e: HttpSyncException) {
        Result.success(null)
    } catch (e: Throwable) {
        Result.failure(e)
    }

    private fun sha(bytes: ByteArray) =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    // ----- Archive producers ------------------------------------------------------------------

    @Test
    fun iosSsZipArchiveFixtureWithZip64ExtrasAndDataDescriptors() = runBlocking {
        val archive = requireNotNull(javaClass.getResourceAsStream("/sync/ios-generated-payload.zip")).readBytes()

        val cover = cover(archive)

        assertNotNull(cover)
        assertEquals("4a21c728ec24523e014010b8e1b5f9e1615da36455e4b20550e9fab7ef225e06", sha(cover!!))
    }

    @Test
    fun androidPayloadCodecArchiveIsDeflatedWithDataDescriptors() = runBlocking {
        val root = temp.newFolder("book")
        root.resolve("images").mkdirs()
        root.resolve("images/b.jpg").writeBytes(page1)
        root.resolve("images/a.jpg").writeBytes(page2)
        root.resolve("mokuro.json").writeBytes(mokuro("images/b.jpg", "images/a.jpg"))
        val zipFile = temp.newFile("payload.zip")
        HttpSyncPayloadCodec(Dispatchers.Unconfined).zipDirectoryToFile(root, zipFile)

        assertArrayEquals(page1, fetchRemoteMangaCover(ProductionRangeTransport.of(zipFile), key, zipFile.length()))
    }

    @Test
    fun javaZip64EndRecordsForMoreThan65535Entries() = runBlocking {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("mokuro.json")); zip.write(mokuro("images/0002.jpg")); zip.closeEntry()
            zip.putNextEntry(ZipEntry("images/0002.jpg")); zip.write(page2); zip.closeEntry()
            repeat(65_536) { zip.putNextEntry(ZipEntry("x/$it")); zip.closeEntry() }
        }
        val archive = out.toByteArray()
        assertEquals(0x06054b50, java.nio.ByteBuffer.wrap(archive, archive.size - 22, 4).order(java.nio.ByteOrder.LITTLE_ENDIAN).int)

        assertArrayEquals(page2, cover(archive))
    }

    @Test
    fun handBuiltZip64EndRecordsAndPartiallySaturatedExtras() = runBlocking {
        val zip = RawZip()
        zip.add("mokuro.json", mokuro("images/0001.jpg"), deflate = true).apply {
            // Only the offset is saturated: the extra holds just the offset.
            extra = RawZip.zip64Extra(localOffset); localOffset = 0xFFFFFFFFL
        }
        zip.add("images/0001.jpg", page1).apply {
            // Only the compressed size is saturated (the size field is real).
            extra = RawZip.zip64Extra(compressedSize); compressedSize = 0xFFFFFFFFL
        }
        val archive = zip.finish(comment = "c".repeat(300).toByteArray(), zip64 = RawZip.Zip64End())
        ZipFile(temp.newFile("check.zip").apply { writeBytes(archive) }).use { assertEquals(2, it.size()) }

        assertArrayEquals(page1, cover(archive))
    }

    @Test
    fun theLongestArchiveCommentIsSkipped() = runBlocking {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.setComment("z".repeat(0xFFFF))
            zip.putNextEntry(ZipEntry("images/0001.jpg")); zip.write(page1); zip.closeEntry()
        }

        assertArrayEquals(page1, cover(out.toByteArray()))
    }

    // ----- Bugs ------------------------------------------------------------------------------

    /**
     * Python zipfile writes ZIP64 end records only for MORE than 65535 entries; with exactly
     * 65535 the EOCD count is 0xFFFF and there is no locator (`py_65535.zip`). The reader treats
     * 0xFFFF as a ZIP64 marker and gives up.
     */
    @Test
    fun exactly65535EntriesWithoutZip64RecordsStillHaveACover() = runBlocking {
        val zip = RawZip()
        zip.add("mokuro.json", mokuro("images/0001.jpg"), deflate = true)
        zip.add("images/0001.jpg", page1)
        repeat(65_533) { zip.add("x/$it", ByteArray(0)) }
        val archive = zip.finish()
        ZipFile(temp.newFile("check.zip").apply { writeBytes(archive) }).use { assertEquals(65_535, it.size()) }

        assertArrayEquals(page1, cover(archive))
    }

    @Test
    fun aNegativeZip64RecordOffsetIsNoCoverNotACrash() = runBlocking {
        val zip = RawZip()
        zip.add("images/0001.jpg", page1)
        val archive = zip.finish(zip64 = RawZip.Zip64End(recordOffset = Long.MIN_VALUE + 5))

        val result = outcome(archive)

        assertTrue("threw ${result.exceptionOrNull()}", result.isSuccess)
    }

    @Test
    fun aZip64RecordOffsetNearLongMaxIsNoCoverNotACrash() = runBlocking {
        val zip = RawZip()
        zip.add("images/0001.jpg", page1)
        val archive = zip.finish(zip64 = RawZip.Zip64End(recordOffset = Long.MAX_VALUE - 10))

        val result = outcome(archive)

        assertTrue("threw ${result.exceptionOrNull()}", result.isSuccess)
    }

    @Test
    fun aZip64DirectoryOffsetNearLongMaxIsNoCoverNotACrash() = runBlocking {
        val zip = RawZip()
        zip.add("images/0001.jpg", page1)
        // directoryOffset + directorySize overflows, so the bounds check passes.
        val archive = zip.finish(zip64 = RawZip.Zip64End(directoryOffset = Long.MAX_VALUE - 5))

        val result = outcome(archive)

        assertTrue("threw ${result.exceptionOrNull()}", result.isSuccess)
    }

    @Test
    fun aNegativeLocalHeaderOffsetInAZip64ExtraIsNoCoverNotACrash() = runBlocking {
        val zip = RawZip()
        zip.add("images/0001.jpg", page1).apply {
            extra = RawZip.zip64Extra(-1L); localOffset = 0xFFFFFFFFL
        }

        val result = outcome(zip.finish())

        assertTrue("threw ${result.exceptionOrNull()}", result.isSuccess)
    }

    /** Random corruption of the end records and central directory must never escape as a crash. */
    @Test
    fun corruptedArchivesNeverThrowAnythingButNetworkErrors() = fuzz(includeZip64 = true)

    /** Without ZIP64 every field is at most 32 bits, so no value can go negative or overflow. */
    @Test
    fun corruptedClassicArchivesNeverThrowAnythingButNetworkErrors() = fuzz(includeZip64 = false)

    private fun fuzz(includeZip64: Boolean) = runBlocking {
        val plain = RawZip().apply {
            add("mokuro.json", mokuro("images/0001.jpg"), deflate = true)
            add("images/0001.jpg", page1); add("images/0002.jpg", page2, deflate = true)
        }.finish()
        val zip64 = RawZip().apply {
            add("mokuro.json", mokuro("images/0001.jpg"), deflate = true).apply {
                extra = RawZip.zip64Extra(size, compressedSize, localOffset)
                size = 0xFFFFFFFFL; compressedSize = 0xFFFFFFFFL; localOffset = 0xFFFFFFFFL
            }
            add("images/0001.jpg", page1).apply {
                extra = RawZip.zip64Extra(size, compressedSize, localOffset)
                size = 0xFFFFFFFFL; compressedSize = 0xFFFFFFFFL; localOffset = 0xFFFFFFFFL
            }
        }.finish(zip64 = RawZip.Zip64End())
        val random = Random(42)
        val crashes = sortedMapOf<String, Int>()
        var example = ""
        for (iteration in 0 until 4_000) {
            val base = if (includeZip64 && iteration % 2 == 1) zip64 else plain
            val archive = base.copyOf()
            val tailStart = maxOf(0, archive.size - 400)
            repeat(1 + random.nextInt(4)) {
                val at = tailStart + random.nextInt(archive.size - tailStart)
                if (random.nextBoolean()) {
                    archive[at] = random.nextInt(256).toByte()
                } else {
                    // Overwrite eight bytes with an extreme value (a ZIP64 field or two 32-bit ones).
                    val value = listOf(-1L, Long.MIN_VALUE, Long.MAX_VALUE, Long.MAX_VALUE - 7, 0x80000000L, random.nextLong()).random(random)
                    for (b in 0 until 8) if (at + b < archive.size) archive[at + b] = (value ushr (8 * b)).toByte()
                }
            }
            val failure = outcome(archive).exceptionOrNull() ?: continue
            val name = failure.javaClass.simpleName
            crashes[name] = (crashes[name] ?: 0) + 1
            if (example.isEmpty()) example = failure.toString()
        }
        assertEquals("first: $example", emptyMap<String, Int>(), crashes)
    }

    /** A sidecar naming a non-image keeps the archive's real first page as the cover. */
    @Test
    fun aMokuroPathNamingANonImageFallsBackToThePageImages() = runBlocking {
        val zip = RawZip()
        zip.add("mokuro.json", mokuro("notes.txt"), deflate = true)
        zip.add("notes.txt", "not an image".toByteArray())
        zip.add("images/0001.jpg", page1)

        assertArrayEquals(page1, cover(zip.finish()))
    }

    // ----- Malformed input that is already handled (coverage) ---------------------------------

    @Test
    fun malformedArchivesGiveNoCoverWithoutThrowing() = runBlocking {
        val good = RawZip().apply {
            add("mokuro.json", mokuro("images/0001.jpg"), deflate = true); add("images/0001.jpg", page1)
        }.finish()
        val cases = linkedMapOf<String, ByteArray>()
        cases["truncated tail"] = good.copyOf(good.size - 7)
        cases["truncated mid directory"] = good.copyOf(good.size - 60)
        cases["directory beyond archive"] = good.copyOf().also { patch32(it, it.size - 6, good.size.toLong() + 10) }
        cases["name length beyond directory"] = RawZip().apply {
            add("images/0001.jpg", page1)
        }.finish().also { a -> patch16(a, indexOfCentral(a) + 28, 0xFFFF) }
        cases["compressed size differs from size (stored)"] = RawZip().apply {
            add("images/0001.jpg", page1).apply { compressedSize = size - 1 }
        }.finish()
        cases["deflate bomb (declares 1 KiB, inflates to 64 MiB)"] = RawZip().apply {
            add("images/0001.jpg", ByteArray(64 * 1024 * 1024), deflate = true).apply { size = 1024 }
        }.finish()
        cases["cover above 32 MiB"] = RawZip().apply {
            add("images/0001.jpg", ByteArray(33 * 1024 * 1024), deflate = true)
        }.finish()
        cases["non-UTF-8 names only"] = RawZip().apply {
            add("", page1, nameBytes = byteArrayOf(0x82.toByte(), 0xA0.toByte(), 0xFF.toByte(), '.'.code.toByte(), 'j'.code.toByte(), 'p'.code.toByte(), 'g'.code.toByte()))
        }.finish()
        cases["empty archive"] = RawZip().finish()
        cases["unknown method"] = RawZip().apply { add("images/0001.jpg", page1).apply { method = 12 } }.finish()
        val unexpected = mutableListOf<String>()
        for ((name, archive) in cases) {
            val started = System.nanoTime()
            val result = outcome(archive)
            val millis = (System.nanoTime() - started) / 1_000_000
            if (result.isFailure) unexpected += "$name threw ${result.exceptionOrNull()}"
            if (millis > 5_000) unexpected += "$name took $millis ms"
        }
        assertEquals(emptyList<String>(), unexpected)
        // The non-UTF-8 name still decodes (with replacement characters) and is used.
        assertArrayEquals(page1, outcome(cases.getValue("non-UTF-8 names only")).getOrNull())
        assertNull(outcome(cases.getValue("deflate bomb (declares 1 KiB, inflates to 64 MiB)")).getOrNull())
    }

    @Test
    fun aMokuroPathNamingAMissingEntryFallsBackToThePageImages() = runBlocking {
        val zip = RawZip()
        zip.add("mokuro.json", mokuro("images/missing.jpg"), deflate = true)
        zip.add("images/0002.jpg", page2)
        zip.add("images/0001.jpg", page1)

        assertArrayEquals(page1, cover(zip.finish()))
    }

    private fun indexOfCentral(archive: ByteArray): Int {
        for (i in archive.size - 22 downTo 0) {
            if (archive[i] == 0x50.toByte() && archive[i + 1] == 0x4b.toByte() && archive[i + 2] == 1.toByte() && archive[i + 3] == 2.toByte()) return i
        }
        error("no central header")
    }

    private fun patch16(a: ByteArray, at: Int, v: Int) { a[at] = v.toByte(); a[at + 1] = (v ushr 8).toByte() }
    private fun patch32(a: ByteArray, at: Int, v: Long) { for (b in 0 until 4) a[at + b] = (v ushr (8 * b)).toByte() }

    private companion object {
    }
}
