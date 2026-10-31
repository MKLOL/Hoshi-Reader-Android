package moe.antimony.hoshi.features.reader

import moe.antimony.hoshi.features.sasayaki.SasayakiSettings
import moe.antimony.hoshi.features.sasayaki.SasayakiSettingsSyncCodec

/** Apply only the fields this UI edit changed, preserving newer repository/remote values. */
internal fun applyReaderSettingsEdit(
    current: ReaderSettings,
    previous: ReaderSettings,
    edited: ReaderSettings,
): ReaderSettings {
    val before = ReaderSettingsSyncCodec.values(previous)
    var result = current
    ReaderSettingsSyncCodec.values(edited).forEach { (name, value) ->
        if (value != before[name]) result = ReaderSettingsSyncCodec.apply(result, name, value)
    }
    // These preferences belong to the separate statistics lane, but remain locally editable.
    return result.copy(
        statisticsSyncEnabled = if (previous.statisticsSyncEnabled != edited.statisticsSyncEnabled) edited.statisticsSyncEnabled else result.statisticsSyncEnabled,
        statisticsSyncMode = if (previous.statisticsSyncMode != edited.statisticsSyncMode) edited.statisticsSyncMode else result.statisticsSyncMode,
        statisticsStreakMinimumMinutes = if (previous.statisticsStreakMinimumMinutes != edited.statisticsStreakMinimumMinutes) edited.statisticsStreakMinimumMinutes else result.statisticsStreakMinimumMinutes,
        statisticsDayResetHour = if (previous.statisticsDayResetHour != edited.statisticsDayResetHour) edited.statisticsDayResetHour else result.statisticsDayResetHour,
        statisticsSettingsUpdatedAt = if (previous.statisticsSettingsUpdatedAt != edited.statisticsSettingsUpdatedAt) edited.statisticsSettingsUpdatedAt else result.statisticsSettingsUpdatedAt,
        statisticsTimeZone = if (previous.statisticsTimeZone != edited.statisticsTimeZone) edited.statisticsTimeZone else result.statisticsTimeZone,
    )
}

/** Sasayaki has the same whole-snapshot UI callbacks as the reader settings. */
internal fun applySasayakiSettingsEdit(
    current: SasayakiSettings,
    previous: SasayakiSettings,
    edited: SasayakiSettings,
): SasayakiSettings {
    val before = SasayakiSettingsSyncCodec.values(previous)
    var result = current
    SasayakiSettingsSyncCodec.values(edited).forEach { (name, value) ->
        if (value != before[name]) result = SasayakiSettingsSyncCodec.apply(result, name, value)
    }
    return result.copy(
        syncEnabled = if (previous.syncEnabled != edited.syncEnabled) edited.syncEnabled else result.syncEnabled,
        copyAudiobookToPrivateStorage = if (previous.copyAudiobookToPrivateStorage != edited.copyAudiobookToPrivateStorage) edited.copyAudiobookToPrivateStorage else result.copyAudiobookToPrivateStorage,
    )
}
