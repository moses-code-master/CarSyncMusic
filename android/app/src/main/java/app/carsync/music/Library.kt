package app.carsync.music

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.room.*
import kotlinx.coroutines.flow.Flow
import java.security.MessageDigest

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

fun importTrack(context: Context, uri: Uri): Track {
    val sha = MessageDigest.getInstance("SHA-256")
    context.contentResolver.openInputStream(uri)?.use { input ->
        val buf = ByteArray(65536); while (true) { val n = input.read(buf); if (n < 0) break; sha.update(buf, 0, n) }
    } ?: error("Storage is unavailable. Reconnect the SD card or USB drive.")
    val hash = sha.digest().joinToString("") { "%02x".format(it) }
    val meta = MediaMetadataRetriever()
    try {
        meta.setDataSource(context, uri)
        val duration = meta.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0
        require(duration > 0) { "This file has no playable audio duration" }
        val mime = meta.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE) ?: ""
        require(mime.contains("mpeg") || uri.toString().contains("mp3", true)) { "Select an MP3 file" }
        val art = meta.embeddedPicture?.let { bytes -> java.io.File(context.filesDir, "$hash.jpg").also { it.writeBytes(bytes) }.absolutePath }
        return Track(hash, uri.toString(), meta.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE) ?: uri.lastPathSegment?.substringAfterLast('/') ?: "Untitled", meta.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST) ?: "Unknown artist", meta.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM) ?: "", duration, art)
    } finally { meta.release() }
}
