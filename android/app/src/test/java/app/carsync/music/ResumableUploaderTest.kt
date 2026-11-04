package app.carsync.music

import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException

class ResumableUploaderTest {
    private fun response(request: Request, text: String, code: Int = 200) = Response.Builder()
        .request(request).protocol(Protocol.HTTP_1_1).code(code).message("test")
        .header("Content-Type", "application/json").body(text.toResponseBody("application/json".toMediaType())).build()

    @Test fun lostChunkResponseResumesAtAcknowledgedOffset() = runBlocking {
        val source = ByteArray(600000) { (it % 251).toByte() }
        var offset = 100000 // A prior interrupted run already uploaded these bytes.
        val starts = mutableListOf<Int>()
        var lost = false
        val progress = mutableListOf<Long>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            assertEquals("Bearer original-session-token", request.header("Authorization"))
            if (request.method == "GET") response(request, """{"offset":$offset,"complete":${offset == source.size}}""")
            else {
                val buffer = Buffer(); request.body!!.writeTo(buffer)
                val bytes = buffer.readByteArray()
                starts += offset
                assertEquals("bytes $offset-${offset + bytes.size - 1}/${source.size}", request.header("Content-Range"))
                assertArrayEquals(source.copyOfRange(offset, offset + bytes.size), bytes)
                offset += bytes.size
                if (!lost) { lost = true; throw IOException("Acknowledgement lost after server committed chunk") }
                response(request, """{"offset":$offset,"complete":${offset == source.size}}""")
            }
        }.build()
        ResumableUploader(client).upload("https://example.test/audio", "original-session-token", source.size.toLong(),
            { ByteArrayInputStream(source) }) { bytes, _ -> if (bytes >= 0) progress += bytes }
        assertEquals(listOf(100000, 362144), starts)
        assertEquals(source.size.toLong(), progress.last())
    }

    @Test fun completedTrackIsNotUploadedAgain() = runBlocking {
        var requests = 0
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            requests++; assertEquals("GET", chain.request().method)
            response(chain.request(), """{"offset":123,"complete":true}""")
        }.build()
        ResumableUploader(client).upload("https://example.test/audio", "token", 123,
            { error("Completed track must not reopen the local file") }) { _, _ -> }
        assertEquals(1, requests)
    }

    @Test fun permissionFailureDoesNotRetry() = runBlocking {
        var requests = 0
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            requests++; response(chain.request(), """{"error":"Sharing revoked"}""", 403)
        }.build()
        try {
            ResumableUploader(client).upload("https://example.test/audio", "token", 123,
                { error("No file should be read") }) { _, _ -> }
            fail("Expected rejection")
        } catch (e: IllegalStateException) { assertEquals("Sharing revoked", e.message) }
        assertEquals(1, requests)
    }

    @Test fun existingServerKeepsFullFileUploadsWithProgress() = runBlocking {
        val source = ByteArray(170000) { (it % 199).toByte() }
        val progress = mutableListOf<Long>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            assertEquals("PUT", request.method)
            assertEquals("Bearer token", request.header("Authorization"))
            assertNull(request.header("Content-Range"))
            assertEquals(source.size.toLong(), request.body!!.contentLength())
            val buffer = Buffer(); request.body!!.writeTo(buffer)
            assertArrayEquals(source, buffer.readByteArray())
            response(request, """{"ok":true}""")
        }.build()
        ResumableUploader(client).uploadLegacy("https://example.test/audio", "token", source.size.toLong(), "audio/mpeg",
            { ByteArrayInputStream(source) }) { bytes, _ -> if (bytes >= 0) progress += bytes }
        assertEquals(source.size.toLong(), progress.last())
        assertTrue(progress.any { it in 1 until source.size.toLong() })
    }
}
