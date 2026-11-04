package app.carsync.music

import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.io.InputStream

/** The server's acknowledged offset is authoritative, including after a lost response. */
class ResumableUploader(private val http: OkHttpClient) {
    suspend fun upload(url: String, token: String, size: Long, open: () -> InputStream,
                       progress: (Long, Int) -> Unit) = withContext(Dispatchers.IO) {
        var failures = 0
        while (true) {
            ensureActive()
            try {
                val status = execute(Request.Builder().url("$url?upload=1&size=$size").header("Authorization", "Bearer $token").get().build())
                val offset = status.getLong("offset")
                require(offset in 0..size) { "Server upload size differs; reimport the track." }
                progress(offset, 0)
                if (status.getBoolean("complete")) {
                    require(offset == size) { "Server track size differs." }
                    break
                }
                require(offset < size) { "Server upload is incomplete; retry sharing." }
                val count = minOf(256 * 1024L, size - offset).toInt()
                val bytes = ByteArray(count)
                open().use { input ->
                    var remaining = offset
                    while (remaining > 0) {
                        ensureActive()
                        val skipped = input.skip(remaining)
                        if (skipped > 0) remaining -= skipped
                        else { if (input.read() < 0) throw IOException("File ended before upload offset"); remaining-- }
                    }
                    var read = 0
                    while (read < count) {
                        ensureActive()
                        val n = input.read(bytes, read, count - read)
                        if (n < 0) throw IOException("File changed during upload")
                        read += n
                    }
                }
                val result = execute(Request.Builder().url(url).header("Authorization", "Bearer $token")
                    .header("Content-Range", "bytes $offset-${offset + count - 1}/$size")
                    .patch(bytes.toRequestBody("application/octet-stream".toMediaType())).build())
                val next = result.getLong("offset")
                require(next == offset + count) { "Unexpected upload acknowledgement" }
                progress(next, 0)
                failures = 0
                if (result.getBoolean("complete")) break
            } catch (e: IOException) {
                ensureActive()
                failures++
                if (failures > 6) throw IOException("Upload paused. Saved progress is kept; tap Retry upload when connected.", e)
                progress(-1, failures)
                delay(minOf(1000L shl (failures - 1), 30000L))
            }
        }
    }

    /** Keep v1.9.1 servers usable until they advertise resumable upload support. */
    suspend fun uploadLegacy(url: String, token: String, size: Long, mime: String, open: () -> InputStream,
                             progress: (Long, Int) -> Unit) = withContext(Dispatchers.IO) {
        var failures = 0
        while (true) {
            ensureActive()
            try {
                progress(0, 0)
                val body = object : RequestBody() {
                    override fun contentType() = mime.toMediaType()
                    override fun contentLength() = size
                    override fun writeTo(sink: okio.BufferedSink) {
                        open().use { input ->
                            val buffer = ByteArray(65536); var written = 0L
                            while (true) {
                                ensureActive()
                                val n = input.read(buffer)
                                if (n < 0) break
                                written += n
                                check(written <= size) { "File changed during upload" }
                                sink.write(buffer, 0, n); progress(written, 0)
                            }
                            check(written == size) { "File changed during upload" }
                        }
                    }
                }
                http.newCall(Request.Builder().url(url).header("Authorization", "Bearer $token").put(body).build()).execute().use { response ->
                    if (response.code == 409 || response.code == 429 || response.code >= 500) throw IOException("Server busy (${response.code})")
                    check(response.isSuccessful) { runCatching { JSONObject(response.body?.string().orEmpty()).optString("error") }.getOrDefault("Upload rejected (${response.code})") }
                }
                progress(size, 0); break
            } catch (e: IOException) {
                ensureActive(); failures++
                if (failures > 6) throw IOException("Upload paused. Tap Retry upload when connected.", e)
                progress(-1, failures); delay(minOf(1000L shl (failures - 1), 30000L))
            }
        }
    }

    private fun execute(request: Request): JSONObject = http.newCall(request).execute().use { response ->
        if (response.isSuccessful) check(response.header("Content-Type")?.startsWith("application/json") == true) { "Server needs the resumable upload update." }
        val text = response.body?.string().orEmpty()
        if (response.code == 409 || response.code == 429 || response.code >= 500) throw IOException("Server busy (${response.code})")
        check(response.isSuccessful) {
            if (response.code == 404) "Server needs the resumable upload update, or this track/session is no longer available."
            else runCatching { JSONObject(text).optString("error") }.getOrDefault("Upload rejected (${response.code})")
        }
        JSONObject(text)
    }
}
