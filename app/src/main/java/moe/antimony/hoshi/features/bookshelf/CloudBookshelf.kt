package moe.antimony.hoshi.features.bookshelf

import android.text.format.Formatter
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.SyncProblem
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import moe.antimony.hoshi.R
import moe.antimony.hoshi.epub.BookSortOption
import moe.antimony.hoshi.features.sync.http.HttpSyncCloudBookRecord
import moe.antimony.hoshi.ui.theme.LocalHoshiEInkMode
import java.io.File

/** Section key of the "Not downloaded" folder in the shelf's expansion state. */
internal const val CLOUD_SECTION_KEY = "__cloud__"

/** What a cloud manga's cover shows. */
internal sealed interface CloudBookState {
    /** On the server only; a tap downloads it. */
    data object Available : CloudBookState

    /** Tapped; the sync that downloads it has not reached it yet. */
    data object Waiting : CloudBookState

    data class Downloading(val fraction: Float) : CloudBookState

    /** The last requested download failed; a tap tries again. */
    data class Failed(val message: String) : CloudBookState
}

internal data class CloudBookItem(
    val syncId: String,
    val title: String,
    val sizeBytes: Long,
    val coverSource: BookCoverSource?,
    val state: CloudBookState,
) {
    val canDownload: Boolean get() = state is CloudBookState.Available || state is CloudBookState.Failed
}

/**
 * Shelf items for the cloud catalogue. A manga already installed here is left out (another
 * engine may have downloaded it before the catalogue caught up). A request only shows as
 * waiting while a sync is running to carry it out; otherwise the book can be tapped again.
 */
internal fun cloudBookItems(
    records: List<HttpSyncCloudBookRecord>,
    downloads: Map<String, Float?>,
    installedSyncIds: Set<String>,
    syncRunning: Boolean,
    sortOption: BookSortOption,
    coverFile: (HttpSyncCloudBookRecord) -> File?,
): List<CloudBookItem> {
    val visible = records.filter { it.syncId !in installedSyncIds }
    val sorted = when (sortOption) {
        BookSortOption.Recent -> visible.sortedByDescending { it.discoveredAt }
        BookSortOption.Title -> visible.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.title })
    }
    return sorted.map { record ->
        val state = when {
            record.syncId in downloads -> downloads[record.syncId]
                ?.let { CloudBookState.Downloading(it) }
                ?: CloudBookState.Waiting
            record.downloadRequestedAt != null && syncRunning -> CloudBookState.Waiting
            record.downloadError != null -> CloudBookState.Failed(record.downloadError)
            else -> CloudBookState.Available
        }
        CloudBookItem(
            syncId = record.syncId,
            title = record.title,
            sizeBytes = record.sizeBytes,
            coverSource = coverFile(record)?.let { file ->
                BookCoverSource(path = file.absolutePath, cacheKey = "cloud:${file.name}")
            },
            state = state,
        )
    }
}

/**
 * The cover of a cloud manga: a cloud badge, or faded under a filling ring while it downloads.
 * Decorative for accessibility: the cell's text says the same.
 */
@Composable
internal fun CloudBookCover(item: CloudBookItem, modifier: Modifier = Modifier) {
    val transferring = item.state is CloudBookState.Waiting || item.state is CloudBookState.Downloading
    val eInk = LocalHoshiEInkMode.current
    val colors = MaterialTheme.colorScheme
    Box(modifier = modifier) {
        BookCoverCard(
            coverSource = item.coverSource,
            modifier = Modifier.alpha(if (transferring) 0.35f else 1f),
        )
        // A disc keeps the ring readable on any cover, and outlined on E-ink.
        val ringBackground = Modifier
            .align(Alignment.Center)
            .size(52.dp)
            .background(colors.surface.copy(alpha = if (eInk) 1f else 0.85f), CircleShape)
            .then(if (eInk) Modifier.border(1.dp, colors.onSurface, CircleShape) else Modifier)
            .padding(5.dp)
        val ringColor = if (eInk) colors.onSurface else colors.primary
        when (val state = item.state) {
            CloudBookState.Waiting -> Box(ringBackground) {
                CircularProgressIndicator(modifier = Modifier.fillMaxSize(), color = ringColor, strokeWidth = 4.dp)
            }
            is CloudBookState.Downloading -> Box(ringBackground) {
                CircularProgressIndicator(
                    progress = { state.fraction },
                    modifier = Modifier.fillMaxSize(),
                    color = ringColor,
                    strokeWidth = 4.dp,
                    // E-ink outlines are the content colour, which would draw the ring as full.
                    trackColor = if (eInk) Color.Transparent else colors.surfaceVariant,
                )
            }
            CloudBookState.Available, is CloudBookState.Failed -> Icon(
                imageVector = if (state is CloudBookState.Failed) Icons.Rounded.SyncProblem else Icons.Rounded.CloudDownload,
                contentDescription = null,
                tint = when {
                    eInk -> colors.onSurface
                    state is CloudBookState.Failed -> colors.error
                    else -> colors.primary
                },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(8.dp)
                    .background(colors.background, CircleShape)
                    .then(if (eInk) Modifier.border(1.dp, colors.onSurface, CircleShape) else Modifier)
                    .padding(3.dp)
                    .size(22.dp),
            )
        }
    }
}

@Composable
internal fun CloudBookGridCell(
    item: CloudBookItem,
    layoutSpec: MainShellLayoutSpec,
    enabled: Boolean,
    onDownload: () -> Unit,
) {
    val context = LocalContext.current
    val status = when (val state = item.state) {
        CloudBookState.Available -> stringResource(
            R.string.bookshelf_cloud_size_format,
            Formatter.formatShortFileSize(context, item.sizeBytes),
        )
        CloudBookState.Waiting -> stringResource(R.string.bookshelf_cloud_waiting)
        // Downloaded: its bookmark, chats and statistics are being applied.
        is CloudBookState.Downloading -> if (state.fraction >= 1f) {
            stringResource(R.string.bookshelf_cloud_finishing)
        } else {
            stringResource(R.string.bookshelf_cloud_downloading_format, (state.fraction * 100).toInt())
        }
        is CloudBookState.Failed -> stringResource(R.string.bookshelf_cloud_failed)
    }
    Column(
        modifier = Modifier.clickable(
            enabled = enabled && item.canDownload,
            onClickLabel = stringResource(R.string.bookshelf_cloud_download),
            onClick = onDownload,
        ),
    ) {
        CloudBookCover(item)
        Spacer(Modifier.height(6.dp))
        Text(
            text = status,
            style = MaterialTheme.typography.labelLarge,
            color = if (item.state is CloudBookState.Failed) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = item.title,
            style = layoutSpec.bookTitleTextStyle.toTextStyle(),
            fontWeight = layoutSpec.bookTitleFontWeight.toFontWeight(),
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
