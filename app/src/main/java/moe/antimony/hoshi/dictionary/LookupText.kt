package moe.antimony.hoshi.dictionary

/**
 * Returns this text with every unpaired UTF-16 surrogate replaced by U+FFFD.
 *
 * The native engine decodes lookup text with utfcpp's checked API, which throws on a lone
 * surrogate; the JNI layer has no handler, so the process aborts. In-app selections already
 * keep characters whole, but the process-text and share intents forward arbitrary text from
 * other apps, and pasted Dictionary searches are unconstrained too. Sanitizing here, at the
 * single Kotlin entry point to the native lookup, covers every caller.
 *
 * Both the UTF-16 length and the code point count are preserved, so callers' highlight and
 * selection offsets remain valid for the returned text.
 */
internal fun String.withUnpairedSurrogatesReplaced(): String {
    var index = 0
    while (index < length && !this[index].isSurrogate()) index++
    if (index == length) return this
    val out = StringBuilder(length).append(this, 0, index)
    while (index < length) {
        val unit = this[index]
        if (unit.isHighSurrogate() && index + 1 < length && this[index + 1].isLowSurrogate()) {
            out.append(unit).append(this[index + 1])
            index += 2
        } else {
            out.append(if (unit.isSurrogate()) REPLACEMENT_CHARACTER else unit)
            index += 1
        }
    }
    return out.toString()
}

private const val REPLACEMENT_CHARACTER = '�'
