package moe.antimony.hoshi.features.podcasts

import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PodcastTranscriptTest {
    @get:Rule val temp = TemporaryFolder()
    private val episode = "e".repeat(64)
    private val account = "a".repeat(64)

    private fun transcript(lines: String, id: String = episode) =
        """{"version":1,"episode":"$id","source":"auto","duration":12.5,"lines":[$lines],"future":"ignored"}"""

    @Test fun aServerTranscriptDecodesWithItsOptionalEnglish() {
        val parsed = parsePodcastTranscript(transcript(
            """{"start":0.5,"end":4,"text":"皆さん、こんにちは。","english":"Hello, everyone."},{"start":4.5,"end":9.25,"text":"今日は天気がいいです。"}""",
        ), episode)!!
        assertEquals(12.5, parsed.duration, 0.0)
        assertEquals(listOf("皆さん、こんにちは。", "今日は天気がいいです。"), parsed.lines.map { it.text })
        assertEquals("Hello, everyone.", parsed.lines[0].english)
        assertNull(parsed.lines[1].english)
    }

    @Test fun aTranscriptThatCannotBeFollowedIsRefusedNotShownWrong() {
        val line = """{"start":1,"end":2,"text":"一"}"""
        assertNull("another episode's", parsePodcastTranscript(transcript(line, id = "f".repeat(64)), episode))
        assertNull("no lines", parsePodcastTranscript(transcript(""), episode))
        assertNull("backwards", parsePodcastTranscript(transcript("""{"start":5,"end":6,"text":"二"},$line"""), episode))
        assertNull("not JSON", parsePodcastTranscript("<html>", episode))
        // Lines nobody could see or seek to are dropped; the rest still play.
        val kept = parsePodcastTranscript(transcript("""$line,{"start":3,"end":2,"text":"x"},{"start":4,"end":5,"text":"  "}"""), episode)!!
        assertEquals(listOf("一"), kept.lines.map { it.text })
    }

    @Test fun theCurrentLineIsTheLastOneThatHasStarted() {
        val lines = listOf(
            PodcastTranscriptLine(1.0, 3.0, "一"),
            PodcastTranscriptLine(4.0, 6.0, "二"),
            PodcastTranscriptLine(8.0, 9.0, "三"),
        )
        assertEquals(-1, podcastTranscriptLineAt(lines, 999))
        assertEquals(0, podcastTranscriptLineAt(lines, 1_000))
        // The pause between two lines keeps the earlier one highlighted.
        assertEquals(0, podcastTranscriptLineAt(lines, 3_500))
        assertEquals(1, podcastTranscriptLineAt(lines, 7_999))
        assertEquals(2, podcastTranscriptLineAt(lines, 60_000))
        assertEquals(-1, podcastTranscriptLineAt(emptyList(), 5_000))
    }

    @Test fun theOriginalRecordingHasItsOwnMediaIdAndPlaybackPosition() {
        assertEquals(PodcastMediaRef(account, episode, original = false), parsePodcastMediaId(podcastMediaId(account, episode)))
        assertEquals(PodcastMediaRef(account, episode, original = true), parsePodcastMediaId(podcastMediaId(account, episode, original = true)))
        assertNotEquals(podcastMediaId(account, episode), podcastMediaId(account, episode, original = true))
        for (foreign in listOf("$account:$episode:lesson", "$account:$episode:original:x", "$account:${episode}0", "x:$episode", "")) {
            assertNull(foreign, parsePodcastMediaId(foreign))
        }
    }

    @Test fun aTranscriptNeedsBothItsRecordingAndItsText() {
        val files = PodcastFiles(temp.newFolder())
        files.original(account, episode).apply { parentFile!!.mkdirs() }.writeText("mp3")
        assertFalse(files.hasTranscript(account, episode))
        files.transcript(account, episode).writeText(transcript("""{"start":0,"end":1,"text":"一"}"""))
        assertTrue(files.hasTranscript(account, episode))
        assertNotEquals(files.audio(account, episode), files.original(account, episode))
    }

    @Test fun anEpisodeWithOnlyItsTranscriptOnTheDeviceStaysListedOffline() {
        val root = temp.newFolder()
        val files = PodcastFiles(root)
        val saved = PodcastEpisode(episode, "Transcript only", "2026-10-01", 300, "ready", show = "archived", transcript = true)
        files.saveCatalogue(account, PodcastCatalogue(listOf(saved), listOf(PodcastShow("archived", "Archived"))))
        files.rememberDownload(account, saved)
        files.original(account, episode).writeText("mp3")
        files.transcript(account, episode).writeText("{}")

        files.saveCatalogue(account, PodcastCatalogue(emptyList()))

        assertEquals(listOf(saved), PodcastFiles(root).loadCatalogue(account)!!.episodes)
    }

    @Test fun episodesSayWhetherTheyHaveATranscriptAndWhetherTheyWereUploaded() {
        val json = Json { ignoreUnknownKeys = true }
        val base = """"id":"$episode","title":"t","published_at":"p","status":"ready""""
        val older = json.decodeFromString<PodcastEpisode>("{$base}")
        assertFalse(older.transcript)
        assertFalse(older.uploaded)
        val newer = json.decodeFromString<PodcastEpisode>("""{$base,"transcript":true,"uploaded":true}""")
        assertTrue(newer.transcript)
        assertTrue(newer.uploaded)
    }
}
