package moe.antimony.hoshi.features.update

import android.content.Intent
import android.content.res.Resources
import android.net.Uri
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CleaningServices
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.antimony.hoshi.BuildConfig
import moe.antimony.hoshi.LocalHoshiAppContainer
import moe.antimony.hoshi.R
import moe.antimony.hoshi.features.settings.SettingsDetailScaffold
import moe.antimony.hoshi.features.settings.SettingsLoadState
import moe.antimony.hoshi.features.settings.collectAsSettingsLoadState
import moe.antimony.hoshi.features.storage.StorageCleanupCategoryId
import moe.antimony.hoshi.features.storage.StorageCleanupReport
import java.io.File
import java.util.Locale

// Repo URL is driven by [UpdateConfig] so a fork can repoint the About-screen link by
// editing one constant.
private const val GitHubRepositoryUrl = UpdateConfig.REPO_URL

@Composable
fun AboutScreen(
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val appContainer = LocalHoshiAppContainer.current
    val scope = rememberCoroutineScope()
    val recordLoadState = appContainer.updateDownloadStore.record.collectAsSettingsLoadState()
    val record = (recordLoadState as? SettingsLoadState.Loaded)?.value
    val actionableRecord = record?.takeIf { it.shouldSurfaceInAbout(BuildConfig.VERSION_NAME) }
    var checkState by remember { mutableStateOf<AboutUpdateCheckState>(AboutUpdateCheckState.Idle) }
    var cleanupState by remember { mutableStateOf<StorageCleanupUiState>(StorageCleanupUiState.Idle) }
    var pendingCleanupConfirmation by remember { mutableStateOf<StorageCleanupReport?>(null) }
    var pendingAvailableUpdate by remember { mutableStateOf<AvailableUpdate?>(null) }
    var updatePromptMessage by remember { mutableStateOf<String?>(null) }

    pendingCleanupConfirmation?.let { report ->
        AlertDialog(
            onDismissRequest = { pendingCleanupConfirmation = null },
            title = { Text(stringResource(R.string.about_storage_cleanup_confirm_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.about_storage_cleanup_confirm_message_format,
                        formatStorageSize(report.totalSizeBytes),
                    ),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingCleanupConfirmation = null
                        scope.launch {
                            cleanupState = StorageCleanupUiState.Cleaning(report)
                            cleanupState = runCatching {
                                withContext(Dispatchers.IO) {
                                    appContainer.storageCleanupRepository.clean(report)
                                }
                            }.fold(
                                onSuccess = { StorageCleanupUiState.Ready(it) },
                                onFailure = { error ->
                                    StorageCleanupUiState.Error(
                                        error.localizedMessage
                                            ?: resources.getString(R.string.about_storage_cleanup_failed),
                                    )
                                },
                            )
                        }
                    },
                ) {
                    Text(stringResource(R.string.action_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingCleanupConfirmation = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
    pendingAvailableUpdate?.let { update ->
        AvailableUpdatePromptDialog(
            versionName = update.versionName,
            message = updatePromptMessage,
            onLater = {
                pendingAvailableUpdate = null
                updatePromptMessage = null
            },
            onSkip = {
                scope.launch {
                    withContext(Dispatchers.IO) {
                        appContainer.updateDownloadStore.skip(update)
                    }
                    pendingAvailableUpdate = null
                    updatePromptMessage = null
                }
            },
            onDownload = {
                scope.launch {
                    val outcome = runCatching {
                        withContext(Dispatchers.IO) {
                            appContainer.updateCheckService.download(update)
                        }
                    }.fold(
                        onSuccess = { AboutUpdateCheckState.Result(it) },
                        onFailure = { error ->
                            AboutUpdateCheckState.Error(
                                error.localizedMessage
                                    ?: resources.getString(R.string.about_update_download_failed),
                            )
                        },
                    )
                    checkState = outcome
                    if (outcome is AboutUpdateCheckState.Error) {
                        updatePromptMessage = outcome.message
                    } else {
                        pendingAvailableUpdate = null
                        updatePromptMessage = null
                    }
                }
            },
        )
    }

    SettingsDetailScaffold(
        title = stringResource(R.string.settings_about),
        onClose = onClose,
        modifier = modifier,
    ) { innerPadding ->
        if (recordLoadState !is SettingsLoadState.Loaded) return@SettingsDetailScaffold
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                AboutCard {
                    ListItem(
                        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
                        headlineContent = { Text(stringResource(R.string.app_name)) },
                        supportingContent = {
                            Text(
                                stringResource(
                                    R.string.about_version_format,
                                    BuildConfig.VERSION_NAME,
                                    BuildConfig.VERSION_CODE,
                                ),
                            )
                        },
                    )
                }
            }
            item {
                AboutCard {
                    Column(Modifier.padding(16.dp)) {
                        Text(
                            text = "GitHub",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = stringResource(R.string.about_github_body),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(16.dp))
                        OutlinedButton(
                            onClick = {
                                context.startActivity(
                                    Intent(Intent.ACTION_VIEW, Uri.parse(GitHubRepositoryUrl)),
                                )
                            },
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Rounded.OpenInNew,
                                contentDescription = null,
                                modifier = Modifier.padding(end = 8.dp),
                            )
                            Text("GitHub")
                        }
                    }
                }
            }
            // ---- Attribution / credits card ----
            // Hoshi Manga is a fork of HuangAntimony's Android EPUB reader, which is
            // itself a recreation of Manhhao's web/iOS Hoshi Reader. Per GPLv3's
            // conspicuous-notice requirement, surface both upstreams + the license +
            // a short list of the open-source libraries the manga reading flow leans
            // on heavily so users know what they're standing on.
            item {
                AboutCard {
                    Column(Modifier.padding(16.dp)) {
                        Text(
                            text = "Credits",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(
                            text = "Hoshi Manga is a manga-focused fork of the original " +
                                "Hoshi Reader project. Huge thanks to the upstream maintainers " +
                                "and to the open-source ecosystem this reader builds on:",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(12.dp))
                        CreditRow(
                            label = "Hoshi Reader Android (upstream fork)",
                            description = "by HuangAntimony — the EPUB reader codebase " +
                                "this manga fork is built on.",
                            url = "https://github.com/HuangAntimony/Hoshi-Reader-Android",
                            context = context,
                        )
                        Spacer(Modifier.height(10.dp))
                        CreditRow(
                            label = "Hoshi Reader (original web + iOS)",
                            description = "by Manhhao — the original Hoshi Reader project " +
                                "the Android port grew from.",
                            url = "https://github.com/Manhhao/Hoshi-Reader",
                            context = context,
                        )
                        Spacer(Modifier.height(10.dp))
                        CreditRow(
                            label = "mokuro",
                            description = "by kha-white — the OCR pipeline that produces " +
                                "the manga sidecar files this reader consumes.",
                            url = "https://github.com/kha-white/mokuro",
                            context = context,
                        )
                        Spacer(Modifier.height(10.dp))
                        CreditRow(
                            label = "mokuro-reader (Gnathonic fork)",
                            description = "by Gnathonic — the reference web reader whose " +
                                "OCR shrink-to-fit + word-wrap algorithm this app ports.",
                            url = "https://github.com/Gnathonic/mokuro-reader",
                            context = context,
                        )
                        Spacer(Modifier.height(10.dp))
                        CreditRow(
                            label = "Yomitan / hoshidicts",
                            description = "Yomitan dictionary format (foosoft) + the " +
                                "hoshidicts native lookup engine (de.manhhao).",
                            url = "https://github.com/yomidevs/yomitan",
                            context = context,
                        )
                        Spacer(Modifier.height(10.dp))
                        CreditRow(
                            label = "AnkiDroid",
                            description = "Anki on Android — the host for the card-mining " +
                                "integration that adds manga lookups to your reviews.",
                            url = "https://github.com/ankidroid/Anki-Android",
                            context = context,
                        )
                        Spacer(Modifier.height(16.dp))
                        // License row — GPLv3 requires conspicuous notice in the running
                        // program, satisfied here.
                        Text(
                            text = "License",
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "Hoshi Manga is licensed under the GNU General Public " +
                                "License, version 3. Source code, license terms, and full " +
                                "third-party attribution are available on GitHub.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(12.dp))
                        OutlinedButton(
                            onClick = {
                                context.startActivity(
                                    Intent(
                                        Intent.ACTION_VIEW,
                                        Uri.parse("https://www.gnu.org/licenses/gpl-3.0.html"),
                                    ),
                                )
                            },
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Rounded.OpenInNew,
                                contentDescription = null,
                                modifier = Modifier.padding(end = 8.dp),
                            )
                            Text("View GPLv3")
                        }
                    }
                }
            }
            item {
                AboutCard {
                    Column(Modifier.padding(16.dp)) {
                        Text(
                            text = stringResource(R.string.about_storage_cleanup),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = storageCleanupStatusText(resources, cleanupState),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        val report = cleanupState.reportOrNull()
                        if (report != null && report.categories.isNotEmpty()) {
                            Spacer(Modifier.height(12.dp))
                            report.categories.forEach { category ->
                                Text(
                                    text = resources.getString(
                                        R.string.about_storage_category_format,
                                        resources.getString(category.id.titleRes),
                                        formatStorageSize(category.sizeBytes),
                                        formatItemCount(resources, category.itemCount),
                                    ),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        Spacer(Modifier.height(16.dp))
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Button(
                                onClick = {
                                    scope.launch {
                                        cleanupState = StorageCleanupUiState.Scanning
                                        cleanupState = runCatching {
                                            withContext(Dispatchers.IO) {
                                                appContainer.storageCleanupRepository.scan()
                                            }
                                        }.fold(
                                            onSuccess = { StorageCleanupUiState.Ready(it) },
                                            onFailure = { error ->
                                                StorageCleanupUiState.Error(
                                                    error.localizedMessage
                                                        ?: resources.getString(R.string.about_storage_scan_failed),
                                                )
                                            },
                                        )
                                    }
                                },
                                enabled = !cleanupState.isBusy,
                            ) {
                                if (cleanupState == StorageCleanupUiState.Scanning) {
                                    CircularProgressIndicator(
                                        modifier = Modifier
                                            .padding(end = 8.dp)
                                            .size(18.dp),
                                        strokeWidth = 2.dp,
                                    )
                                } else {
                                    Icon(
                                        imageVector = Icons.Rounded.CleaningServices,
                                        contentDescription = null,
                                        modifier = Modifier.padding(end = 8.dp),
                                    )
                                }
                                Text(stringResource(R.string.action_scan))
                            }
                            if (report?.hasCleanableItems == true) {
                                OutlinedButton(
                                    onClick = { pendingCleanupConfirmation = report },
                                    enabled = !cleanupState.isBusy,
                                ) {
                                    Text(stringResource(R.string.about_clean_up))
                                }
                            }
                        }
                    }
                }
            }
            item {
                AboutCard {
                    Column(Modifier.padding(16.dp)) {
                        Text(
                            text = stringResource(R.string.about_updates),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = updateStatusText(resources, checkState, actionableRecord),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(16.dp))
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Button(
                                onClick = {
                                    scope.launch {
                                        checkState = AboutUpdateCheckState.Checking
                                        checkState = runCatching {
                                            withContext(Dispatchers.IO) {
                                                appContainer.updateCheckService.check(ignoreSkipped = true)
                                            }
                                        }.fold(
                                            onSuccess = { outcome ->
                                                if (outcome is UpdateCheckOutcome.Available) {
                                                    pendingAvailableUpdate = outcome.update
                                                }
                                                AboutUpdateCheckState.Result(outcome)
                                            },
                                            onFailure = { error ->
                                                AboutUpdateCheckState.Error(
                                                    error.localizedMessage
                                                        ?: resources.getString(R.string.about_update_check_failed),
                                                )
                                            },
                                        )
                                    }
                                },
                                enabled = checkState !is AboutUpdateCheckState.Checking,
                            ) {
                                if (checkState is AboutUpdateCheckState.Checking) {
                                    CircularProgressIndicator(
                                        modifier = Modifier
                                            .padding(end = 8.dp)
                                            .size(18.dp),
                                        strokeWidth = 2.dp,
                                    )
                                }
                                Text(stringResource(R.string.about_check_updates))
                            }
                            val downloadedFile = actionableRecord
                                ?.takeIf { it.status == UpdateDownloadRecordStatus.Downloaded }
                                ?.let { appContainer.updateDownloadManager.updateFile(it.fileName) }
                                ?.takeIf(File::isFile)
                            val availableUpdate = actionableRecord
                                ?.takeIf {
                                    it.status == UpdateDownloadRecordStatus.Available ||
                                        it.status == UpdateDownloadRecordStatus.Skipped ||
                                        it.status == UpdateDownloadRecordStatus.Failed
                                }
                                ?.toAvailableUpdate()
                            if (availableUpdate != null) {
                                OutlinedButton(
                                    onClick = {
                                        scope.launch {
                                            checkState = runCatching {
                                                withContext(Dispatchers.IO) {
                                                    appContainer.updateCheckService.download(availableUpdate)
                                                }
                                            }.fold(
                                                onSuccess = { AboutUpdateCheckState.Result(it) },
                                                onFailure = { error ->
                                                    AboutUpdateCheckState.Error(
                                                        error.localizedMessage
                                                            ?: resources.getString(R.string.about_update_download_failed),
                                                    )
                                                },
                                            )
                                        }
                                    },
                                    enabled = checkState !is AboutUpdateCheckState.Checking,
                                ) {
                                    Text(stringResource(R.string.action_download))
                                }
                            }
                            if (downloadedFile != null) {
                                OutlinedButton(
                                    onClick = {
                                        openDownloadedUpdate(context, downloadedFile)?.let { message ->
                                            checkState = AboutUpdateCheckState.Error(message)
                                        }
                                    },
                                ) {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Rounded.OpenInNew,
                                        contentDescription = null,
                                        modifier = Modifier.padding(end = 8.dp),
                                    )
                                    Text(stringResource(R.string.action_install))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * One row inside the Credits card: project name + one-sentence description + a tappable
 * link button. Kept compact (no Surface wrapper) so multiple rows stack densely.
 */
@Composable
private fun CreditRow(
    label: String,
    description: String,
    url: String,
    context: android.content.Context,
) {
    Column {
        Text(text = label, style = MaterialTheme.typography.titleSmall)
        Text(
            text = description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(
            onClick = {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            },
            contentPadding = PaddingValues(horizontal = 0.dp, vertical = 4.dp),
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.OpenInNew,
                contentDescription = null,
                modifier = Modifier.size(14.dp).padding(end = 4.dp),
            )
            Text(text = url, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun AboutCard(content: @Composable () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        tonalElevation = 0.dp,
    ) {
        content()
    }
}

private sealed interface AboutUpdateCheckState {
    data object Idle : AboutUpdateCheckState
    data object Checking : AboutUpdateCheckState
    data class Result(val outcome: UpdateCheckOutcome) : AboutUpdateCheckState
    data class Error(val message: String) : AboutUpdateCheckState
}

private sealed interface StorageCleanupUiState {
    data object Idle : StorageCleanupUiState
    data object Scanning : StorageCleanupUiState
    data class Ready(val report: StorageCleanupReport) : StorageCleanupUiState
    data class Cleaning(val report: StorageCleanupReport) : StorageCleanupUiState
    data class Error(val message: String) : StorageCleanupUiState
}

private val StorageCleanupUiState.isBusy: Boolean
    get() = this == StorageCleanupUiState.Scanning || this is StorageCleanupUiState.Cleaning

private fun StorageCleanupUiState.reportOrNull(): StorageCleanupReport? =
    when (this) {
        is StorageCleanupUiState.Ready -> report
        is StorageCleanupUiState.Cleaning -> report
        else -> null
    }

private fun storageCleanupStatusText(resources: Resources, state: StorageCleanupUiState): String =
    when (state) {
        StorageCleanupUiState.Idle -> resources.getString(R.string.about_storage_cleanup_scan_hint)
        StorageCleanupUiState.Scanning -> resources.getString(R.string.about_storage_scanning)
        is StorageCleanupUiState.Cleaning -> resources.getString(R.string.about_storage_cleaning)
        is StorageCleanupUiState.Error -> state.message
        is StorageCleanupUiState.Ready -> {
            if (state.report.hasCleanableItems) {
                resources.getString(
                    R.string.about_storage_cleanable_format,
                    formatStorageSize(state.report.totalSizeBytes),
                    state.report.categories.size,
                )
            } else {
                resources.getString(R.string.about_storage_no_leftovers)
            }
        }
    }

private fun formatStorageSize(bytes: Long): String {
    if (bytes < 1024L) return "$bytes B"
    val units = listOf("KB", "MB", "GB")
    var value = bytes.toDouble() / 1024.0
    units.forEachIndexed { index, unit ->
        val isLast = index == units.lastIndex
        if (value < 1024.0 || isLast) {
            return if (value < 10.0) {
                "%.1f %s".format(Locale.US, value, unit)
            } else {
                "%.0f %s".format(Locale.US, value, unit)
            }
        }
        value /= 1024.0
    }
    return "$bytes B"
}

private fun formatItemCount(resources: Resources, count: Int): String =
    resources.getQuantityString(R.plurals.about_item_count, count, count)

private val StorageCleanupCategoryId.titleRes: Int
    get() = when (this) {
        StorageCleanupCategoryId.AnkiMediaCache -> R.string.about_storage_category_anki_media_cache
        StorageCleanupCategoryId.EpubImportResidue -> R.string.about_storage_category_epub_import_residue
        StorageCleanupCategoryId.BackupRestoreResidue -> R.string.about_storage_category_backup_restore_residue
        StorageCleanupCategoryId.DictionaryImportResidue -> R.string.about_storage_category_dictionary_import_residue
        StorageCleanupCategoryId.LocalAudioImportResidue -> R.string.about_storage_category_local_audio_import_residue
        StorageCleanupCategoryId.OrphanSasayakiAudio -> R.string.about_storage_category_orphan_sasayaki_audio
    }

private fun updateStatusText(
    resources: Resources,
    checkState: AboutUpdateCheckState,
    record: UpdateDownloadRecord?,
): String =
    when (checkState) {
        AboutUpdateCheckState.Idle -> when (record?.status) {
            UpdateDownloadRecordStatus.Available -> resources.getString(R.string.about_update_available_format, record.versionName)
            UpdateDownloadRecordStatus.Skipped -> resources.getString(R.string.about_update_skipped_format, record.versionName)
            UpdateDownloadRecordStatus.Downloading -> resources.getString(R.string.about_update_downloading)
            UpdateDownloadRecordStatus.Downloaded -> resources.getString(R.string.about_update_downloaded_format, record.versionName)
            UpdateDownloadRecordStatus.Failed -> resources.getString(R.string.about_update_last_download_failed)
            null -> resources.getString(R.string.about_update_check_github)
        }
        AboutUpdateCheckState.Checking -> resources.getString(R.string.about_update_checking_github)
        is AboutUpdateCheckState.Error -> checkState.message
        is AboutUpdateCheckState.Result -> when (val outcome = checkState.outcome) {
            UpdateCheckOutcome.UpToDate -> resources.getString(R.string.about_update_latest)
            UpdateCheckOutcome.NoInstallableAsset -> resources.getString(R.string.about_update_no_matching_apk)
            is UpdateCheckOutcome.Skipped -> resources.getString(R.string.about_update_skipped_format, outcome.update.versionName)
            is UpdateCheckOutcome.Available -> resources.getString(R.string.about_update_available_format, outcome.update.versionName)
            is UpdateCheckOutcome.DownloadStarted -> resources.getString(R.string.about_update_downloading_format, outcome.update.versionName)
            is UpdateCheckOutcome.DownloadInProgress -> resources.getString(
                R.string.about_update_already_downloading_format,
                outcome.update.versionName,
            )
            is UpdateCheckOutcome.DownloadAlreadyFinished -> resources.getString(
                R.string.about_update_already_downloaded_format,
                outcome.update.versionName,
            )
        }
    }
