package moe.antimony.hoshi.features.sync.http

import kotlinx.coroutines.runBlocking
import moe.antimony.hoshi.epub.ContentType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.FileTime
import java.util.concurrent.TimeUnit

class HttpSyncStatisticsValidationCacheTest {
    @get:Rule val temp = TemporaryFolder()

    private suspend fun HttpSyncStatisticsValidationCache.check(
        root: File,
        syncId: String = "book",
        contentType: ContentType = ContentType.Mokuro,
        readingPresent: Boolean = true,
        mangaPresent: Boolean = true,
        validate: suspend () -> Boolean,
    ) = hasChanges(root, syncId, contentType, readingPresent, mangaPresent, validate)

    @Test fun repeatedUnchangedPollsValidateEachHistoryOnlyOnce() = runBlocking {
        val cache = HttpSyncStatisticsValidationCache()
        val roots = List(1_001) { temp.newFolder() }
        var validations = 0
        repeat(5) {
            for (root in roots) {
                assertFalse(cache.check(root) { validations++; false })
            }
        }
        assertEquals("Only the initial poll should parse/hash each history", 1_001, validations)
    }

    @Test fun eachRelevantSidecarInvalidatesOnSameSizeReplacementEvenWithItsTimestampRestored() = runBlocking {
        for (filename in listOf("statistics.json", "manga_statistics.json", STATISTICS_SYNC_STATE_FILENAME)) {
            val root = temp.newFolder()
            val file = root.resolve(filename).apply { writeText("before") }
            val oldStamp = Files.getLastModifiedTime(file.toPath())
            val cache = HttpSyncStatisticsValidationCache()
            var validations = 0
            assertFalse(cache.check(root) { validations++; false })
            val replacement = root.resolve("replacement").apply { writeText("after!") }
            Files.setLastModifiedTime(replacement.toPath(), oldStamp)
            Files.move(replacement.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            assertEquals(oldStamp, Files.getLastModifiedTime(file.toPath()))
            assertEquals(6L, file.length())

            assertTrue(filename, cache.check(root) { validations++; true })
            assertEquals(filename, 2, validations)
        }
    }

    @Test fun fullPrecisionModificationTimeInvalidatesAnInPlaceSameSizeWrite() = runBlocking {
        val root = temp.newFolder()
        val file = root.resolve("statistics.json").apply { writeText("before") }
        val firstTime = FileTime.from(1_700_000_000_000_000_100L, TimeUnit.NANOSECONDS)
        val nextTime = FileTime.from(1_700_000_000_000_000_200L, TimeUnit.NANOSECONDS)
        Files.setLastModifiedTime(file.toPath(), firstTime)
        val cache = HttpSyncStatisticsValidationCache()
        var validations = 0
        assertFalse(cache.check(root) { validations++; false })
        file.writeText("after!")
        Files.setLastModifiedTime(file.toPath(), nextTime)
        assertEquals(firstTime.toMillis(), Files.getLastModifiedTime(file.toPath()).toMillis())

        assertTrue(cache.check(root) { validations++; true })
        assertEquals(2, validations)
    }

    @Test fun sidecarCreationDeletionAndRestoreAllRequireValidation() = runBlocking {
        for (filename in listOf("statistics.json", "manga_statistics.json", STATISTICS_SYNC_STATE_FILENAME)) {
            val root = temp.newFolder()
            val file = root.resolve(filename)
            val cache = HttpSyncStatisticsValidationCache()
            var validations = 0
            assertFalse(cache.check(root) { validations++; false })
            file.writeText("data")
            assertFalse(cache.check(root) { validations++; false })
            assertTrue(file.delete())
            assertFalse(cache.check(root) { validations++; false })
            file.writeText("restored")
            assertFalse(cache.check(root) { validations++; false })
            assertEquals(filename, 4, validations)
        }
    }

    @Test fun identityRemotePresenceAndContentTypeArePartOfTheValidation() = runBlocking {
        val root = temp.newFolder()
        val cache = HttpSyncStatisticsValidationCache()
        var validations = 0
        suspend fun validate() = false.also { validations++ }
        cache.check(root, validate = ::validate)
        cache.check(root, syncId = "another", validate = ::validate)
        cache.check(root, syncId = "another", readingPresent = false, validate = ::validate)
        cache.check(root, syncId = "another", readingPresent = false, mangaPresent = false, validate = ::validate)
        cache.check(root, syncId = "another", readingPresent = false, mangaPresent = false, contentType = ContentType.Epub, validate = ::validate)
        assertEquals(5, validations)
        root.resolve("manga_statistics.json").writeText("EPUB ignores manga history")
        cache.check(root, syncId = "another", readingPresent = false, mangaPresent = false, contentType = ContentType.Epub, validate = ::validate)
        assertEquals(5, validations)
    }

    @Test fun dirtyResultsAreNeverRememberedAndMutationsDuringValidationInvalidateTheNextPoll() = runBlocking {
        val root = temp.newFolder()
        val cache = HttpSyncStatisticsValidationCache()
        var validations = 0
        repeat(2) { assertTrue(cache.check(root) { validations++; true }) }
        assertFalse(cache.check(root) {
            validations++
            root.resolve("statistics.json").writeText("migrated during repository read")
            false
        })
        assertFalse(cache.check(root) { validations++; false })
        assertFalse(cache.check(root) { validations++; false })
        assertEquals(4, validations)
    }

    @Test fun unreadableFileShapesBypassMemoization() = runBlocking {
        val root = temp.newFolder()
        assertTrue(root.resolve("statistics.json").mkdir())
        val cache = HttpSyncStatisticsValidationCache()
        var validations = 0
        repeat(2) { assertFalse(cache.check(root) { validations++; false }) }
        assertEquals(2, validations)
    }

    @Test fun memoryIsBoundedAndEvictsTheLeastRecentlyUsedBook() = runBlocking {
        val cache = HttpSyncStatisticsValidationCache(capacity = 2)
        val a = temp.newFolder()
        val b = temp.newFolder()
        val c = temp.newFolder()
        var validations = 0
        suspend fun validate() = false.also { validations++ }
        cache.check(a, validate = ::validate)
        cache.check(b, validate = ::validate)
        cache.check(a, validate = ::validate)
        cache.check(c, validate = ::validate)
        cache.check(a, validate = ::validate)
        assertEquals(3, validations)
        cache.check(b, validate = ::validate)
        assertEquals(4, validations)
    }
}
