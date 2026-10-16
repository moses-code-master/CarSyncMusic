package app.carsync.music

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.ActivityInfo
import android.os.Build
import android.view.LayoutInflater
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay

private val VideoAccent = Color(0xFFB4F36D)

@Composable fun VideoLibraryScreen(s: AppState, sync: SyncCoordinator, onPlayer: () -> Unit) {
    val context = LocalContext.current
    var query by remember { mutableStateOf("") }; var selected by remember { mutableStateOf(listOf<String>()) }
    var name by remember { mutableStateOf("Video playlist") }; var editId by remember { mutableStateOf(0L) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { sync.importFiles(it, "video") }
    val fallback = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val clip = result.data?.clipData
            sync.importFiles(if (clip != null) (0 until clip.itemCount).map { clip.getItemAt(it).uri } else listOfNotNull(result.data?.data), "video")
        }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { if (it) sync.scanVideos() else sync.message("Allow video access, or import with a file picker.") }
    Text("Your videos", fontSize = 30.sp)
    Text("Parked viewing only · by Brayo", color = VideoAccent)
    Text("MP4, MKV and WebM depend on device codecs. For two screens, import the identical file on both; videos are not uploaded to the free server.")
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = { launchPickerSafely({ picker.launch(arrayOf("video/*", "application/x-matroska")) }, {
            fallback.launch(Intent(Intent.ACTION_GET_CONTENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*").putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("video/*", "application/x-matroska")).putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        }) { sync.message("No video picker. Use Scan videos, or FileManager → Open with / Share → CarSync Music.") } }, enabled = !s.busy, modifier = Modifier.heightIn(min = 56.dp)) { Text("Import videos") }
        Button(onClick = {
            val p = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_VIDEO else Manifest.permission.READ_EXTERNAL_STORAGE
            if (ContextCompat.checkSelfPermission(context, p) == android.content.pm.PackageManager.PERMISSION_GRANTED) sync.scanVideos()
            else launchPickerSafely({ permission.launch(p) }) { sync.message("Cannot open media permission dialog. Import from FileManager instead.") }
        }, enabled = !s.busy, modifier = Modifier.heightIn(min = 56.dp)) { Text("Scan videos") }
        if (s.role == "local") OutlinedButton(onClick = { sync.leave() }, modifier = Modifier.heightIn(min = 56.dp)) { Text("Clear local playback") }
    }
    OutlinedTextField(query, { query = it }, label = { Text("Search videos") }, modifier = Modifier.fillMaxWidth())
    val videos = s.library.filter { it.mediaType == "video" && it.title.contains(query, true) }
    if (videos.isEmpty()) Text("No matching videos. Import a file or scan device storage.")
    LazyColumn(Modifier.fillMaxWidth().height(320.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        items(videos, key = { it.hash }) { t ->
            Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Artwork(t.artPath, Modifier.size(64.dp), true)
                    Column(Modifier.weight(1f).padding(start = 12.dp)) {
                        Text(t.title, fontSize = 18.sp)
                        Text("${videoTime(t.durationMs)} · ${t.width}×${t.height} · ${"%.1f".format(t.sizeBytes / 1048576.0)} MB", fontSize = 13.sp)
                    }
                    Checkbox(t.hash in selected, { selected = if (t.hash in selected) selected - t.hash else (selected + t.hash).take(500) })
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { sync.playTrack(t.hash); onPlayer() }, enabled = s.role != "guest") { Text("Play video") }
                    TextButton(onClick = { sync.removeVideo(t); selected = selected - t.hash }) { Text("Remove record") }
                }
            } }
        }
    }
    Text("Video playlists", fontSize = 24.sp)
    s.playlists.filter { it.mediaType == "video" }.forEach { p ->
        val hashes = p.hashes.split(',').filter(String::isNotBlank)
        Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(14.dp)) {
            Text("${p.name} · ${hashes.size} videos", fontSize = 18.sp)
            Row(Modifier.horizontalScroll(rememberScrollState())) {
                TextButton({ name = p.name; selected = hashes; editId = p.id }) { Text("Edit / rename") }
                TextButton({ sync.listenLocal(hashes); onPlayer() }, enabled = s.code.isBlank() && hashes.isNotEmpty()) { Text("Listen locally") }
                TextButton({ sync.publish(hashes); onPlayer() }, enabled = s.role == "host" && hashes.isNotEmpty()) { Text("Publish videos") }
                TextButton({ sync.deletePlaylist(p) }) { Text("Delete playlist") }
            }
        } }
    }
    OutlinedTextField(name, { name = it }, label = { Text("Video playlist name") }, modifier = Modifier.fillMaxWidth())
    Text("Selected order · ${selected.size} videos")
    selected.forEachIndexed { index, hash -> Row(verticalAlignment = Alignment.CenterVertically) {
        Text(s.library.find { it.hash == hash }?.title ?: "Missing video", Modifier.weight(1f))
        TextButton({ if (index > 0) selected = selected.toMutableList().apply { val item = removeAt(index); add(index - 1, item) } }) { Text("↑") }
        TextButton({ if (index < selected.lastIndex) selected = selected.toMutableList().apply { val item = removeAt(index); add(index + 1, item) } }) { Text("↓") }
        TextButton({ selected = selected.filterIndexed { i, _ -> i != index } }) { Text("Remove") }
    } }
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button({ sync.savePlaylist(name, selected, editId, "video"); editId = 0 }, enabled = selected.isNotEmpty()) { Text("Save video playlist") }
        Button({ sync.listenLocal(selected); onPlayer() }, enabled = selected.isNotEmpty() && s.code.isBlank()) { Text("Play selected locally") }
        Button({ sync.publish(selected); onPlayer() }, enabled = selected.isNotEmpty() && s.role == "host") { Text("Publish selected videos") }
        OutlinedButton({ selected = emptyList(); editId = 0; name = "Video playlist" }) { Text("New playlist") }
    }
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable private fun VideoSurface(sync: SyncCoordinator, modifier: Modifier, onTap: () -> Unit = {}) {
    AndroidView(factory = { context -> (LayoutInflater.from(context).inflate(R.layout.video_surface, null) as PlayerView).apply { useController = false; resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT } },
        update = { it.player = sync.renderingPlayer(); it.keepScreenOn = true; it.setOnClickListener { onTap() } }, onRelease = { it.player = null; it.keepScreenOn = false }, modifier = modifier)
}

@Composable fun VideoPanel(s: AppState, sync: SyncCoordinator) {
    var fullscreen by remember { mutableStateOf(false) }
    LaunchedEffect(s.showVideo) { if (!s.showVideo) fullscreen = false }
    Text(s.track?.title.orEmpty(), fontSize = 24.sp)
    Text("${s.role.uppercase()} · ${if (s.connected) "connected" else "local / reconnecting"} · ${if (s.buffering) "buffering" else "player drift ${s.driftMs?.let { "$it ms" } ?: "—"}"}", fontSize = 13.sp, color = VideoAccent)
    if (!s.showVideo) {
        Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(if (s.driving) "Picture blocked in Driving Mode" else if (s.automotive && (!s.vehicleKnown || s.vehicleRestricted)) "Vehicle parking restrictions block video" else if (s.audioOnlyVideo) "Audio-only video playback" else "Confirm parked before showing video", fontSize = 20.sp)
            Text("This display is for stationary use. Generic head units do not provide reliable motion detection. Confirmation does not detect movement. Audio can continue with the picture hidden on supported devices.")
            Button({ sync.confirmParked() }, enabled = !s.driving && !s.vehicleRestricted && (!s.automotive || s.vehicleKnown)) { Text("Confirm parked & show picture") }
        } }
    } else if (!fullscreen) VideoSurface(sync, Modifier.fillMaxWidth().height(220.dp).background(Color.Black))
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Switch(s.audioOnlyVideo, { sync.audioOnlyVideo(it) }); Text("Audio only")
        Button({ fullscreen = true }, enabled = s.showVideo) { Text("Fullscreen") }
        if (s.showVideo) TextButton({ sync.revokeParked() }) { Text("Hide picture") }
    }
    if (s.missing) Text(s.message, color = Color(0xFFFFB19F))
    if (fullscreen) Dialog(onDismissRequest = { fullscreen = false }, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        val activity = LocalContext.current as? Activity
        val window = (LocalView.current.parent as? DialogWindowProvider)?.window
        DisposableEffect(activity, window) {
            val previous = activity?.requestedOrientation
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            window?.let { WindowCompat.getInsetsController(it, it.decorView).apply { systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE; hide(WindowInsetsCompat.Type.systemBars()) } }
            onDispose { if (previous != null) activity?.requestedOrientation = previous; window?.let { WindowCompat.getInsetsController(it, it.decorView).show(WindowInsetsCompat.Type.systemBars()) } }
        }
        var controls by remember { mutableStateOf(true) }
        var seek by remember { mutableStateOf<Float?>(null) }
        LaunchedEffect(controls, s.playing) { if (controls && s.playing) { delay(5000); controls = false } }
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            if (s.showVideo) VideoSurface(sync, Modifier.fillMaxSize()) { controls = !controls }
            if (controls) Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color(0xCC0C1017)).padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) { Text(s.track?.title.orEmpty(), Modifier.weight(1f), color = Color.White); TextButton({ fullscreen = false }) { Text("Exit fullscreen") } }
                Text("${s.role} · ${if (s.buffering) "Buffering" else "${videoTime(s.positionMs)} / ${videoTime(s.track?.durationMs ?: 0)}"} · Drift ${s.driftMs ?: 0} ms", color = VideoAccent)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button({ sync.command("previous") }, enabled = s.canControl, modifier = Modifier.heightIn(min = 56.dp)) { Text("Previous") }
                    Button({ sync.command("seek", (s.positionMs - 10000).coerceAtLeast(0)) }, enabled = s.canControl, modifier = Modifier.heightIn(min = 56.dp)) { Text("−10s") }
                    Button({ sync.command(if (s.requestedPlaying) "pause" else "play") }, enabled = s.canControl && !s.missing, modifier = Modifier.heightIn(min = 56.dp)) { Text(if (s.requestedPlaying) "Pause" else "Play") }
                    Button({ sync.command("seek", s.positionMs + 10000) }, enabled = s.canControl, modifier = Modifier.heightIn(min = 56.dp)) { Text("+10s") }
                    Button({ sync.command("next") }, enabled = s.canControl, modifier = Modifier.heightIn(min = 56.dp)) { Text("Next") }
                }
                Slider(seek ?: s.positionMs.toFloat().coerceIn(0f, (s.track?.durationMs ?: 1).toFloat()), { seek = it }, onValueChangeFinished = { seek?.let { sync.command("seek", it.toLong()) }; seek = null }, valueRange = 0f..(s.track?.durationMs ?: 1).toFloat().coerceAtLeast(1f), enabled = s.canControl)
            }
        }
    }
}

private fun videoTime(ms: Long) = "%d:%02d".format(ms / 60000, ms / 1000 % 60)

