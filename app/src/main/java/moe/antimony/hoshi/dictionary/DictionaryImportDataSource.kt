package moe.antimony.hoshi.dictionary

import android.content.ContentResolver
import android.net.Uri
import kotlinx.serialization.json.Json
import moe.antimony.hoshi.importing.ImportFileType
import moe.antimony.hoshi.importing.validateImportFile
import java.io.File
import java.io.InputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.zip.ZipFile

internal class DictionaryImportDataSource(
    private val nativeBridge: DictionaryNativeBridge = HoshiDictionaryNativeBridge,
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    fun importDictionary(
        contentResolver: ContentResolver,
        uri: Uri,
        typeDirectory: File,
        shouldSkip: (DictionaryIndex) -> Boolean = { false },
    ): Boolean {
        contentResolver.validateImportFile(uri, ImportFileType.DictionaryArchive)
        return contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "Unable to open dictionary file." }
            importDictionary(input, typeDirectory, shouldSkip)
        }
    }

    fun importDictionary(
        input: InputStream,
        typeDirectory: File,
        shouldSkip: (DictionaryIndex) -> Boolean = { false },
    ): Boolean = importDictionaryWithResult(input, typeDirectory, shouldSkip).isNotEmpty()

    fun importDictionaryWithResult(
        input: InputStream,
        typeDirectory: File,
        shouldSkip: (DictionaryIndex) -> Boolean = { false },
    ): List<ImportedDictionary> {
        typeDirectory.mkdirs()
        val importId = UUID.randomUUID()
        val tempZip = typeDirectory.resolve(".dictionary-import-$importId.zip")
        val stagingRoot = typeDirectory.resolve(".dictionary-import-$importId")
        try {
            input.use { source ->
                tempZip.outputStream().use { output -> source.copyTo(output) }
            }
            val index = readDictionaryIndexFromZip(tempZip)
            if (shouldSkip(index)) return emptyList()
            stagingRoot.mkdirs()
            val imported = nativeBridge.importDictionary(tempZip.absolutePath, stagingRoot.absolutePath)
            require(imported) { "Failed to import dictionary." }
            return commitStagedDictionaries(stagingRoot, typeDirectory)
        } finally {
            tempZip.delete()
            stagingRoot.deleteRecursively()
        }
    }

    private fun readDictionaryIndexFromZip(zipFile: File): DictionaryIndex {
        ZipFile(zipFile).use { zip ->
            val entry = zip.getEntry("index.json")
                ?: error("Unable to read dictionary index.")
            zip.getInputStream(entry).use { input ->
                return json.decodeFromString<DictionaryIndex>(input.readBytes().decodeToString())
            }
        }
    }

    private fun commitStagedDictionaries(stagingRoot: File, typeDirectory: File): List<ImportedDictionary> {
        val importedDictionaries = stagingRoot.listFiles()?.filter(File::isDirectory).orEmpty()
        require(importedDictionaries.isNotEmpty()) { "Failed to import dictionary." }
        return importedDictionaries.map { stagedDictionary ->
            val imported = ImportedDictionary(
                fileName = stagedDictionary.name,
                index = readDictionaryIndexFile(stagedDictionary.resolve("index.json")),
            )
            commitStagedDictionary(stagedDictionary, typeDirectory.resolve(stagedDictionary.name))
            imported
        }
    }

    private fun readDictionaryIndexFile(indexFile: File): DictionaryIndex =
        json.decodeFromString<DictionaryIndex>(indexFile.readText())

    private fun commitStagedDictionary(stagedDictionary: File, target: File) {
        val replacementBackup = target.takeIf(File::exists)?.let { existing ->
            requireNotNull(target.parentFile).resolve(".${target.name}-replace-${UUID.randomUUID()}")
                .also { backup -> moveReplacing(existing, backup) }
        }
        try {
            moveReplacing(stagedDictionary, target)
            replacementBackup?.deleteRecursively()
        } catch (error: Throwable) {
            target.deleteRecursively()
            if (replacementBackup?.exists() == true) {
                moveReplacing(replacementBackup, target)
            }
            throw error
        }
    }

    private fun moveReplacing(source: File, target: File) {
        runCatching {
            Files.move(
                source.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        }.recoverCatching { error ->
            if (error !is AtomicMoveNotSupportedException) throw error
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }.getOrThrow()
    }
}
