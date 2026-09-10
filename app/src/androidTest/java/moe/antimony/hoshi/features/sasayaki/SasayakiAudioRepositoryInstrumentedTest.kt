package moe.antimony.hoshi.features.sasayaki

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith

/** Dedicated-emulator coverage of atomic replacement on Android app-private storage. */
@RunWith(AndroidJUnit4::class)
class SasayakiAudioRepositoryInstrumentedTest {
    @Test
    fun failedCopyPreservesExistingAudioAndSuccessfulCopyReplacesIt() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val bookRoot = File(context.cacheDir, "sasayaki-copy-test-${UUID.randomUUID()}")
        try {
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

            val copiedName = "replacement audiobook".byteInputStream().use {
                repository.copyAudio(it, "m4b")
            }
            assertEquals(audioFile.name, copiedName)
            assertEquals("replacement audiobook", audioFile.readText())
            assertEquals(listOf(audioFile.name), audioFile.parentFile!!.list()!!.toList())
        } finally {
            bookRoot.deleteRecursively()
        }
    }
}
