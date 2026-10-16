package app.carsync.music

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import androidx.room.*
import kotlinx.coroutines.flow.Flow
import java.security.MessageDigest
import java.io.File

@Entity(tableName = "tracks")
data class Track(@PrimaryKey val hash: String, val uri: String, val title: String, val artist: String, val album: String, val durationMs: Long, val artPath: String?)
@Entity(tableName = "playlists")
data class SavedPlaylist(@PrimaryKey(autoGenerate = true) val id: Long = 0, val name: String, val hashes: String)
@Dao interface LibraryDao {
    @Query("SELECT * FROM tracks ORDER BY title COLLATE NOCASE") fun observeTracks(): Flow<List<Track>>
    @Query("SELECT * FROM tracks") suspend fun tracks(): List<Track>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insert(track: Track)
    @Query("SELECT * FROM playlists ORDER BY name") fun observePlaylists(): Flow<List<SavedPlaylist>>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun save(playlist: SavedPlaylist): Long
    @Delete suspend fun delete(playlist: SavedPlaylist)
}
@Database(entities = [Track::class, SavedPlaylist::class], version = 1, exportSchema = false)
abstract class LibraryDb : RoomDatabase() { abstract fun dao(): LibraryDao }

fun importTrack(context: Context, uri: Uri, copyToApp: Boolean = true): Track {
    val sha = MessageDigest.getInstance("SHA-256")
    val directory = File(context.filesDir, "music").apply { mkdirs() }
    val temporary = File(directory, "${java.util.UUID.randomUUID()}.part")
    try {
        var bytes = 0L
        context.contentResolver.openInputStream(uri)?.use { input ->
            val out = if (copyToApp) temporary.outputStream() else null
            try {
                val buffer = ByteArray(65536)
                while (true) { val n = input.read(buffer); if (n < 0) break; bytes += n; require(bytes <= 104857600) { "MP3 exceeds 100 MB" }; sha.update(buffer, 0, n); out?.write(buffer, 0, n) }
            } finally { out?.close() }
        } ?: error("Cannot read the selected file. Try Scan phone or import again.")
        require(bytes > 0) { "File is empty" }
        val hash = sha.digest().joinToString("") { "%02x".format(it) }
        val owned = File(directory, "$hash.mp3")
        if (copyToApp) { if (!owned.exists()) require(temporary.renameTo(owned)) { "Not enough space to save MP3" } else temporary.delete() }
        val playableUri = if (copyToApp) Uri.fromFile(owned) else uri
        val name = if (uri.scheme == "content") runCatching { context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null } }.getOrNull() else uri.lastPathSegment
        val meta = MediaMetadataRetriever()
        try {
            meta.setDataSource(context, playableUri)
            val duration = meta.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0
            require(duration > 0) { "This file has no playable audio duration" }
            val mime = meta.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE).orEmpty()
            require(mime.isEmpty() || mime.contains("mpeg")) { "Choose an MP3 audio file" }
            val art = meta.embeddedPicture?.let { bytes -> File(context.filesDir, "$hash.jpg").also { it.writeBytes(bytes) }.absolutePath }
            return Track(hash, playableUri.toString(), meta.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE) ?: name ?: "Untitled", meta.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST) ?: "Unknown artist", meta.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM).orEmpty(), duration, art)
        } finally { meta.release() }
    } finally { temporary.delete() }
}
