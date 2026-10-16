package app.carsync.music

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiSelector
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.room.Room
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PlaybackRuntimeTest {
    @get:Rule val permissions: androidx.test.rule.GrantPermissionRule = androidx.test.rule.GrantPermissionRule.grant(*if (android.os.Build.VERSION.SDK_INT >= 33) arrayOf(android.Manifest.permission.POST_NOTIFICATIONS, android.Manifest.permission.READ_MEDIA_AUDIO) else arrayOf(android.Manifest.permission.READ_EXTERNAL_STORAGE))
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val sync get() = (context.applicationContext as CarSyncApp).sync
    private lateinit var scenario: ActivityScenario<MainActivity>
    private lateinit var track: Track
    private fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)
    private fun await(label: String, timeout: Long = 30000, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeout
        while (SystemClock.elapsedRealtime() < deadline) { if (condition()) return; Thread.sleep(100) }
        fail("$label. State: ${sync.state.value}")
    }
    private fun capture(device: UiDevice, name: String) {
        val file = File(context.getExternalFilesDir(null), name)
        assertTrue(device.takeScreenshot(file))
        device.executeShellCommand("mkdir -p /sdcard/Download/CarSync-previews")
        device.executeShellCommand("cp ${file.absolutePath} /sdcard/Download/CarSync-previews/$name")
    }
    @Before fun setup() {
        context.getSharedPreferences("session", Context.MODE_PRIVATE).edit().clear().commit()
        scenario = ActivityScenario.launch(MainActivity::class.java)
        main { sync.leave(); sync.driving(false) }
        await("Audio service never attached") { sync.state.value.serviceReady }
        val source = File(context.cacheDir, "fixture.mp3")
        instrumentation.context.assets.open("tone.mp3").use { input -> source.outputStream().use { input.copyTo(it) } }
        track = importTrack(context, Uri.fromFile(source))
        runBlocking { sync.dao.insert(track) }
        source.delete() // Playback MUST continue from owned copy after original disappears.
        await("Library did not load") { sync.state.value.library.any { it.hash == track.hash } }
    }
    @After fun cleanup() { main { sync.leave() }; scenario.close() }
    @Test fun importWithoutSystemFilePickerShowsHelpInsteadOfCrashing() {
        val device = UiDevice.getInstance(instrumentation)
        val handlers = listOf(android.content.Intent.ACTION_OPEN_DOCUMENT, android.content.Intent.ACTION_GET_CONTENT).flatMap { action ->
            context.packageManager.queryIntentActivities(android.content.Intent(action).addCategory(android.content.Intent.CATEGORY_OPENABLE).setType("audio/*"), 0).map { it.activityInfo.packageName }
        }.filter { it != "android" && it != context.packageName }.toSet()
        assertTrue("Emulator has no picker to disable", handlers.isNotEmpty())
        try {
            handlers.forEach { device.executeShellCommand("pm disable-user --user 0 $it") }
            device.findObject(UiSelector().text("Library")).click()
            val button = device.findObject(UiSelector().text("Import files")); assertTrue(button.waitForExists(5000)); button.click()
            await("Missing picker crashed or did not show help") { sync.state.value.message.contains("No working file picker") }
            assertEquals(androidx.lifecycle.Lifecycle.State.RESUMED, scenario.state)
            main { sync.importFiles(listOf(Uri.parse("content://missing.provider/unreadable.mp3"))) }
            await("Bad provider error was hidden") { !sync.state.value.busy && sync.state.value.message.contains("Import failed:") }
        } finally { handlers.forEach { device.executeShellCommand("pm enable --user 0 $it") } }
    }
    @Test fun pickerLaunchFallsBackAfterProviderFailure() {
        var fallbackOpened = false; var helpShown = false
        launchPickerSafely({ throw android.content.ActivityNotFoundException("No DocumentsUI") }, { fallbackOpened = true }) { helpShown = true }
        assertTrue(fallbackOpened); assertFalse(helpShown)
        launchPickerSafely({ throw SecurityException("OEM blocked picker") }, { throw android.content.ActivityNotFoundException() }) { helpShown = true }
        assertTrue(helpShown)
    }
    @Test fun localMp3ActuallyDecodesAdvancesPausesSeeksAndResumes() {
        assertTrue(File(Uri.parse(track.uri).path!!).exists())
        main { sync.playTrack(track.hash) }
        await("Local MP3 did not play") { sync.state.value.playing && sync.state.value.positionMs > 1000 }
        main { sync.command("pause") }
        await("Pause failed") { !sync.state.value.playing }
        main { sync.command("seek", 10000) }
        await("Seek failed") { sync.state.value.positionMs in 9800..10500 }
        main { sync.command("play") }
        await("Resume failed") { sync.state.value.playing && sync.state.value.positionMs > 11000 }
        val device = UiDevice.getInstance(instrumentation)
        device.findObject(UiSelector().text("Player")).click()
        Thread.sleep(1500)
        capture(device, "player-portrait.png")
        device.setOrientationLeft(); Thread.sleep(1500)
        capture(device, "player-landscape.png"); device.setOrientationNatural()
    }
    @Test fun playbackSurvivesActivityClosingAndServiceReconnects() {
        main { sync.playTrack(track.hash) }
        await("Initial playback failed") { sync.state.value.playing }
        scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
        val before = sync.state.value.positionMs
        await("Background playback did not advance") { sync.state.value.positionMs > before + 1200 }
        scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
        await("Returning activity lost playback") { sync.state.value.serviceReady && sync.state.value.playing }
    }
    @Test fun localNextPreviousEndOfTrackAndSavedPlaylistWork() {
        val source = File(context.cacheDir, "second.mp3")
        instrumentation.context.assets.open("second.mp3").use { input -> source.outputStream().use { input.copyTo(it) } }
        val second = importTrack(context, Uri.fromFile(source)); source.delete()
        runBlocking { sync.dao.insert(second) }
        await("Second song did not load") { sync.state.value.library.any { it.hash == second.hash } }
        main { sync.listenLocal(listOf(track.hash, second.hash)) }
        await("First track failed") { sync.state.value.index == 0 && sync.state.value.playing }
        main { sync.command("next") }; await("Next failed") { sync.state.value.index == 1 && sync.state.value.playing }
        main { sync.command("previous") }; await("Previous failed") { sync.state.value.index == 0 && sync.state.value.playing }
        main { sync.command("seek", 44000) }; await("End-of-track advance failed") { sync.state.value.index == 1 && sync.state.value.playing }
        main { sync.savePlaylist("Test saved playlist", listOf(track.hash, second.hash)) }
        await("Save playlist failed") { sync.state.value.playlists.any { it.name == "Test saved playlist" } }
        val saved = sync.state.value.playlists.first { it.name == "Test saved playlist" }
        main { sync.savePlaylist("Renamed playlist", listOf(second.hash, track.hash), saved.id) }
        await("Rename/reorder failed") { sync.state.value.playlists.any { it.id == saved.id && it.name == "Renamed playlist" && it.hashes == "${second.hash},${track.hash}" } }
    }
    @Test @androidx.test.filters.SdkSuppress(minSdkVersion = 29)
    fun scanPhoneFindsMediaStoreMp3AndPlaysIt() {
        val values = android.content.ContentValues().apply {
            put(android.provider.MediaStore.Audio.Media.DISPLAY_NAME, "CarSync scan test.mp3")
            put(android.provider.MediaStore.Audio.Media.MIME_TYPE, "audio/mpeg")
            put(android.provider.MediaStore.Audio.Media.RELATIVE_PATH, "Music/CarSyncTest/")
            put(android.provider.MediaStore.Audio.Media.IS_PENDING, 1)
        }
        val uri = context.contentResolver.insert(android.provider.MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)!!
        try {
            context.contentResolver.openOutputStream(uri)!!.use { out -> instrumentation.context.assets.open("tone.mp3").use { it.copyTo(out) } }
            context.contentResolver.update(uri, android.content.ContentValues().apply { put(android.provider.MediaStore.Audio.Media.IS_PENDING, 0) }, null, null)
            main { sync.scanPhone() }; await("Phone scanner did not find MP3") { !sync.state.value.busy && sync.state.value.library.any { it.uri == uri.toString() } }
            main { sync.playTrack(track.hash) }; await("Scanned phone MP3 did not play") { sync.state.value.playing && sync.state.value.positionMs > 1000 }
            val device = UiDevice.getInstance(instrumentation); device.findObject(UiSelector().text("Library")).click(); Thread.sleep(1000)
            capture(device, "library.png")
        } finally { main { sync.leave() }; context.contentResolver.delete(uri, null, null); runBlocking { sync.dao.insert(track) } }
    }
    @Test fun hostAndGuestPlaySameFileAndFollowPauseSeekAndReconnect() {
        main { sync.configure("http://10.0.2.2:8080", "Test host"); sync.create() }
        await("Host connection failed") { sync.state.value.connected && sync.state.value.clockReady }
        main { sync.playTrack(track.hash) }
        await("Host MP3 did not advance") { sync.state.value.playing && sync.state.value.positionMs > 2000 }
        val db = Room.inMemoryDatabaseBuilder(context, LibraryDb::class.java).build()
        runBlocking { db.dao().insert(track) }
        context.getSharedPreferences("test-guest", Context.MODE_PRIVATE).edit().clear().commit()
        lateinit var guest: SyncCoordinator; lateinit var p: ExoPlayer
        main { guest = SyncCoordinator(context, db.dao(), "test-guest"); guest.configure("http://10.0.2.2:8080", "Guest"); p = ExoPlayer.Builder(context).build(); guest.attach(p); guest.join(sync.state.value.code) }
        try {
            await("Guest MP3 did not advance") { guest.state.value.playing && guest.state.value.positionMs > 2000 }
            await("Guest player drift stayed above 300 ms") { guest.state.value.driftMs?.let { kotlin.math.abs(it) < 300 } == true }
            main { sync.command("pause") }
            await("Shared pause failed") { !sync.state.value.playing && !guest.state.value.playing }
            main { sync.command("seek", 15000); sync.command("play") }
            await("Shared seek/play failed") { sync.state.value.positionMs > 15000 && guest.state.value.positionMs > 15000 && guest.state.value.playing }
            main { guest.detach(); p.release(); p = ExoPlayer.Builder(context).build(); guest.attach(p) }
            await("Guest reconnect playback failed") { guest.state.value.connected && guest.state.value.playing && guest.state.value.positionMs > 15000 }
        } finally { main { guest.leave(); guest.shutdown(); p.release() }; db.close(); main { sync.endSession() }; await("Host session cleanup") { sync.state.value.code.isBlank() } }
    }
    @Test fun guestWithoutLocalMusicStartsAutomaticallyAfterHostPermitsSharing() {
        main { sync.configure("http://10.0.2.2:8080", "Relay host"); sync.create() }
        await("Host connection failed") { sync.state.value.connected && sync.state.value.clockReady }
        main { sync.playTrack(track.hash) }; await("Host MP3 failed") { sync.state.value.playing }
        val db = Room.inMemoryDatabaseBuilder(context, LibraryDb::class.java).build()
        context.getSharedPreferences("relay-guest", Context.MODE_PRIVATE).edit().clear().commit()
        lateinit var guest: SyncCoordinator; lateinit var p: ExoPlayer
        main {
            guest = SyncCoordinator(context, db.dao(), "relay-guest")
            guest.configure("http://10.0.2.2:8080", "Relay guest")
            val http = guest.http.newBuilder().addInterceptor(Interceptor { chain -> chain.proceed(chain.request().newBuilder().header("Authorization", "Bearer ${guest.audioToken()}").build()) }).build()
            p = ExoPlayer.Builder(context).setMediaSourceFactory(DefaultMediaSourceFactory(DefaultDataSource.Factory(context, OkHttpDataSource.Factory(http)))).build()
            guest.attach(p); guest.join(sync.state.value.code)
        }
        try {
            await("Missing-file warning absent") { guest.state.value.missing }; assertFalse(guest.state.value.playing)
            val device = UiDevice.getInstance(instrumentation)
            device.findObject(UiSelector().text("Player")).click()
            device.findObject(UiSelector().text("Share music with guest")).click()
            await("Sharing not enabled") { sync.state.value.sharing }
            await("Automatic upload did not complete") { sync.state.value.message.contains("Audio relay ready") }
            await("Guest did not automatically decode/play relay MP3") { guest.state.value.playing && guest.state.value.positionMs > 1000 }
            main { sync.command("pause") }; await("Relay guest pause failed") { !guest.state.value.playing }
            main { sync.command("play") }; await("Relay guest resume failed") { guest.state.value.playing }
            main { sync.settings(sharing = false) }; await("Revocation did not stop guest") { guest.state.value.missing && !guest.state.value.playing }
        } finally { main { guest.leave(); guest.shutdown(); p.release() }; db.close(); main { sync.endSession() }; await("Host session cleanup") { sync.state.value.code.isBlank() } }
    }
}

