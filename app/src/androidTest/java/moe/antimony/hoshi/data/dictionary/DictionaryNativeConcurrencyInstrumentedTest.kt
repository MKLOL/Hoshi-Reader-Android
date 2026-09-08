package moe.antimony.hoshi.data.dictionary

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import de.manhhao.hoshi.HoshiDicts
import moe.antimony.hoshi.features.reader.sentence.sentenceLookupQuery
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Uses its own imported fixture and native session, never the reader's dictionary query or files. */
@RunWith(AndroidJUnit4::class)
class DictionaryNativeConcurrencyInstrumentedTest {
    @Test
    fun concurrentRebuildPreservesLookupStylesAndMedia() = withFixture { fixture ->
        val ready = CountDownLatch(4)
        val start = CountDownLatch(1)
        val failures = ConcurrentLinkedQueue<Throwable>()
        val operations: List<Pair<String, () -> Unit>> = listOf(
            "rebuild" to { fixture.rebuild() },
            "lookup" to {
                fixture.assertLookup("食べた", "食べる", "たべる")
                fixture.assertLookup("食べる𠮷", "食べる", "たべる")
                fixture.assertLookup("𠮷野", "𠮷野", "よしの")
            },
            "styles" to { fixture.assertStyles() },
            "media" to { fixture.assertMedia() },
        )
        operations.forEach { (name, operation) ->
            fixture.worker(name) {
                try {
                    ready.countDown()
                    assertTrue("Workers were not released", start.await(5, TimeUnit.SECONDS))
                    repeat(200) { operation() }
                } catch (error: Throwable) {
                    failures.add(error)
                }
            }.start()
        }
        try {
            assertTrue("Workers did not become ready", ready.await(5, TimeUnit.SECONDS))
        } finally {
            start.countDown()
        }
        fixture.joinWorkers()
        failures.peek()?.let { throw AssertionError("Concurrent native dictionary operation failed", it) }
        fixture.assertLookup("食べる", "食べる", "たべる")
        val sentenceQuery = requireNotNull(sentenceLookupQuery("食べる" + "あ".repeat(28) + "𠮷野", 0))
        fixture.assertLookup(sentenceQuery.text, "食べる", "たべる")
        fixture.assertStyles()
        fixture.assertMedia()
    }

    @Test
    fun nativeReadsAndRebuildWaitForSharedSessionMonitor() = withFixture { fixture ->
        val operations: List<Pair<String, () -> Unit>> = listOf(
            "rebuild" to { fixture.rebuild() },
            "lookup" to { fixture.assertLookup("食べる", "食べる", "たべる") },
            "styles" to { fixture.assertStyles() },
            "media" to { fixture.assertMedia() },
        )
        operations.forEach { (name, operation) ->
            val entered = CountDownLatch(1)
            val finished = CountDownLatch(1)
            val failures = ConcurrentLinkedQueue<Throwable>()
            val worker = fixture.worker("monitor-$name") {
                try {
                    entered.countDown()
                    operation()
                } catch (error: Throwable) {
                    failures.add(error)
                } finally {
                    finished.countDown()
                }
            }
            synchronized(HoshiDicts) {
                worker.start()
                assertTrue("$name worker did not start", entered.await(5, TimeUnit.SECONDS))
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                while (worker.isAlive && worker.state != Thread.State.BLOCKED && System.nanoTime() < deadline) {
                    Thread.sleep(1)
                }
                assertEquals("$name must wait for the shared native-session monitor", Thread.State.BLOCKED, worker.state)
                assertEquals("$name ran while another thread held the monitor", 1L, finished.count)
            }
            fixture.joinWorkers()
            assertEquals("$name did not finish after monitor release", 0L, finished.count)
            failures.peek()?.let { throw AssertionError("Native $name operation failed", it) }
        }
    }

    private fun withFixture(block: (Fixture) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.cacheDir, "native-dictionary-concurrency-${UUID.randomUUID()}")
        assertTrue(directory.mkdirs())
        var fixture: Fixture? = null
        try {
            val archive = File(directory, "fixture.zip")
            ZipOutputStream(archive.outputStream()).use { zip ->
                fun entry(name: String, bytes: ByteArray) {
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(bytes)
                    zip.closeEntry()
                }
                entry("index.json", """{"title":"$DictionaryTitle","format":3,"revision":"1","sequenced":true}""".toByteArray())
                entry(
                    "term_bank_1.json",
                    """[["食べる","たべる","","v1",1,["to eat"],1,""],["𠮷野","よしの","","",1,["Yoshino"],2,""]]""".toByteArray(),
                )
                entry("styles.css", DictionaryStyles.toByteArray())
                entry(MediaPath, MediaBytes)
            }
            val imported = HoshiDicts.importDictionary(archive.absolutePath, directory.absolutePath, true)
            assertTrue("Fixture dictionary import failed", imported.success)
            assertEquals(2L, imported.termCount)
            assertEquals(1L, imported.mediaCount)
            val session = HoshiDicts.createLookupObject()
            assertTrue("Native session was not created", session != 0L)
            fixture = Fixture(session, File(directory, DictionaryTitle))
            fixture.rebuild()
            block(fixture)
        } finally {
            // Never free/unmap the fixture while a failed or delayed worker can still read it.
            // A timed-out worker fails this cleanup before either destroy or file removal.
            fixture?.joinWorkers()
            fixture?.let { HoshiDicts.destroyLookupObject(it.session) }
            directory.deleteRecursively()
        }
    }

    private class Fixture(val session: Long, private val dictionary: File) {
        private val workers = mutableListOf<Thread>()

        fun worker(name: String, action: () -> Unit): Thread =
            Thread(action, "native-dictionary-$name").also(workers::add)

        fun joinWorkers() {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
            workers.forEach { worker ->
                if (worker.isAlive) {
                    val remainingMillis = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()).coerceAtLeast(1)
                    worker.join(remainingMillis)
                }
            }
            assertTrue("Native workers did not stop; retaining their session and fixture", workers.none { it.isAlive })
        }

        fun rebuild() {
            HoshiDicts.rebuildQuery(session, arrayOf(dictionary.absolutePath), emptyArray(), emptyArray())
        }

        fun assertLookup(query: String, expression: String, reading: String) {
            val results = HoshiDicts.lookup(session, query, 16, 16)
            assertEquals("Unexpected results for $query", 1, results.size)
            val result = results.single()
            assertEquals(expression, result.term.expression)
            assertEquals(reading, result.term.reading)
            assertEquals(DictionaryTitle, result.term.glossaries.single().dictName)
            assertEquals(if (expression == "食べる") """["to eat"]""" else """["Yoshino"]""", result.term.glossaries.single().glossary)
        }

        fun assertStyles() {
            val styles = HoshiDicts.getStyles(session)
            assertEquals(1, styles.size)
            assertEquals(DictionaryTitle, styles.single().dictName)
            assertEquals(DictionaryStyles, styles.single().styles)
        }

        fun assertMedia() {
            assertArrayEquals(MediaBytes, HoshiDicts.getMediaFile(session, DictionaryTitle, MediaPath))
        }
    }

    private companion object {
        const val DictionaryTitle = "Native concurrency fixture"
        const val DictionaryStyles = ".glossary-content { color: #123456; }"
        const val MediaPath = "media/fixture.bin"
        val MediaBytes = ByteArray(1024) { (it % 256).toByte() }
    }
}
