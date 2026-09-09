package moe.antimony.hoshi.features.news

/** Finds the article link in text shared from a browser ("Title https://... via App"). */
object NewsSharedUrl {
    private val URL = Regex("""https?://[^\s<>"'）)\]]+""", RegexOption.IGNORE_CASE)

    fun extract(text: CharSequence?): String? {
        val match = URL.find(text ?: return null) ?: return null
        return match.value.trimEnd('.', ',', '。', '、')
    }
}
