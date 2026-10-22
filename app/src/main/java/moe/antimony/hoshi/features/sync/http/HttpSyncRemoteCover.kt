package moe.antimony.hoshi.features.sync.http

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import moe.antimony.hoshi.epub.MOKURO_SIDECAR_FILE
import java.util.zip.CRC32
import java.util.zip.Inflater

/**
 * Reads a manga's cover — its first page — out of the remote `payload.zip` without downloading
 * the archive. Three small byte ranges do it: the ZIP's end records (the archive tail), its
 * central directory, and the one page entry. When `mokuro.json` is small enough it names the
 * first page exactly; otherwise the first page image in natural name order is used.
 *
 * Returns null when the cover cannot be read from this archive (no range support, a malformed
 * or unexpected archive, no image), so it is not tried again. Network failures throw
 * [HttpSyncException] so the caller can retry later.
 */
internal suspend fun fetchRemoteMangaCover(
    transport: HttpSyncKvTransport,
    zipKey: String,
    archiveSize: Long,
): ByteArray? {
    if (archiveSize < EOCD_SIZE) return null
    val tail = transport.getRange(zipKey, start = null, length = minOf(archiveSize, TAIL_BYTES.toLong()).toInt())
        ?: return null
    // A different size means the archive changed since its manifest was read.
    if (tail.totalSize != archiveSize) return null
    return try {
        val entries = readCentralDirectory(transport, zipKey, tail) ?: return null
        val cover = chooseCoverEntry(transport, zipKey, entries) ?: return null
        readZipEntry(transport, zipKey, cover, MAX_COVER_BYTES)
    } catch (e: HttpSyncException) {
        throw e
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (_: RuntimeException) {
        // Values no reader could use (crafted or corrupt offsets): this archive has no cover.
        null
    }
}

internal data class RemoteZipEntry(
    val name: String,
    val method: Int,
    val crc: Long,
    val compressedSize: Long,
    val size: Long,
    val localHeaderOffset: Long,
)

private suspend fun readCentralDirectory(
    transport: HttpSyncKvTransport,
    zipKey: String,
    tail: HttpSyncKvRange,
): List<RemoteZipEntry>? {
    val bytes = tail.body
    var eocd = -1
    for (offset in bytes.size - EOCD_SIZE downTo 0) {
        if (bytes.u32(offset) == EOCD_SIGNATURE &&
            offset + EOCD_SIZE + bytes.u16(offset + 20) == bytes.size
        ) {
            eocd = offset
            break
        }
    }
    if (eocd < 0) return null
    var entryCount = bytes.u16(eocd + 10).toLong()
    var directorySize = bytes.u32(eocd + 12)
    var directoryOffset = bytes.u32(eocd + 16)
    val locator = eocd - ZIP64_LOCATOR_SIZE
    // Saturated fields point at ZIP64 records; without a locator they are the real values
    // (exactly 65,535 entries is written that way).
    if ((entryCount == 0xFFFFL || directorySize == 0xFFFFFFFFL || directoryOffset == 0xFFFFFFFFL) &&
        locator >= 0 && bytes.u32(locator) == ZIP64_LOCATOR_SIGNATURE
    ) {
        val recordOffset = bytes.u64(locator + 8)
        if (!fits(recordOffset, ZIP64_EOCD_SIZE.toLong(), tail.totalSize)) return null
        val record = slice(transport, zipKey, tail, recordOffset, ZIP64_EOCD_SIZE) ?: return null
        if (record.u32(0) != ZIP64_EOCD_SIGNATURE) return null
        entryCount = record.u64(32)
        directorySize = record.u64(40)
        directoryOffset = record.u64(48)
    }
    if (entryCount <= 0 || directorySize <= 0 || directorySize > MAX_DIRECTORY_BYTES) return null
    if (!fits(directoryOffset, directorySize, tail.totalSize)) return null
    val directory = slice(transport, zipKey, tail, directoryOffset, directorySize.toInt()) ?: return null
    return parseCentralDirectory(directory)
}

internal fun parseCentralDirectory(directory: ByteArray): List<RemoteZipEntry>? {
    val entries = mutableListOf<RemoteZipEntry>()
    var position = 0
    while (position + CENTRAL_HEADER_SIZE <= directory.size) {
        if (directory.u32(position) != CENTRAL_HEADER_SIGNATURE) return null
        val method = directory.u16(position + 10)
        val crc = directory.u32(position + 16)
        var compressedSize = directory.u32(position + 20)
        var size = directory.u32(position + 24)
        val nameLength = directory.u16(position + 28)
        val extraLength = directory.u16(position + 30)
        val commentLength = directory.u16(position + 32)
        var localOffset = directory.u32(position + 42)
        val nameStart = position + CENTRAL_HEADER_SIZE
        val extraStart = nameStart + nameLength
        val next = extraStart + extraLength + commentLength
        if (next > directory.size) return null
        val name = String(directory, nameStart, nameLength, Charsets.UTF_8)
        // ZIP64 keeps the real values in extra field 0x0001, in this order, for each 32-bit
        // field that holds the 0xFFFFFFFF marker.
        var extra = extraStart
        while (extra + 4 <= extraStart + extraLength) {
            val id = directory.u16(extra)
            val length = directory.u16(extra + 2)
            if (extra + 4 + length > extraStart + extraLength) return null
            if (id == ZIP64_EXTRA_ID) {
                var field = extra + 4
                if (size == 0xFFFFFFFFL && field + 8 <= extra + 4 + length) {
                    size = directory.u64(field); field += 8
                }
                if (compressedSize == 0xFFFFFFFFL && field + 8 <= extra + 4 + length) {
                    compressedSize = directory.u64(field); field += 8
                }
                if (localOffset == 0xFFFFFFFFL && field + 8 <= extra + 4 + length) {
                    localOffset = directory.u64(field)
                }
            }
            extra += 4 + length
        }
        entries += RemoteZipEntry(name, method, crc, compressedSize, size, localOffset)
        position = next
    }
    return entries
}

private suspend fun chooseCoverEntry(
    transport: HttpSyncKvTransport,
    zipKey: String,
    entries: List<RemoteZipEntry>,
): RemoteZipEntry? {
    val files = entries.filterNot { it.name.endsWith("/") }
    val byName = files.associateBy { it.name }
    byName[MOKURO_SIDECAR_FILE]
        ?.takeIf { it.compressedSize <= MAX_SIDECAR_COMPRESSED_BYTES && it.size <= MAX_SIDECAR_BYTES }
        ?.let { sidecar -> readZipEntry(transport, zipKey, sidecar, MAX_SIDECAR_BYTES) }
        ?.let(::firstMokuroPagePath)
        ?.let { path -> byName[path] }
        ?.takeIf { it.isPageImage() && it.size in 1..MAX_COVER_BYTES }
        ?.let { return it }
    return firstPageImage(files.filter { it.size in 1..MAX_COVER_BYTES })
}

private fun RemoteZipEntry.isPageImage(): Boolean =
    name.substringAfterLast('.', "").lowercase() in PAGE_IMAGE_EXTENSIONS

/** Whether [length] bytes at [offset] lie inside a value of [total] bytes, without overflowing. */
private fun fits(offset: Long, length: Long, total: Long): Boolean =
    offset >= 0 && length >= 0 && length <= total && offset <= total - length

/** The first page `img_path` in a `mokuro.json`, normalized to an archive entry name. */
internal fun firstMokuroPagePath(sidecar: ByteArray): String? = runCatching {
    sidecarJson.decodeFromString(RemoteMokuroPages.serializer(), sidecar.toString(Charsets.UTF_8))
        .pages.firstOrNull()?.imgPath
        ?.replace('\\', '/')?.trim()?.removePrefix("./")?.trimStart('/')
        ?.takeIf { it.isNotEmpty() }
}.getOrNull()

/** The first page image by natural name order, preferring the importer's `images/` folder. */
internal fun firstPageImage(entries: List<RemoteZipEntry>): RemoteZipEntry? {
    val images = entries.filter { it.isPageImage() }
    val pages = images.filter { it.name.startsWith("images/") }.ifEmpty { images }
    return pages.minWithOrNull { a, b -> compareNatural(a.name, b.name) }
}

/** Orders digit runs by value, so `page2` sorts before `page10`. */
internal fun compareNatural(a: String, b: String): Int {
    var i = 0
    var j = 0
    while (i < a.length && j < b.length) {
        if (a[i].isDigit() && b[j].isDigit()) {
            val startA = i
            val startB = j
            while (i < a.length && a[i].isDigit()) i++
            while (j < b.length && b[j].isDigit()) j++
            val numberA = a.substring(startA, i).trimStart('0')
            val numberB = b.substring(startB, j).trimStart('0')
            if (numberA.length != numberB.length) return numberA.length - numberB.length
            val byValue = numberA.compareTo(numberB)
            if (byValue != 0) return byValue
        } else {
            val byChar = a[i].lowercaseChar().compareTo(b[j].lowercaseChar())
            if (byChar != 0) return byChar
            i++
            j++
        }
    }
    return (a.length - i) - (b.length - j)
}

private suspend fun readZipEntry(
    transport: HttpSyncKvTransport,
    zipKey: String,
    entry: RemoteZipEntry,
    maxBytes: Int,
): ByteArray? {
    if (entry.size <= 0 || entry.size > maxBytes || entry.compressedSize <= 0 || entry.compressedSize > maxBytes) {
        return null
    }
    if (entry.localHeaderOffset < 0) return null
    val header = transport.getRange(zipKey, entry.localHeaderOffset, LOCAL_HEADER_SIZE) ?: return null
    if (header.body.size != LOCAL_HEADER_SIZE || header.body.u32(0) != LOCAL_HEADER_SIGNATURE) return null
    val dataStart = entry.localHeaderOffset + LOCAL_HEADER_SIZE +
        header.body.u16(26) + header.body.u16(28)
    val data = transport.getRange(zipKey, dataStart, entry.compressedSize.toInt()) ?: return null
    if (data.body.size.toLong() != entry.compressedSize) return null
    val bytes = when (entry.method) {
        METHOD_STORED -> data.body
        METHOD_DEFLATED -> inflate(data.body, entry.size.toInt()) ?: return null
        else -> return null
    }
    if (bytes.size.toLong() != entry.size) return null
    val crc = CRC32().apply { update(bytes) }.value
    return bytes.takeIf { crc == entry.crc }
}

private fun inflate(compressed: ByteArray, size: Int): ByteArray? {
    val inflater = Inflater(true)
    return try {
        inflater.setInput(compressed)
        val output = ByteArray(size)
        var written = 0
        var paddedEnd = false
        while (written < size && !inflater.finished()) {
            val read = inflater.inflate(output, written, size - written)
            if (read == 0) {
                if (inflater.needsDictionary() || !inflater.needsInput() || paddedEnd) return null
                // Raw deflate may need one dummy byte past the data to report the end.
                inflater.setInput(DUMMY_BYTE)
                paddedEnd = true
            }
            written += read
        }
        output.takeIf { written == size }
    } catch (_: java.util.zip.DataFormatException) {
        null
    } finally {
        inflater.end()
    }
}

/** [length] bytes at [offset]: from the already-fetched [tail] when it holds them, else one range. */
private suspend fun slice(
    transport: HttpSyncKvTransport,
    zipKey: String,
    tail: HttpSyncKvRange,
    offset: Long,
    length: Int,
): ByteArray? {
    if (offset >= tail.start && offset + length <= tail.start + tail.body.size) {
        val from = (offset - tail.start).toInt()
        return tail.body.copyOfRange(from, from + length)
    }
    val fetched = transport.getRange(zipKey, offset, length) ?: return null
    return fetched.body.takeIf { it.size == length }
}

private fun ByteArray.u16(offset: Int): Int =
    (this[offset].toInt() and 0xFF) or ((this[offset + 1].toInt() and 0xFF) shl 8)

private fun ByteArray.u32(offset: Int): Long =
    u16(offset).toLong() or (u16(offset + 2).toLong() shl 16)

private fun ByteArray.u64(offset: Int): Long =
    u32(offset) or (u32(offset + 4) shl 32)

@Serializable
private data class RemoteMokuroPages(val pages: List<RemoteMokuroPage> = emptyList())

@Serializable
private data class RemoteMokuroPage(@SerialName("img_path") val imgPath: String? = null)

private val sidecarJson = Json { ignoreUnknownKeys = true }
private val DUMMY_BYTE = byteArrayOf(0)

private val PAGE_IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp")
private const val EOCD_SIGNATURE = 0x06054b50L
private const val ZIP64_LOCATOR_SIGNATURE = 0x07064b50L
private const val ZIP64_EOCD_SIGNATURE = 0x06064b50L
private const val CENTRAL_HEADER_SIGNATURE = 0x02014b50L
private const val LOCAL_HEADER_SIGNATURE = 0x04034b50L
private const val ZIP64_EXTRA_ID = 0x0001
private const val EOCD_SIZE = 22
private const val ZIP64_LOCATOR_SIZE = 20
private const val ZIP64_EOCD_SIZE = 56
private const val CENTRAL_HEADER_SIZE = 46
private const val LOCAL_HEADER_SIZE = 30
private const val METHOD_STORED = 0
private const val METHOD_DEFLATED = 8

/** End record, the longest possible archive comment, and the ZIP64 locator before them. */
private const val TAIL_BYTES = EOCD_SIZE + 0xFFFF + ZIP64_LOCATOR_SIZE
private const val MAX_DIRECTORY_BYTES = 16L * 1024 * 1024
// Real mokuro.json files are well under a megabyte; covers are one page image.
private const val MAX_SIDECAR_COMPRESSED_BYTES = 2L * 1024 * 1024
private const val MAX_SIDECAR_BYTES = 8 * 1024 * 1024
internal const val MAX_COVER_BYTES = 12 * 1024 * 1024
