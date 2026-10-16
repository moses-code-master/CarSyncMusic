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
data class Track(@PrimaryKey val hash: String, val uri: String, val title: String, val artist: String, val album: String, val durationMs: Long, val artPath: String?, @ColumnInfo(defaultValue = "'audio'") val mediaType: String = "audio", @ColumnInfo(defaultValue = "'audio/mpeg'") val mimeType: String = "audio/mpeg", @ColumnInfo(defaultValue = "0") val sizeBytes: Long = 0, @ColumnInfo(defaultValue = "0") val width: Int = 0, @ColumnInfo(defaultValue = "0") val height: Int = 0)
@Entity(tableName = "playlists")
data class SavedPlaylist(@PrimaryKey(autoGenerate = true) val id: Long = 0, val name: String, val hashes: String, @ColumnInfo(defaultValue = "'audio'") val mediaType: String = "audio")
@Dao interface LibraryDao {
    @Query("SELECT * FROM tracks ORDER BY title COLLATE NOCASE") fun observeTracks(): Flow<List<Track>>
    @Query("SELECT * FROM tracks") suspend fun tracks(): List<Track>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insert(track: Track)
    @Delete suspend fun delete(track: Track)
    @Query("SELECT * FROM playlists ORDER BY name") fun observePlaylists(): Flow<List<SavedPlaylist>>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun save(playlist: SavedPlaylist): Long
    @Delete suspend fun delete(playlist: SavedPlaylist)
}
@Database(entities = [Track::class, SavedPlaylist::class], version = 2, exportSchema = false)
abstract class LibraryDb : RoomDatabase() {
    abstract fun dao(): LibraryDao
    companion object {
        val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE tracks ADD COLUMN mediaType TEXT NOT NULL DEFAULT 'audio'")
                db.execSQL("ALTER TABLE tracks ADD COLUMN mimeType TEXT NOT NULL DEFAULT 'audio/mpeg'")
                db.execSQL("ALTER TABLE tracks ADD COLUMN sizeBytes INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE tracks ADD COLUMN width INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE tracks ADD COLUMN height INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE playlists ADD COLUMN mediaType TEXT NOT NULL DEFAULT 'audio'")
            }
        }
    }
}

fun importTrack(context: Context, uri: Uri, copyToApp: Boolean = true): Track = importLocalMedia(context, uri, "audio", copyToApp)

