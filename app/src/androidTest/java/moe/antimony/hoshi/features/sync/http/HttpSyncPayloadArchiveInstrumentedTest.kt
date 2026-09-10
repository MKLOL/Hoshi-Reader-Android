package moe.antimony.hoshi.features.sync.http

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises Android's own ZIP implementation using only a disposable test-cache directory. */
@RunWith(AndroidJUnit4::class)
class HttpSyncPayloadArchiveInstrumentedTest {
    @Test
    fun importsOriginalIosZip64PayloadWithoutLosingPageBytes() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.context
        val root = File(instrumentation.targetContext.cacheDir, "ios-zip64-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            val archive = File(root, "payload.zip")
            context.assets.open("sync/ios-generated-payload.zip").use { input ->
                archive.outputStream().use { input.copyTo(it) }
            }
            val target = File(root, "unpacked")

            HttpSyncPayloadCodec().unzipInto(archive, target)

            val expected = mapOf(
                "mokuro.json" to "71be77829ec9888c3a3ca645efc7237fc9a3dfe338cec46984bacefe2b4bd23f",
                "images/page001.png" to "4a21c728ec24523e014010b8e1b5f9e1615da36455e4b20550e9fab7ef225e06",
                "images/page002.png" to "3a40bb671a5d62abe33537cdb1840931396ad3467f5326018c891c6b9c386efc",
                "images/page003.png" to "3a40bb671a5d62abe33537cdb1840931396ad3467f5326018c891c6b9c386efc",
                "cover.png" to "f36df15062b907caa1de8eebeae0713355bb8f019e868b37bc4cdac8c4b05a5a",
            )
            assertEquals(expected.size, target.walkTopDown().count { it.isFile })
            for ((path, hash) in expected) {
                val actual = MessageDigest.getInstance("SHA-256").digest(target.resolve(path).readBytes())
                    .joinToString("") { "%02x".format(it) }
                assertEquals(path, hash, actual)
            }
        } finally {
            root.deleteRecursively()
        }
    }
}
