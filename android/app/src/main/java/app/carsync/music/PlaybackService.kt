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
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaLibraryService.LibraryParams
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import okhttp3.Interceptor
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

@androidx.annotation.OptIn(UnstableApi::class)
class PlaybackService : MediaLibraryService() {
    private var session: MediaLibrarySession? = null
    private val carControllers = mutableSetOf<MediaSession.ControllerInfo>()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
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
            private fun select(items: List<MediaItem>, index: Int = 0) {
                val hash = items.getOrNull(index)?.mediaId?.let(CarAudioCatalog::hash)
                if (hash != null) sync.selectCarAudio(hash)
            }
            override fun setMediaItem(mediaItem: MediaItem) { select(listOf(mediaItem)) }
            override fun setMediaItem(mediaItem: MediaItem, resetPosition: Boolean) { select(listOf(mediaItem)) }
            override fun setMediaItem(mediaItem: MediaItem, startPositionMs: Long) { select(listOf(mediaItem)); sync.command("seek", startPositionMs) }
            override fun setMediaItems(mediaItems: List<MediaItem>) { select(mediaItems) }
            override fun setMediaItems(mediaItems: List<MediaItem>, resetPosition: Boolean) { select(mediaItems) }
            override fun setMediaItems(mediaItems: List<MediaItem>, startIndex: Int, startPositionMs: Long) { select(mediaItems, startIndex); if (startPositionMs != C.TIME_UNSET) sync.command("seek", startPositionMs) }
            override fun getAvailableCommands(): Player.Commands {
                val commands = super.getAvailableCommands().buildUpon()
                if (sync.state.value.canControl && sync.state.value.queue.size > 1) commands.add(Player.COMMAND_SEEK_TO_NEXT).add(Player.COMMAND_SEEK_TO_PREVIOUS)
                return commands.build()
            }
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
        session = MediaLibrarySession.Builder(this, routed, object : MediaLibrarySession.Callback {
            override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
                if (controller.packageName != packageName && !controller.isTrusted) return MediaSession.ConnectionResult.reject()
                if (controller.isTrusted && (session.isAutomotiveController(controller) || session.isAutoCompanionController(controller))) { carControllers.add(controller); sync.carHostConnected(true) }
                val commands = Player.Commands.Builder().add(Player.COMMAND_PLAY_PAUSE).add(Player.COMMAND_PREPARE).add(Player.COMMAND_STOP).add(Player.COMMAND_SET_MEDIA_ITEM).add(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM).add(Player.COMMAND_SEEK_TO_NEXT).add(Player.COMMAND_SEEK_TO_PREVIOUS).add(Player.COMMAND_GET_CURRENT_MEDIA_ITEM).add(Player.COMMAND_GET_TIMELINE).add(Player.COMMAND_GET_METADATA).add(Player.COMMAND_SET_VOLUME).add(Player.COMMAND_GET_VOLUME).build()
                return MediaSession.ConnectionResult.AcceptedResultBuilder(session).setAvailableSessionCommands(MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS).setAvailablePlayerCommands(commands).build()
            }
            override fun onGetLibraryRoot(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, params: LibraryParams?): ListenableFuture<LibraryResult<MediaItem>> =
                Futures.immediateFuture(LibraryResult.ofItem(rootItem(), params))
            override fun onDisconnected(session: MediaSession, controller: MediaSession.ControllerInfo) {
                if (carControllers.remove(controller)) sync.carHostConnected(carControllers.isNotEmpty())
            }
            override fun onGetChildren(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, parentId: String, page: Int, pageSize: Int, params: LibraryParams?): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> =
                Futures.immediateFuture(if (parentId != CarAudioCatalog.ROOT || page < 0 || pageSize < 1) LibraryResult.ofError(SessionError.ERROR_BAD_VALUE) else LibraryResult.ofItemList(CarAudioCatalog.page(catalog(), page, pageSize), params))
            override fun onGetItem(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, mediaId: String): ListenableFuture<LibraryResult<MediaItem>> =
                Futures.immediateFuture((if (mediaId == CarAudioCatalog.ROOT) rootItem() else catalog().find { it.mediaId == mediaId || CarAudioCatalog.hash(it.mediaId) == mediaId }?.buildUpon()?.setMediaId(mediaId)?.build())?.let { LibraryResult.ofItem(it, null) } ?: LibraryResult.ofError(SessionError.ERROR_BAD_VALUE))
            override fun onSearch(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, query: String, params: LibraryParams?): ListenableFuture<LibraryResult<Void>> {
                session.notifySearchResultChanged(browser, query, search(query).size, params)
                return Futures.immediateFuture(LibraryResult.ofVoid(params))
            }
            override fun onGetSearchResult(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, query: String, page: Int, pageSize: Int, params: LibraryParams?): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> =
                Futures.immediateFuture(LibraryResult.ofItemList(CarAudioCatalog.page(search(query), page, pageSize), params))
            override fun onAddMediaItems(mediaSession: MediaSession, controller: MediaSession.ControllerInfo, mediaItems: List<MediaItem>): ListenableFuture<List<MediaItem>> {
                val available = catalog()
                val resolved = mediaItems.mapNotNull { requested ->
                    available.find { it.mediaId == requested.mediaId || CarAudioCatalog.hash(it.mediaId) == requested.mediaId } ?: requested.requestMetadata.searchQuery?.let { query -> search(query).firstOrNull() }
                }
                if (resolved.size != mediaItems.size || resolved.isEmpty() || (sync.state.value.code.isNotBlank() && !sync.state.value.canControl)) return Futures.immediateFailedFuture(SecurityException("Choose available audio; the host controls convoy playback."))
                return Futures.immediateFuture(resolved)
            }
            override fun onPlaybackResumption(mediaSession: MediaSession, controller: MediaSession.ControllerInfo): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
                val item = catalog().find { CarAudioCatalog.hash(it.mediaId) == sync.state.value.track?.hash } ?: catalog().firstOrNull()
                return if (item == null) Futures.immediateFailedFuture(IllegalStateException("Import audio on the phone first."))
                else Futures.immediateFuture(MediaSession.MediaItemsWithStartPosition(listOf(item), 0, if (CarAudioCatalog.hash(item.mediaId) == sync.state.value.track?.hash) sync.state.value.positionMs else 0L))
            }
        }).setSessionActivity(activity).build()
        sync.attach(raw)
        serviceScope.launch { sync.state.map { Triple(it.library, it.queue, it.code) }.distinctUntilChanged().collect { session?.notifyChildrenChanged(CarAudioCatalog.ROOT, catalog().size, null) } }
    }
    private fun rootItem() = MediaItem.Builder().setMediaId(CarAudioCatalog.ROOT).setMediaMetadata(MediaMetadata.Builder().setTitle("CarSync Music · audio").setIsBrowsable(true).setIsPlayable(false).build()).build()
    private fun catalog(): List<MediaItem> {
        val state = sync.state.value
        val audio = if (state.code.isNotBlank()) state.queue.filter { it.mediaType == "audio" }.map { Triple(it.hash, it.title, it.artist) }
            else state.library.filter { it.mediaType == "audio" }.map { Triple(it.hash, it.title, it.artist) }
        return audio.distinctBy { it.first }.map { (hash, title, artist) -> MediaItem.Builder().setMediaId(CarAudioCatalog.mediaId(hash)).setUri("carsync://audio/$hash")
            .setMediaMetadata(MediaMetadata.Builder().setTitle(title).setArtist(artist).setIsBrowsable(false).setIsPlayable(true).build()).build() }
    }
    private fun search(query: String) = catalog().filter { it.mediaMetadata.title.toString().contains(query, true) || it.mediaMetadata.artist.toString().contains(query, true) }
    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = session
    override fun onTaskRemoved(rootIntent: Intent?) { if (!raw.playWhenReady) { sync.detach(); stopSelf() } }
    override fun onDestroy() { serviceScope.cancel(); carControllers.clear(); sync.carHostConnected(false); sync.detach(); session?.release(); raw.release(); super.onDestroy() }
}
