package app.carsync.music

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.common.*
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.*
import okhttp3.Interceptor
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

@androidx.annotation.OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null
    private lateinit var raw: ExoPlayer
    private val sync get() = (application as CarSyncApp).sync
    override fun onCreate() {
        super.onCreate()
        // Credentials are attached only to the configured relay origin, never a redirected host.
        val client = sync.http.newBuilder().followRedirects(false).followSslRedirects(false).addInterceptor(Interceptor { chain ->
            val req = chain.request()
            val base = sync.state.value.endpoint.toHttpUrlOrNull()
            val safe = base != null && req.url.host == base.host && req.url.port == base.port && req.url.scheme == base.scheme
            chain.proceed(if (safe) req.newBuilder().header("Authorization", "Bearer ${sync.audioToken()}").build() else req)
        }).build()
        val source = DefaultDataSource.Factory(this, OkHttpDataSource.Factory(client))
        raw = ExoPlayer.Builder(this).setMediaSourceFactory(DefaultMediaSourceFactory(source)).build()
        raw.setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
        raw.setHandleAudioBecomingNoisy(true); raw.setWakeMode(C.WAKE_MODE_LOCAL)
        val routed = object : ForwardingPlayer(raw) {
            override fun play() { sync.command("play") }
            override fun pause() { sync.command("pause") }
            override fun setPlayWhenReady(playWhenReady: Boolean) { sync.command(if (playWhenReady) "play" else "pause") }
            override fun seekTo(positionMs: Long) { sync.command("seek", positionMs) }
            override fun seekTo(mediaItemIndex: Int, positionMs: Long) { sync.command("seek", positionMs) }
            override fun seekToNext() { sync.command("next") }
            override fun seekToNextMediaItem() { sync.command("next") }
            override fun seekToPrevious() { sync.command("previous") }
            override fun seekToPreviousMediaItem() { sync.command("previous") }
            override fun seekBack() { sync.command("seek", (raw.currentPosition - 10000).coerceAtLeast(0)) }
            override fun seekForward() { sync.command("seek", raw.currentPosition + 10000) }
            override fun stop() { sync.command("pause") }
        }
        val activity = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        session = MediaSession.Builder(this, routed).setSessionActivity(activity).setCallback(object : MediaSession.Callback {
            override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
                if (controller.packageName != packageName && controller.packageName != "com.android.systemui" && controller.packageName != "com.android.bluetooth") return MediaSession.ConnectionResult.reject()
                val commands = Player.Commands.Builder().add(Player.COMMAND_PLAY_PAUSE).add(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM).add(Player.COMMAND_GET_CURRENT_MEDIA_ITEM).add(Player.COMMAND_GET_TIMELINE).add(Player.COMMAND_GET_METADATA).add(Player.COMMAND_SET_VOLUME).add(Player.COMMAND_GET_VOLUME).build()
                return MediaSession.ConnectionResult.AcceptedResultBuilder(session).setAvailablePlayerCommands(commands).build()
            }
        }).build()
        sync.attach(raw)
    }
    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session
    override fun onTaskRemoved(rootIntent: Intent?) { if (!raw.playWhenReady) { sync.detach(); stopSelf() } }
    override fun onDestroy() { sync.detach(); session?.release(); raw.release(); super.onDestroy() }
}
