package moe.antimony.hoshi.features.reader

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import moe.antimony.hoshi.epub.BookRepository
import moe.antimony.hoshi.epub.ContentType
import moe.antimony.hoshi.epub.ReadingStatistics
import moe.antimony.hoshi.epub.STATISTICS_SYNC_STATE_FILE_NAME
import moe.antimony.hoshi.epub.bookContentType
import moe.antimony.hoshi.features.bookshelf.isBookCompleted
import moe.antimony.hoshi.features.bookshelf.toBookCoverSource
import moe.antimony.hoshi.features.statistics.charactersByDate
import moe.antimony.hoshi.features.statistics.readingDays
import moe.antimony.hoshi.features.sync.http.syncIdForMetadata
import moe.antimony.hoshi.mokuro.MangaTextStatistic
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime
import java.time.LocalDate
import java.util.TreeMap

/**
 * Reuses unchanged sidecars and their reading-day totals across Statistics refreshes.
 * A changed sidecar is cumulative, so compare its calendar rows before regrouping hours.
 * Each row can change several reading days, including days outside the viewer's recent window.
 */
internal class ReadingStatisticsOverviewCache(
    private val project: (BookStatisticsInput, Int) -> List<DailyReading> = { input, resetHour ->
        readingDays(input.statistics, input.charactersByDate(), resetHour)
    },
) {
    private val loadLock = Mutex()
    private val books = mutableMapOf<String, CachedBook>()
    private val totals = ReadingDateTotals()
    private var previousCutoff: String? = null
    private var previousReset: Int? = null
    private var olderDays = emptyList<DailyReading>()
    private var historyRevision = Any()

    suspend fun load(repository: BookRepository, todayKey: String, resetHour: Int): ReadingStatisticsOverview =
        withContext(Dispatchers.IO) {
            loadLock.withLock {
                val history = repository.loadStatisticsHistory().associateBy { it.syncId }
                val joined = mutableSetOf<String>()
                val seen = mutableSetOf<String>()
                val summaries = mutableListOf<BookReadingSummary>()

                suspend fun add(root: File, retainedRoot: File?, input: BookStatisticsInput) {
                    val path = root.absolutePath
                    seen += path
                    val book = books.getOrPut(path) { CachedBook() }
                    val source = captureSource(repository, root, retainedRoot, input.contentType)
                    val reread = source == null || source != book.source
                    if (reread) {
                        val roots = listOfNotNull(root, retainedRoot)
                        val reading = roots.flatMap { repository.loadStatistics(it) }
                        val manga = if (input.contentType == ContentType.Mokuro) {
                            roots.flatMap { repository.loadMangaTextStatistics(it) }
                        } else emptyList()
                        book.reading = reading
                        book.manga = manga
                        // A read may migrate or absorb history. A racing write must also be seen
                        // on the next refresh, so never cache a fingerprint taken after the read alone.
                        book.source = source?.takeIf { it == captureSource(repository, root, retainedRoot, input.contentType) }
                    }
                    if (reread || book.resetHour != resetHour) {
                        for (day in book.update(input, resetHour)) {
                            totals.set(path, day, book.totals.days[day].takeIf { book.summary != null })
                        }
                    }
                    book.summary?.let { summary ->
                        summaries += summary.copy(
                            bookId = input.bookId,
                            title = input.title,
                            progress = input.progress.coerceIn(0.0, 1.0),
                            finished = isBookCompleted(input.progress),
                            coverSource = input.coverSource,
                            onDevice = input.onDevice,
                            progressKnown = input.progressKnown,
                        )
                    }
                }

                for (entry in repository.loadBookEntries()) {
                    // Retained history may still exist until this read folds it into a reinstalled book.
                    val retained = syncIdForMetadata(entry.metadata)?.takeIf(joined::add)?.let(history::get)
                    add(entry.root, retained?.root, BookStatisticsInput(
                        bookId = entry.metadata.id,
                        title = entry.displayTitle,
                        contentType = bookContentType(entry.root),
                        statistics = emptyList(),
                        progress = repository.loadReadingProgress(entry.root),
                        coverSource = repository.coverFile(entry)?.toBookCoverSource(),
                    ))
                }
                for ((id, retained) in history) {
                    if (id in joined) continue
                    add(retained.root, null, BookStatisticsInput(
                        bookId = STATISTICS_HISTORY_BOOK_ID_PREFIX + id,
                        title = retained.title,
                        contentType = retained.contentType,
                        statistics = emptyList(),
                        progress = retained.progress ?: 0.0,
                        onDevice = false,
                        progressKnown = retained.progress != null,
                    ))
                }
                for (path in books.keys.toList() - seen) {
                    books.remove(path)?.totals?.days?.keys?.forEach { day -> totals.set(path, day, null) }
                }

                val today = runCatching { LocalDate.parse(todayKey) }.getOrNull()
                val cutoff = today?.minusDays(2)?.toString()
                val previous = previousCutoff
                fun isOlder(day: String): Boolean =
                    (cutoff != null && day < cutoff) || (previous != null && day < previous)
                var olderSecondsChanged = false
                val changed = totals.refresh { if (isOlder(it)) olderSecondsChanged = true }
                if (olderSecondsChanged || resetHour != previousReset) historyRevision = Any()
                if (cutoff != previous || changed.any(::isOlder)) {
                    olderDays = cutoff?.let { totals.days.headMap(it, false).descendingMap().values.toList() }.orEmpty()
                }
                previousCutoff = cutoff
                previousReset = resetHour
                val recent = cutoff?.let { totals.days.tailMap(it, true).descendingMap().values.toList() }
                val daily = if (recent != null) recent + olderDays else totals.days.descendingMap().values.toList()
                summarizeBooks(summaries, daily, todayKey).copy(
                    olderStreakDays = olderDays.takeIf { today != null },
                    recentStreakDays = recent?.filter { it.dateKey <= todayKey },
                    streakHistoryRevision = historyRevision.takeIf { today != null },
                    streakWindowDay = today,
                )
            }
        }

    private inner class CachedBook {
        var source: Source? = null
        var reading: List<ReadingStatistics> = emptyList()
        var manga: List<MangaTextStatistic> = emptyList()
        var resetHour: Int? = null
            private set
        var summary: BookReadingSummary? = null
            private set
        val totals = ReadingDateTotals()
        private val calendarRows = mutableMapOf<String, CalendarRows>()

        fun update(input: BookStatisticsInput, reset: Int): Set<String> {
            val wasListed = summary != null
            val readingByDate = reading.groupBy { it.dateKey }
            val mangaByDate = manga.groupBy { it.dateKey }
            for (date in calendarRows.keys + readingByDate.keys + mangaByDate.keys) {
                val previous = calendarRows[date]
                val read = readingByDate[date].orEmpty()
                val text = mangaByDate[date].orEmpty()
                if (resetHour == reset && previous != null && previous.reading == read && previous.manga == text) continue
                previous?.days?.forEach { totals.set(date, it.dateKey, null) }
                if (read.isEmpty() && text.isEmpty()) {
                    calendarRows.remove(date)
                } else {
                    val days = project(input.copy(statistics = read, mangaTextStatistics = text), reset)
                    days.forEach { totals.set(date, it.dateKey, it) }
                    calendarRows[date] = CalendarRows(read, text, days)
                }
            }
            resetHour = reset
            val changed = totals.refresh()
            summary = summarizeBookReading(
                input.copy(statistics = reading, mangaTextStatistics = manga),
                totals.days.descendingMap().values.toList(),
                reset,
            )
            return if (wasListed != (summary != null)) changed + totals.days.keys else changed
        }
    }

    private data class CalendarRows(
        val reading: List<ReadingStatistics>,
        val manga: List<MangaTextStatistic>,
        val days: List<DailyReading>,
    )
}

/** Calendar rows within a book, and books within the library, both contribute to daily totals. */
private class ReadingDateTotals {
    private val sources = mutableMapOf<String, MutableMap<String, DailyReading>>()
    private val dirty = mutableSetOf<String>()
    val days = TreeMap<String, DailyReading>()

    fun set(source: String, date: String, contribution: DailyReading?) {
        if (contribution == null) {
            val onDay = sources[date] ?: return
            if (onDay.remove(source) == null) return
            if (onDay.isEmpty()) sources.remove(date)
        } else {
            val onDay = sources.getOrPut(date) { sortedMapOf() }
            if (onDay.put(source, contribution) == contribution) return
        }
        dirty += date
    }

    fun refresh(onSecondsChanged: (String) -> Unit = {}): Set<String> = buildSet {
        for (date in dirty) {
            val contributions = sources[date]?.values
            val next = contributions?.let { DailyReading(date, it.sumOf { day -> day.seconds }, it.sumOf { day -> day.characters }) }
            val previous = days[date]
            if (previous == next) continue
            if ((previous?.seconds ?: 0.0) != (next?.seconds ?: 0.0)) onSecondsChanged(date)
            if (next == null) days.remove(date) else days[date] = next
            add(date)
        }
        dirty.clear()
    }
}

private sealed interface Stamp {
    data object Missing : Stamp
    data class File(val id: Any, val modified: FileTime, val length: Long) : Stamp
}

private data class Source(
    val restoreVersion: Long,
    val contentType: ContentType,
    val files: Map<File, Stamp>,
    val exchanged: List<Boolean>,
)

private fun captureSource(
    repository: BookRepository,
    root: File,
    retainedRoot: File?,
    contentType: ContentType,
): Source? {
    val folders = listOfNotNull(root, retainedRoot)
    val files = linkedMapOf<File, Stamp>()
    for (folder in folders) {
        val reading = folder.resolve("statistics.json")
        files[reading] = stamp(reading) ?: return null
        if (contentType == ContentType.Mokuro) {
            val manga = folder.resolve("manga_statistics.json")
            files[manga] = stamp(manga) ?: return null
        }
    }
    // Presence controls whether this device can claim rows that predate device IDs.
    val exchanged = folders.map { it.resolve(STATISTICS_SYNC_STATE_FILE_NAME).isFile }
    return Source(repository.statisticsRestoreVersion, contentType, files, exchanged)
}

private fun stamp(file: File): Stamp? = try {
    val attributes = Files.readAttributes(file.toPath(), BasicFileAttributes::class.java)
    attributes.fileKey()?.takeIf { attributes.isRegularFile }?.let {
        Stamp.File(it, attributes.lastModifiedTime(), attributes.size())
    }
} catch (_: NoSuchFileException) {
    Stamp.Missing
} catch (_: IOException) {
    null
} catch (_: SecurityException) {
    null
}
