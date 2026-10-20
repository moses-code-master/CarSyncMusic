package app.carsync.music

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import androidx.media3.common.*
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.*
import java.io.IOException
import java.util.UUID
import kotlin.math.abs

data class RemoteTrack(val hash: String, val title: String, val artist: String, val album: String, val durationMs: Long, val mediaType: String = "audio", val mimeType: String = "audio/mpeg", val sizeBytes: Long = 0, val width: Int = 0, val height: Int = 0)
data class Car(
    val name: String, val role: String, val online: Boolean, val driftMs: Long?,
    val rttMs: Long?, val buffered: Boolean, val missing: Boolean,
    val memberId: String = "", val location: ConvoyPosition? = null
)
data class AppState(
    val endpoint: String = ServerDefaults.CONVOY_URL, val carName: String = "My car", val code: String = "", val role: String = "", val connected: Boolean = false,
    val library: List<Track> = emptyList(), val playlists: List<SavedPlaylist> = emptyList(), val queue: List<RemoteTrack> = emptyList(), val cars: List<Car> = emptyList(), val maxGuests: Int = 10, val speakerId: String? = null, val memberId: String = "", val votes: Map<String, Int> = emptyMap(), val radarEnabled: Boolean = false, val discoveredServer: String = "", val localSearchActive: Boolean = false, val localSearchStatus: String = "", val reconnectAttempt: Int = 0, val talkPending: Boolean = false,
    val index: Int = 0, val positionMs: Long = 0, val playing: Boolean = false, val buffering: Boolean = false, val missing: Boolean = false,
    val clockReady: Boolean = false, val driftMs: Long? = null, val rttMs: Long = 0, val volume: Float = 0.8f,
    val guestControls: Boolean = false, val sharing: Boolean = false, val guestAdditions: Boolean = false, val serverFeatures: Set<String> = emptySet(), val pendingGuestSong: String = "", val pendingGuestSongs: List<String> = emptyList(), val audioAvailable: Set<String> = emptySet(), val audioManifestKnown: Boolean = false, val repeat: String = "off", val driving: Boolean = false,
    val latencyMs: Long = 0, val message: String = "Import MP3s and create or join a session.", val busy: Boolean = false,
    val invitationCode: String = "", val invitationServer: String = "", val serviceReady: Boolean = false, val requestedPlaying: Boolean = false, val audioError: String = "",
    val parkedConfirmed: Boolean = false, val audioOnlyVideo: Boolean = false, val automotive: Boolean = false, val vehicleKnown: Boolean = false, val vehicleRestricted: Boolean = false, val mediaProtocol: Int = 1, val radioStatus: String = "Waiting for a radio connection", val acousticProbeMs: Long? = null, val acousticVariationMs: Long? = null, val acousticProfileReliable: Boolean = false
) {
    val canControl get() = role == "host" || role == "local" || guestControls
    val supportsVoice get() = "convoy_ptt_pcm16_v1" in serverFeatures
    val supportsCompactRadio get() = "convoy_ptt_mulaw_v1" in serverFeatures
    val supportsRadar get() = "convoy_location_v1" in serverFeatures
    val supportsGuestMusic get() = "guest_music_upload_v1" in serverFeatures
    val track get() = queue.getOrNull(index)
    val showVideo get() = VideoSafetyPolicy.canShowPicture(parkedConfirmed, driving, audioOnlyVideo, automotive, vehicleKnown, vehicleRestricted)
}

class SyncCoordinator(private val context: Context, val dao: LibraryDao, preferencesName: String = "session") {
    private val prefs = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
    private val initialEndpoint = ServerDefaults.resolve(
        prefs.getString("endpoint", null),
        prefs.getBoolean("manualServer", false),
        !prefs.getString("code", "").isNullOrBlank()
    )
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val http = OkHttpClient.Builder().connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS).readTimeout(30, java.util.concurrent.TimeUnit.SECONDS).pingInterval(10, java.util.concurrent.TimeUnit.SECONDS).build()
    private val mutable = MutableStateFlow(AppState(endpoint = initialEndpoint, carName = prefs.getString("name", "My car")!!, code = prefs.getString("code", "")!!, role = prefs.getString("role", "")!!, memberId = prefs.getString("memberId", "")!!, latencyMs = prefs.getLong("latency", 0), driving = prefs.getBoolean("driving", false),
        acousticProbeMs = prefs.getLong("acoustic_profile_ms", -1).takeIf { it >= 0 },
        acousticVariationMs = prefs.getLong("acoustic_profile_variation", -1).takeIf { it >= 0 },
        acousticProfileReliable = prefs.getBoolean("acoustic_profile_reliable", false)))
    val state: StateFlow<AppState> = mutable.asStateFlow()
    private var bearer = prefs.getString("token", "")!!
    private var ws: WebSocket? = null
    private val convoyAudio = ConvoyAudio(context, { player },
        { codec, data ->
            val type = if (codec == "mulaw16") "ptt_mulaw" else "ptt_pcm"
            ws?.send(JSONObject().put("type", type).put("data", data).toString())
        },
        { reason -> scope.launch { message(reason); pttRelease() } },
        { detail -> scope.launch { update { it.copy(radioStatus = detail) } } }
    )
    private var recentMovingAtMs = -100000L
    private val radar = ConvoyRadar(context) { loc ->
        if (loc != null && loc.hasSpeed() && loc.speed > 5f / 3.6f && loc.accuracy < 30f) {
            recentMovingAtMs = SystemClock.elapsedRealtime()
            if (!state.value.driving) driving(true)
        }
        val packet = JSONObject().put("type", "location")
        if (loc == null) packet.put("enabled", false)
        else packet.put("lat", loc.latitude).put("lon", loc.longitude)
            .put("speed", loc.speed.toDouble()).put("bearing", loc.bearing.toDouble())
            .put("accuracy", loc.accuracy.toDouble())
        ws?.send(packet.toString())
    }
    private val discovery = LanDiscovery(context,
        { endpoint -> scope.launch {
            update { it.copy(discoveredServer = endpoint, localSearchStatus = "Found a local Wi-Fi relay at $endpoint", localSearchActive = false) }
            stopDiscovery()
        } },
        { status -> scope.launch { update { it.copy(localSearchStatus = status) } } }
    )
    private var discoveryTimeout: Job? = null
    fun beginDiscovery() {
        if (state.value.localSearchActive) return
        update { it.copy(discoveredServer = "", localSearchActive = true, localSearchStatus = "Searching Wi-Fi for a CarSync relay for 10 seconds…") }
        discovery.start()
        discoveryTimeout?.cancel()
        discoveryTimeout = scope.launch {
            delay(10000)
            if (state.value.localSearchActive) {
                discovery.stop()
                update { it.copy(localSearchActive = false,
                    localSearchStatus = "No local relay found. This is normal for cloud convoys: use the automatic internet server. A laptop relay must run on the same Wi-Fi with mDNS enabled.") }
            }
        }
    }
    fun stopDiscovery() {
        discoveryTimeout?.cancel(); discoveryTimeout = null
        discovery.stop(); update { it.copy(localSearchActive = false) }
    }
    fun toggleRadar(enabled: Boolean) {
        if (enabled && !state.value.supportsRadar) { message("Convoy radar needs the updated session server."); return }
        if (enabled && !state.value.connected) { message("Join a session before sharing your location."); return }
        if (!enabled) {
            radar.stop()
            ConvoyForeground.stopLocation(context)
            update { it.copy(radarEnabled = false) }
            return
        }
        val started = ConvoyForeground.startLocation(context) && radar.start()
        if (!started) {
            ConvoyForeground.stopLocation(context)
            message("Location cannot start. Allow location access while CarSync is open.")
        }
        update { it.copy(radarEnabled = started) }
    }
    private var talkGrantTimeout: Job? = null
    private fun clearTalkRequest() {
        talkGrantTimeout?.cancel(); talkGrantTimeout = null
        update { it.copy(talkPending = false) }
    }
    fun pttRequest() {
        if (state.value.talkPending) return
        if (!state.value.connected) { message("Join a session first to talk with another car."); return }
        if (state.value.speakerId != null) { message("Intercom is busy. Release the current speaker first."); return }
        if (!state.value.supportsVoice) { message("Intercom needs the newer convoy server. Update your Render deployment or use the matching local backend."); return }
        if (!ConvoyForeground.startMicrophone(context)) {
            message("Microphone service could not start. Open CarSync and try again.")
            return
        }
        if (ws?.send(JSONObject().put("type", "ptt_request").toString()) != true) {
            ConvoyForeground.stopMicrophone(context)
            message("Intercom connection unavailable. Reconnect and retry.")
            return
        }
        update { it.copy(talkPending = true) }
        message("Requesting talk channel…")
        talkGrantTimeout?.cancel()
        talkGrantTimeout = scope.launch {
            delay(6500)
            if (state.value.talkPending) {
                clearTalkRequest()
                ConvoyForeground.stopMicrophone(context)
                ws?.send(JSONObject().put("type", "ptt_release").toString())
                message("Talk request timed out. Check mobile data and retry.")
            }
        }
    }
    fun pttRelease() {
        clearTalkRequest()
        convoyAudio.stopCapture()
        ConvoyForeground.stopMicrophone(context)
        if (state.value.speakerId == state.value.memberId) {
            convoyAudio.duck(false)
            update { it.copy(speakerId = null) }
        }
        ws?.send(JSONObject().put("type", "ptt_release").toString())
        message("Talk channel released.")
    }
    fun voteTrack(hash: String) {
        if ("convoy_votes_v1" in state.value.serverFeatures && state.value.connected)
            send(JSONObject().put("type", "vote_track").put("hash", hash))
        else message("This server does not support guest song voting. Update the server.")
    }
    fun addGuestSong(hash: String) = addGuestSongs(listOf(hash))
    fun addGuestSongs(hashes: List<String>) {
        val state = state.value
        if (!state.connected || state.driving) { message("Connect and park before contributing media."); return }
        if (!state.supportsGuestMusic) { message("The server needs the latest guest media update."); return }
        if (!state.sharing || !state.guestAdditions) { message("Ask host to enable media sharing and guest additions."); return }
        if (state.pendingGuestSong.isNotBlank() || state.busy) { message("A batch is already uploading."); return }
        val accepted = hashes.distinct().filter { hash ->
            val t = state.library.find { it.hash == hash }
            t != null && ((t.mediaType == "audio" && t.mimeType == "audio/mpeg") ||
                    (t.mediaType == "video" && t.mimeType == "video/mp4" &&
                     "guest_video_mp4_v1" in state.serverFeatures)) &&
                    t.durationMs > 0 && t.sizeBytes in 1L..104857600L &&
                    state.queue.none { it.hash == hash }
        }.take(500)
        if (accepted.isEmpty()) {
            message("No new supported MP3 or MP4 files selected. Each must be at most 100 MB.")
            return
        }
        update { it.copy(pendingGuestSongs = accepted) }
        requestNextGuestUpload()
    }
    private fun requestNextGuestUpload() {
        if (!state.value.connected || !state.value.sharing || !state.value.guestAdditions) {
            update { it.copy(pendingGuestSong = "", pendingGuestSongs = emptyList(), busy = false) }
            return
        }
        val next = state.value.pendingGuestSongs.firstOrNull()
        if (next == null) {
            update { it.copy(pendingGuestSong = "", busy = false) }
            message("Selected media batch finished uploading.")
            return
        }
        val t = state.value.library.find { it.hash == next }
        if (t == null) {
            update { it.copy(pendingGuestSongs = it.pendingGuestSongs.drop(1)) }
            requestNextGuestUpload()
            return
        }
        update { it.copy(pendingGuestSong = next, pendingGuestSongs = it.pendingGuestSongs.drop(1)) }
        val data = JSONObject().put("hash", t.hash).put("title", t.title)
            .put("artist", t.artist).put("album", t.album)
            .put("durationMs", t.durationMs).put("mediaType", t.mediaType)
            .put("mimeType", t.mimeType).put("sizeBytes", t.sizeBytes)
            .put("width", t.width).put("height", t.height)
        send(JSONObject().put("type", "guest_add").put("track", data))
        message("Requesting upload: ${t.title}")
    }
    private fun uploadGuestSong(hash: String) {
        val state = state.value
        val track = state.library.find { it.hash == hash } ?: run { message("Media missing from device."); return }
        scope.launch {
            update { it.copy(busy = true) }
            try {
                withContext(Dispatchers.IO) {
                    val uri = Uri.parse(track.uri)
                    val size = context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
                    require(size in 1L..104857600L) { "File size unavailable or exceeds 100 MB" }
                    val body = object : RequestBody() {
                        override fun contentType() = track.mimeType.toMediaType()
                        override fun contentLength(): Long = size
                        override fun writeTo(sink: okio.BufferedSink) {
                            context.contentResolver.openInputStream(uri)?.use { input ->
                                val buffer = ByteArray(65536)
                                while (true) {
                                    val n = input.read(buffer)
                                    if (n < 0) break
                                    sink.write(buffer, 0, n)
                                }
                            } ?: throw IOException("Media file is no longer readable")
                        }
                    }
                    val route = if (track.mediaType == "video") "video" else "audio"
                    val request = Request.Builder().url("${state.endpoint}/sessions/${state.code}/$route/$hash")
                        .header("Authorization", "Bearer $bearer").put(body).build()
                    http.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) error(JSONObject(response.body?.string() ?: "{}").optString("error", "Upload rejected by server"))
                    }
                }
                message("Added ${track.title} to shared queue.")
            } catch (e: Exception) { message("Media upload failed: ${e.message}") }
            finally {
                update { it.copy(busy = false, pendingGuestSong = "") }
                requestNextGuestUpload()
            }
        }
    }
    /** Automatically performs three acoustic loopback measurements while parked.
     * Results are diagnostics only; recording latency is not an output-only offset.
     */
    fun measureAcoustics() {
        if (state.value.busy || state.value.driving || state.value.playing || state.value.requestedPlaying ||
            state.value.speakerId != null || state.value.talkPending ||
            (state.value.automotive && (!state.value.vehicleKnown || state.value.vehicleRestricted || !state.value.parkedConfirmed))) {
            message("Park, stop intercom and pause music before acoustic calibration."); return
        }
        scope.launch {
            update { it.copy(busy = true) }
            val oldProfile = prefs.getLong("acoustic_profile_ms", -1L).takeIf { it >= 0 }
            try {
                val probe = AcousticProbe(context)
                val measurements = mutableListOf<Long?>()
                repeat(3) { index ->
                    if (state.value.driving || state.value.speakerId != null) throw IllegalStateException("Stopped: vehicle or intercom became active")
                    message("Automatic acoustic check: tone ${index + 1} of 3. Hold the phone still and keep the cabin quiet.")
                    measurements += probe.measure()
                    if (index < 2) delay(300)
                }
                val profile = AutomaticAcousticCalibration.summarize(measurements)
                if (profile == null) {
                    message("Acoustic calibration failed. Check microphone access, speaker volume and cabin noise.")
                } else {
                    prefs.edit().putLong("acoustic_profile_ms", profile.estimatedRoundTripMs)
                        .putLong("acoustic_profile_variation", profile.variationMs)
                        .putBoolean("acoustic_profile_reliable", profile.reliable).apply()
                    update { it.copy(acousticProbeMs = profile.estimatedRoundTripMs,
                        acousticVariationMs = profile.variationMs, acousticProfileReliable = profile.reliable) }
                    val routeChange = AutomaticAcousticCalibration.routeChanged(oldProfile, profile)
                    val quality = if (profile.reliable) "stable" else "variable"
                    message("Automatic acoustic profile: ${profile.estimatedRoundTripMs}ms speaker-to-mic, ${profile.variationMs}ms variation ($quality)." +
                        if (routeChange) " Audio route changed; check manual synchronization settings." else
                            " This includes microphone delay and cannot automatically align different cars.")
                }
            } catch (e: Exception) { message("Calibration stopped: ${e.message.orEmpty()}") }
            finally { update { it.copy(busy = false) } }
        }
    }
    private var generation = 0
    private var attempts = 0
    private var reconnect: Job? = null
    private var loops: Job? = null
    private var scheduleJob: Job? = null
    private var player: ExoPlayer? = null
    fun renderingPlayer(): ExoPlayer? = player
    private val vehicleSafety = VehicleSafetyMonitor(context) { known, restricted ->
        update { it.copy(vehicleKnown = known, vehicleRestricted = restricted, parkedConfirmed = if (restricted) false else it.parkedConfirmed) }
        refreshVideoOutput()
        if (restricted && state.value.track?.mediaType == "video") { player?.pause(); message("Vehicle restrictions block video playback. Music remains available.") }
    }
    private val clock = ClockEstimator()
    private var desired: PlaybackFrame? = null
    private var previous: PlaybackFrame? = null
    private var activeRevision = -1L
    private var preparedHash = ""
    private var endedRevision = -1L
    private var playPublishedHash: String? = null
    private var lastStatus = 0L
    private var lastHardSeek = 0L
    private val pings = mutableSetOf<Double>()
    private fun localNow() = SystemClock.elapsedRealtime().toDouble()
    private fun serverNow() = localNow() + clock.offsetMs
    private fun update(f: (AppState) -> AppState) { mutable.value = f(mutable.value) }
    fun message(text: String) { update { it.copy(message = text) } }
    fun invitation(uri: Uri) {
        if (uri.scheme == "carsync" && uri.host == "join") update { it.copy(invitationCode = uri.getQueryParameter("code") ?: "", invitationServer = uri.getQueryParameter("server") ?: "", message = "Review the invitation server address before joining.") }
    }
    init {
        // Existing v1.6 phones migrate off the old default unless they are still in
        // a session or have explicitly chosen a custom server.
        if (prefs.getString("code", "").isNullOrBlank() &&
            prefs.getString("endpoint", null) != initialEndpoint) {
            prefs.edit().putString("endpoint", initialEndpoint).apply()
        }
        update { it.copy(automotive = vehicleSafety.automotive, vehicleRestricted = vehicleSafety.automotive) }; vehicleSafety.start()
        scope.launch { dao.observeTracks().collect { list -> update { it.copy(library = list) }; if (state.value.missing) desired?.let { schedule(it, true) } } }
        scope.launch { dao.observePlaylists().collect { list -> update { it.copy(playlists = list) } } }
    }
    fun attach(p: ExoPlayer) {
        player = p; preparedHash = ""; activeRevision = -1; p.volume = 1f; refreshVideoOutput()
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
        update { it.copy(serviceReady = true, volume = audio.getStreamVolume(android.media.AudioManager.STREAM_MUSIC).toFloat() / audio.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC).coerceAtLeast(1)) }
        p.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                preparedHash = ""
                val detail = "Cannot play this ${if (state.value.track?.mediaType == "video") "video/codec" else "file"}: ${error.errorCodeName}. ${error.cause?.message.orEmpty()}. ${if (state.value.track?.mediaType == "video") "Try MP4 H.264/AAC or audio-only mode; codecs depend on the device." else "Try reimporting it or use Scan phone."}"
                update { it.copy(audioError = detail, message = detail, requestedPlaying = false) }
            }
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY && clock.ready) desired?.let { if (serverNow() >= it.executeAt) apply(it) }
                if (playbackState == Player.STATE_ENDED) {
                    if (state.value.role == "local") { if (state.value.index < state.value.queue.lastIndex) command("next") else update { it.copy(requestedPlaying = false) }; return }
                    val f = desired ?: return
                    if (state.value.role == "host" && f.revision != endedRevision) { endedRevision = f.revision; command("ended", revision = f.revision, index = f.index) }
                }
            }
        })
        if (bearer.isNotBlank()) connect()
        else if (state.value.role == "local" && state.value.queue.isNotEmpty()) { if (prepare(PlaybackFrame(state.value.index, state.value.positionMs, false, 0.0, 0)) && state.value.requestedPlaying && !automotiveVideoBlocked()) p.play() }
        loops?.cancel(); loops = scope.launch { while (isActive) { tick(); delay(250) } }
    }
    fun detach() { clearTalkRequest(); convoyAudio.close(); radar.stop(); discovery.stop(); ConvoyForeground.stopLocation(context); ConvoyForeground.stopMicrophone(context); player = null; preparedHash = ""; activeRevision = -1; discoveryTimeout?.cancel(); loops?.cancel(); scheduleJob?.cancel(); generation++; ws?.close(1000, "Service stopped"); ws = null; reconnect?.cancel(); update { it.copy(connected = false, playing = false, serviceReady = false) } }
    fun shutdown() { detach(); vehicleSafety.close(); scope.cancel() }
    fun configure(endpoint: String, name: String) {
        require(!state.value.connected && state.value.code.isBlank()) { "Leave the current session before changing the server" }
        val clean = endpoint.trim().trimEnd('/')
        val url = clean.toHttpUrlOrNull() ?: error("Enter a full http:// or https:// server address")
        require(url.encodedPath == "/" && url.query == null && url.username.isEmpty() && url.password.isEmpty()) { "Use the server root address without paths or credentials" }
        require(url.isHttps || isLanHost(url.host)) { "Public servers require HTTPS. HTTP is allowed only on a private network." }
        prefs.edit().putString("endpoint", clean).putBoolean("manualServer", true).putString("name", name.take(40)).apply(); update { it.copy(endpoint = clean, carName = name.take(40)) }
    }
    fun create() = establish(null)
    fun join(code: String) = establish(code)
    private fun establish(code: String?) {
        if (state.value.driving) { message("Park before connecting a session."); return }
        if (state.value.code.isNotBlank()) { message("Leave the existing session first."); return }
        if (state.value.endpoint.isBlank()) { message("Set your server address in Settings first."); return }
        if (code != null && !code.matches(Regex("\\d{6}"))) { message("Enter the six-digit session code."); return }
        scope.launch {
            update { it.copy(busy = true) }
            try {
                val path = if (code == null) "/sessions" else "/sessions/$code/join"
                val result = withContext(Dispatchers.IO) { request(path, "POST", JSONObject().put("name", state.value.carName)) }
                bearer = result.getString("token")
                prefs.edit().putString("token", bearer).putString("code", result.getString("code")).putString("role", result.getString("role")).putString("memberId", result.getString("memberId")).apply()
                update { it.copy(code = result.getString("code"), role = result.getString("role"), memberId = result.getString("memberId"), message = "Connecting session…") }; connect()
            } catch (e: Exception) { message(e.message ?: "Cannot reach server") }
            finally { update { it.copy(busy = false) } }
        }
    }
    private fun request(path: String, method: String, body: JSONObject? = null): JSONObject {
        val req = Request.Builder().url(state.value.endpoint + path).header("Authorization", "Bearer $bearer").method(method, if (method == "GET" || method == "DELETE") null else RequestBody.create("application/json".toMediaType(), body.toString())).build()
        http.newCall(req).execute().use { response -> val result = JSONObject(response.body?.string() ?: "{}"); if (!response.isSuccessful) error(result.optString("error", "Server error ${response.code}")); return result }
    }
    private fun connect() {
        if (bearer.isBlank() || player == null) return
        reconnect?.cancel(); val gen = ++generation; clock.reset(); pings.clear(); activeRevision = -1
        update { it.copy(connected = false, clockReady = false, reconnectAttempt = attempts, message = if (attempts > 0) "Reconnecting to convoy server (attempt ${attempts + 1})…" else "Connecting…") }
        ws?.close(1000, "Reconnect")
        val url = state.value.endpoint.replaceFirst("https://", "wss://").replaceFirst("http://", "ws://") + "/ws"
        ws = http.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) { webSocket.send(JSONObject().put("type", "auth").put("code", state.value.code).put("token", bearer).put("mediaCapabilities", JSONArray(listOf("audio", "video"))).put("voiceCodecs", JSONArray(listOf("mulaw16"))).toString()) }
            override fun onMessage(webSocket: WebSocket, text: String) { scope.launch { if (gen == generation) try { receive(JSONObject(text)) } catch (e: Exception) { message("Invalid server message: ${e.message}") } } }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { scope.launch { if (gen == generation) lost() } }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, reason)
                scope.launch { if (gen == generation) {
                    if (code in 4001..4003) { message(reason); leave(false) } else lost()
                } }
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                scope.launch { if (gen == generation && state.value.code.isNotBlank() && code !in 4001..4003) lost() }
            }
        })
    }
    private fun lost() {
        clearTalkRequest()
        convoyAudio.close()
        radar.stop()
        ConvoyForeground.stopMicrophone(context)
        ConvoyForeground.stopLocation(context)
        update { it.copy(connected = false, radarEnabled = false, speakerId = null, talkPending = false, clockReady = false, reconnectAttempt = attempts + 1, message = "Internet connection lost. Local media may continue; reconnecting automatically…") }
        scheduleJob?.cancel()
        if (reconnect?.isActive == true) return
        val delayMs = (1000L shl attempts.coerceAtMost(5)) + (0..500).random(); attempts++
        reconnect = scope.launch { delay(delayMs); reconnect = null; connect() }
    }
    private fun frame(x: JSONObject) = PlaybackFrame(x.getInt("index"), x.getDouble("positionMs").toLong(), x.getBoolean("playing"), x.getDouble("executeAt"), x.getLong("revision"))
    private fun cars(array: JSONArray, serverNowMs: Double? = null): List<Car> = (0 until array.length()).map { i ->
        val c = array.getJSONObject(i)
        val loc = c.optJSONObject("location")?.let {
            // Server and Android clocks can differ. Age is calculated using the
            // server's own fix timestamp and serverNow, then mapped to local time.
            val ageMs = if (serverNowMs != null && serverNowMs.isFinite()) {
                (serverNowMs - it.optDouble("at", serverNowMs))
                    .toLong().coerceIn(0L, 30000L)
            } else 0L
            ConvoyPosition(
                it.optDouble("lat"), it.optDouble("lon"), it.optDouble("speed"),
                it.optDouble("bearing"), it.optDouble("accuracy"),
                System.currentTimeMillis() - ageMs
            )
        }
        Car(c.getString("name"), c.getString("role"), c.getBoolean("online"),
            if (c.isNull("driftMs")) null else c.optDouble("driftMs").toLong(),
            if (c.isNull("rttMs")) null else c.optDouble("rttMs").toLong(),
            c.optBoolean("buffered"), c.optBoolean("missing"), c.optString("id"), loc)
    }
    private fun receive(x: JSONObject) {
        when (x.getString("type")) {
            "cars" -> update { it.copy(cars = cars(x.getJSONArray("cars"), x.optDouble("serverNow", Double.NaN).takeIf(Double::isFinite))) }
            "guest_upload_granted" -> {
                val hash = x.optString("hash")
                if (hash.isNotBlank() && state.value.pendingGuestSong == hash) uploadGuestSong(hash)
            }
            "ptt_start" -> {
                clearTalkRequest()
                update { it.copy(speakerId = if (x.isNull("speakerId")) null else x.optString("speakerId")) }
                convoyAudio.duck(true)
                message(if (x.optString("speakerId") == state.value.memberId) "Microphone granted. Talking to other cars." else "Listening to another car…")
                // Capture only after the server explicitly grants the speaking floor.
                if (x.optString("speakerId") == state.value.memberId && !convoyAudio.beginCapture()) {
                    ConvoyForeground.stopMicrophone(context)
                    ws?.send(JSONObject().put("type", "ptt_release").toString())
                    message("Microphone unavailable. Grant microphone permission before speaking.")
                }
            }
            "ptt_stop" -> {
                clearTalkRequest()
                if (state.value.speakerId == state.value.memberId) ConvoyForeground.stopMicrophone(context)
                convoyAudio.close()
                update { it.copy(speakerId = null) }
                message("Intercom ready. Tap Start talking.")
            }
            "ptt_pcm" -> convoyAudio.receive(x.getString("data"), "pcm16")
            "ptt_mulaw" -> convoyAudio.receive(x.getString("data"), "mulaw16")
            "ptt_denied" -> {
                clearTalkRequest()
                ConvoyForeground.stopMicrophone(context)
                message(x.optString("reason", "Intercom is busy"))
            }
            "pong" -> {
                val t0 = x.getDouble("clientTime")
                if (!pings.remove(t0)) return
                val wasReady = clock.ready
                clock.add(t0, x.getDouble("t1"), x.getDouble("t2"), localNow())
                update { it.copy(clockReady = clock.ready, rttMs = clock.rttMs.toLong()) }
                if (!wasReady && clock.ready) desired?.let { schedule(it, true) }
            }
            "state" -> {
                attempts = 0
                val array = x.getJSONArray("playlist")
                val queue = (0 until array.length()).map { i -> val t = array.getJSONObject(i); RemoteTrack(t.getString("hash"), t.getString("title"), t.getString("artist"), t.optString("album"), t.getDouble("durationMs").toLong(), t.optString("mediaType", "audio"), t.optString("mimeType", "audio/mpeg"), t.optLong("sizeBytes"), t.optInt("width"), t.optInt("height")) }
                val cars = cars(x.getJSONArray("cars"), x.optDouble("serverNow", Double.NaN).takeIf(Double::isFinite))
                val advertised = x.optJSONArray("capabilities")?.let { values ->
                    (0 until values.length()).map { i -> values.optString(i) }.toSet()
                } ?: emptySet()
                val wasConnected = state.value.connected
                convoyAudio.setCompactVoice("convoy_ptt_mulaw_v1" in advertised)
                val sharingRevoked = state.value.sharing && !x.getBoolean("sharing")
                val available = x.optJSONArray("audioAvailable")?.let { a -> (0 until a.length()).map { a.getString(it) }.toSet() } ?: emptySet()
                val audioChanged = state.value.audioAvailable != available || state.value.sharing != x.getBoolean("sharing")
                update { it.copy(connected = true, reconnectAttempt = 0, queue = queue, cars = cars, maxGuests = x.optInt("maxGuests", 1).coerceIn(1, 50), guestControls = x.getBoolean("guestControls"), guestAdditions = x.optBoolean("guestAdditions", false), serverFeatures = advertised,
                    radioStatus = if ("convoy_ptt_mulaw_v1" in advertised) "Compact radio · G.711 16 kHz (half-size voice frames)" else "Compatible radio · PCM16 (uncompressed)", speakerId = if (x.isNull("speakerId")) null else x.optString("speakerId").takeIf { name -> name.isNotBlank() && name != "null" }, votes = x.optJSONObject("votes")?.let { obj -> obj.keys().asSequence().associateWith { key -> obj.optInt(key) } } ?: emptyMap(), sharing = x.getBoolean("sharing"), audioAvailable = available, audioManifestKnown = x.has("audioAvailable"), mediaProtocol = x.optInt("mediaProtocol", 1), repeat = x.getString("repeat"), message = if (!wasConnected) "Session connected. Matching clocks…" else it.message) }
                if (!wasConnected && "convoy_ptt_pcm16_v1" !in advertised) {
                    message("Connected to an older server: intercom, GPS, voting and guest songs need the v1.7 backend.")
                }
                previous = x.optJSONObject("previous")?.let(::frame)
                val f = frame(x.getJSONObject("frame"))
                update { it.copy(requestedPlaying = f.playing) }
                if (sharingRevoked && state.value.library.none { it.hash == queue.getOrNull(f.index)?.hash }) { player?.stop(); preparedHash = "" }
                if (desired?.revision != f.revision || !wasConnected || audioChanged) { desired = f; if (clock.ready) schedule(f, true) }
                if (playPublishedHash != null && queue.firstOrNull()?.hash == playPublishedHash) { playPublishedHash = null; command("play") }
                if (state.value.role == "host" && state.value.sharing && !state.value.busy && queue.any { it.mediaType == "audio" && it.hash !in available }) uploadMissingAudio()
            }
            "error" -> {
                val error = x.getString("error")
                if (state.value.talkPending) {
                    clearTalkRequest()
                    ConvoyForeground.stopMicrophone(context)
                    message("Talk request rejected: $error")
                    return
                }
                if (state.value.pendingGuestSong.isNotBlank() && !state.value.busy) {
                    update { it.copy(pendingGuestSong = "") }
                    message("File skipped: $error")
                    requestNextGuestUpload()
                } else message(error)
            }
        }
    }
    private fun prepare(f: PlaybackFrame): Boolean {
        val p = player ?: return false
        val remote = state.value.queue.getOrNull(f.index) ?: run { p.pause(); p.clearMediaItems(); preparedHash = ""; return false }
        if (preparedHash == remote.hash) { update { it.copy(index = f.index) }; return true }
        val local = state.value.library.find { it.hash == remote.hash && it.mediaType == remote.mediaType }
        val relayReady = state.value.sharing && (!state.value.audioManifestKnown || remote.hash in state.value.audioAvailable) && (remote.mediaType == "audio" || (remote.mediaType == "video" && remote.mimeType == "video/mp4"))
        val uri = if (local != null) local.uri else if (relayReady) "${state.value.endpoint}/sessions/${state.value.code}/${if (remote.mediaType == "video") "video" else "audio"}/${remote.hash}" else null
        if (uri == null) { p.pause(); p.clearMediaItems(); preparedHash = ""; update { it.copy(index = f.index, missing = true, playing = false, audioError = "", message = if (remote.mediaType == "video") "Missing video: ${remote.title}. Request MP4 sharing or import the matching file." else if (it.sharing) "Waiting for the host to upload ${remote.title}. Playback will start automatically." else "Connected, but this song is not on this device. Ask the host to tap Share music with guests, or import the identical MP3.") }; return false }
        val metadata = MediaMetadata.Builder().setTitle(remote.title).setArtist(remote.artist).setAlbumTitle(remote.album).apply { local?.artPath?.let { setArtworkUri(Uri.fromFile(java.io.File(it))) } }.build()
        activeRevision = -1
        p.pause(); p.setMediaItem(MediaItem.Builder().setMediaId(remote.hash).setUri(uri).setMimeType(remote.mimeType).setMediaMetadata(metadata).build(), f.positionMs); p.prepare(); preparedHash = remote.hash
        update { it.copy(missing = false, index = f.index, audioError = "") }; return true
    }
    private fun schedule(f: PlaybackFrame, recovery: Boolean = false) {
        if (!clock.ready) return
        scheduleJob?.cancel()
        val delayMs = (f.executeAt - serverNow() - state.value.latencyMs).toLong()
        // Stage a new track paused to allow decoding/buffering before its deadline.
        // Same-track play/pause/seek keeps playing until the scheduled deadline.
        if (delayMs > 0) {
            prepare(f)
            scheduleJob = scope.launch { delay(delayMs); if (desired?.revision == f.revision) apply(f) }
        } else { apply(f) }
    }
    private fun apply(f: PlaybackFrame) {
        val p = player ?: return
        if (!prepare(f)) return
        if (state.value.track?.mediaType == "video" && state.value.automotive && (!state.value.vehicleKnown || state.value.vehicleRestricted)) { p.pause(); message("Vehicle parking restrictions block video playback."); return }
        val target = f.target(serverNow(), state.value.latencyMs).coerceAtMost(state.value.queue.getOrNull(f.index)?.durationMs ?: Long.MAX_VALUE)
        // Apply a command once. Seeking again on every READY callback can keep a
        // slow video decoder buffering forever. Once playing, tick() corrects drift.
        if (activeRevision != f.revision) { p.seekTo(target); activeRevision = f.revision; p.playbackParameters = PlaybackParameters(1f) }
        p.playWhenReady = f.playing
        update { it.copy(index = f.index, message = if (clock.rttMs > 500) "High network delay: synchronization may be less accurate." else "Following synchronized playback") }
    }
    private fun tick() {
        val p = player ?: return
        if (state.value.connected && SystemClock.elapsedRealtime() - lastStatus > 2000) {
            lastStatus = SystemClock.elapsedRealtime()
            val t0 = localNow(); pings.add(t0); if (pings.size > 30) pings.clear()
            ws?.send(JSONObject().put("type", "ping").put("clientTime", t0).toString())
            ws?.send(JSONObject().put("type", "status").put("driftMs", state.value.driftMs ?: JSONObject.NULL).put("rttMs", clock.rttMs).put("buffered", p.playbackState == Player.STATE_READY).put("missing", state.value.missing).put("clockReady", clock.ready).toString())
        }
        val f = desired
        var error: Long? = null
        if (f != null && clock.ready && state.value.connected && serverNow() >= f.executeAt && preparedHash == state.value.queue.getOrNull(f.index)?.hash && p.playbackState == Player.STATE_READY) {
            if (activeRevision != f.revision) apply(f)
            val target = f.target(serverNow(), state.value.latencyMs).coerceAtMost(state.value.queue.getOrNull(f.index)?.durationMs ?: Long.MAX_VALUE)
            error = target - p.currentPosition
            if (f.playing && p.isPlaying) {
                if (abs(error) > 300 && SystemClock.elapsedRealtime() - lastHardSeek > 1500) { p.seekTo(target); lastHardSeek = SystemClock.elapsedRealtime(); p.playbackParameters = PlaybackParameters(1f) }
                else p.playbackParameters = PlaybackParameters(if (abs(error) > 35) driftSpeed(error) else 1f)
            }
        }
        update { it.copy(positionMs = p.currentPosition, playing = p.isPlaying, buffering = p.playbackState == Player.STATE_BUFFERING, driftMs = error) }
    }
    fun command(kind: String, positionMs: Long? = null, index: Int? = null, revision: Long? = null) {
        val s = state.value
        if (kind == "play" && automotiveVideoBlocked()) { message("Vehicle parking restrictions block video playback."); return }
        if (kind in listOf("play", "pause")) update { it.copy(requestedPlaying = kind == "play") }
        if (s.role == "local") {
            val p = player ?: return
            when (kind) {
                "play" -> p.play()
                "pause" -> p.pause()
                "seek" -> p.seekTo(positionMs ?: 0)
                "next", "previous", "select" -> {
                    if (s.queue.isEmpty()) return
                    val i = index ?: ((s.index + if (kind == "next") 1 else -1) + s.queue.size) % s.queue.size
                    if (prepare(PlaybackFrame(i, 0, false, 0.0, 0))) { p.seekTo(0); if (!automotiveVideoBlocked()) p.play(); update { it.copy(index = i) } }
                }
            }; return
        }
        if (!s.connected) { message("Reconnect before changing synchronized playback."); return }
        if (!s.canControl) { message("The host controls playback."); return }
        val action = JSONObject().put("kind", kind)
        positionMs?.let { action.put("positionMs", it) }; index?.let { action.put("index", it) }; revision?.let { action.put("revision", it) }
        send(JSONObject().put("type", "control").put("action", action))
    }
    private fun send(x: JSONObject) { x.put("id", UUID.randomUUID().toString()); if (ws?.send(x.toString()) != true) message("Connection unavailable. Retry when reconnected.") }
    fun publish(hashes: List<String>) {
        if (state.value.driving) { message("Park before changing the playlist."); return }
        if (!state.value.connected || state.value.role != "host") { message("Create a host session first."); return }
        val list = hashes.mapNotNull { h -> state.value.library.find { it.hash == h } }
        if (list.any { it.mediaType == "video" } && state.value.mediaProtocol < 2) { message("The server needs the video protocol update before sharing a video session."); return }
        val array = JSONArray(); list.forEach { t -> array.put(JSONObject().put("hash", t.hash).put("title", t.title).put("artist", t.artist).put("album", t.album).put("durationMs", t.durationMs).put("mediaType", t.mediaType).put("mimeType", t.mimeType).put("sizeBytes", t.sizeBytes).put("width", t.width).put("height", t.height)) }
        send(JSONObject().put("type", "playlist").put("playlist", array)); message("Publishing playlist…")
    }
    fun listenLocal(hashes: List<String>) {
        if (state.value.driving || state.value.code.isNotBlank()) { message("Leave the session before listening locally."); return }
        val queue = hashes.mapNotNull { h -> state.value.library.find { it.hash == h } }.map { RemoteTrack(it.hash, it.title, it.artist, it.album, it.durationMs, it.mediaType, it.mimeType, it.sizeBytes, it.width, it.height) }
        desired = null; preparedHash = ""; update { it.copy(role = "local", queue = queue, index = 0, requestedPlaying = true, audioError = "", message = "Local playback — no synchronization session") }
        if (queue.isNotEmpty() && prepare(PlaybackFrame(0, 0, false, 0.0, 0)) && !automotiveVideoBlocked()) player?.play()
    }
    fun playTrack(hash: String) {
        val kind = state.value.library.find { it.hash == hash }?.mediaType ?: return
        val hashes = state.value.library.filter { it.mediaType == kind }.map { it.hash }
        val index = hashes.indexOf(hash)
        if (index < 0) return
        val ordered = hashes.drop(index) + hashes.take(index)
        if (state.value.role == "guest") { message("The host selects the music."); return }
        if (state.value.code.isNotBlank() && state.value.connected) { playPublishedHash = hash; publish(ordered) }
        else listenLocal(ordered)
    }
    fun scanPhone() {
        if (state.value.driving) return
        scope.launch {
            update { it.copy(busy = true, message = "Scanning phone music…") }
            var count = 0; var failures = 0
            try {
                withContext(Dispatchers.IO) {
                    val collection = android.provider.MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
                    context.contentResolver.query(collection, arrayOf("_id", "_display_name", "mime_type"), null, null, null)?.use { cursor ->
                        while (cursor.moveToNext()) {
                            val name = cursor.getString(1).orEmpty(); val mime = cursor.getString(2).orEmpty()
                            if (!name.endsWith(".mp3", true) && mime != "audio/mpeg") continue
                            try { dao.insert(importTrack(context, android.content.ContentUris.withAppendedId(collection, cursor.getLong(0)), false)); count++ } catch (e: Exception) { failures++ }
                        }
                    }
                }
                message("Found $count MP3s${if (failures > 0) "; $failures unreadable files" else ""}. Tap a song to play.")
            } catch (e: Exception) { message("Scan failed: ${e.message}. Allow Music and audio permission, or use Import files.") }
            finally { update { it.copy(busy = false) } }
        }
    }
    fun settings(guestControls: Boolean = state.value.guestControls, sharing: Boolean = state.value.sharing, repeat: String = state.value.repeat, guestAdditions: Boolean = state.value.guestAdditions) {
        if (state.value.driving || !state.value.connected || state.value.role != "host") return
        send(JSONObject().put("type", "settings").put("guestControls", guestControls).put("sharing", sharing).put("repeat", repeat).put("guestAdditions", guestAdditions))
    }
    fun volume(v: Float) {
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
        audio.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, (v * audio.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)).toInt(), 0)
        player?.volume = if (state.value.speakerId != null) 0.2f else 1f; update { it.copy(volume = v) }
    }
    fun driving(v: Boolean) {
        if (!v && SystemClock.elapsedRealtime() - recentMovingAtMs < 15000L) {
            message("GPS still reports movement. Stop and wait before leaving driving mode.")
            return
        }
        prefs.edit().putBoolean("driving", v).apply(); update { it.copy(driving = v, parkedConfirmed = if (v) false else it.parkedConfirmed) }; refreshVideoOutput() }
    fun confirmParked() { if (!state.value.driving && !state.value.vehicleRestricted && (!state.value.automotive || state.value.vehicleKnown)) { update { it.copy(parkedConfirmed = true, audioOnlyVideo = false) }; refreshVideoOutput() } }
    fun revokeParked() { update { it.copy(parkedConfirmed = false) }; refreshVideoOutput() }
    fun audioOnlyVideo(value: Boolean) { update { it.copy(audioOnlyVideo = value) }; refreshVideoOutput(); if (value && player?.playerError != null) retryAudio() }
    private fun refreshVideoOutput() { player?.let { it.trackSelectionParameters = it.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, !state.value.showVideo).build() } }
    private fun automotiveVideoBlocked() = state.value.track?.mediaType == "video" && state.value.automotive && (!state.value.vehicleKnown || state.value.vehicleRestricted)
    fun scanVideos() {
        if (state.value.driving || state.value.busy) return
        scope.launch {
            update { it.copy(busy = true, message = "Scanning device videos…") }; var count = 0; var failures = 0
            try {
                withContext(Dispatchers.IO) {
                    val collection = android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                    context.contentResolver.query(collection, arrayOf("_id"), null, null, null)?.use { cursor ->
                        while (cursor.moveToNext()) try { dao.insert(importVideo(context, android.content.ContentUris.withAppendedId(collection, cursor.getLong(0)), false)); count++ } catch (_: Exception) { failures++ }
                    }
                }
                message("Found $count videos${if (failures > 0) "; $failures unsupported/unreadable" else ""}.")
            } catch (e: Exception) { message("Video scan failed: ${e.message}. Allow Videos permission or import a file.") }
            finally { update { it.copy(busy = false) } }
        }
    }
    fun removeVideo(track: Track) {
        if (state.value.driving || track.mediaType != "video") return
        if (state.value.queue.any { it.hash == track.hash }) { message("This video is queued. Clear local playback or leave the session before removing it."); return }
        scope.launch {
            dao.delete(track)
            state.value.playlists.filter { it.mediaType == "video" }.forEach { p -> dao.save(p.copy(hashes = p.hashes.split(',').filter { it != track.hash }.joinToString(","))) }
            val file = Uri.parse(track.uri).path?.let { java.io.File(it) }
            if (file != null && file.canonicalPath.startsWith(java.io.File(context.filesDir, "videos").canonicalPath + java.io.File.separator)) file.delete()
            track.artPath?.let { java.io.File(it).delete() }; message("Video removed from library.")
        }
    }
    fun latency(ms: Long) { prefs.edit().putLong("latency", ms.coerceIn(0, 2000)).apply(); update { it.copy(latencyMs = ms.coerceIn(0, 2000)) } }
    fun importFiles(uris: List<Uri>, mediaType: String = "audio") {
        if (state.value.driving || uris.isEmpty()) return
        scope.launch {
            update { it.copy(busy = true) }; var count = 0; var firstError: String? = null
            withContext(Dispatchers.IO) { for (uri in uris) try {
                dao.insert(if (mediaType == "video") importVideo(context, uri) else importTrack(context, uri)); count++
            } catch (e: Exception) { if (firstError == null) firstError = e.message ?: "Cannot read this file" } }
            update { it.copy(busy = false, message = "Imported $count of ${uris.size} ${if (mediaType == "video") "video" else "MP3"} files." + (firstError?.let { error -> " Import failed: $error" } ?: "")) }
        }
    }
    fun savePlaylist(name: String, hashes: List<String>, id: Long = 0, mediaType: String = "audio") { if (!state.value.driving && name.isNotBlank()) scope.launch { dao.save(SavedPlaylist(id, name.trim(), hashes.joinToString(","), mediaType)); message("Playlist saved.") } }
    fun deletePlaylist(p: SavedPlaylist) { if (!state.value.driving) scope.launch { dao.delete(p) } }
    fun uploadMissingAudio() {
        val s = state.value
        if (s.busy || !s.sharing || s.role != "host" || !s.connected) return
        scope.launch {
            update { it.copy(busy = true) }
            try {
                withContext(Dispatchers.IO) {
                    for (remote in s.queue.distinctBy { it.hash }) {
                        if (remote.mediaType == "video") continue
                        if (remote.hash in s.audioAvailable) continue
                        val t = s.library.find { it.hash == remote.hash } ?: continue
                        val uri = Uri.parse(t.uri)
                        val size = context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1
                        require(size in 1..104857600) { "Track size unknown or above 100 MB. Copy it to internal storage and import again." }
                        val body = object : RequestBody() {
                            override fun contentType() = "audio/mpeg".toMediaType()
                            override fun contentLength() = size
                            override fun writeTo(sink: okio.BufferedSink) { context.contentResolver.openInputStream(uri)?.use { input -> val buf = ByteArray(65536); while (true) { val n = input.read(buf); if (n < 0) break; sink.write(buf, 0, n) } } ?: throw IOException("Storage unavailable") }
                        }
                        val req = Request.Builder().url("${s.endpoint}/sessions/${s.code}/audio/${t.hash}").header("Authorization", "Bearer $bearer").put(body).build()
                        http.newCall(req).execute().use { response -> if (!response.isSuccessful) error(JSONObject(response.body?.string() ?: "{}").optString("error", "Upload failed")) }
                        withContext(Dispatchers.Main) { message("Uploaded ${t.title}") }
                    }
                }
                message("Audio relay ready. All guests start automatically.")
            } catch (e: Exception) { message(e.message ?: "Upload failed") }
            finally {
                update { it.copy(busy = false) }
                if (state.value.queue.map { it.hash } != s.queue.map { it.hash }) uploadMissingAudio()
            }
        }
    }
    fun retryAudio() {
        preparedHash = ""
        if (desired != null) desired?.let { schedule(it, true) }
        else if (state.value.role == "local" && state.value.queue.isNotEmpty() && prepare(PlaybackFrame(state.value.index, state.value.positionMs, false, 0.0, 0)) && !automotiveVideoBlocked()) { player?.play(); update { it.copy(requestedPlaying = true) } }
    }
    fun exportMetrics(uri: Uri) {
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val req = Request.Builder().url("${state.value.endpoint}/sessions/${state.value.code}/metrics").header("Authorization", "Bearer $bearer").build()
                    http.newCall(req).execute().use { response ->
                        require(response.isSuccessful) { "Host-only export; reconnect to the server first" }
                        context.contentResolver.openOutputStream(uri)?.use { out -> response.body?.byteStream()?.use { it.copyTo(out) } } ?: error("Cannot write export")
                    }
                }; message("Telemetry CSV exported.")
            } catch (e: Exception) { message(e.message ?: "Export failed") }
        }
    }
    fun audioToken() = bearer
    fun resume() { if (bearer.isNotBlank() && !state.value.connected) connect() }
    fun leave(clearMessage: Boolean = true) { convoyAudio.close(); radar.stop(); ConvoyForeground.stopLocation(context); ConvoyForeground.stopMicrophone(context);
        generation++; reconnect?.cancel(); ws?.close(1000, "Left session"); ws = null; scheduleJob?.cancel(); player?.pause(); player?.clearMediaItems(); preparedHash = ""; desired = null; activeRevision = -1; bearer = ""
        val nextEndpoint = ServerDefaults.resolve(
            state.value.endpoint, prefs.getBoolean("manualServer", false), false
        )
        prefs.edit().remove("token").remove("code").remove("role").remove("memberId")
            .putString("endpoint", nextEndpoint).apply()
        update { it.copy(endpoint = nextEndpoint, code = "", role = "", memberId = "", radarEnabled = false, speakerId = null, serverFeatures = emptySet(), radioStatus = "Waiting for a radio connection", guestAdditions = false, pendingGuestSong = "", pendingGuestSongs = emptyList(), connected = false, queue = emptyList(), cars = emptyList(), clockReady = false, driftMs = null, requestedPlaying = false, audioError = "", message = if (clearMessage) "Session left. The host can create a new session for the group." else it.message) }
    }
    fun endSession() { scope.launch { try { withContext(Dispatchers.IO) { request("/sessions/${state.value.code}", "DELETE") }; leave() } catch (e: Exception) { message(e.message ?: "Could not end session") } } }
}

