package moe.antimony.hoshi

import android.content.ContentResolver
import android.content.Context
import android.os.Build
import android.provider.Settings
import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import moe.antimony.hoshi.dictionary.DictionaryRepository
import moe.antimony.hoshi.epub.BookRepository
import moe.antimony.hoshi.epub.DeviceIdentity
import moe.antimony.hoshi.features.ai.AiChatSettingsRepository
import moe.antimony.hoshi.features.ai.aiChatSettingsRepository
import moe.antimony.hoshi.features.audio.AudioSettingsRepository
import moe.antimony.hoshi.features.audio.LocalAudioRepository
import moe.antimony.hoshi.features.audio.audioSettingsRepository
import moe.antimony.hoshi.features.anki.AndroidAnkiContentApi
import moe.antimony.hoshi.features.anki.AnkiDroidBackendAdapter
import moe.antimony.hoshi.features.anki.AnkiRepository
import moe.antimony.hoshi.features.anki.AnkiSettingsRepository
import moe.antimony.hoshi.features.anki.ankiSettingsRepository
import moe.antimony.hoshi.features.backup.HoshiBackupRepository
import moe.antimony.hoshi.features.ai.offline.OfflineTranslationSettingsRepository
import moe.antimony.hoshi.features.ai.offline.offlineTranslationSettingsRepository
import moe.antimony.hoshi.features.news.NewsFeedStore
import moe.antimony.hoshi.features.news.NewsHttp
import moe.antimony.hoshi.features.news.NewsRepository
import moe.antimony.hoshi.features.news.NewsSettingsRepository
import moe.antimony.hoshi.features.news.WebViewNewsExtractor
import moe.antimony.hoshi.features.news.newsSettingsRepository
import moe.antimony.hoshi.features.bookshelf.AndroidBookshelfRepository
import moe.antimony.hoshi.features.bookshelf.BookshelfRepository
import moe.antimony.hoshi.features.bookshelf.BookshelfSettingsRepository
import moe.antimony.hoshi.features.bookshelf.bookshelfSettingsRepository
import moe.antimony.hoshi.features.dictionary.AndroidDictionaryViewModelRepository
import moe.antimony.hoshi.features.dictionary.AndroidDictionarySearchRepository
import moe.antimony.hoshi.features.dictionary.DictionarySettingsRepository
import moe.antimony.hoshi.features.dictionary.DictionarySearchRepository
import moe.antimony.hoshi.features.dictionary.DictionaryViewModelRepository
import moe.antimony.hoshi.features.dictionary.dictionarySettingsRepository
import moe.antimony.hoshi.features.reader.ReaderFontManager
import moe.antimony.hoshi.features.reader.ReaderSettingsRepository
import moe.antimony.hoshi.features.reader.readerSettingsRepository
import moe.antimony.hoshi.features.sasayaki.SasayakiSettingsRepository
import moe.antimony.hoshi.features.sasayaki.sasayakiSettingsRepository
import moe.antimony.hoshi.features.storage.StorageCleanupRepository
import moe.antimony.hoshi.features.sync.DeviceCodeDriveAuthorizer
import moe.antimony.hoshi.features.sync.DriveAuthorizer
import moe.antimony.hoshi.features.sync.GoogleDriveClient
import moe.antimony.hoshi.features.sync.SyncManager
import moe.antimony.hoshi.features.sync.SyncSettingsRepository
import moe.antimony.hoshi.features.sync.syncSettingsRepository
import moe.antimony.hoshi.features.sync.http.HttpSyncStatisticsPushScheduler
import moe.antimony.hoshi.features.sync.http.HttpSyncAutoPush
import moe.antimony.hoshi.features.sync.http.HttpSyncBatchState
import moe.antimony.hoshi.features.sync.http.HttpSyncBookmarkScheduler
import moe.antimony.hoshi.features.sync.http.HttpSyncEngineDispatcher
import moe.antimony.hoshi.features.sync.http.HttpSyncFastSync
import moe.antimony.hoshi.features.sync.http.HttpSyncFullCycleRunner
import moe.antimony.hoshi.features.sync.http.HttpSyncPusher
import moe.antimony.hoshi.features.sync.http.HttpSyncReconciler
import moe.antimony.hoshi.features.sync.http.HttpSyncSettingsRepository
import moe.antimony.hoshi.features.sync.http.httpSyncSettingsRepository
import moe.antimony.hoshi.features.sync.v3.V3SyncEngine
import moe.antimony.hoshi.features.update.AndroidUpdateDownloadManager
import moe.antimony.hoshi.features.update.GitHubReleaseUpdateRepository
import moe.antimony.hoshi.features.update.UpdateCheckService
import moe.antimony.hoshi.features.update.UpdateDownloadStore
import moe.antimony.hoshi.features.update.UpdateSettingsRepository
import moe.antimony.hoshi.features.update.updateDownloadStore
import moe.antimony.hoshi.features.update.updateSettingsRepository
import moe.antimony.hoshi.mokuro.MokuroBookParser
import moe.antimony.hoshi.navigation.ReaderRouteStateHolder
import java.nio.charset.StandardCharsets
import java.util.UUID

internal class HoshiAppContainer(context: Context) {
    private val appContext = context.applicationContext
    val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    // Shared by imports, readers, auto-push, and both HTTP reconcilers so directory swaps and
    // sidecar writes for one book cannot interleave.
    val httpSyncBookLocks: moe.antimony.hoshi.features.sync.http.HttpSyncBookLocks =
        moe.antimony.hoshi.features.sync.http.HttpSyncBookLocks()
    private val installationId: String = httpSyncInstallationId(appContext)
    /** This device, as reading statistics record it: the sync installation id plus the device's name. */
    val deviceIdentity: DeviceIdentity = DeviceIdentity(id = installationId, name = deviceDisplayName(appContext))
    val bookRepository: BookRepository = BookRepository(
        filesDir = appContext.filesDir,
        bookLocks = httpSyncBookLocks,
        deviceIdentity = deviceIdentity,
    )
    val dictionaryRepository: DictionaryRepository = DictionaryRepository(appContext.filesDir)
    // Shared between the bookshelf's metadata-sidecar write and the manga reader's load
    // path so opening a book parses mokuro.json once instead of twice. See MokuroBookParser.
    val mokuroParser: MokuroBookParser = MokuroBookParser()
    val readerSettingsRepository: ReaderSettingsRepository = appContext.readerSettingsRepository()
    val dictionarySettingsRepository: DictionarySettingsRepository = appContext.dictionarySettingsRepository()
    val audioSettingsRepository: AudioSettingsRepository = appContext.audioSettingsRepository()
    val ankiSettingsRepository: AnkiSettingsRepository = appContext.ankiSettingsRepository()
    val sasayakiSettingsRepository: SasayakiSettingsRepository = appContext.sasayakiSettingsRepository()
    val syncSettingsRepository: SyncSettingsRepository = appContext.syncSettingsRepository()
    val bookshelfSettingsRepository: BookshelfSettingsRepository = appContext.bookshelfSettingsRepository()
    val updateSettingsRepository: UpdateSettingsRepository = appContext.updateSettingsRepository()
    val updateDownloadStore: UpdateDownloadStore = appContext.updateDownloadStore()
    val readerFontManager: ReaderFontManager = ReaderFontManager(appContext.filesDir)
    val localAudioRepository: LocalAudioRepository = LocalAudioRepository(appContext.filesDir)
    val backupRepository: HoshiBackupRepository = HoshiBackupRepository(appContext.filesDir)
    val storageCleanupRepository: StorageCleanupRepository = StorageCleanupRepository(appContext.filesDir, appContext.cacheDir)
    val deviceCodeDriveAuthorizer: DeviceCodeDriveAuthorizer = DeviceCodeDriveAuthorizer(appContext)
    val driveAuthorizer: DriveAuthorizer = deviceCodeDriveAuthorizer
    val googleDriveClient: GoogleDriveClient = GoogleDriveClient(appContext, driveAuthorizer)
    val syncManager: SyncManager = SyncManager(
        bookRepository = bookRepository,
        drive = googleDriveClient,
    )
    val httpSyncSettingsRepository: HttpSyncSettingsRepository = appContext.httpSyncSettingsRepository()
    val aiChatSettingsRepository: AiChatSettingsRepository = appContext.aiChatSettingsRepository()
    // Wallclock of the most recent successful manual Sync now. The reader hooks read it
    // to clear their circuit breaker on the next page turn after a successful manual sync.
    val httpSyncManualSyncSuccessAt: kotlinx.coroutines.flow.MutableStateFlow<Long> =
        kotlinx.coroutines.flow.MutableStateFlow(0L)
    val httpSyncPusher: HttpSyncPusher = HttpSyncPusher(
        bookRepository = bookRepository,
        bookLocks = httpSyncBookLocks,
    )
    val httpSyncReconciler: HttpSyncReconciler = HttpSyncReconciler(
        bookRepository = bookRepository,
        aiSettingsRepository = aiChatSettingsRepository,
        bookLocks = httpSyncBookLocks,
    )
    val httpSyncBatchState: HttpSyncBatchState = HttpSyncBatchState(
        bookRepository = bookRepository,
        bookLocks = httpSyncBookLocks,
        installationId = installationId,
    )
    val httpSyncFullCycleRunner: HttpSyncFullCycleRunner = HttpSyncFullCycleRunner(appScope)
    val httpSyncFastSync: HttpSyncFastSync = HttpSyncFastSync(
        state = httpSyncBatchState,
        fullCycleRunner = httpSyncFullCycleRunner,
    )
    // v3 engine ships side-by-side with v2 (HttpSyncReconciler). The "Sync now" UI
    // dispatches between them based on the HttpSyncSettings.useV3Sync flag (default v2).
    // Both write the same on-disk + remote state, so flipping mid-life is safe. See
    // HttpSyncEngineDispatcher for the call-site branch.
    val v3SyncEngine: V3SyncEngine = V3SyncEngine(
        bookRepository = bookRepository,
        aiSettingsRepository = aiChatSettingsRepository,
        bookLocks = httpSyncBookLocks,
    )
    val httpSyncManualSync = moe.antimony.hoshi.features.sync.http.HttpSyncManualSync(appScope) { onProgress ->
        val settings = httpSyncSettingsRepository.settings.first()
        require(settings.isConfigured) { appContext.getString(R.string.http_sync_not_configured) }
        val result = httpSyncFastSync.syncNow(settings) { reconcileSettings, transport ->
            HttpSyncEngineDispatcher.syncOnce(
                reconciler = httpSyncReconciler,
                v3Engine = v3SyncEngine,
                settings = reconcileSettings,
                transport = transport,
                onProgress = onProgress,
            )
        }
        result.newLastSyncedAt?.let { cursor ->
            httpSyncSettingsRepository.update { it.copy(lastSyncedAt = cursor) }
        }
        if (result.errors.isEmpty()) {
            httpSyncManualSyncSuccessAt.value = System.currentTimeMillis()
        }
        result
    }
    val httpSyncBookmarkScheduler: HttpSyncBookmarkScheduler = HttpSyncBookmarkScheduler(
        state = httpSyncBatchState,
        currentSettings = { httpSyncSettingsRepository.settings.first() },
        syncBooksNow = { settings, transport ->
            HttpSyncEngineDispatcher.syncOnce(
                reconciler = httpSyncReconciler,
                v3Engine = v3SyncEngine,
                settings = settings,
                transport = transport,
            )
        },
        fullCycleRunner = httpSyncFullCycleRunner,
        scope = appScope,
    )
    // Fire-and-forget metadata writes plus a map refresh for new/replaced/deleted books.
    val httpSyncAutoPush: HttpSyncAutoPush = HttpSyncAutoPush(
        bookRepository = bookRepository,
        pusher = httpSyncPusher,
        reconciler = httpSyncReconciler,
        currentSettings = { httpSyncSettingsRepository.settings.first() },
        scope = appScope,
        onBooksChanged = httpSyncBookmarkScheduler::refreshNow,
        queueBookmark = httpSyncBookmarkScheduler::onBookmarkChanged,
        breakerResetSignal = { httpSyncManualSyncSuccessAt.value },
    )

    init {
        // AI chat settings edits sync immediately (debounced inside the hook; no-op when
        // sync is off). Mirrors iOS AiChatSettingsStore → HttpSyncManager.onAiSettingsChanged.
        aiChatSettingsRepository.onSyncRelevantEdit = { httpSyncAutoPush.onAiSettingsChanged() }
        // Prime the remote ETag/body cache before reading and retry durable bookmarks
        // left by a process death. With no changes this is one small HTTP request.
        httpSyncBookmarkScheduler.start()
    }
    val ankiRepository: AnkiRepository = AnkiRepository(
        context = appContext,
        backend = AnkiDroidBackendAdapter(AndroidAnkiContentApi(appContext)),
        settingsRepository = ankiSettingsRepository,
    )
    val updateDownloadManager: AndroidUpdateDownloadManager = AndroidUpdateDownloadManager(
        context = appContext,
        store = updateDownloadStore,
    )
    val updateCheckService: UpdateCheckService = UpdateCheckService(
        currentVersionName = BuildConfig.VERSION_NAME,
        releaseRepository = GitHubReleaseUpdateRepository(),
        downloadController = updateDownloadManager,
        updateStore = updateDownloadStore,
    )

    /** Debounced statistics pushes from both readers; see HttpSyncStatisticsSync for the merge. */
    val httpSyncStatisticsPushScheduler: HttpSyncStatisticsPushScheduler =
        HttpSyncStatisticsPushScheduler(
            scope = appScope,
            currentSettings = { httpSyncSettingsRepository.settings.first() },
            push = httpSyncPusher::pushStatistics,
        )

    fun readerRouteStateHolder(): ReaderRouteStateHolder =
        ReaderRouteStateHolder(
            repository = bookRepository,
            onBookmarkPersisted = { root, title, syncId ->
                httpSyncBookmarkScheduler.onBookmarkChanged(root, title, syncId)
            },
            onStatisticsPersisted = { root, title, syncId, flush ->
                if (flush) {
                    httpSyncStatisticsPushScheduler.flushNow(root, title.orEmpty(), syncId)
                } else {
                    httpSyncStatisticsPushScheduler.onStatisticsChanged(root, title.orEmpty(), syncId)
                }
            },
        )

    val offlineTranslationSettingsRepository: OfflineTranslationSettingsRepository =
        appContext.offlineTranslationSettingsRepository()
    val newsSettingsRepository: NewsSettingsRepository = appContext.newsSettingsRepository()
    val newsFeedStore: NewsFeedStore = NewsFeedStore(appContext.filesDir)
    val newsRepository: NewsRepository by lazy {
        NewsRepository(
            filesDir = appContext.filesDir,
            store = newsFeedStore,
            settings = newsSettingsRepository,
            extractor = WebViewNewsExtractor(appContext),
            http = NewsHttp(),
            bookshelf = bookshelfRepository(appContext),
        )
    }

    fun bookshelfRepository(context: Context): BookshelfRepository =
        AndroidBookshelfRepository(
            context = context,
            bookRepository = bookRepository,
            dictionaryRepository = dictionaryRepository,
            settingsRepository = bookshelfSettingsRepository,
            syncManager = syncManager,
            mokuroParser = mokuroParser,
            httpSyncAutoPush = httpSyncAutoPush,
        )

    fun dictionaryViewModelRepository(contentResolver: ContentResolver): DictionaryViewModelRepository =
        AndroidDictionaryViewModelRepository(
            contentResolver = contentResolver,
            dictionaryRepository = dictionaryRepository,
            settingsRepository = dictionarySettingsRepository,
            ankiSettingsRepository = ankiSettingsRepository,
        )

    fun dictionarySearchRepository(): DictionarySearchRepository =
        AndroidDictionarySearchRepository(
            dictionaryRepository = dictionaryRepository,
            dictionarySettingsRepository = dictionarySettingsRepository,
            audioSettingsRepository = audioSettingsRepository,
        )
}

internal val LocalHoshiAppContainer = staticCompositionLocalOf<HoshiAppContainer> {
    error("HoshiAppContainer is not provided.")
}

/** The user-visible device name ("Dragos's Pixel"), falling back to the model when none is set. */
private fun deviceDisplayName(context: Context): String =
    runCatching { Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME) }
        .getOrNull()
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?: Build.MODEL.orEmpty().ifBlank { "Android" }

private fun httpSyncInstallationId(context: Context): String {
    val androidId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
    if (!androidId.isNullOrBlank()) {
        return UUID.nameUUIDFromBytes(
            "hoshi:$androidId".toByteArray(StandardCharsets.UTF_8),
        ).toString()
    }
    val file = context.noBackupFilesDir.resolve("http_sync_installation_id")
    val existing = runCatching { UUID.fromString(file.readText().trim()).toString() }.getOrNull()
    if (existing != null) return existing
    return UUID.randomUUID().toString().also { created ->
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(created)
        }
    }
}
