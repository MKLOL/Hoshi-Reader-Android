package moe.antimony.hoshi.features.anki

import org.junit.Assert.assertEquals
import org.junit.Test

class AnkiConnectUrlValidatorTest {
    @Test
    fun acceptsPublicHttpsHosts() {
        assertEquals(
            "https://anki.example.com:8765",
            AnkiConnectUrlValidator.requireValidEndpoint("https://anki.example.com:8765").toString(),
        )
    }

    @Test
    fun acceptsLocalAndPrivateHttpHosts() {
        val accepted = listOf(
            "http://localhost:8765",
            "http://127.0.0.1:8765",
            "http://[::1]:8765",
            "http://10.0.2.2:8765",
            "http://192.168.1.20:8765",
            "http://172.16.0.5:8765",
            "http://169.254.1.2:8765",
            "http://[fd00::1]:8765",
        )

        assertEquals(
            accepted,
            accepted.map { AnkiConnectUrlValidator.requireValidEndpoint(it).toString() },
        )
    }

    @Test
    fun rejectsPublicHttpHosts() {
        val error = try {
            AnkiConnectUrlValidator.requireValidEndpoint("http://anki.example.com:8765")
            throw AssertionError("Expected public HTTP to be rejected.")
        } catch (error: AnkiConnectUrlException) {
            error
        }

        assertEquals(
            "Public AnkiConnect HTTP URLs are blocked. Use HTTPS for internet hosts.",
            error.message,
        )
    }

    @Test
    fun rejectsMissingOrUnsupportedSchemes() {
        assertEquals(
            "Enter a valid http:// or https:// AnkiConnect URL.",
            ankiConnectUrlError("anki.example.com:8765"),
        )
        assertEquals(
            "Enter a valid http:// or https:// AnkiConnect URL.",
            ankiConnectUrlError("ftp://anki.example.com"),
        )
    }

    private fun ankiConnectUrlError(rawUrl: String): String? =
        try {
            AnkiConnectUrlValidator.requireValidEndpoint(rawUrl)
            null
        } catch (error: AnkiConnectUrlException) {
            error.message
        }
}
