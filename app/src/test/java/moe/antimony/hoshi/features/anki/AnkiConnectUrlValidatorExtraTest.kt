package moe.antimony.hoshi.features.anki

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Coverage beyond [AnkiConnectUrlValidatorTest] for the cleartext-restriction edge cases.
 *
 * The validator's job is to refuse any plaintext HTTP URL except those addressed at a
 * host that is provably inside the user's own network (RFC 1918 / loopback / link-local /
 * `.local` mDNS / ULA). HTTPS is unconditionally trusted.
 */
class AnkiConnectUrlValidatorExtraTest {

    @Test
    fun acceptsRfc1918TenSlashEight() {
        // The base test covers 192.168/172.16 but not 10/8 — yet 10.x.x.x is the most
        // common home-network range mokuro/AnkiConnect users actually hit.
        val uri = AnkiConnectUrlValidator.requireValidEndpoint("http://10.0.1.50:8765")
        assertEquals("10.0.1.50", uri.host)
    }

    @Test
    fun acceptsIpv6LinkLocalOverHttp() {
        // fe80::/10 link-local IPv6 — a phone on the same Wi-Fi as the host should be
        // reachable here without HTTPS.
        val uri = AnkiConnectUrlValidator.requireValidEndpoint("http://[fe80::1]:8765")
        assertNotNull(uri.host)
    }

    @Test
    fun acceptsDotLocalMdnsHosts() {
        // `.local` (mDNS) is private-network-only; the validator allows it over HTTP.
        val uri = AnkiConnectUrlValidator.requireValidEndpoint("http://anki-host.local:8765")
        assertEquals("anki-host.local", uri.host)
    }

    @Test
    fun rejectsPublicHttpAddressedByIp() {
        // 8.8.8.8 — public, must be rejected. This is the attack to defend against:
        // tricking the validator into hitting an attacker-controlled internet host.
        val error = runCatching {
            AnkiConnectUrlValidator.requireValidEndpoint("http://8.8.8.8:8765")
        }.exceptionOrNull()
        assertTrue("expected AnkiConnectUrlException, got $error", error is AnkiConnectUrlException)
        assertEquals(
            "Public AnkiConnect HTTP URLs are blocked. Use HTTPS for internet hosts.",
            (error as AnkiConnectUrlException).message,
        )
    }

    @Test
    fun rejectsPublicHttpWithCommonSubdomain() {
        // A plausible-looking but public-internet host.
        val error = runCatching {
            AnkiConnectUrlValidator.requireValidEndpoint("http://anki.attacker.example:8765")
        }.exceptionOrNull()
        assertTrue("expected public-HTTP rejection, got $error", error is AnkiConnectUrlException)
    }

    @Test
    fun rejectsHostnameInRfc1918LookalikeRange() {
        // 172.32 is *outside* RFC 1918's 172.16-172.31 range and must NOT bypass HTTPS.
        // This pins the upper bound of the second-octet check.
        val error = runCatching {
            AnkiConnectUrlValidator.requireValidEndpoint("http://172.32.0.1:8765")
        }.exceptionOrNull()
        assertTrue("172.32 must be rejected, got $error", error is AnkiConnectUrlException)
    }

    @Test
    fun acceptsHttpsForAnyHost() {
        // HTTPS bypasses the private-host check — the transport itself authenticates.
        assertEquals(
            "https://anything.example.com:8765",
            AnkiConnectUrlValidator.requireValidEndpoint("https://anything.example.com:8765").toString(),
        )
    }

    @Test
    fun rejectsBlankInput() {
        val error = runCatching {
            AnkiConnectUrlValidator.requireValidEndpoint("   ")
        }.exceptionOrNull()
        assertTrue("expected blank-URL rejection, got $error", error is AnkiConnectUrlException)
    }
}
