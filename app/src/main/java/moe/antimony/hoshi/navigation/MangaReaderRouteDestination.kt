package moe.antimony.hoshi.navigation

import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import moe.antimony.hoshi.features.reader.ReaderSettings

/**
 * Navigation entry point for the mokuro manga reader route (`AppRoute.MangaReaderRoute`).
 *
 * This is the foundation stub. The real manga reader — a WebView that renders mokuro pages
 * with selectable OCR text boxes, wires dictionary lookup through the shared selection
 * bridge, and handles right-to-left page navigation — is implemented in the manga reader
 * feature package and replaces the body of this function.
 *
 * The signature mirrors the slice of [ReaderRouteDestination]'s contract that the manga
 * reader needs, so wiring it up in [AppShell] does not change again once the real reader
 * lands.
 */
@Composable
internal fun MangaReaderRouteDestination(
    bookId: String,
    readerSettings: ReaderSettings,
    onReaderSettingsChange: (ReaderSettings) -> Unit,
    onReaderKeyEventHandlerChange: (((KeyEvent) -> Boolean)?) -> Unit,
    onBookmarkSaved: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val systemDark = isSystemInDarkTheme()
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(readerSettings.backgroundColor(systemDark))),
        contentAlignment = Alignment.Center,
    ) {
        Text("Manga reader is not implemented yet (book: $bookId).")
    }
}
