package app.carsync.music

import android.content.ComponentName
import android.net.Uri
import android.os.Build
import androidx.media3.common.MediaItem
import androidx.media3.session.*
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class CarBrowserRuntimeTest {
    @get:Rule val permissions: androidx.test.rule.GrantPermissionRule = androidx.test.rule.GrantPermissionRule.grant(*if (Build.VERSION.SDK_INT >= 33) arrayOf(android.Manifest.permission.POST_NOTIFICATIONS) else arrayOf(android.Manifest.permission.READ_EXTERNAL_STORAGE))
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val sync get() = (context.applicationContext as CarSyncApp).sync
    private fun <T> main(block: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(block) }
        return result!!.getOrThrow()
    }
    private fun await(condition: () -> Boolean) {
        repeat(200) { if (condition()) return; Thread.sleep(100) }
        fail("State did not settle: ${sync.state.value}")
    }
    @Test fun audioBrowserSelectsMusicRejectsPathsAndOmitsVideo() {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        var browser: MediaBrowser? = null
        var audio: Track? = null
        val video = Track("b".repeat(64), "file:///unused.mp4", "Video must stay off car browser", "Test", "", 1000, null, "video", "video/mp4")
        try {
            main { sync.leave(); sync.carHostConnected(false); sync.driving(false) }
            await { sync.state.value.serviceReady }
            val fixture = File(context.cacheDir, "car-browser.mp3")
            instrumentation.context.assets.open("tone.mp3").use { input -> fixture.outputStream().use { input.copyTo(it) } }
            val track = importTrack(context, Uri.fromFile(fixture)); audio = track
            runBlocking { sync.dao.insert(track); sync.dao.insert(video) }
            await { sync.state.value.library.any { it.hash == track.hash } }
            val future = main { MediaBrowser.Builder(context, SessionToken(context, ComponentName(context, PlaybackService::class.java))).buildAsync() }
            val connected = future.get(15, TimeUnit.SECONDS); browser = connected
            val root = main { connected.getLibraryRoot(null) }.get(10, TimeUnit.SECONDS)
            assertEquals(LibraryResult.RESULT_SUCCESS, root.resultCode)
            val children = main { connected.getChildren(CarAudioCatalog.ROOT, 0, 100, null) }.get(10, TimeUnit.SECONDS).value!!
            assertTrue(children.any { it.mediaId == CarAudioCatalog.mediaId(track.hash) })
            assertFalse(children.any { it.mediaId.contains(video.hash) })
            val rejected = main { connected.getItem("file:///private/song.mp3") }.get(10, TimeUnit.SECONDS)
            assertEquals(LibraryResult.RESULT_ERROR_BAD_VALUE, rejected.resultCode)
            main { connected.setMediaItem(MediaItem.Builder().setMediaId(CarAudioCatalog.mediaId(track.hash)).build()); connected.prepare(); connected.play() }
            await { sync.state.value.playing && sync.state.value.positionMs > 500 }
            assertEquals(track.hash, sync.state.value.track?.hash)
            main { connected.pause() }; await { !sync.state.value.playing }
            main { context.startActivity(android.content.Intent("android.media.action.MEDIA_PLAY_FROM_SEARCH").setPackage(context.packageName)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(android.app.SearchManager.QUERY, track.title)) }
            await { sync.state.value.playing && sync.state.value.track?.hash == track.hash }
            main { sync.carHostConnected(true); sync.driving(false) }
            assertTrue(sync.state.value.driving)
            assertFalse(sync.state.value.showVideo)
        } finally {
            main { browser?.release(); sync.carHostConnected(false); sync.leave(); sync.driving(false) }
            runBlocking { audio?.let { sync.dao.delete(it) }; sync.dao.delete(video) }
            scenario.close()
        }
    }
}
