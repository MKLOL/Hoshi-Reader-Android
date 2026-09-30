package moe.antimony.hoshi.features.sync.v3

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import moe.antimony.hoshi.epub.BookMetadata
import moe.antimony.hoshi.epub.BookRepository
import moe.antimony.hoshi.features.ai.AiChatHistoryStore
import moe.antimony.hoshi.features.ai.EPUB_TRANSLATIONS_FILENAME
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.StandardCopyOption

class V3LocalTranslationHashTest {
    @get:Rule val temp = TemporaryFolder()

    @Test
    fun aReplacedTranslationIsDetectedEvenWhenItsSizeAndTimestampMatch() = runBlocking {
        val repo = BookRepository(temp.newFolder(), Dispatchers.Unconfined)
        val root = repo.createBookDirectoryForImportedTitle("Novel")
        repo.saveMetadata(root, BookMetadata(id = "novel", title = "Novel", cover = null, folder = root.name, lastAccess = 0.0))
        val file = root.resolve(EPUB_TRANSLATIONS_FILENAME)
        file.writeText("old translation")
        val reader = V3LocalState(repo, AiChatHistoryStore(), null)
        val first = reader.read().books.single().sentencesEtag
        assertEquals(first, reader.read().books.single().sentencesEtag)

        val replacement = root.resolve("replacement.tmp")
        replacement.writeText("new translation")
        Files.setLastModifiedTime(replacement.toPath(), Files.getLastModifiedTime(file.toPath()))
        Files.move(replacement.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        assertNotEquals(first, reader.read().books.single().sentencesEtag)

        file.delete()
        assertNull(reader.read().books.single().sentencesEtag)
    }
}
