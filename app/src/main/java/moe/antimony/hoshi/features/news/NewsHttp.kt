package moe.antimony.hoshi.features.news

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Plain HTTP GETs for feeds and cover images; the app has no HTTP client library. */
class NewsHttp(
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val userAgent: String = DEFAULT_USER_AGENT,
) {
    suspend fun getText(url: String, maxBytes: Int = 4 * 1024 * 1024): String =
        String(getBytes(url, maxBytes), Charsets.UTF_8)

    suspend fun getBytes(url: String, maxBytes: Int): ByteArray = withContext(ioDispatcher) {
        var current = url
        repeat(MAX_REDIRECTS) {
            val connection = (URL(current).openConnection() as HttpURLConnection).apply {
                connectTimeout = 20_000
                readTimeout = 30_000
                instanceFollowRedirects = false
                setRequestProperty("User-Agent", userAgent)
                setRequestProperty("Accept", "*/*")
            }
            try {
                val code = connection.responseCode
                if (code in 300..399) {
                    val location = connection.getHeaderField("Location") ?: throw IOException("Redirect without location from $current")
                    current = URL(URL(current), location).toString()
                    return@repeat
                }
                if (code !in 200..299) throw IOException("HTTP $code for $current")
                return@withContext connection.inputStream.use { input ->
                    val out = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(16 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        out.write(buffer, 0, read)
                        if (out.size() > maxBytes) throw IOException("Response larger than $maxBytes bytes: $current")
                    }
                    out.toByteArray()
                }
            } finally {
                connection.disconnect()
            }
        }
        throw IOException("Too many redirects for $url")
    }

    companion object {
        private const val MAX_REDIRECTS = 5
        const val DEFAULT_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0 Mobile Safari/537.36 HoshiReader"
    }
}
