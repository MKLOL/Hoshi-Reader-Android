package moe.antimony.hoshi.epub

import kotlinx.serialization.Serializable
import java.io.File

@Serializable
data class Bookmark(
    val chapterIndex: Int,
    val progress: Double,
    val characterCount: Int,
    val lastModified: Double? = null,
)

@Serializable
data class BookInfo(
    val characterCount: Int,
    val chapterInfo: Map<String, ChapterInfo>,
) {
    @Serializable
    data class ChapterInfo(
        val spineIndex: Int?,
        val currentTotal: Int,
        val chapterCount: Int,
    )
}

@Serializable
data class BookMetadata(
    val id: String,
    val title: String?,
    val cover: String?,
    val folder: String?,
    val lastAccess: Double,
    /** Stable HTTP-sync identity. New imports persist it; legacy records derive it on read. */
    val syncId: String? = null,
    /**
     * RFC 3339 UTC timestamp recording when this book was imported on the current device.
     * Optional for older books written before this field existed; new imports populate it
     * (user-side import in [moe.antimony.hoshi.features.bookshelf.BookshelfRepository],
     * v2 sync import in [moe.antimony.hoshi.features.sync.http.HttpSyncReconciler], and v3
     * sync import in [moe.antimony.hoshi.features.sync.v3.V3Executor]).
     *
     * Used by both sync engines to defeat a stale remote tombstone after a re-import: if
     * the local `importedAt` is strictly greater than the remote metadata's `deletedAt`,
     * the user re-imported the book *after* the deletion was published — the engines
     * overwrite the tombstone instead of deleting the freshly-imported local copy.
     */
    val importedAt: String? = null,
    val renamedTitle: String? = null,
) {
    val displayTitle: String
        get() = renamedTitle?.takeIf { it.isNotBlank() } ?: title.orEmpty()
}

@Serializable
data class BookShelf(
    val name: String,
    val bookIds: List<String>,
)

data class BookEntry(
    val root: File,
    val metadata: BookMetadata,
) {
    val displayTitle: String
        get() = metadata.displayTitle.ifBlank { root.name }
}

enum class BookSortOption {
    Recent,
    Title,
}
