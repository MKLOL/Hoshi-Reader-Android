package moe.antimony.hoshi.features.sync.http

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/**
 * Optional live-device check for large multipart uploads through Android's HTTP stack.
 * Skips unless base URL, token, and a pushed large file path are supplied as instrumentation
 * args. The uploaded key is deleted in `finally` so the live server is not left with test
 * payloads.
 *
 * Repeatable private-file run shape:
 *  1. Push the large zip to `/data/local/tmp/<name>.zip`.
 *  2. Copy it into target app files with `adb shell run-as moe.antimony.hoshi.debug cp ...`.
 *  3. Put the bearer token in target app files as `hoshi-kv-token.txt`.
 *  4. Run instrumentation with `HOSHI_KV_TOKEN_FILE=target:hoshi-kv-token.txt` and
 *     `HOSHI_KV_MULTIPART_FILE=target:<name>.zip`; do not pass the token directly.
 */
@RunWith(AndroidJUnit4::class)
class HttpSyncDeviceMultipartTest {

    @Test
    fun largeFileMultipartUploadRoundTripsMetadataAgainstLiveServer() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        val baseUrl = args.getString("HOSHI_KV_BASE_URL")
        val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
        val token = args.getString("HOSHI_KV_TOKEN")
            ?: args.getString("HOSHI_KV_TOKEN_FILE")
                ?.let { path ->
                    runCatching {
                        if (path.startsWith(TARGET_FILE_PREFIX)) {
                            targetContext.openFileInput(path.removePrefix(TARGET_FILE_PREFIX))
                                .bufferedReader()
                                .use { it.readText().trim() }
                        } else {
                            File(path).readText().trim()
                        }
                    }.getOrNull()
                }
        val filePath = args.getString("HOSHI_KV_MULTIPART_FILE")
            ?: "/sdcard/Download/012Yotsubato.zip"
        assumeTrue("HOSHI_KV_BASE_URL not set", !baseUrl.isNullOrBlank())
        assumeTrue("HOSHI_KV_TOKEN not set", !token.isNullOrBlank())

        val file = if (filePath.startsWith(TARGET_FILE_PREFIX)) {
            targetContext.getFileStreamPath(filePath.removePrefix(TARGET_FILE_PREFIX))
        } else {
            File(filePath)
        }
        assumeTrue("large multipart source file missing: $filePath", file.isFile)
        assumeTrue("multipart source must be larger than 64 MiB", file.length() > 64L * 1024L * 1024L)

        val client = HttpSyncKvClient(baseUrl!!, token!!)
        val testId = "__smoke/android_multipart_${UUID.randomUUID().toString().substring(0, 8)}"
        val key = "$testId/payload.zip"
        try {
            val response = client.putFile(key, "application/zip", file)
            assertEquals(key, response.key)
            assertEquals(file.length(), response.size.toLong())
            assertEquals(sha256Hex(file), response.etag)

            val listed = client.list(prefix = "$testId/")
            val meta = listed.keys.single { it.key == key }
            assertEquals(file.length(), meta.size.toLong())
            assertTrue(meta.contentType.startsWith("application/zip"))
        } finally {
            runCatching { client.delete(key) }
        }
    }

    private fun sha256Hex(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered(DEFAULT_BUFFER_SIZE).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return "sha256:" + digest.digest().joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val TARGET_FILE_PREFIX = "target:"
    }
}
