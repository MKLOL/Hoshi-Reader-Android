package moe.antimony.hoshi.mokuro

import android.content.ContentResolver
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import moe.antimony.hoshi.epub.MOKURO_SIDECAR_FILE
import moe.antimony.hoshi.importing.ImportFileType
import moe.antimony.hoshi.importing.validateImportFile
import java.io.File
import java.io.InputStream
import java.util.UUID
import java.util.zip.ZipInputStream

/**
 * The book-root-relative directory that imported page images are flattened into. The
 * importer rewrites every mokuro page `img_path` to `IMAGES_DIR/<basename>` so the sidecar
 * resolves against the on-disk layout regardless of how the source was structured.
 */
const val MOKURO_IMAGES_DIR: String = "images"

/** Image file extensions the importer recognises as mokuro page images. */
private val MOKURO_IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp")

private const val MOKURO_FILE_EXTENSION = "mokuro"

/**
 * Imports a mokuro manga volume into a Hoshi book directory.
 *
 * Mokuro's output for one volume is a `.mokuro` JSON file plus a folder of page images.
 * This importer accepts that output two ways — a `.zip`/`.cbz` bundle, or a SAF document
 * tree the user picked — and in both cases produces the canonical on-disk layout under
 * `Books/<folder>/`:
 *
 *  - `mokuro.json`: the `.mokuro` JSON, with every page's `img_path` rewritten to
 *    `images/<basename>` so it resolves against the book directory.
 *  - `images/`: every page image, flattened to basenames.
 *
 * The presence of `mokuro.json` is what marks the directory as manga
 * ([moe.antimony.hoshi.epub.bookContentType]); writing `metadata.json` / `bookinfo.json`
 * is left to the bookshelf repository, which owns the shared sidecar shapes.
 *
 * Archive extraction and tree copying are guarded against path traversal in the same way
 * as the EPUB importer.
 */
class MokuroImporter(
    filesDir: File,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val importTempRoot = File(filesDir, "ImportTemp")

    // Pretty-printed to match the rest of Hoshi's sidecar files; unknown keys are kept so
    // the rewritten sidecar stays a faithful copy of the mokuro tool output.
    @OptIn(ExperimentalSerializationApi::class)
    private val json = Json {
        prettyPrint = true
        prettyPrintIndent = "    "
        ignoreUnknownKeys = true
    }

    /**
     * Imports a `.zip`/`.cbz` bundle selected via SAF. The archive must contain exactly one
     * `.mokuro` JSON file plus the page images it references; images may sit at the archive
     * root or in a subfolder — each is located via the `.mokuro`'s `img_path` values
     * resolved relative to the `.mokuro` file's own location inside the archive.
     */
    suspend fun importFromBundle(
        contentResolver: ContentResolver,
        uri: Uri,
        targetRootFactory: suspend (title: String) -> File,
    ): MokuroImportResult = withContext(ioDispatcher) {
        contentResolver.validateImportFile(uri, ImportFileType.Mokuro)
        val staging = createStagingDirectory()
        runCatching {
            contentResolver.openInputStream(uri).use { input ->
                requireNotNull(input) { "Unable to open the selected manga archive." }
                extractArchive(input, staging)
            }
            assembleMokuroBook(staging, targetRootFactory)
        }.also { staging.deleteRecursively() }.getOrThrow()
    }

    /**
     * Imports a mokuro output folder picked via `ACTION_OPEN_DOCUMENT_TREE`. The tree is
     * enumerated for a `.mokuro` JSON file and the page images it references; both are
     * copied into app storage. [openInputStream] reads a SAF document's bytes (typically
     * `contentResolver::openInputStream`).
     */
    suspend fun importFromTree(
        tree: DocumentFile,
        openInputStream: (Uri) -> InputStream?,
        targetRootFactory: suspend (title: String) -> File,
    ): MokuroImportResult = withContext(ioDispatcher) {
        val staging = createStagingDirectory()
        runCatching {
            copyTreeToStaging(tree, staging, openInputStream)
            assembleMokuroBook(staging, targetRootFactory)
        }.also { staging.deleteRecursively() }.getOrThrow()
    }

    /**
     * Core of both import paths: given a [staging] directory that mirrors the source layout,
     * locates the `.mokuro` file, copies its referenced images into the target book
     * directory under [MOKURO_IMAGES_DIR], and writes the path-rewritten `mokuro.json`.
     *
     * Visible for testing so the layout-production logic can be exercised without SAF.
     */
    internal suspend fun assembleMokuroBook(
        staging: File,
        targetRootFactory: suspend (title: String) -> File,
    ): MokuroImportResult {
        val mokuroFile = staging.findMokuroFile()
            ?: throw MokuroImportException(
                "No .mokuro file found. Pick a folder or .zip/.cbz produced by the mokuro tool " +
                    "(an .html-only export without a .mokuro JSON is not supported).",
            )
        val sourceRoot = mokuroFile.parentFile ?: staging
        // A malformed .mokuro surfaces as either a SerializationException (invalid JSON) or, for a
        // top-level array/primitive, an IllegalArgumentException from `.jsonObject`. Both extend
        // IllegalArgumentException; turn them into a friendly MokuroImportException like the other
        // failure modes here instead of leaking a cryptic parser message.
        val rawSidecar = try {
            json.parseToJsonElement(mokuroFile.readText()).jsonObject
        } catch (e: IllegalArgumentException) {
            throw MokuroImportException(
                "The .mokuro file is not valid JSON — it may be corrupt or incomplete.",
            )
        }

        val pages = rawSidecar["pages"]?.jsonArray
            ?: throw MokuroImportException("The .mokuro file has no \"pages\" — it may be corrupt.")
        if (pages.isEmpty()) throw MokuroImportException("The .mokuro file contains no pages.")

        val title = rawSidecar.stringOrNull("volume")
            ?: rawSidecar.stringOrNull("title")
            ?: mokuroFile.nameWithoutExtension

        // First pass: resolve every page image against the .mokuro file's location and plan
        // its flattened destination. Fail before touching the target directory if anything
        // is missing, so a partial book is never left on disk.
        val plannedImages = LinkedHashMap<String, File>() // destination basename -> source file
        val rewrittenPages = pages.map { pageElement ->
            val page = pageElement.jsonObject
            val imgPath = page.stringOrNull("img_path")
                ?: throw MokuroImportException("A page in the .mokuro file is missing its \"img_path\".")
            val sourceImage = locatePageImage(staging, sourceRoot, imgPath)
                ?: throw MokuroImportException("Page image referenced by the .mokuro file is missing: $imgPath")
            val basename = uniqueBasename(sourceImage.name, plannedImages)
            plannedImages[basename] = sourceImage
            val rewritten = "$MOKURO_IMAGES_DIR/$basename"
            JsonObject(page + ("img_path" to JsonPrimitive(rewritten)))
        }

        val targetRoot = targetRootFactory(title)
        return runCatching {
            val imagesDir = File(targetRoot, MOKURO_IMAGES_DIR).apply { mkdirs() }
            plannedImages.forEach { (basename, source) ->
                source.copyTo(File(imagesDir, basename), overwrite = true)
            }
            val rewrittenSidecar = JsonObject(rawSidecar + ("pages" to JsonArray(rewrittenPages)))
            File(targetRoot, MOKURO_SIDECAR_FILE).writeText(json.encodeToString(JsonElement.serializer(), rewrittenSidecar))
            MokuroImportResult(
                bookRoot = targetRoot,
                title = title,
                pageCount = rewrittenPages.size,
                coverImagePath = rewrittenPages.firstOrNull()
                    ?.jsonObject?.stringOrNull("img_path"),
            )
        }.onFailure {
            targetRoot.deleteRecursively()
        }.getOrThrow()
    }

    private fun createStagingDirectory(): File =
        File(importTempRoot, UUID.randomUUID().toString()).apply { mkdirs() }

    /** Extracts a zip/cbz [input] into [staging], rejecting path-traversing entries. */
    private fun extractArchive(input: InputStream, staging: File) {
        val stagingRoot = staging.canonicalFile
        ZipInputStream(input).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                val output = stagingRoot.resolve(entry.name).canonicalFile
                require(
                    output.path == stagingRoot.path ||
                        output.path.startsWith(stagingRoot.path + File.separator),
                ) { "Unsafe archive entry: ${entry.name}" }
                if (entry.isDirectory) {
                    output.mkdirs()
                } else {
                    output.parentFile?.mkdirs()
                    output.outputStream().use { zip.copyTo(it) }
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
    }

    /**
     * Recursively copies a SAF document [tree] into [staging], preserving relative
     * structure so `img_path` values keep resolving. Only the `.mokuro` file and recognised
     * image files are copied — other files in the picked folder are ignored.
     */
    private fun copyTreeToStaging(
        tree: DocumentFile,
        staging: File,
        openInputStream: (Uri) -> InputStream?,
    ) {
        val stagingRoot = staging.canonicalFile
        fun copy(node: DocumentFile, relativeDir: File) {
            for (child in node.listFiles()) {
                val name = child.name ?: continue
                if (child.isDirectory) {
                    val childDir = relativeDir.resolve(name)
                    copy(child, childDir)
                    continue
                }
                val extension = name.substringAfterLast('.', "").lowercase()
                if (extension != MOKURO_FILE_EXTENSION && extension !in MOKURO_IMAGE_EXTENSIONS) {
                    continue
                }
                val destination = relativeDir.resolve(name).canonicalFile
                require(
                    destination.path == stagingRoot.path ||
                        destination.path.startsWith(stagingRoot.path + File.separator),
                ) { "Unsafe tree entry: $name" }
                destination.parentFile?.mkdirs()
                val source = openInputStream(child.uri)
                    ?: throw MokuroImportException("Unable to read \"$name\" from the selected folder.")
                source.use { stream -> destination.outputStream().use { stream.copyTo(it) } }
            }
        }
        copy(tree, stagingRoot)
    }
}

/** Outcome of a successful mokuro import. */
data class MokuroImportResult(
    val bookRoot: File,
    val title: String,
    val pageCount: Int,
    /** Book-root-relative path of the first page image, used as the bookshelf cover. */
    val coverImagePath: String?,
)

/** Raised when a source cannot be imported as a mokuro manga, with a user-facing message. */
class MokuroImportException(message: String) : IllegalArgumentException(message)

/**
 * Finds the single `.mokuro` JSON file anywhere within this directory tree. If several are
 * present (a multi-volume archive) the shallowest, then alphabetically-first one wins so the
 * choice is deterministic.
 */
internal fun File.findMokuroFile(): File? =
    walkTopDown()
        .filter { it.isFile && it.extension.equals(MOKURO_FILE_EXTENSION, ignoreCase = true) }
        .sortedWith(compareBy({ it.relativeTo(this).path.count { c -> c == File.separatorChar } }, { it.name }))
        .firstOrNull()

/**
 * Locates the page image referenced by a mokuro `img_path`.
 *
 * Mokuro's standard output keeps the `.mokuro` file as a *sibling* of the image folder, so
 * `img_path` is relative to that folder rather than to the `.mokuro` file's own directory.
 * Resolution therefore falls back from the strict interpretation to matching the `img_path`
 * tail, then a unique basename, anywhere under the extracted [staging] tree. Fallbacks only
 * succeed when they identify one file; silently choosing between volumes can bind the wrong
 * page to the imported sidecar. Every candidate comes from `walkTopDown()` over [staging], so
 * results are always inside it (no traversal).
 */
private fun locatePageImage(staging: File, sourceRoot: File, imgPath: String): File? {
    // 1. Strict: img_path relative to the .mokuro file's own directory.
    sourceRoot.resolveWithin(imgPath)?.takeIf { it.isFile }?.let { return it }
    val normalized = imgPath.replace('\\', '/').trim().trimStart('/')
    if (normalized.isEmpty()) return null
    // 2. Common mokuro layout: images in a sibling folder — require a unique
    // img_path-tail match. Stop after two because any additional match is already
    // ambiguous and must not be bound to the sidecar arbitrarily.
    val tailMatches = staging.walkTopDown()
        .filter { it.isFile && it.invariantSeparatorsPath.endsWith("/$normalized") }
        .take(2)
        .toList()
    if (tailMatches.size == 1) return tailMatches.single()
    if (tailMatches.size > 1) return null
    // 3. Last resort: a single unambiguous basename match anywhere in the tree.
    val basename = normalized.substringAfterLast('/')
    return staging.walkTopDown()
        .filter { it.isFile && it.name == basename }
        .toList()
        .singleOrNull()
}

/**
 * Resolves [relativePath] against this directory, returning null if it escapes the
 * directory. Mokuro `img_path` values are forward-slash relative paths.
 */
private fun File.resolveWithin(relativePath: String): File? {
    val root = canonicalFile
    val resolved = root.resolve(relativePath.replace('\\', '/')).canonicalFile
    return resolved.takeIf {
        it.path == root.path || it.path.startsWith(root.path + File.separator)
    }
}

/**
 * Returns a destination basename for [originalName] that is not already used in [taken].
 * Flattening the image folder to basenames can collide when a source nests images in
 * differently-named subfolders; a numeric suffix keeps every page distinct.
 */
private fun uniqueBasename(originalName: String, taken: Map<String, File>): String {
    if (originalName !in taken) return originalName
    val stem = originalName.substringBeforeLast('.', originalName)
    val extension = originalName.substringAfterLast('.', "")
    val suffix = if (extension.isEmpty()) "" else ".$extension"
    var index = 1
    while ("$stem-$index$suffix" in taken) index++
    return "$stem-$index$suffix"
}

private fun JsonObject.stringOrNull(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.ifBlank { null }
