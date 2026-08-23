package moe.antimony.hoshi.navigation

import androidx.navigation3.runtime.NavKey
import moe.antimony.hoshi.epub.ContentType

internal fun MutableList<NavKey>.popAppRoute() {
    if (size > 1) {
        removeAt(lastIndex)
    }
}

internal fun MutableList<NavKey>.selectTopLevelRoute(route: AppRoute) {
    if (size == 1 && lastOrNull() == route) {
        return
    }
    clear()
    add(route)
}

internal fun MutableList<NavKey>.openReaderRoute(bookId: String) {
    selectTopLevelRoute(AppRoute.BooksRoute)
    add(AppRoute.ReaderRoute(bookId))
}

internal fun MutableList<NavKey>.openMangaReaderRoute(bookId: String) {
    selectTopLevelRoute(AppRoute.BooksRoute)
    add(AppRoute.MangaReaderRoute(bookId))
}

internal fun MutableList<NavKey>.openBookRoute(bookId: String, contentType: ContentType) {
    when (contentType) {
        ContentType.Epub -> openReaderRoute(bookId)
        ContentType.Mokuro -> openMangaReaderRoute(bookId)
    }
}

internal fun MutableList<NavKey>.openSasayakiMatchRoute(bookId: String) {
    selectTopLevelRoute(AppRoute.BooksRoute)
    add(AppRoute.SasayakiMatchRoute(bookId))
}

internal fun MutableList<NavKey>.routeExternalBookImport() {
    selectTopLevelRoute(AppRoute.BooksRoute)
}

internal fun MutableList<NavKey>.returnFromMediaSession() = Unit
