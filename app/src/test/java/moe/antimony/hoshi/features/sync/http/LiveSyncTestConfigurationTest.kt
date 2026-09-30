package moe.antimony.hoshi.features.sync.http

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LiveSyncTestConfigurationTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun credentialsNeverEnableExternalTestsWithoutExplicitOptIn() {
        temp.newFile(".hoshi-sync-secret.env").writeText("HOSHI_KV_TOKEN=private-test-value")
        assertNull(HttpSyncLiveServerSmokeTest.readSecret(
            "HOSHI_KV_TOKEN", enabled = false,
            environment = { error("Must not resolve private credentials") }, startDirectory = temp.root,
        ))
    }

    @Test fun explicitOptInAllowsEnvironmentThenPrivateFileFallback() {
        temp.newFile(".hoshi-sync-secret.env").writeText("# local test fixture\nHOSHI_KV_TOKEN='file-token'")
        assertEquals("environment-token", HttpSyncLiveServerSmokeTest.readSecret(
            "HOSHI_KV_TOKEN", true, { "environment-token" }, temp.root,
        ))
        assertEquals("file-token", HttpSyncLiveServerSmokeTest.readSecret(
            "HOSHI_KV_TOKEN", true, { null }, temp.root,
        ))
    }
}
