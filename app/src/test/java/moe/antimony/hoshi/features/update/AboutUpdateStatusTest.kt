package moe.antimony.hoshi.features.update

import moe.antimony.hoshi.R
import moe.antimony.hoshi.ui.UiText
import org.junit.Assert.assertEquals
import org.junit.Test

class AboutUpdateStatusTest {
    private val running = UpdateDownloadRecord(
        versionName = "0.11.8",
        assetName = "Hoshi-Manga-v0.11.8.apk",
        fileName = "update.apk",
        downloadId = 1,
        sha256 = null,
        status = UpdateDownloadRecordStatus.Downloading,
        bytesDownloaded = 25,
        totalBytes = 100,
    )

    @Test
    fun failedCheckDoesNotHideProgressOrLaterTransferTransitions() {
        assertEquals(
            UiText.Resource(R.string.about_update_progress_format, 25),
            aboutUpdateStatus(AboutUpdateCheckState.Idle, running),
        )
        val failedCheck = AboutUpdateCheckState.Error("Could not check for updates")
        assertEquals(
            UiText.Resource(R.string.about_update_progress_format, 25),
            aboutUpdateStatus(failedCheck, running),
        )
        val paused = running.copy(
            status = UpdateDownloadRecordStatus.Paused,
            pauseReason = UpdateDownloadPauseReason.Network,
        )
        assertEquals(
            UiText.Resource(R.string.about_update_waiting_network),
            aboutUpdateStatus(failedCheck, paused),
        )
        val resumed = running.copy(bytesDownloaded = 75)
        assertEquals(
            UiText.Resource(R.string.about_update_progress_format, 75),
            aboutUpdateStatus(failedCheck, resumed),
        )
        val completed = running.copy(status = UpdateDownloadRecordStatus.Downloaded, bytesDownloaded = 100)
        assertEquals(
            UiText.Resource(R.string.about_update_downloaded_format, running.versionName),
            aboutUpdateStatus(failedCheck, completed),
        )
    }

    @Test
    fun checkingDoesNotHideAnExistingTransfer() {
        assertEquals(
            UiText.Resource(R.string.about_update_progress_format, 25),
            aboutUpdateStatus(AboutUpdateCheckState.Checking, running),
        )
        assertEquals(
            UiText.Resource(R.string.about_update_checking_github),
            aboutUpdateStatus(AboutUpdateCheckState.Checking, null),
        )
    }

    @Test
    fun actionFailureDoesNotReplaceTheAvailableDownloadStatus() {
        val available = running.copy(status = UpdateDownloadRecordStatus.Available, downloadId = null)
        assertEquals(
            UiText.Resource(R.string.about_update_available_format, available.versionName),
            aboutUpdateStatus(AboutUpdateCheckState.Error("Could not start the download"), available),
        )
    }
}
