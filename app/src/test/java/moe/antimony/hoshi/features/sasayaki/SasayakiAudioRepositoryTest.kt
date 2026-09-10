package moe.antimony.hoshi.features.sasayaki

import moe.antimony.hoshi.epub.SasayakiPlaybackData
import java.io.IOException
import java.io.InputStream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SasayakiAudioRepositoryTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun privateCopiedAudioSourceResolvesOnlyExistingFilesUnderBookAudioDirectory() {
        val bookRoot = temporaryFolder.newFolder("book")
        val repository = SasayakiAudioRepository(bookRoot)
        val audioFile = bookRoot.resolve("Sasayaki/sasayaki_audio.m4b")
        audioFile.parentFile!!.mkdirs()
        audioFile.writeText("audio")

        val source = repository.playbackSource(playback(audioFileName = "sasayaki_audio.m4b"))

        assertEquals(SasayakiPlaybackSource.PrivateFile(audioFile.canonicalFile), source)
        assertNull(repository.playbackSource(playback(audioFileName = "../outside.m4b")))
        assertNull(repository.playbackSource(playback(audioFileName = "missing.m4b")))
    }

    @Test
    fun deleteAudioRemovesOnlyResolvedPrivateAudioFile() {
        val bookRoot = temporaryFolder.newFolder("delete-book")
        val repository = SasayakiAudioRepository(bookRoot)
        val audioFile = bookRoot.resolve("Sasayaki/sasayaki_audio.mp3")
        audioFile.parentFile!!.mkdirs()
        audioFile.writeText("audio")

        assertTrue(repository.deleteAudio(playback(audioFileName = "sasayaki_audio.mp3")))

        assertFalse(audioFile.exists())
        assertFalse(repository.deleteAudio(playback(audioFileName = "../outside.mp3")))
    }

    @Test
    fun storageSummaryDescribesPrivateCopyExternalLinkAndMissingAudio() {
        val repository = SasayakiAudioRepository(temporaryFolder.newFolder("summary-book"))

        assertEquals(
            "Copied to app storage. The original audiobook file can be deleted.",
            repository.storageSummary(playback(audioFileName = "sasayaki_audio.m4b")),
        )
        assertEquals(
            "Linked to the external audiobook file. Keep the original file available.",
            repository.storageSummary(playback(audioUri = "content://audio/book.m4b")),
        )
        assertEquals(
            "Select an .mp3 or .m4b audiobook",
            repository.storageSummary(playback()),
        )
    }

    @Test
    fun failedReplacementCopyPreservesTheExistingAudiobook() {
        val bookRoot = temporaryFolder.newFolder("failed-copy-book")
        val repository = SasayakiAudioRepository(bookRoot)
        val audioFile = bookRoot.resolve("Sasayaki/sasayaki_audio.m4b")
        audioFile.parentFile!!.mkdirs()
        audioFile.writeText("existing audiobook")
        val failingInput = object : InputStream() {
            private var reads = 0
            override fun read(): Int {
                if (reads++ < 10) return 1
                throw IOException("Provider disconnected")
            }
        }

        assertThrows(IOException::class.java) {
            failingInput.use { repository.copyAudio(it, "m4b") }
        }

        assertEquals("existing audiobook", audioFile.readText())
        assertEquals(listOf(audioFile.name), audioFile.parentFile!!.list()!!.toList())
    }

    @Test
    fun successfulReplacementCopyPublishesTheCompleteAudiobook() {
        val bookRoot = temporaryFolder.newFolder("successful-copy-book")
        val repository = SasayakiAudioRepository(bookRoot)
        val audioFile = bookRoot.resolve("Sasayaki/sasayaki_audio.mp3")
        audioFile.parentFile!!.mkdirs()
        audioFile.writeText("old audiobook")

        val name = "new audiobook".byteInputStream().use { repository.copyAudio(it, "mp3") }

        assertEquals(audioFile.name, name)
        assertEquals("new audiobook", audioFile.readText())
        assertEquals(listOf(audioFile.name), audioFile.parentFile!!.list()!!.toList())
    }

    private fun playback(
        audioUri: String? = null,
        audioFileName: String? = null,
    ): SasayakiPlaybackData =
        SasayakiPlaybackData(
            lastPosition = 0.0,
            audioUri = audioUri,
            audioFileName = audioFileName,
        )
}
