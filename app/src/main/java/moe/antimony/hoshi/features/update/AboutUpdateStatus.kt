package moe.antimony.hoshi.features.update

import moe.antimony.hoshi.R
import moe.antimony.hoshi.ui.UiText

internal sealed interface AboutUpdateCheckState {
    data object Idle : AboutUpdateCheckState
    data object Checking : AboutUpdateCheckState
    data class Result(val outcome: UpdateCheckOutcome) : AboutUpdateCheckState
    data class Error(val message: String) : AboutUpdateCheckState
}

internal fun aboutUpdateStatus(checkState: AboutUpdateCheckState, record: UpdateDownloadRecord?): UiText {
    // Transfer state remains visible while a separate check or action is running or fails.
    // About renders action errors separately, so they cannot freeze the progress text.
    if (record != null) return when (record.status) {
        UpdateDownloadRecordStatus.Available -> UiText.Resource(R.string.about_update_available_format, record.versionName)
        UpdateDownloadRecordStatus.Skipped -> UiText.Resource(R.string.about_update_skipped_format, record.versionName)
        UpdateDownloadRecordStatus.Queued -> UiText.Resource(R.string.about_update_queued)
        UpdateDownloadRecordStatus.Paused -> UiText.Resource(when (record.pauseReason) {
            UpdateDownloadPauseReason.Network -> R.string.about_update_waiting_network
            UpdateDownloadPauseReason.Wifi -> R.string.about_update_waiting_wifi
            UpdateDownloadPauseReason.Retry -> R.string.about_update_waiting_retry
            else -> R.string.about_update_paused
        })
        UpdateDownloadRecordStatus.Downloading -> if (record.totalBytes > 0) {
            val percent = (record.bytesDownloaded.toDouble() / record.totalBytes * 100).toInt().coerceIn(0, 100)
            UiText.Resource(R.string.about_update_progress_format, percent)
        } else UiText.Resource(R.string.about_update_downloading)
        UpdateDownloadRecordStatus.Downloaded -> UiText.Resource(R.string.about_update_downloaded_format, record.versionName)
        UpdateDownloadRecordStatus.Failed -> UiText.Resource(R.string.about_update_last_download_failed)
    }
    if (checkState is AboutUpdateCheckState.Checking) return UiText.Resource(R.string.about_update_checking_github)
    return when (val outcome = (checkState as? AboutUpdateCheckState.Result)?.outcome) {
        UpdateCheckOutcome.UpToDate -> UiText.Resource(R.string.about_update_latest)
        UpdateCheckOutcome.NoInstallableAsset -> UiText.Resource(R.string.about_update_no_matching_apk)
        is UpdateCheckOutcome.Skipped -> UiText.Resource(R.string.about_update_skipped_format, outcome.update.versionName)
        is UpdateCheckOutcome.Available -> UiText.Resource(R.string.about_update_available_format, outcome.update.versionName)
        is UpdateCheckOutcome.DownloadStarted, is UpdateCheckOutcome.DownloadInProgress,
        is UpdateCheckOutcome.DownloadAlreadyFinished -> UiText.Resource(R.string.about_update_check_github)
        null -> UiText.Resource(R.string.about_update_check_github)
    }
}
