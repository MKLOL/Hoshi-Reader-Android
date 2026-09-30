package moe.antimony.hoshi.features.sync.http

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import android.content.Context
import kotlinx.coroutines.runBlocking
import moe.antimony.hoshi.epub.ContentType
import moe.antimony.hoshi.storage.writeSidecarAtomically
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.file.Files

@RunWith(AndroidJUnit4::class)
class HttpSyncStatisticsCacheDeviceTest {
    @Test
    fun androidFileIdentityReusesUnchangedHistoryAndDetectsAtomicReplacement() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val directory = Files.createTempDirectory(context.filesDir.toPath(), "statistics-cache-test").toFile()
        try {
            val roots = List(100) { index ->
                directory.resolve("book-$index").apply {
                    mkdirs()
                    resolve("statistics.json").writeText("before")
                }
            }
            val cache = HttpSyncStatisticsValidationCache()
            var validations = 0
            repeat(5) {
                for (root in roots) {
                    assertFalse(cache.hasChanges(root, root.name, ContentType.Epub, true, false) {
                        validations++
                        false
                    })
                }
            }
            assertEquals("Android app storage must support unchanged-history reuse", 100, validations)

            val root = roots.first()
            val history = root.resolve("statistics.json")
            val timestamp = Files.getLastModifiedTime(history.toPath())
            writeSidecarAtomically(history, "after!")
            Files.setLastModifiedTime(history.toPath(), timestamp)
            assertEquals(6L, history.length())
            assertEquals(timestamp, Files.getLastModifiedTime(history.toPath()))
            assertTrue(cache.hasChanges(root, root.name, ContentType.Epub, true, false) {
                validations++
                true
            })
            assertEquals("Replacement must be detected even with matching size and time", 101, validations)
        } finally {
            directory.deleteRecursively()
        }
    }
}
