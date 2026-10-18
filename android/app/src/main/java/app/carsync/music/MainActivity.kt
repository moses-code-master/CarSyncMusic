package app.carsync.music

import android.Manifest
import android.content.Intent
import android.content.ComponentName
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.painterResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.core.content.ContextCompat
import com.google.common.util.concurrent.ListenableFuture
import androidx.compose.ui.graphics.Brush
import com.google.zxing.*
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeWriter
import java.io.File

class MainActivity : ComponentActivity() {
    private var controller: ListenableFuture<MediaController>? = null
    private val sync get() = (application as CarSyncApp).sync
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        volumeControlStream = android.media.AudioManager.STREAM_MUSIC
        if (Build.VERSION.SDK_INT >= 33) registerForActivityResult(ActivityResultContracts.RequestPermission()) {}.launch(Manifest.permission.POST_NOTIFICATIONS)
        acceptLink(intent)
        setContent { CarSyncScreen(sync) }
    }
    override fun onStart() {
        super.onStart()
        val future = MediaController.Builder(this, SessionToken(this, ComponentName(this, PlaybackService::class.java))).buildAsync()
        controller = future
        future.addListener({ try { future.get() } catch (e: Exception) { sync.message("Audio service could not start: ${e.cause?.message ?: e.message}") } }, ContextCompat.getMainExecutor(this))
    }
    override fun onStop() { sync.revokeParked(); controller?.let { MediaController.releaseFuture(it) }; controller = null; super.onStop() }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); acceptLink(intent) }
    private fun acceptLink(intent: Intent) {
        if (intent.data?.scheme == "carsync") { sync.invitation(intent.data!!); return }
        val uris = when (intent.action) {
            Intent.ACTION_VIEW -> listOfNotNull(intent.data)
            Intent.ACTION_SEND -> listOfNotNull(intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))
            Intent.ACTION_SEND_MULTIPLE -> intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM).orEmpty()
            else -> emptyList()
        }.filter { it.scheme == "content" || it.scheme == "file" }
        if (uris.isNotEmpty()) uris.groupBy { if (intent.type?.startsWith("video/") == true || isVideoUri(this, it)) "video" else "audio" }.forEach { (kind, files) -> sync.importFiles(files, kind) }
    }
}

private val Accent = Color(0xFFB4F36D)
private val Panel = Color(0xFF19212B)
private val Muted = Color(0xFF96A6B8)
private val pages = listOf("Home", "Music Library", "Playlists", "Create Session", "Join Session", "Now Playing", "Connected Cars", "Settings")

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun CarSyncScreen(sync: SyncCoordinator) {
    val s by sync.state.collectAsStateWithLifecycle()
    var page by rememberSaveableCompat("Home")
    val pageScroll = rememberScrollState()
    LaunchedEffect(page) { pageScroll.scrollTo(0) }
    var search by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf(listOf<String>()) }
    var playlistName by remember { mutableStateOf("Road trip") }
    var editId by remember { mutableStateOf(0L) }
    var joinCode by remember(s.invitationCode) { mutableStateOf(s.invitationCode) }
    var endpoint by remember(s.endpoint, s.invitationServer) { mutableStateOf(s.invitationServer.ifBlank { s.endpoint }) }
    var carName by remember(s.carName) { mutableStateOf(s.carName) }
    var latency by remember(s.latencyMs) { mutableStateOf(s.latencyMs.toString()) }
    LaunchedEffect(s.invitationCode) { if (s.invitationCode.isNotBlank()) page = "Join Session" }
    val context = androidx.compose.ui.platform.LocalContext.current
    val import = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { sync.importFiles(it) }
    val legacyImport = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            val data = result.data
            val uris = data?.clipData?.let { clip -> (0 until clip.itemCount).map { clip.getItemAt(it).uri } } ?: listOfNotNull(data?.data)
            sync.importFiles(uris)
        }
    }
    val openImport = {
        launchPickerSafely({ import.launch(arrayOf("audio/*")) }, {
            legacyImport.launch(Intent(Intent.ACTION_GET_CONTENT).addCategory(Intent.CATEGORY_OPENABLE).setType("audio/*").putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        }) { sync.message("No working file picker on this head unit. Use Scan phone, or open an MP3 in FileManager and choose Open with / Share → CarSync Music.") }
    }
    val scanPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> if (granted) sync.scanPhone() else sync.message("Allow Music and audio access to scan your phone. Import files works without this permission.") }
    val scan = {
        val permission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE
        if (ContextCompat.checkSelfPermission(context, permission) == android.content.pm.PackageManager.PERMISSION_GRANTED) sync.scanPhone() else scanPermission.launch(permission)
    }
    val metricsExport = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri -> if (uri != null) sync.exportMetrics(uri) }
    val qrImport = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) try {
            val bmp = context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) } ?: error("Cannot read QR image")
            val pixels = IntArray(bmp.width * bmp.height); bmp.getPixels(pixels, 0, bmp.width, 0, 0, bmp.width, bmp.height)
            val text = MultiFormatReader().decode(BinaryBitmap(HybridBinarizer(RGBLuminanceSource(bmp.width, bmp.height, pixels)))).text
            val link = Uri.parse(text); require(link.scheme == "carsync" && link.host == "join") { "This is not a CarSync invitation" }
            endpoint = link.getQueryParameter("server") ?: ""; joinCode = link.getQueryParameter("code") ?: ""; page = "Join Session"
            sync.message("Review the QR server address, save it in Settings, then join.")
        } catch (e: Exception) { sync.message(e.message ?: "Unable to read QR") }
    }
    val colors = darkColorScheme(primary = Accent, onPrimary = Color(0xFF162609), background = Color(0xFF0B1016), surface = Panel, onSurface = Color(0xFFF2F5FA), secondary = Color(0xFF92BFFF))
    MaterialTheme(colorScheme = colors) {
        Scaffold(topBar = {
            Column(Modifier.background(Color(0xFF0C1017)).statusBarsPadding().padding(horizontal = 20.dp, vertical = 10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(painterResource(R.drawable.ic_wave), null, tint = Accent, modifier = Modifier.size(34.dp)); Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(CreatorBrand.PRODUCT, fontWeight = FontWeight.Bold, fontSize = 22.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(CreatorBrand.SIGNATURE, color = Accent, fontSize = 12.sp)
                    }
                    Text(if (s.connected) "● ${s.role.uppercase()} · ${s.code}" else if (s.role == "local") "LOCAL PLAYBACK" else "● OFFLINE", fontSize = 12.sp, color = if (s.connected) Accent else Muted)
                }
                Text(page, color = Muted, fontSize = 15.sp, modifier = Modifier.padding(top = 6.dp))
            }
        }, bottomBar = {
            Column(Modifier.fillMaxWidth().background(Panel)) {
                if (s.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(s.message, color = if (s.audioError.isNotBlank()) Color(0xFFFFB19F) else Muted, fontSize = 13.sp, maxLines = 3, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                NavigationBar(containerColor = Color(0xFF10171F)) {
                    val nav = listOf("Home" to Icons.Default.Home, "Music Library" to Icons.Default.List, "Videos" to Icons.Default.PlayArrow, "Now Playing" to Icons.Default.PlayArrow, "Create Session" to Icons.Default.Share, "Settings" to Icons.Default.Settings)
                    val labels = mapOf("Home" to "Home", "Music Library" to "Library", "Videos" to "Videos", "Now Playing" to "Player", "Create Session" to "Session", "Settings" to "Settings")
                    nav.forEach { (target, icon) -> NavigationBarItem(selected = page == target || (target == "Create Session" && page in listOf("Join Session", "Connected Cars")) || (target == "Music Library" && page == "Playlists") || (target == "Settings" && page in listOf("About", "License")), onClick = { page = target }, icon = { if (target == "Videos") Icon(painterResource(R.drawable.ic_video), target) else Icon(icon, target) }, label = { Text(labels.getValue(target), fontSize = 10.sp, maxLines = 1) }) }
                }
            }
        }) { insets ->
            Column(Modifier.fillMaxSize().padding(insets).verticalScroll(pageScroll).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                if (s.driving && page !in listOf("Now Playing", "Connected Cars", "Settings")) { Text("Driving mode: only playback and connection status are available."); LargeButton("Now Playing") { page = "Now Playing" } }
                else when (page) {
                    "Home" -> {
                        Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF243B28)), shape = RoundedCornerShape(28.dp)) { Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text("YOUR ROAD. YOUR SOUND.", color = Accent, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            Text(CreatorBrand.WELCOME_CREDIT, color = Accent, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                            Text("Good music.\nShared miles.", fontSize = 38.sp, lineHeight = 44.sp, fontWeight = FontWeight.Bold)
                            Text("Play your phone’s MP3s instantly. Share the journey with multiple guest cars.", color = Color(0xFFCEDACD), fontSize = 18.sp)
                            LargeButton("Find music on this phone", !s.busy) { scan(); page = "Music Library" }
                        } }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Stat("LIBRARY", "${s.library.count { it.mediaType == "audio" }} tracks", Modifier.weight(1f)); Stat("SESSION", if (s.code.isBlank()) "Ready to connect" else s.code, Modifier.weight(1f))
                        }
                        LargeButton("Create a listening session", enabled = !s.busy) { page = "Create Session" }
                        LargeButton("Join the other car") { page = "Join Session" }
                        LargeButton("Open music library") { page = "Music Library" }
                        LargeButton("Open videos · parked viewing") { page = "Videos" }
                        if (s.library.isNotEmpty() && s.track == null) s.library.filter { it.mediaType == "audio" }.take(4).forEach { track -> TrackRow(track, false, { sync.playTrack(track.hash); page = "Now Playing" }, {}) }
                        if (s.track != null) NowPlaying(s, sync)
                    }
                    "Music Library" -> {
                        Title("Your music", "Tap any song to play. Scan your phone or import from a folder, USB or SD card.")
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            LargeButton("Scan phone", !s.busy) { scan() }
                            LargeButton("Import files", !s.busy) { openImport() }
                            LargeButton("Playlists") { page = "Playlists" }
                        }
                        OutlinedTextField(search, { search = it }, label = { Text("Search title, artist or album") }, modifier = Modifier.fillMaxWidth())
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            LargeButton("Select all") { selected = s.library.filter { it.mediaType == "audio" }.map { it.hash } }
                            LargeButton("Clear") { selected = emptyList() }
                        }
                        LazyColumn(Modifier.fillMaxWidth().height(360.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) { items(s.library.filter { it.mediaType == "audio" && "${it.title} ${it.artist} ${it.album}".contains(search, true) }, key = { it.hash }) { t ->
                            TrackRow(t, t.hash in selected, { sync.playTrack(t.hash); page = "Now Playing" }, { selected = if (t.hash in selected) selected - t.hash else selected + t.hash })
                        } }
                        Text("${selected.size} selected · selection order becomes playlist order", color = Accent)
                        OutlinedTextField(playlistName, { playlistName = it }, label = { Text("Playlist name") }, modifier = Modifier.fillMaxWidth())
                        LargeButton("Save playlist", selected.isNotEmpty()) { sync.savePlaylist(playlistName, selected, editId); editId = 0; page = "Playlists" }
                        LargeButton("Listen locally", selected.isNotEmpty()) { sync.listenLocal(selected); page = "Now Playing" }
                        LargeButton("Publish to session", selected.isNotEmpty() && s.role == "host") { sync.publish(selected); page = "Now Playing" }
                    }
                    "Playlists" -> {
                        Title("Road trip playlists", "Save, rename, reorder or publish. Publishing resets playback to the first track.")
                        s.playlists.filter { it.mediaType == "audio" }.forEach { p ->
                            Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(p.name, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                                val hashes = p.hashes.split(',').filter { it.isNotBlank() }
                                Text("${hashes.size} tracks", color = Muted)
                                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    LargeButton("Edit") { editId = p.id; playlistName = p.name; selected = hashes }
                                    LargeButton("Publish", s.role == "host") { sync.publish(hashes); page = "Now Playing" }
                                    LargeButton("Listen") { sync.listenLocal(hashes); page = "Now Playing" }
                                    LargeButton("Delete") { sync.deletePlaylist(p); if (editId == p.id) editId = 0 }
                                }
                            } }
                        }
                        if (editId != 0L) {
                            OutlinedTextField(playlistName, { playlistName = it }, label = { Text("Rename playlist") }, modifier = Modifier.fillMaxWidth())
                            selected.forEachIndexed { i, hash ->
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(s.library.find { it.hash == hash }?.title ?: "Missing local file", Modifier.weight(1f))
                                    IconButton({ if (i > 0) selected = selected.toMutableList().apply { val v = removeAt(i); add(i - 1, v) } }, Modifier.size(56.dp)) { Icon(Icons.Default.KeyboardArrowUp, "Move up") }
                                    IconButton({ if (i < selected.lastIndex) selected = selected.toMutableList().apply { val v = removeAt(i); add(i + 1, v) } }, Modifier.size(56.dp)) { Icon(Icons.Default.KeyboardArrowDown, "Move down") }
                                    IconButton({ selected = selected.filterIndexed { ix, _ -> ix != i } }, Modifier.size(56.dp)) { Icon(Icons.Default.Close, "Remove") }
                                }
                            }
                            LargeButton("Shuffle order") { selected = selected.shuffled() }
                            LargeButton("Add music") { page = "Music Library" }
                            LargeButton("Save changes") { sync.savePlaylist(playlistName, selected, editId); editId = 0 }
                        }
                        LargeButton("Build a new playlist") { editId = 0; selected = emptyList(); page = "Music Library" }
                    }
                    "Create Session" -> {
                        Title("Lead the soundtrack", "One host and up to ${s.maxGuests} guests. Everyone uses the same server and session code. Sessions expire after 24 hours.")
                        Text("Server: ${s.endpoint.ifBlank { "Configure in Settings" }}", color = Muted)
                        LargeButton("Create session", s.code.isBlank() && !s.busy) { sync.create() }
                        LargeButton("Join another car") { page = "Join Session" }
                        LargeButton("Connected cars") { page = "Connected Cars" }
                        if (s.role == "host") {
                            Text(s.code, fontSize = 56.sp, fontWeight = FontWeight.Bold, color = Accent)
                            val link = "carsync://join?code=${s.code}&server=${Uri.encode(s.endpoint)}"
                            val qr = remember(link) { qrBitmap(link) }
                            Image(qr.asImageBitmap(), "Guest session QR code", Modifier.size(250.dp))
                            Text("Guest: scan with a QR reader, or import a QR image in Join Session. Review the server address.", color = Muted)
                            LargeButton("Choose music") { page = "Music Library" }
                        }
                    }
                    "Join Session" -> {
                        Title("Follow the host", "Enter the host’s code using the same server. Import matching MP3s before joining for lowest data use.")
                        Text("Server: ${s.endpoint.ifBlank { "Configure in Settings" }}", color = Muted)
                        OutlinedTextField(joinCode, { joinCode = it.filter(Char::isDigit).take(6) }, label = { Text("Six-digit session code") }, modifier = Modifier.fillMaxWidth())
                        LargeButton("Join session", !s.busy && s.code.isBlank()) { sync.join(joinCode); page = "Now Playing" }
                        LargeButton("Read QR from image") { launchPickerSafely({ qrImport.launch(arrayOf("image/*")) }) { sync.message("No working image picker. Enter the six-digit session code instead.") } }
                        if (endpoint != s.endpoint && endpoint.isNotBlank()) { Text("QR suggests server: $endpoint", color = Accent); LargeButton("Review in Settings") { page = "Settings" } }
                    }
                    "Videos" -> VideoLibraryScreen(s, sync) { page = "Now Playing" }
                    "Now Playing" -> NowPlaying(s, sync)
                    "Connected Cars" -> {
                        Title("Travelling together", "Drift is measured against the server timeline; it does not measure speaker or Bluetooth delay.")
                        if (s.connected) Text("${s.cars.count { it.role == "guest" && it.online }} guests online · ${s.cars.count { it.role == "guest" }}/${s.maxGuests} guest places used", color = Accent, fontSize = 20.sp)
                        s.cars.forEach { car -> Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("${if (car.online) "●" else "○"} ${car.name}", color = if (car.online) Accent else Muted, fontSize = 26.sp)
                            Text("${car.role.uppercase()} · ${if (car.online) "Connected" else "Reconnecting / offline"}")
                            Text("Player drift: ${car.driftMs?.let { "$it ms" } ?: "Measuring"} · network RTT: ${car.rttMs?.let { "$it ms" } ?: "—"}", color = Muted)
                            if (car.missing) Text("Needs matching MP3 or permitted audio relay", color = Color(0xFFFFBF7A))
                            else Text(if (car.buffered) "Audio ready" else "Preparing / buffering", color = Muted)
                        } } }
                        if (s.cars.isEmpty()) Text("Create or join a session to see the host and all guests.")
                    }
                    "Settings" -> {
                        if (!s.driving) LargeButton("About CarSync Music") { page = "About" }
                        Title("Setup & playback", "Use HTTPS for mobile data. Use a private Wi-Fi server address on a shared hotspot.")
                        SettingToggle("Driving mode", s.driving, "Hides library, session setup and complex controls. Park before disabling; automatic vehicle-speed detection is unavailable.") { sync.driving(it); if (it) page = "Now Playing" }
                        if (!s.driving) {
                            OutlinedTextField(endpoint, { endpoint = it }, label = { Text("Server · https://music.example.com") }, modifier = Modifier.fillMaxWidth())
                            OutlinedTextField(carName, { carName = it }, label = { Text("Car name") }, modifier = Modifier.fillMaxWidth())
                            LargeButton("Save server & car name", s.code.isBlank()) { try { sync.configure(endpoint, carName) } catch (e: Exception) { sync.message(e.message ?: "Invalid server") } }
                            OutlinedTextField(latency, { latency = it.filter(Char::isDigit).take(4) }, label = { Text("Local output latency compensation · 0–2000 ms") }, modifier = Modifier.fillMaxWidth())
                            LargeButton("Save calibration") { sync.latency(latency.toLongOrNull() ?: 0) }
                            Text("A device with more output delay starts earlier. Compare recordings from both speakers and adjust this value while parked.", color = Muted)
                            if (s.role == "host") {
                                SettingToggle("Guest playback controls", s.guestControls, "Allow all guests to play, pause, skip and seek for the whole session.") { sync.settings(guestControls = it) }
                                SettingToggle("I have permission to share this audio", s.sharing, "Opt in only for audio you may lawfully share. Each MP3 uploads once to your server, then every guest can stream it. Revoking stops guest relay playback.") { sync.settings(sharing = it) }
                                LargeButton("Upload current playlist to relay", s.sharing && !s.busy) { sync.uploadMissingAudio() }
                                LargeButton("Export synchronization telemetry") { launchPickerSafely({ metricsExport.launch("carsync-${s.code}-metrics.csv") }) { sync.message("This device has no working file picker for saving reports.") } }
                            }
                            if (s.code.isNotBlank()) {
                                LargeButton("Reconnect now") { sync.resume() }
                                LargeButton("Leave session") { sync.leave() }
                                if (s.role == "host") LargeButton("End session for everyone") { sync.endSession() }
                            }
                            Text("No Google Play Services required. Intended for head units that install ordinary Android APKs; this is not an Android Auto projection app.", color = Muted)
                        }
                    }
                    "About" -> AboutScreen({ page = "License" }, sync::message)
                    "License" -> { LargeButton("Back to About") { page = "About" }; ProjectLicenseScreen() }
                }
            }
        }
    }
}

@Composable private fun NowPlaying(s: AppState, sync: SyncCoordinator) {
    val t = s.track
    if (t == null) { Title("Ready for the road", "Import MP3s, publish a playlist, then press play."); return }
    if (s.role == "host" && !s.driving && t.mediaType == "audio") {
        if (!s.sharing) {
            Text("Share this playlist with all guests. Each song uploads once; guests without matching local files can stream it. Enable only for MP3s you have permission to share.", color = Muted)
            LargeButton("Share music with guests", !s.busy) { sync.settings(sharing = true) }
        } else Text(if (s.busy) "Preparing shared music for all guests…" else "Audio sharing enabled for all guests · new playlist songs upload automatically", color = Accent)
    }
    if (s.role == "guest" && s.missing) Text(if (t.mediaType == "video") s.message else if (s.sharing) "Waiting for music from the host. Playback starts automatically when ready." else "Controls are connected. Ask the host to tap Share music with guests to hear this song.", color = Accent)
    if (t.mediaType == "video") { VideoPanel(s, sync); PlaybackControls(s, sync) }
    else BoxWithConstraints {
        val wide = maxWidth > 500.dp
        val art = s.library.find { it.hash == t.hash }?.artPath
        if (wide) Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) { Artwork(art, Modifier.size(140.dp)); Column(Modifier.weight(1f)) { PlaybackControls(s, sync, compact = true) } }
        else Column(verticalArrangement = Arrangement.spacedBy(16.dp)) { Artwork(art, Modifier.fillMaxWidth().height(140.dp)); PlaybackControls(s, sync) }
    }
    if (!s.driving) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            LargeButton("Shuffle", s.role == "host") { sync.publish(s.queue.map { it.hash }.shuffled()) }
            LargeButton("Repeat: ${s.repeat}", s.role == "host") { sync.settings(repeat = when (s.repeat) { "off" -> "all"; "all" -> "one"; else -> "off" }) }
            LargeButton("Retry audio") { sync.retryAudio() }
        }
        Text("UP NEXT · ${s.queue.size} TRACKS", color = Muted, fontSize = 14.sp)
        s.queue.forEachIndexed { i, track ->
            Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).background(if (i == s.index) Panel else Color.Transparent, RoundedCornerShape(12.dp)).clickable(enabled = s.canControl) { sync.command("select", index = i) }.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${i + 1}".padStart(2, '0'), color = if (i == s.index) Accent else Muted); Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) { Text(track.title, fontSize = 19.sp); Text(track.artist, color = Muted) }; Text(time(track.durationMs), color = Muted)
            }
        }
    }
}
@Composable private fun PlaybackControls(s: AppState, sync: SyncCoordinator, compact: Boolean = false) {
    val t = s.track ?: return
    if (!compact) Text(if (s.buffering) "BUFFERING" else if (s.connected && s.clockReady) "CONNECTED PLAYBACK" else "${s.role.uppercase()} PLAYBACK", color = Accent, fontSize = 13.sp)
    Text(t.title, fontSize = if (compact) 20.sp else 26.sp, fontWeight = FontWeight.Bold, maxLines = if (compact) 1 else 2, overflow = TextOverflow.Ellipsis)
    if (!compact) Text(t.artist, fontSize = 18.sp, color = Muted)
    var seeking by remember { mutableStateOf<Float?>(null) }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp), modifier = Modifier.padding(vertical = if (compact) 4.dp else 12.dp)) {
        IconButton({ sync.command("previous") }, Modifier.size(64.dp), enabled = s.canControl) { Icon(painterResource(R.drawable.ic_previous), "Previous track", Modifier.size(40.dp)) }
        FilledIconButton({ sync.command(if (s.requestedPlaying) "pause" else "play") }, Modifier.size(if (compact) 64.dp else 96.dp), enabled = s.canControl && !s.missing && s.serviceReady) { Icon(painterResource(if (s.requestedPlaying) R.drawable.ic_pause else R.drawable.ic_play), "Play or pause", Modifier.size(48.dp)) }
        IconButton({ sync.command("next") }, Modifier.size(64.dp), enabled = s.canControl) { Icon(painterResource(R.drawable.ic_next), "Next track", Modifier.size(40.dp)) }
    }
    if (!s.driving) Slider(value = seeking ?: s.positionMs.toFloat().coerceIn(0f, t.durationMs.toFloat()), onValueChange = { seeking = it }, onValueChangeFinished = { seeking?.let { sync.command("seek", it.toLong()) }; seeking = null }, valueRange = 0f..t.durationMs.toFloat().coerceAtLeast(1f), enabled = s.canControl, modifier = Modifier.height(if (compact) 32.dp else 56.dp))
    Text("${time(s.positionMs)} / ${time(t.durationMs)}", color = Muted)
    Text("Local volume", color = Muted)
    Slider(s.volume, { sync.volume(it) }, modifier = Modifier.height(56.dp))
    if (s.audioError.isNotBlank()) Text(s.audioError, color = Color(0xFFFFB19F))
    Text(if (s.role == "local") "On this device · no internet needed" else if (s.clockReady) "Player drift: ${s.driftMs?.let { "$it ms" } ?: "Measuring"} · RTT ${s.rttMs} ms" else "Matching clocks… playback will start when ready", color = Muted)
}
@Composable internal fun Artwork(path: String?, modifier: Modifier, thumbnail: Boolean = false) {
    val bitmap = remember(path) { path?.let {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }; BitmapFactory.decodeFile(it, bounds)
        val options = BitmapFactory.Options().apply { inSampleSize = (maxOf(bounds.outWidth, bounds.outHeight) / if (thumbnail) 96 else 512).coerceAtLeast(1) }
        BitmapFactory.decodeFile(it, options)
    } }
    Box(modifier.background(Brush.linearGradient(listOf(Color(0xFF425C42), Color(0xFF14222D), Color(0xFF263542))), RoundedCornerShape(28.dp)), contentAlignment = Alignment.Center) {
        if (bitmap != null) Image(bitmap.asImageBitmap(), "Album cover", Modifier.fillMaxSize())
        else Icon(painterResource(R.drawable.ic_wave), "Album artwork placeholder", Modifier.size(100.dp), tint = Accent)
    }
}
@Composable private fun TrackRow(track: Track, selected: Boolean, play: () -> Unit, select: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = play), shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = Panel)) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Artwork(track.artPath, Modifier.size(58.dp), true)
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) { Text(track.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 18.sp, fontWeight = FontWeight.SemiBold); Text(track.artist, maxLines = 1, color = Muted, fontSize = 14.sp); Text(time(track.durationMs), color = Muted, fontSize = 12.sp) }
            Checkbox(selected, { select() })
            IconButton(play, Modifier.size(48.dp)) { Icon(Icons.Default.PlayArrow, "Play ${track.title}", tint = Accent) }
        }
    }
}
@Composable private fun Title(title: String, subtitle: String) { Text(title, fontSize = 30.sp, fontWeight = FontWeight.Bold); Text(subtitle, color = Muted, fontSize = 18.sp) }
@Composable private fun LargeButton(text: String, enabled: Boolean = true, click: () -> Unit) { Button(click, enabled = enabled, shape = RoundedCornerShape(14.dp), modifier = Modifier.heightIn(min = 58.dp)) { Text(text, fontSize = 17.sp) } }
@Composable private fun Stat(label: String, value: String, modifier: Modifier) { Card(modifier) { Column(Modifier.padding(20.dp)) { Text(label, color = Muted, fontSize = 13.sp); Text(value, fontSize = 24.sp) } } }
@Composable private fun SettingToggle(title: String, checked: Boolean, detail: String, changed: (Boolean) -> Unit) { Card(Modifier.fillMaxWidth()) { Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text(title, fontSize = 21.sp); Text(detail, color = Muted) }; Switch(checked, changed, Modifier.padding(start = 12.dp)) } } }
@Composable private fun rememberSaveableCompat(value: String) = androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(value) }
private fun time(ms: Long): String = "%d:%02d".format(ms.coerceAtLeast(0) / 60000, ms.coerceAtLeast(0) / 1000 % 60)
private fun qrBitmap(text: String): Bitmap {
    val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 320, 320)
    val pixels = IntArray(320 * 320) { i -> if (matrix[i % 320, i / 320]) android.graphics.Color.BLACK else android.graphics.Color.WHITE }
    return Bitmap.createBitmap(pixels, 320, 320, Bitmap.Config.ARGB_8888)
}

