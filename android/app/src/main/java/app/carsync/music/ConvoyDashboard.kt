package app.carsync.music

import android.Manifest
import android.content.pm.PackageManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat

private val BoardBackground = Color(0xFF121921)
private val BoardPanel = Color(0xFF24303D)
private val BoardAccent = Color(0xFFB4F36D)
private val BoardMuted = Color(0xFFC0C9D3)
private val BoardWhite = Color(0xFFF5F8FB)

@Composable
fun ConvoyDashboard(
    state: AppState,
    onTalk: () -> Unit,
    onSession: () -> Unit,
    onMap: () -> Unit,
    onPlaylist: () -> Unit,
    onCars: () -> Unit,
    onPlayer: () -> Unit
) {
    val stats = ConvoyDashboardData.from(state)
    val speakerIsMe = state.memberId.isNotBlank() && state.speakerId == state.memberId
    val channelBusy = state.speakerId != null && !speakerIsMe
    val intercomReady = state.connected && state.supportsVoice
    val canRequest = intercomReady && (speakerIsMe || state.talkPending || state.speakerId == null)
    val track = state.track
    Column(
        modifier = Modifier.fillMaxWidth().background(BoardBackground, RoundedCornerShape(16.dp)).padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(9.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("♧", color = BoardAccent, fontSize = 28.sp)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text("CarSync Convoy", color = BoardWhite, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text(when {
                    state.connected -> "● Connected · session ${state.code}"
                    state.code.isNotBlank() -> "○ Reconnecting · attempt ${state.reconnectAttempt}"
                    else -> "○ Not connected · create or join a convoy"
                }, color = if (state.connected) BoardAccent else BoardMuted, fontSize = 12.sp, maxLines = 2)
                Text("Created by Brayo", color = BoardMuted, fontSize = 10.sp)
            }
            Surface(color = BoardPanel, shape = RoundedCornerShape(24.dp)) {
                Text(if (state.role.isNotBlank()) state.role.uppercase() else "OFFLINE",
                    color = BoardWhite, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp))
            }
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            ConvoyStat("${stats.onlineCars}", "Cars online", Modifier.weight(1f))
            ConvoyStat(stats.farthestLabel, "Farthest car", Modifier.weight(1f))
            ConvoyStat(when {
                !intercomReady -> "OFF"
                speakerIsMe -> "TALK"
                channelBusy -> "BUSY"
                state.talkPending -> "WAIT"
                else -> "LIVE"
            }, "Intercom", Modifier.weight(1f))
        }
        if (state.connected && stats.farthestMeters == null) {
            Text("Car distances appear when both cars share accurate live GPS.", color = BoardMuted, fontSize = 11.sp)
        }

        Column(Modifier.fillMaxWidth().background(BoardPanel, RoundedCornerShape(10.dp)).clickable { onPlayer() }.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("♫", color = BoardAccent, fontSize = 24.sp)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("Now Playing", color = BoardWhite, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        if (track == null) "No shared track yet · open Playlist" else "${track.title} · ${track.artist}",
                        color = BoardMuted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Icon(Icons.Default.PlayArrow, contentDescription = "Open player", tint = BoardMuted)
            }
            val progress = if (track?.durationMs != null && track.durationMs > 0L)
                (state.positionMs.toFloat() / track.durationMs.toFloat()).coerceIn(0f, 1f) else 0f
            Box(Modifier.fillMaxWidth().height(5.dp).background(Color(0xFF4C5762), RoundedCornerShape(10.dp))) {
                if (progress > 0f) Box(
                    Modifier.fillMaxWidth(progress).fillMaxHeight()
                        .background(BoardAccent, RoundedCornerShape(10.dp))
                )
            }
            if (track != null && state.missing) Text("Media unavailable on this car · import or request sharing.", color = Color(0xFFFFC68D), fontSize = 11.sp)
        }

        Button(
            onClick = { if (speakerIsMe || state.talkPending) onTalk() else if (!channelBusy && intercomReady) onTalk() },
            enabled = canRequest,
            modifier = Modifier.fillMaxWidth().heightIn(min = 100.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = BoardAccent, contentColor = Color(0xFF14200F),
                disabledContainerColor = Color(0xFF526644), disabledContentColor = Color(0xFFD9E1D5)),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(if (speakerIsMe) "◉" else "🎙", fontSize = 27.sp)
                Text(when {
                    speakerIsMe -> "STOP TALKING"
                    state.talkPending -> "CANCEL TALK REQUEST"
                    channelBusy -> "LISTENING"
                    else -> "PUSH TO TALK"
                }, fontSize = 17.sp, fontWeight = FontWeight.Medium)
                Text(when {
                    speakerIsMe -> "You are broadcasting to the convoy"
                    state.talkPending -> "Waiting for the talk channel"
                    channelBusy -> "Another car is speaking"
                    !state.connected -> "Join a convoy to activate"
                    !state.supportsVoice -> "Server requires an intercom upgrade"
                    else -> "Broadcast to the convoy · guest and host"
                }, fontSize = 11.sp)
            }
        }
        if (!state.connected) {
            OutlinedButton(onClick = onSession, modifier = Modifier.fillMaxWidth()) {
                Text("Create or join a convoy", color = BoardWhite)
            }
        }
        if (intercomReady && !speakerIsMe) {
            Text(if (channelBusy) "Listening automatically · wait until the channel is free." else "Listening automatically · microphone access is needed only to talk.",
                color = BoardMuted, fontSize = 11.sp)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            DashboardShortcut("▧", "Live Map", Modifier.weight(1f), !state.driving, onMap)
            DashboardShortcut("☷", "Playlist", Modifier.weight(1f), !state.driving, onPlaylist)
            DashboardShortcut("♙", "Cars", Modifier.weight(1f), true, onCars)
        }
    }
}

@Composable
private fun ConvoyStat(value: String, subtitle: String, modifier: Modifier = Modifier) {
    Column(modifier.background(BoardPanel, RoundedCornerShape(10.dp)).heightIn(min = 57.dp).padding(horizontal = 4.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text(value, color = BoardWhite, fontWeight = FontWeight.Bold, fontSize = 17.sp,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(subtitle, color = BoardWhite, fontSize = 11.sp, maxLines = 1)
    }
}

@Composable
private fun DashboardShortcut(symbol: String, text: String, modifier: Modifier = Modifier, enabled: Boolean = true, action: () -> Unit) {
    Column(modifier.background(BoardPanel, RoundedCornerShape(9.dp)).clickable(enabled = enabled, onClick = action)
        .heightIn(min = 64.dp).padding(horizontal = 3.dp, vertical = 11.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.SpaceEvenly) {
        Text(symbol, color = if (enabled) BoardWhite else BoardMuted, fontSize = 20.sp)
        Text(text, color = if (enabled) BoardWhite else BoardMuted, fontSize = 12.sp, maxLines = 1)
    }
}
