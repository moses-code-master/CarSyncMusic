package app.carsync.music

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiSelector
import androidx.room.Room
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class VideoRuntimeTest {
    @get:Rule val permissions = androidx.test.rule.GrantPermissionRule.grant(*if (android.os.Build.VERSION.SDK_INT >= 33) arrayOf(android.Manifest.permission.POST_NOTIFICATIONS, android.Manifest.permission.READ_MEDIA_VIDEO, android.Manifest.permission.READ_MEDIA_AUDIO) else arrayOf(android.Manifest.permission.READ_EXTERNAL_STORAGE))
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val sync get() = (context.applicationContext as CarSyncApp).sync
    private lateinit var scenario: ActivityScenario<MainActivity>
    private fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)
    private fun await(label: String, check: () -> Boolean) {
        val end = android.os.SystemClock.elapsedRealtime() + 35000
        while (android.os.SystemClock.elapsedRealtime() < end) { if (check()) return; Thread.sleep(100) }
        fail("$label: ${sync.state.value}")
    }
    private fun fixture(name: String): Track {
        val source = File(context.cacheDir, name)
        instrumentation.context.assets.open(name).use { input -> source.outputStream().use { input.copyTo(it) } }
        val track = importVideo(context, Uri.fromFile(source)); source.delete()
        runBlocking { sync.dao.insert(track) }
        await("Video library not updated") { sync.state.value.library.any { it.hash == track.hash } }
        return track
    }
    private fun capture(name: String) {
        val file = File(context.getExternalFilesDir(null), name); val device = UiDevice.getInstance(instrumentation)
        assertTrue(device.takeScreenshot(file)); device.executeShellCommand("mkdir -p /sdcard/Download/CarSync-previews"); device.executeShellCommand("cp ${file.absolutePath} /sdcard/Download/CarSync-previews/$name")
    }
    @Before fun setup() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        main { sync.leave(); sync.driving(false); sync.revokeParked(); sync.audioOnlyVideo(false) }
        await("Service not ready") { sync.state.value.serviceReady }
    }
    @After fun cleanup() { main { sync.leave(); sync.driving(false); sync.revokeParked() }; scenario.close() }

    @Test fun mp4MkvPortraitMetadataLocalFramesPlaylistsAndSafety() {
        val mp4 = fixture("CarSync-Landscape.mp4"); val mkv = fixture("CarSync-Landscape.mkv"); val portrait = fixture("CarSync-Portrait.mp4")
        assertEquals("video", mp4.mediaType); assertTrue(mp4.sizeBytes > 0); assertTrue(mp4.durationMs > 44000)
        assertTrue(mp4.width > mp4.height); assertTrue(portrait.height > portrait.width)
        assertTrue(mp4.artPath?.let { File(it).exists() } == true); assertTrue(mkv.durationMs > 44000); assertNotEquals(mp4.hash, mkv.hash)
        val device = UiDevice.getInstance(instrumentation); device.findObject(UiSelector().text("Videos")).click(); Thread.sleep(500); capture("video-library.png")
        main { sync.listenLocal(listOf(mp4.hash, mkv.hash)); sync.confirmParked() }
        device.findObject(UiSelector().text("Player")).click()
        await("Local video did not render") { var frames = 0; main { frames = sync.renderingPlayer()?.videoDecoderCounters?.renderedOutputBufferCount ?: 0 }; sync.state.value.playing && frames > 2 }
        capture("video-player-portrait.png")
        val full = device.findObject(UiSelector().text("Fullscreen")); if (!full.exists()) androidx.test.uiautomator.UiScrollable(UiSelector().scrollable(true)).scrollTextIntoView("Fullscreen")
        full.click(); Thread.sleep(900); capture("video-player-fullscreen.png"); device.pressBack(); Thread.sleep(600)
        main { sync.command("pause") }; await("Local video pause") { !sync.state.value.playing }
        main { sync.command("seek", 12000); sync.command("play") }; await("Local video seek/resume") { sync.state.value.playing && sync.state.value.positionMs > 12000 }
        main { sync.command("seek", 44000) }; await("Video playlist automatic advance") { sync.state.value.index == 1 && sync.state.value.playing }
        main { sync.audioOnlyVideo(true) }; assertFalse(sync.state.value.showVideo)
        val before = sync.state.value.positionMs; await("Audio-only video stopped timeline") { sync.state.value.positionMs > before + 500 }
        main { sync.confirmParked(); sync.driving(true) }; assertFalse(sync.state.value.showVideo)
        main { sync.confirmParked() }; assertFalse(sync.state.value.parkedConfirmed)
        main { sync.driving(false) }; assertFalse(sync.state.value.showVideo)
        main { sync.confirmParked() }; assertTrue(sync.state.value.showVideo)
        scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED); assertFalse(sync.state.value.parkedConfirmed); scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
        main { sync.savePlaylist("Video test", listOf(mp4.hash, mkv.hash), mediaType = "video") }
        await("Video playlist save") { sync.state.value.playlists.any { it.name == "Video test" } }
        val playlist = sync.state.value.playlists.first { it.name == "Video test" }
        main { sync.savePlaylist("Video renamed", listOf(mkv.hash, mp4.hash), playlist.id, "video") }
        await("Video playlist reorder/rename") { sync.state.value.playlists.any { it.id == playlist.id && it.name == "Video renamed" && it.hashes.startsWith(mkv.hash) } }
        main { sync.listenLocal(listOf(portrait.hash)); sync.confirmParked() }; await("Portrait video failed") { sync.state.value.playing && sync.state.value.track?.hash == portrait.hash }
        main { sync.leave(); sync.removeVideo(portrait) }; await("Video record removal") { sync.state.value.library.none { it.hash == portrait.hash } }
        val invalid = File(context.cacheDir, "invalid.mp4").apply { writeText("not a video") }
        main { sync.importFiles(listOf(Uri.fromFile(invalid)), "video") }
        await("Unsupported video error not surfaced") { !sync.state.value.busy && sync.state.value.message.contains("Import failed:") }; invalid.delete()
    }

    @Test fun localVideoHostGuestMismatchRecoveryPauseSeekNextAndReconnect() {
        val mp4 = fixture("CarSync-Landscape.mp4"); val mkv = fixture("CarSync-Landscape.mkv")
        main { sync.configure("http://10.0.2.2:8080", "Video host"); sync.create() }
        await("Host clock") { sync.state.value.connected && sync.state.value.clockReady }
        main { sync.playTrack(mp4.hash); sync.confirmParked() }; await("Host video playback") { sync.state.value.playing && sync.state.value.track?.hash == mp4.hash }
        val db = Room.inMemoryDatabaseBuilder(context, LibraryDb::class.java).build()
        runBlocking { db.dao().insert(mkv) } // Different container/hash must not substitute for MP4.
        context.getSharedPreferences("video-guest", Context.MODE_PRIVATE).edit().clear().commit()
        lateinit var guest: SyncCoordinator; lateinit var player: ExoPlayer; lateinit var surface: android.view.SurfaceView
        scenario.onActivity { activity -> surface = android.view.SurfaceView(activity); activity.addContentView(surface, android.view.ViewGroup.LayoutParams(32,32)) }
        main { guest = SyncCoordinator(context, db.dao(), "video-guest"); guest.configure("http://10.0.2.2:8080", "Video guest"); player = ExoPlayer.Builder(context).build(); player.setVideoSurfaceView(surface); guest.attach(player); guest.confirmParked(); guest.join(sync.state.value.code) }
        try {
            await("Mismatched guest must be missing") { guest.state.value.missing }; assertFalse(guest.state.value.playing); assertTrue(guest.state.value.message.contains("exact same file"))
            runBlocking { db.dao().insert(mp4) }
            await("Matching video did not recover") { var frames = 0; main { frames = player.videoDecoderCounters?.renderedOutputBufferCount ?: 0 }; guest.state.value.playing && frames > 2 }
            await("Video drift > 300ms") { guest.state.value.driftMs?.let { kotlin.math.abs(it) < 300 } == true }
            main { sync.command("pause") }; await("Shared video pause") { !sync.state.value.playing && !guest.state.value.playing }
            main { sync.command("seek", 15000); sync.command("play") }; await("Shared video seek/resume") { guest.state.value.playing && guest.state.value.positionMs > 15000 && sync.state.value.positionMs > 15000 }
            main { guest.detach(); player.release(); player = ExoPlayer.Builder(context).build(); player.setVideoSurfaceView(surface); guest.attach(player) }
            await("Video guest reconnect") { guest.state.value.connected && guest.state.value.playing && guest.state.value.positionMs > 15000 }
            main { sync.command("next") }; await("Shared next video") { guest.state.value.track?.hash == mkv.hash && guest.state.value.playing }
        } finally { main { guest.leave(); guest.shutdown(); player.release(); (surface.parent as? android.view.ViewGroup)?.removeView(surface); sync.endSession() }; db.close(); await("End video session") { sync.state.value.code.isBlank() } }
    }

    @Test fun schemaMigrationKeepsOldMusicAndPlaylists() {
        val name = "legacy-migration-test.db"; context.deleteDatabase(name)
        val legacy = android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(name), null)
        legacy.execSQL("CREATE TABLE tracks (hash TEXT NOT NULL PRIMARY KEY, uri TEXT NOT NULL, title TEXT NOT NULL, artist TEXT NOT NULL, album TEXT NOT NULL, durationMs INTEGER NOT NULL, artPath TEXT)")
        legacy.execSQL("CREATE TABLE playlists (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, name TEXT NOT NULL, hashes TEXT NOT NULL)")
        legacy.execSQL("INSERT INTO tracks VALUES ('legacy-hash','file:///old.mp3','Old music','Artist','Album',45000,NULL)")
        legacy.execSQL("INSERT INTO playlists VALUES (1,'Existing playlist','legacy-hash')"); legacy.version = 1; legacy.close()
        val db = Room.databaseBuilder(context, LibraryDb::class.java, name).addMigrations(LibraryDb.MIGRATION_1_2).build()
        try { val tracks = runBlocking { db.dao().tracks() }; assertEquals("Old music", tracks.single().title); assertEquals("audio", tracks.single().mediaType)
            val sqlite = db.openHelper.readableDatabase; sqlite.query("SELECT name,hashes,mediaType FROM playlists").use { assertTrue(it.moveToFirst()); assertEquals("Existing playlist",it.getString(0)); assertEquals("legacy-hash",it.getString(1)); assertEquals("audio",it.getString(2)) }
        } finally { db.close(); context.deleteDatabase(name) }
    }
}
