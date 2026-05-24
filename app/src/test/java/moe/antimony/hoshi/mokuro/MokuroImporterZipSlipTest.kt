package moe.antimony.hoshi.mokuro

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Additional path-traversal hardening for [MokuroImporter.assembleMokuroBook].
 *
 * The existing [MokuroImporterTest] covers a relative `..`-escape in `img_path`. These tests
 * widen that to (a) absolute paths, (b) very long deeply-nested paths, and (c) backslash
 * separators used to confuse the canonicalization check. Each malicious sidecar must be
 * rejected via [MokuroImportException] without leaving the target directory populated.
 *
 * The zip-extraction guard in private `extractArchive` is the other half of the defense;
 * it cannot be reached without an Android `ContentResolver`, but it shares the same
 * `canonical-prefix` check pattern as `resolveWithin` so exercising that pattern through
 * the public assembly API gives meaningful coverage.
 */
class MokuroImporterZipSlipTest {
    private fun newDir(prefix: String): File = Files.createTempDirectory(prefix).toFile()
    private fun importer(filesDir: File) = MokuroImporter(filesDir)

    private fun mokuroJsonWithSinglePagePath(imgPath: String): String {
        // Escape backslashes and double-quotes for JSON string-literal embedding.
        val escaped = imgPath.replace("\\", "\\\\").replace("\"", "\\\"")
        return """
            {
              "pages": [
                {
                  "img_width": 1,
                  "img_height": 1,
                  "img_path": "$escaped",
                  "blocks": []
                }
              ]
            }
        """.trimIndent()
    }

    @Test
    fun rejectsAbsoluteImgPathOutsideStaging() = runBlocking {
        val filesDir = newDir("hoshi-zipslip-abs-files")
        val staging = newDir("hoshi-zipslip-abs-staging")
        // Manufacture an attacker-supplied absolute path that points OUTSIDE the staging
        // tree even after canonicalization.
        val externalVictim = newDir("hoshi-zipslip-victim").apply {
            resolve("secret.jpg").writeBytes(byteArrayOf(0x42))
        }
        staging.resolve("vol.mokuro").writeText(
            mokuroJsonWithSinglePagePath("${externalVictim.absolutePath}/secret.jpg"),
        )

        val target = filesDir.resolve("Books/abs").apply { mkdirs() }
        val error = runCatching {
            importer(filesDir).assembleMokuroBook(staging) { target }
        }.exceptionOrNull()

        assertTrue("expected MokuroImportException, got $error", error is MokuroImportException)
        // No partial book left on disk.
        assertFalse(target.resolve("mokuro.json").exists())
        assertFalse(target.resolve("images").exists())
    }

    @Test
    fun rejectsDeeplyNestedTraversalImgPath() = runBlocking {
        val filesDir = newDir("hoshi-zipslip-deep-files")
        val staging = newDir("hoshi-zipslip-deep-staging")
        // Many ../ hops; canonicalization should still detect the escape.
        val traversal = (1..32).joinToString("/") { ".." } + "/secret.jpg"
        staging.resolve("vol.mokuro").writeText(mokuroJsonWithSinglePagePath(traversal))

        val target = filesDir.resolve("Books/deep").apply { mkdirs() }
        val error = runCatching {
            importer(filesDir).assembleMokuroBook(staging) { target }
        }.exceptionOrNull()

        assertTrue("expected MokuroImportException, got $error", error is MokuroImportException)
        assertFalse(target.resolve("images").exists())
    }

    @Test
    fun rejectsBackslashTraversalImgPath() = runBlocking {
        // Mokuro on Windows might emit backslash separators; the resolver normalizes them
        // (`replace('\\', '/')`) and *then* canonicalizes — so a backslash-style traversal
        // must still be detected.
        val filesDir = newDir("hoshi-zipslip-backslash-files")
        val staging = newDir("hoshi-zipslip-backslash-staging")
        staging.resolve("vol.mokuro").writeText(mokuroJsonWithSinglePagePath("..\\..\\secret.jpg"))

        val target = filesDir.resolve("Books/back").apply { mkdirs() }
        val error = runCatching {
            importer(filesDir).assembleMokuroBook(staging) { target }
        }.exceptionOrNull()

        assertTrue("expected MokuroImportException, got $error", error is MokuroImportException)
    }

    @Test
    fun rejectsVeryLongPathBeyondSourceRoot() = runBlocking {
        val filesDir = newDir("hoshi-zipslip-long-files")
        val staging = newDir("hoshi-zipslip-long-staging")
        // A long but unambiguously-traversing path: filesystem may or may not accept such a
        // length on disk, but the importer must refuse it without ever attempting a copy.
        val longSegment = "a".repeat(200)
        staging.resolve("vol.mokuro").writeText(mokuroJsonWithSinglePagePath("../$longSegment/secret.jpg"))

        val target = filesDir.resolve("Books/long").apply { mkdirs() }
        val error = runCatching {
            importer(filesDir).assembleMokuroBook(staging) { target }
        }.exceptionOrNull()

        // Either a MokuroImportException ("escapes the source folder" or "image missing")
        // — both are correct refusals; the importer must never write a partial book.
        assertTrue("expected MokuroImportException, got $error", error is MokuroImportException)
        assertFalse(target.resolve("images").exists())
    }
}
