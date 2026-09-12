package moe.antimony.hoshi.features.mangareader

import kotlinx.coroutines.runBlocking
import moe.antimony.hoshi.epub.BookMetadata
import moe.antimony.hoshi.epub.BookRepository
import moe.antimony.hoshi.epub.MOKURO_SIDECAR_FILE
import moe.antimony.hoshi.features.sync.http.syncIdForMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * [MangaReaderLoader] runs the pre-open hook (sync lease + time-bounded map refresh) before
 * reading the bookmark, so a remote bookmark that lands inside the hook is the page the
 * reader opens on, and one that lands later still reaches the reader via the route's early
 * `remoteBookmarkUpdates` subscription.
 */
class MangaReaderLoaderTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val fivePages = """
        {
          "title": "Series",
          "volume": "Volume 1",
          "pages": [
            ${(0 until 5).joinToString(",") { """{"img_width": 800, "img_height": 1200, "img_path": "p$it.jpg", "blocks": []}""" }}
          ]
        }
    """.trimIndent()

    private suspend fun BookRepository.newManga(): Pair<File, BookMetadata> {
        val root = createBookDirectory("manga")
        root.resolve(MOKURO_SIDECAR_FILE).writeText(fivePages)
        val metadata = BookMetadata(
            id = "manga-id",
            title = "Series Volume 1",
            cover = null,
            folder = root.name,
            lastAccess = 1.0,
        )
        saveMetadata(root, metadata)
        return root to metadata
    }

    @Test
    fun opensOnTheLocalBookmarkAndHandsTheSyncIdToThePreOpenHook() = runBlocking {
        val repository = BookRepository(temporaryFolder.newFolder())
        val (root, metadata) = repository.newManga()
        repository.saveBookmark(root, mangaBookmark(pageIndex = 3, lastModifiedSeconds = 10.0))
        val hookSyncIds = mutableListOf<String?>()

        val state = MangaReaderLoader(repository).load("manga-id", beforeParse = { syncId -> hookSyncIds += syncId })

        assertTrue(state.toString(), state is MangaReaderLoadState.Ready)
        state as MangaReaderLoadState.Ready
        assertEquals(3, state.initialPageIndex)
        assertEquals(root, state.bookRoot)
        assertEquals(listOf(syncIdForMetadata(metadata)), hookSyncIds)
        assertEquals(syncIdForMetadata(metadata), state.syncId)
    }

    @Test
    fun aBookmarkWrittenDuringThePreOpenHookIsThePageTheReaderOpensOn() = runBlocking {
        val repository = BookRepository(temporaryFolder.newFolder())
        val (root, _) = repository.newManga()
        repository.saveBookmark(root, mangaBookmark(pageIndex = 1, lastModifiedSeconds = 10.0))

        val state = MangaReaderLoader(repository).load(
            bookId = "manga-id",
            beforeParse = {
                // What HttpSyncBatchState.syncMaps does when the remote bookmark wins.
                repository.saveBookmark(root, mangaBookmark(pageIndex = 4, lastModifiedSeconds = 20.0))
            },
        )

        assertEquals(4, (state as MangaReaderLoadState.Ready).initialPageIndex)
    }

    @Test
    fun beforeBookmarkReadRunsAfterTheParseAndRightBeforeTheBookmarkIsRead() = runBlocking {
        val repository = BookRepository(temporaryFolder.newFolder())
        val (root, _) = repository.newManga()
        repository.saveBookmark(root, mangaBookmark(pageIndex = 1, lastModifiedSeconds = 10.0))
        val order = mutableListOf<String>()

        val state = MangaReaderLoader(repository).load(
            bookId = "manga-id",
            beforeParse = { order += "beforeParse" },
            beforeBookmarkRead = {
                order += "beforeBookmarkRead"
                // A remote winner landing at this very moment is still what this load opens on,
                // which is why the route only starts reloading on remote updates from here.
                runBlocking {
                    repository.saveBookmark(root, mangaBookmark(pageIndex = 2, lastModifiedSeconds = 20.0))
                }
            },
        )

        assertEquals(listOf("beforeParse", "beforeBookmarkRead"), order)
        assertEquals(2, (state as MangaReaderLoadState.Ready).initialPageIndex)
    }

    @Test
    fun aBookmarkPastTheLastPageIsClampedIntoTheBook() = runBlocking {
        val repository = BookRepository(temporaryFolder.newFolder())
        val (root, _) = repository.newManga()
        repository.saveBookmark(root, mangaBookmark(pageIndex = 40, lastModifiedSeconds = 10.0))

        val state = MangaReaderLoader(repository).load("manga-id")

        assertEquals(4, (state as MangaReaderLoadState.Ready).initialPageIndex)
    }

    @Test
    fun aHookFailureSurfacesAsAnErrorInsteadOfCrashingTheRoute() = runBlocking {
        val repository = BookRepository(temporaryFolder.newFolder())
        repository.newManga()

        val state = MangaReaderLoader(repository).load("manga-id", beforeParse = { error("sync exploded") })

        assertEquals(MangaReaderLoadState.Error("sync exploded"), state)
    }
}
