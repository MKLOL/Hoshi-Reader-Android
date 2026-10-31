package moe.antimony.hoshi.features.sasayaki

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull
import moe.antimony.hoshi.features.reader.ReaderSettingRecord
import moe.antimony.hoshi.features.reader.ReaderSettingsSyncCodec

/** Reader presentation/playback preferences only; transport and private-storage choices stay local. */
internal object SasayakiSettingsSyncCodec {
    private val excluded = setOf("sasayaki.syncEnabled", "sasayaki.copyAudiobookToPrivateStorage")

    private class Field(
        val name: String,
        val read: (SasayakiSettings) -> JsonElement,
        val apply: (SasayakiSettings, JsonElement) -> SasayakiSettings?,
    )

    private val fields = listOf(
        boolean("enabled", { it.enabled }) { settings, value -> settings.copy(enabled = value) },
        boolean("showReaderToggle", { it.showReaderToggle }) { settings, value -> settings.copy(showReaderToggle = value) },
        boolean("showReaderBottomPlaybackControls", { it.showReaderBottomPlaybackControls }) { settings, value -> settings.copy(showReaderBottomPlaybackControls = value) },
        boolean("reverseVerticalReaderSkipButtons", { it.reverseVerticalReaderSkipButtons }) { settings, value -> settings.copy(reverseVerticalReaderSkipButtons = value) },
        boolean("autoScroll", { it.autoScroll }) { settings, value -> settings.copy(autoScroll = value) },
        boolean("autoPause", { it.autoPause }) { settings, value -> settings.copy(autoPause = value) },
        color("lightTextColor", { it.lightTextColor }) { settings, value -> settings.copy(lightTextColor = value) },
        color("lightBackgroundColor", { it.lightBackgroundColor }) { settings, value -> settings.copy(lightBackgroundColor = value) },
        color("darkTextColor", { it.darkTextColor }) { settings, value -> settings.copy(darkTextColor = value) },
        color("darkBackgroundColor", { it.darkBackgroundColor }) { settings, value -> settings.copy(darkBackgroundColor = value) },
        field("readerSkipButtonAction", { it.readerSkipButtonAction }, { JsonPrimitive(it.name) }, { value ->
            (value as? JsonPrimitive)?.takeIf { it.isString }?.content?.let { raw ->
                SasayakiReaderSkipButtonAction.entries.firstOrNull { it.name == raw }
            }
        }) { settings, value -> settings.copy(readerSkipButtonAction = value) },
    ).associateBy { it.name }

    fun values(settings: SasayakiSettings): Map<String, JsonElement> = fields.mapValues { it.value.read(settings) }

    fun apply(settings: SasayakiSettings, name: String, value: JsonElement): SasayakiSettings =
        fields[name]?.apply?.invoke(settings, value) ?: settings

    fun isValidRecord(name: String, record: ReaderSettingRecord): Boolean =
        name.startsWith("sasayaki.") && name !in excluded &&
            ReaderSettingsSyncCodec.isValidRecord(name, record) &&
            (name !in fields || fields.getValue(name).apply(SasayakiSettings(), record.value) != null)

    fun validatedRecords(records: Map<String, ReaderSettingRecord>): Map<String, ReaderSettingRecord> {
        require(records.size <= ReaderSettingsSyncCodec.MAX_RECORDS && records.all { isValidRecord(it.key, it.value) }) {
            "Invalid Sasayaki settings sync records"
        }
        return records.toMap()
    }

    fun validateIncomingRecords(
        records: Map<String, ReaderSettingRecord>,
        nowMillis: Long = System.currentTimeMillis(),
    ): Map<String, ReaderSettingRecord> = validatedRecords(
        ReaderSettingsSyncCodec.validateIncomingRecords(records, nowMillis),
    )

    fun decode(value: String?): Map<String, ReaderSettingRecord> =
        validatedRecords(ReaderSettingsSyncCodec.decode(value))

    private fun <T : Any> field(
        name: String,
        get: (SasayakiSettings) -> T,
        encode: (T) -> JsonElement,
        decode: (JsonElement) -> T?,
        set: (SasayakiSettings, T) -> SasayakiSettings,
    ) = Field("sasayaki.$name", { encode(get(it)) }, { settings, element -> decode(element)?.let { set(settings, it) } })

    private fun boolean(name: String, get: (SasayakiSettings) -> Boolean, set: (SasayakiSettings, Boolean) -> SasayakiSettings) =
        field(name, get, { JsonPrimitive(it) }, { it.typedPrimitive()?.booleanOrNull }, set)

    private fun color(name: String, get: (SasayakiSettings) -> Long, set: (SasayakiSettings, Long) -> SasayakiSettings) =
        field(name, get, { JsonPrimitive(it) }, { it.typedPrimitive()?.longOrNull?.takeIf { value -> value in 0L..0xFFFFFFFFL } }, set)

    private fun JsonElement.typedPrimitive(): JsonPrimitive? = (this as? JsonPrimitive)?.takeUnless { it.isString }
}
