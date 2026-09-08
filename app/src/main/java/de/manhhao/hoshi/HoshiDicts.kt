package de.manhhao.hoshi

class ImportResult(
    val success: Boolean,
    val title: String,
    val termCount: Long,
    val metaCount: Long,
    val freqCount: Long,
    val pitchCount: Long,
    val mediaCount: Long,
)

class DictionaryStyle(
    val dictName: String,
    val styles: String,
)

class Frequency(
    val value: Int,
    val displayValue: String,
)

class GlossaryEntry(
    val dictName: String,
    val glossary: String,
    val definitionTags: String,
    val termTags: String,
)

class FrequencyEntry(
    val dictName: String,
    val frequencies: Array<Frequency>,
)

class PitchEntry(
    val dictName: String,
    val pitchPositions: IntArray,
)

class TermResult(
    val expression: String,
    val reading: String,
    val rules: String,
    val glossaries: Array<GlossaryEntry>,
    val frequencies: Array<FrequencyEntry>,
    val pitches: Array<PitchEntry>,
)

class TransformGroup(
    val name: String,
    val description: String,
)

class LookupResult(
    val matched: String,
    val deinflected: String,
    val process: Array<TransformGroup>,
    val term: TermResult,
    val preprocessorSteps: Int,
)

object HoshiDicts {
    init {
        System.loadLibrary("hoshidicts_jni")
    }

    val lookupObject: Long = createLookupObject()

    external fun importDictionary(zipPath: String, outputDir: String, lowRam: Boolean = false): ImportResult
    external fun createLookupObject(): Long

    // The bridge shares native Lookup/DictionaryQuery objects without internal locks. Rebuilding
    // frees their mapped buffers, so all reads and lifetime changes must use this same monitor,
    // including calls made by another repository or a WebView media thread.
    @Synchronized
    external fun destroyLookupObject(session: Long)

    @Synchronized
    external fun rebuildQuery(
        session: Long,
        termPaths: Array<String>,
        freqPaths: Array<String>,
        pitchPaths: Array<String>,
    )

    @Synchronized
    external fun lookup(session: Long, text: String, maxResults: Int, scanLength: Int): Array<LookupResult>

    @Synchronized
    external fun getStyles(session: Long): Array<DictionaryStyle>

    @Synchronized
    external fun getMediaFile(session: Long, dictName: String, mediaPath: String): ByteArray?
}
