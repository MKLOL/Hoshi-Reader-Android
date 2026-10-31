package moe.antimony.hoshi.features.reader

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/** Each preference has its own logical clock, so independent offline edits can both survive. */
@Serializable
data class ReaderSettingRecord(val value: JsonElement, val editedAt: Long, val deviceId: String)

data class ReaderSettingsSyncSnapshot(val deviceId: String, val records: Map<String, ReaderSettingRecord>)

interface ReaderSettingsSyncStore {
    suspend fun snapshot(): ReaderSettingsSyncSnapshot
    suspend fun merge(remote: Map<String, ReaderSettingRecord>): Boolean

    /** Recheck the account after queued storage work, immediately before applying remote values. */
    suspend fun merge(
        remote: Map<String, ReaderSettingRecord>,
        stillCurrent: suspend () -> Boolean,
    ): Boolean = if (stillCurrent()) merge(remote) else false
}

/** Explicit wire allowlist: never serialize an entire settings/repository object. */
internal object ReaderSettingsSyncCodec {
    const val MAX_RECORDS = 256
    const val MAX_TIMESTAMP = 253402300799999L
    private const val MAX_CLOCK_SKEW_MILLIS = 24 * 60 * 60 * 1000L
    private val json = Json { ignoreUnknownKeys = true }
    private val devicePattern = Regex("[A-Za-z0-9_-]{1,128}")
    private val fieldPattern = Regex("[A-Za-z][A-Za-z0-9_.-]{0,127}")
    private val excluded = setOf(
        "statisticsSyncEnabled", "statisticsSyncMode", "statisticsStreakMinimumMinutes",
        "statisticsDayResetHour", "statisticsSettingsUpdatedAt", "statisticsTimeZone",
    )

    private class Field(
        val name: String,
        val read: (ReaderSettings) -> JsonElement,
        val apply: (ReaderSettings, JsonElement) -> ReaderSettings?,
    )

    private val fields = listOf(
        boolean("eInkMode", { it.eInkMode }) { settings, value -> settings.copy(eInkMode = value) },
        boolean("disablePageTurnAnimation", { it.disablePageTurnAnimation }) { settings, value -> settings.copy(disablePageTurnAnimation = value) },
        boolean("systemLightSepia", { it.systemLightSepia }) { settings, value -> settings.copy(systemLightSepia = value) },
        boolean("sepiaInvertInDark", { it.sepiaInvertInDark }) { settings, value -> settings.copy(sepiaInvertInDark = value) },
        boolean("verticalWriting", { it.verticalWriting }) { settings, value -> settings.copy(verticalWriting = value) },
        boolean("hideFurigana", { it.hideFurigana }) { settings, value -> settings.copy(hideFurigana = value) },
        boolean("showSentenceTranslations", { it.showSentenceTranslations }) { settings, value -> settings.copy(showSentenceTranslations = value) },
        boolean("continuousMode", { it.continuousMode }) { settings, value -> settings.copy(continuousMode = value) },
        boolean("showStatisticsToggle", { it.showStatisticsToggle }) { settings, value -> settings.copy(showStatisticsToggle = value) },
        boolean("showReadingSpeed", { it.showReadingSpeed }) { settings, value -> settings.copy(showReadingSpeed = value) },
        boolean("showReadingTime", { it.showReadingTime }) { settings, value -> settings.copy(showReadingTime = value) },
        boolean("avoidPageBreak", { it.avoidPageBreak }) { settings, value -> settings.copy(avoidPageBreak = value) },
        boolean("justifyText", { it.justifyText }) { settings, value -> settings.copy(justifyText = value) },
        boolean("blurImages", { it.blurImages }) { settings, value -> settings.copy(blurImages = value) },
        boolean("layoutAdvanced", { it.layoutAdvanced }) { settings, value -> settings.copy(layoutAdvanced = value) },
        boolean("showTitle", { it.showTitle }) { settings, value -> settings.copy(showTitle = value) },
        boolean("showCharacters", { it.showCharacters }) { settings, value -> settings.copy(showCharacters = value) },
        boolean("showPercentage", { it.showPercentage }) { settings, value -> settings.copy(showPercentage = value) },
        boolean("alwaysShowProgress", { it.alwaysShowProgress }) { settings, value -> settings.copy(alwaysShowProgress = value) },
        boolean("showProgressTop", { it.showProgressTop }) { settings, value -> settings.copy(showProgressTop = value) },
        boolean("showReaderBackButton", { it.showReaderBackButton }) { settings, value -> settings.copy(showReaderBackButton = value) },
        boolean("popupActionBar", { it.popupActionBar }) { settings, value -> settings.copy(popupActionBar = value) },
        boolean("popupFullWidth", { it.popupFullWidth }) { settings, value -> settings.copy(popupFullWidth = value) },
        boolean("popupSwipeToDismiss", { it.popupSwipeToDismiss }) { settings, value -> settings.copy(popupSwipeToDismiss = value) },
        boolean("popupReducedMotionScrolling", { it.popupReducedMotionScrolling }) { settings, value -> settings.copy(popupReducedMotionScrolling = value) },
        boolean("volumeKeysTurnPages", { it.volumeKeysTurnPages }) { settings, value -> settings.copy(volumeKeysTurnPages = value) },
        boolean("volumeKeysSeekSasayaki", { it.volumeKeysSeekSasayaki }) { settings, value -> settings.copy(volumeKeysSeekSasayaki = value) },
        boolean("reverseVolumeKeyDirection", { it.reverseVolumeKeyDirection }) { settings, value -> settings.copy(reverseVolumeKeyDirection = value) },
        boolean("keepScreenOnWhileReading", { it.keepScreenOnWhileReading }) { settings, value -> settings.copy(keepScreenOnWhileReading = value) },
        boolean("mangaSingleTapLookup", { it.mangaSingleTapLookup }) { settings, value -> settings.copy(mangaSingleTapLookup = value) },
        boolean("mangaUseNotoSansJp", { it.mangaUseNotoSansJp }) { settings, value -> settings.copy(mangaUseNotoSansJp = value) },
        boolean("mangaShowCopyButton", { it.mangaShowCopyButton }) { settings, value -> settings.copy(mangaShowCopyButton = value) },
        boolean("mangaEnlargeSmallText", { it.mangaEnlargeSmallText }) { settings, value -> settings.copy(mangaEnlargeSmallText = value) },
        boolean("mangaShowFurigana", { it.mangaShowFurigana }) { settings, value -> settings.copy(mangaShowFurigana = value) },
        integer("fontSize", 16..60, { it.fontSize }) { settings, value -> settings.copy(fontSize = value) },
        integer("chapterSwipeDistance", 10..60, { it.chapterSwipeDistance }) { settings, value -> settings.copy(chapterSwipeDistance = value) },
        integer("horizontalPadding", 0..50, { it.horizontalPadding }) { settings, value -> settings.copy(horizontalPadding = value) },
        integer("verticalPadding", 0..50, { it.verticalPadding }) { settings, value -> settings.copy(verticalPadding = value) },
        integer("popupWidth", 100..1600, { it.popupWidth }) { settings, value -> settings.copy(popupWidth = value) },
        integer("popupHeight", 100..2000, { it.popupHeight }) { settings, value -> settings.copy(popupHeight = value) },
        integer("popupSwipeThreshold", 20..60, { it.popupSwipeThreshold }) { settings, value -> settings.copy(popupSwipeThreshold = value) },
        integer("popupReducedMotionScrollPercent", 40..100, { it.popupReducedMotionScrollPercent }) { settings, value -> settings.copy(popupReducedMotionScrollPercent = value) },
        integer("popupReducedMotionSwipeThreshold", 0..100, { it.popupReducedMotionSwipeThreshold }) { settings, value -> settings.copy(popupReducedMotionSwipeThreshold = value) },
        decimal("lineHeight", 1.0..2.5, { it.lineHeight }) { settings, value -> settings.copy(lineHeight = value) },
        decimal("characterSpacing", -10.0..10.0, { it.characterSpacing }) { settings, value -> settings.copy(characterSpacing = value) },
        decimal("paragraphSpacing", 0.0..3.0, { it.paragraphSpacing }) { settings, value -> settings.copy(paragraphSpacing = value) },
        decimal("popupScale", 0.8..1.5, { it.popupScale }) { settings, value -> settings.copy(popupScale = value) },
        color("customBackgroundColor", { it.customBackgroundColor }) { settings, value -> settings.copy(customBackgroundColor = value) },
        color("customTextColor", { it.customTextColor }) { settings, value -> settings.copy(customTextColor = value) },
        color("customInfoColor", { it.customInfoColor }) { settings, value -> settings.copy(customInfoColor = value) },
        enumField("theme", ReaderTheme.entries, { it.theme }, { it.name }) { settings, value -> settings.copy(theme = value) },
        enumField("uiTheme", ReaderInterfaceTheme.entries, { it.uiTheme }, { it.name }) { settings, value -> settings.copy(uiTheme = value) },
        field("selectedFont", { it.selectedFont }, { JsonPrimitive(it) }, { value ->
            value.stringValue()?.takeIf { name ->
                name.isNotBlank() && name.length <= 255 && name.none { it == '/' || it == '\u0000' }
            }?.let(ReaderFontManager::normalizeDefaultFont)
        }) { settings, value -> settings.copy(selectedFont = value) },
        nullableDecimal("dictionaryCard.width", 100.0..1600.0, { it.dictionaryCard.width }) { settings, value -> settings.copy(dictionaryCard = settings.dictionaryCard.copy(width = value)) },
        nullableDecimal("dictionaryCard.height", 100.0..2000.0, { it.dictionaryCard.height }) { settings, value -> settings.copy(dictionaryCard = settings.dictionaryCard.copy(height = value)) },
        nullableDecimal("dictionaryCard.scale", 0.5..3.0, { it.dictionaryCard.scale }) { settings, value -> settings.copy(dictionaryCard = settings.dictionaryCard.copy(scale = value)) },
        nullableDecimal("dictionaryCard.fontScale", 0.5..3.0, { it.dictionaryCard.fontScale }) { settings, value -> settings.copy(dictionaryCard = settings.dictionaryCard.copy(fontScale = value)) },
        nullableDecimal("translationCard.width", 100.0..1600.0, { it.translationCard.width }) { settings, value -> settings.copy(translationCard = settings.translationCard.copy(width = value)) },
        nullableDecimal("translationCard.height", 100.0..2000.0, { it.translationCard.height }) { settings, value -> settings.copy(translationCard = settings.translationCard.copy(height = value)) },
        nullableDecimal("translationCard.scale", 0.5..3.0, { it.translationCard.scale }) { settings, value -> settings.copy(translationCard = settings.translationCard.copy(scale = value)) },
        nullableDecimal("translationCard.fontScale", 0.5..3.0, { it.translationCard.fontScale }) { settings, value -> settings.copy(translationCard = settings.translationCard.copy(fontScale = value)) },
    ).associateBy { it.name }

    fun values(settings: ReaderSettings): Map<String, JsonElement> = fields.mapValues { it.value.read(settings) }

    fun apply(settings: ReaderSettings, name: String, value: JsonElement): ReaderSettings =
        fields[name]?.apply?.invoke(settings, value) ?: settings

    fun isValidDeviceId(value: String): Boolean = devicePattern.matches(value)

    fun isValidRecord(name: String, record: ReaderSettingRecord): Boolean =
        fieldPattern.matches(name) && name !in excluded &&
            record.editedAt in 0..MAX_TIMESTAMP && isValidDeviceId(record.deviceId) &&
            record.value.toString().length <= 4096 &&
            (name !in fields || fields.getValue(name).apply(ReaderSettings(), record.value) != null)

    fun validatedRecords(records: Map<String, ReaderSettingRecord>): Map<String, ReaderSettingRecord> {
        require(records.size <= MAX_RECORDS && records.all { isValidRecord(it.key, it.value) }) {
            "Invalid reader settings sync records"
        }
        return records.toMap()
    }

    fun validateIncomingRecords(records: Map<String, ReaderSettingRecord>, nowMillis: Long): Map<String, ReaderSettingRecord> {
        val validated = validatedRecords(records)
        val latestAllowed = nowMillis.coerceIn(0L, MAX_TIMESTAMP - MAX_CLOCK_SKEW_MILLIS) + MAX_CLOCK_SKEW_MILLIS
        require(validated.values.all { it.editedAt <= latestAllowed }) { "Reader settings sync clock is too far ahead" }
        return validated
    }

    fun nextTimestamp(nowMillis: Long, records: Map<String, ReaderSettingRecord>): Long {
        val next = maxOf(nowMillis.coerceIn(1L, MAX_TIMESTAMP), (records.values.maxOfOrNull { it.editedAt } ?: 0L) + 1L)
        require(next <= MAX_TIMESTAMP) { "Reader settings sync clock exhausted" }
        return next
    }

    fun encode(records: Map<String, ReaderSettingRecord>): String = json.encodeToString(records)

    // Corrupt sync metadata must not be silently replaced by an unversioned snapshot.
    fun decode(value: String?): Map<String, ReaderSettingRecord> = value?.let {
        validatedRecords(json.decodeFromString<Map<String, ReaderSettingRecord>>(it))
    }.orEmpty()

    fun isNewer(candidate: ReaderSettingRecord, current: ReaderSettingRecord?): Boolean =
        current == null || candidate.editedAt > current.editedAt ||
            (candidate.editedAt == current.editedAt && candidate.deviceId > current.deviceId)

    private fun <T : Any> field(
        name: String,
        get: (ReaderSettings) -> T,
        encode: (T) -> JsonElement,
        decode: (JsonElement) -> T?,
        set: (ReaderSettings, T) -> ReaderSettings,
    ) = Field(name, { encode(get(it)) }, { settings, element -> decode(element)?.let { set(settings, it) } })

    private fun boolean(name: String, get: (ReaderSettings) -> Boolean, set: (ReaderSettings, Boolean) -> ReaderSettings) =
        field(name, get, { JsonPrimitive(it) }, { it.typedPrimitive()?.booleanOrNull }, set)

    private fun integer(name: String, range: IntRange, get: (ReaderSettings) -> Int, set: (ReaderSettings, Int) -> ReaderSettings) =
        field(name, get, { JsonPrimitive(it) }, { it.typedPrimitive()?.intOrNull?.takeIf { value -> value in range } }, set)

    // Match DataStore's Float precision, including in the defaults used for first-run seeding.
    private fun decimal(name: String, range: ClosedFloatingPointRange<Double>, get: (ReaderSettings) -> Double, set: (ReaderSettings, Double) -> ReaderSettings) =
        field(name, get, { JsonPrimitive(it.toFloat().toDouble()) }, {
            it.typedPrimitive()?.doubleOrNull?.takeIf { value -> value.isFinite() && value in range }
        }, set)

    private fun color(name: String, get: (ReaderSettings) -> Long, set: (ReaderSettings, Long) -> ReaderSettings) =
        field(name, get, { JsonPrimitive(it) }, { it.typedPrimitive()?.longOrNull?.takeIf { value -> value in 0L..0xFFFFFFFFL } }, set)

    private fun <T : Any> enumField(name: String, entries: List<T>, get: (ReaderSettings) -> T, raw: (T) -> String, set: (ReaderSettings, T) -> ReaderSettings) =
        field(name, get, { JsonPrimitive(raw(it)) }, { value -> entries.firstOrNull { raw(it) == value.stringValue() } }, set)

    private fun nullableDecimal(
        name: String,
        range: ClosedFloatingPointRange<Double>,
        get: (ReaderSettings) -> Double?,
        set: (ReaderSettings, Double?) -> ReaderSettings,
    ) = Field(name, { get(it)?.let(::JsonPrimitive) ?: JsonNull }, { settings, element ->
        if (element == JsonNull) set(settings, null) else element.typedPrimitive()?.doubleOrNull
            ?.takeIf { it.isFinite() && it in range }?.let { set(settings, it) }
    })

    private fun JsonElement.typedPrimitive(): JsonPrimitive? = (this as? JsonPrimitive)?.takeUnless { it.isString }
    private fun JsonElement.stringValue(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content
}
