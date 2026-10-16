package app.carsync.music

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import java.io.File
import java.security.MessageDigest
import java.util.UUID

fun importVideo(context: Context, uri: Uri, copyToApp: Boolean = true): Track = importLocalMedia(context, uri, "video", copyToApp)

fun isVideoUri(context: Context, uri: Uri): Boolean {
    val mime = runCatching { context.contentResolver.getType(uri) }.getOrNull().orEmpty()
    return mime.startsWith("video/") || uri.lastPathSegment.orEmpty().substringAfterLast('.').lowercase() in setOf("mp4", "mkv", "webm", "m4v", "mov", "3gp", "ts", "avi")
}

fun importLocalMedia(context: Context, uri: Uri, kind: String, copyToApp: Boolean): Track {
    require(kind == "audio" || kind == "video")
    val video = kind == "video"
    val directory = File(context.filesDir, if (video) "videos" else "music").apply { mkdirs() }
    val temporary = File(directory, "${UUID.randomUUID()}.part")
    var newOwned: File? = null
    try {
        val sha = MessageDigest.getInstance("SHA-256"); var size = 0L
        context.contentResolver.openInputStream(uri)?.use { input ->
            val out = if (copyToApp) temporary.outputStream() else null
            try {
                val buffer = ByteArray(65536)
                while (true) {
                    val n = input.read(buffer); if (n < 0) break
                    size += n
                    require(size <= if (video) 4L * 1024 * 1024 * 1024 else 104857600L) { if (video) "Local video limit is 4 GB. Cloud video upload is not supported." else "MP3 exceeds 100 MB" }
                    if (out != null) require(directory.usableSpace > 8L * 1024 * 1024) { "Not enough free storage to import this file" }
                    sha.update(buffer, 0, n); out?.write(buffer, 0, n)
                }
            } finally { out?.close() }
        } ?: error("Cannot read the file. Allow media access or open it from FileManager with read permission.")
        require(size > 0) { "File is empty" }
        val hash = sha.digest().joinToString("") { "%02x".format(it) }
        val owned = File(directory, "$hash.${if (video) "video" else "mp3"}")
        if (copyToApp && !owned.exists()) { require(temporary.renameTo(owned)) { "Cannot save imported file" }; newOwned = owned }
        val playable = if (copyToApp) Uri.fromFile(owned) else uri
        val name = runCatching { context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null } }.getOrNull() ?: uri.lastPathSegment ?: "Untitled"
        val meta = MediaMetadataRetriever()
        try {
            try { meta.setDataSource(context, playable) } catch (e: RuntimeException) { error("Cannot read this ${if (video) "video/container" else "MP3"} on this device. Try MP4 with H.264/AAC. ${e.message.orEmpty()}") }
            val duration = meta.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0
            require(duration in 1..86400000) { "File has no supported duration (maximum 24 hours)" }
            val detected = meta.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE).orEmpty()
            var width = meta.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            var height = meta.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            if (video) require(width > 0 && height > 0) { "No readable video track. Try an MP4/MKV/WebM supported by this device." }
            else require((detected.isEmpty() || detected.contains("mpeg")) && width == 0) { "Choose an MP3, or import this file from Videos" }
            val rotation = meta.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            if (rotation == 90 || rotation == 270) { val swap = width; width = height; height = swap }
            val art = if (video) {
                try {
                    val w = if (width >= height) 320 else (320L * width / height).toInt().coerceAtLeast(1)
                    val h = if (height >= width) 320 else (320L * height / width).toInt().coerceAtLeast(1)
                    val frame = if (Build.VERSION.SDK_INT >= 27) meta.getScaledFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, w, h) else meta.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    frame?.let {
                        val thumb = Bitmap.createScaledBitmap(it, w, h, true)
                        val file = File(context.filesDir, "$hash.jpg"); file.outputStream().use { stream -> thumb.compress(Bitmap.CompressFormat.JPEG, 80, stream) }
                        if (thumb !== it) thumb.recycle(); it.recycle(); file.absolutePath
                    }
                } catch (_: RuntimeException) { null } catch (_: OutOfMemoryError) { null } // Thumbnail is optional, never required to play.
            } else meta.embeddedPicture?.let { bytes -> File(context.filesDir, "$hash.jpg").also { it.writeBytes(bytes) }.absolutePath }
            val mime = if (!video) "audio/mpeg" else if (detected.startsWith("video/")) detected else when (name.substringAfterLast('.').lowercase()) { "mkv" -> "video/x-matroska"; "webm" -> "video/webm"; else -> "video/mp4" }
            val result = Track(hash, playable.toString(), meta.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE) ?: name, meta.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST) ?: if (video) "Video" else "Unknown artist", meta.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM).orEmpty(), duration, art, kind, mime, size, width, height)
            newOwned = null
            return result
        } finally { meta.release() }
    } finally { temporary.delete(); newOwned?.delete() }
}
