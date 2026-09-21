package moe.antimony.hoshi.epub

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import moe.antimony.hoshi.mokuro.MangaTextStatistic
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class MangaTextStatisticsSidecarTest {
    @Test
    fun roundTripsThroughMangaStatisticsJsonAndDeduplicatesDays() = runBlocking {
        val repository = BookRepository(Files.createTempDirectory("hoshi-manga-statistics").toFile())
        val root = repository.createBookDirectory("manga-a")

        assertTrue(repository.loadMangaTextStatistics(root).isEmpty())

        repository.saveMangaTextStatistics(
            root,
            listOf(
                MangaTextStatistic("2026-09-12", 10, lastModified = 1),
                MangaTextStatistic("2026-09-12", 30, lastModified = 2),
                MangaTextStatistic("2026-09-11", 4, lastModified = 3),
            ),
        )

        assertTrue(root.resolve("manga_statistics.json").isFile)
        assertEquals(
            listOf(MangaTextStatistic("2026-09-12", 30, 2), MangaTextStatistic("2026-09-11", 4, 3)),
            repository.loadMangaTextStatistics(root),
        )
    }

    @Test
    fun savingMergesWithTheDaysAlreadyOnDiskAndConcurrentSavesNeverTearTheFile() = runBlocking {
        val repository = BookRepository(Files.createTempDirectory("hoshi-manga-merge").toFile())
        val root = repository.createBookDirectory("manga-b")
        repository.saveMangaTextStatistics(root, listOf(MangaTextStatistic("2026-09-10", 100, lastModified = 1)))

        repository.saveMangaTextStatistics(root, listOf(MangaTextStatistic("2026-09-12", 40, lastModified = 2)))
        assertEquals(
            setOf(MangaTextStatistic("2026-09-10", 100, 1), MangaTextStatistic("2026-09-12", 40, 2)),
            repository.loadMangaTextStatistics(root).toSet(),
        )

        (1..20).map { n ->
            async(Dispatchers.IO) {
                repository.saveMangaTextStatistics(root, listOf(MangaTextStatistic("2026-09-12", n, lastModified = 100L + n)))
            }
        }.awaitAll()
        val loaded = repository.loadMangaTextStatistics(root)
        assertEquals(MangaTextStatistic("2026-09-12", 20, 120L), loaded.first { it.dateKey == "2026-09-12" })
        assertTrue(root.resolve("manga_statistics.json").isFile)
        assertTrue(root.listFiles().orEmpty().none { it.name.endsWith(".tmp") })
    }
}
