package moe.antimony.hoshi.features.podcasts

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import kotlinx.serialization.json.Json
import moe.antimony.hoshi.BuildConfig
import moe.antimony.hoshi.features.sync.http.HttpSyncSettings
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.HttpUrl.Companion.toHttpUrl

internal class PodcastHttpException(val status: Int) : IOException()

internal class PodcastApi {
    private val json = Json { ignoreUnknownKeys = true }
    val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()

    fun request(settings: HttpSyncSettings, suffix: String, post: Boolean = false): Request {
        val base = settings.baseUrl.trimEnd('/').toHttpUrl()
        require(base.isHttps || (BuildConfig.DEBUG && base.host in setOf("127.0.0.1", "localhost", "10.0.2.2")))
        require(base.username.isEmpty() && base.password.isEmpty() && base.query == null && base.fragment == null)
        require(settings.bearerToken.isNotBlank())
        return Request.Builder().url("${base.toString().trimEnd('/')}/v1/podcasts$suffix")
            .header("Authorization", "Bearer ${settings.bearerToken}")
            .apply { if (post) post(ByteArray(0).toRequestBody()) }.build()
    }

    private suspend fun text(settings: HttpSyncSettings, suffix: String, post: Boolean = false): String =
        suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request(settings, suffix, post))
            call.timeout().timeout(45, TimeUnit.SECONDS)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, error: IOException) { continuation.resumeWithException(error) }
                override fun onResponse(call: Call, response: Response) {
                    try {
                        response.use {
                            if (!it.isSuccessful) throw PodcastHttpException(it.code)
                            val source = it.body?.source() ?: throw IOException()
                            source.request(2L * 1024 * 1024 + 1)
                            if (source.buffer.size > 2 * 1024 * 1024) throw IOException()
                            continuation.resume(source.readUtf8())
                        }
                    } catch (error: Exception) { continuation.resumeWithException(error) }
                }
            })
        }

    suspend fun access(settings: HttpSyncSettings): Boolean =
        json.decodeFromString<PodcastAccess>(text(settings, "/access")).enabled
    suspend fun catalogue(settings: HttpSyncSettings): PodcastCatalogue =
        json.decodeFromString(text(settings, ""))
    suspend fun prepare(settings: HttpSyncSettings, id: String): PodcastEpisode {
        require(validPodcastId(id))
        return json.decodeFromString(text(settings, "/$id/prepare", true))
    }
}
