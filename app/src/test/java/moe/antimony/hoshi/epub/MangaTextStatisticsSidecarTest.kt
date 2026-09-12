package moe.antimony.hoshi.epub

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
}
