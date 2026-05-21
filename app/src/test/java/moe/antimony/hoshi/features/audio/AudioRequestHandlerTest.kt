package moe.antimony.hoshi.features.audio

import org.junit.Assert.assertEquals
import org.junit.Test
import java.net.URLEncoder
import java.nio.file.Files

class AudioRequestHandlerTest {
    @Test
    fun ankiconnectAndroidLocalAudioUrlIsFetchedAsExternalJsonSource() {
        val filesDir = Files.createTempDirectory("hoshi-audio-request").toFile()
        var fetchedTarget: String? = null
        val handler = AudioRequestHandler(
            localAudioRepository = LocalAudioRepository(filesDir),
            fetchRemoteAudioList = { target ->
                fetchedTarget = target
                """{"type":"audioSourceList","audioSources":[{"name":"nhk16","url":"http://localhost:8765/localaudio/nhk16/yomu.mp3"}]}""".toByteArray()
            },
        )
        val target = "http://localhost:8765/localaudio/get/?term=%E8%AA%AD%E3%82%80&reading=%E3%82%88%E3%82%80"

        val body = handler.handleAudioRequestBody("https://hoshi.local/audio?url=${target.urlEncodeForQuery()}")

        assertEquals(target, fetchedTarget)
        assertEquals(
            """{"type":"audioSourceList","audioSources":[{"name":"nhk16","url":"http://localhost:8765/localaudio/nhk16/yomu.mp3"}]}""",
            body?.toString(Charsets.UTF_8),
        )
    }

    private fun String.urlEncodeForQuery(): String =
        URLEncoder.encode(this, Charsets.UTF_8.name())
}
