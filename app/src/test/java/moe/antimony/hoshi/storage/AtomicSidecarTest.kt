package moe.antimony.hoshi.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class AtomicSidecarTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test
    fun replacesAnExistingSidecarOnlyAfterTheNewContentsAreComplete() {
        val file = temporaryFolder.newFile("statistics.json").apply { writeText("old") }

        writeSidecarAtomically(file, "new") { source, target ->
            assertEquals("old", target.readText())
            assertEquals("new", source.readText())
            source.renameTo(target)
        }

        assertEquals("new", file.readText())
        assertEquals(listOf(file), temporaryFolder.root.listFiles()!!.toList())
    }

    @Test
    fun failedRenamePreservesThePreviousSidecarAndCleansTheTemporaryFile() {
        val file = temporaryFolder.newFile("shelves.json").apply { writeText("old") }

        assertThrows(IOException::class.java) {
            writeSidecarAtomically(file, "new") { _, _ -> false }
        }

        assertEquals("old", file.readText())
        assertEquals(listOf(file), temporaryFolder.root.listFiles()!!.toList())
    }

    @Test
    fun failedPublishCleansTemporaryFileEvenWhenRenameThrows() {
        val file = temporaryFolder.newFile("metadata.json").apply { writeText("old") }

        assertThrows(SecurityException::class.java) {
            writeSidecarAtomically(file, "new") { _, _ -> throw SecurityException("Denied") }
        }

        assertEquals("old", file.readText())
        assertEquals(listOf(file), temporaryFolder.root.listFiles()!!.toList())
    }

    @Test
    fun concurrentWritersPublishOnlyTheirOwnCompleteContents() {
        val file = temporaryFolder.newFile("bookmark.json")
        val ready = CountDownLatch(2)
        val workers = Executors.newFixedThreadPool(2)
        try {
            val sources = listOf("first".repeat(10_000), "second".repeat(10_000)).map { text ->
                workers.submit<File> {
                    var ownTemporary: File? = null
                    writeSidecarAtomically(file, text) { source, target ->
                        ownTemporary = source
                        ready.countDown()
                        assertTrue(ready.await(5, TimeUnit.SECONDS))
                        assertEquals(text, source.readText())
                        source.renameTo(target)
                    }
                    ownTemporary!!
                }
            }.map { it.get(10, TimeUnit.SECONDS) }

            assertEquals(2, sources.toSet().size)
            assertTrue(file.readText() in listOf("first".repeat(10_000), "second".repeat(10_000)))
            assertEquals(listOf(file), temporaryFolder.root.listFiles()!!.toList())
        } finally {
            workers.shutdownNow()
        }
    }
}
