package app.carsync.music

import android.Manifest
import android.content.pm.PackageManager
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker

private val MapAccent = Color(0xFFB4F36D)
private val MapMuted = Color(0xFFB8C3CF)

@Composable
fun LiveConvoyMap(
    state: AppState,
    sync: SyncCoordinator,
    onCars: () -> Unit,
    onHome: () -> Unit,
    requestLocation: () -> Unit
) {
    val context = LocalContext.current
    val now = System.currentTimeMillis()
    val positioned = if (state.connected) state.cars.filter {
        it.online && it.location?.usable(now) == true
    } else emptyList()
    val self = positioned.firstOrNull { it.memberId == state.memberId }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("LIVE CONVOY MAP", color = MapAccent, style = MaterialTheme.typography.titleLarge)
        Text("Only online cars that chose to share accurate, recent GPS locations appear. Locations are not public.", color = MapMuted, fontSize = 13.sp)
        Text("${positioned.size} of ${state.cars.count { it.online }} online cars sharing usable GPS",
            color = MapAccent, fontSize = 14.sp)

        if (!state.connected) {
            Text("Join a convoy to see cars on the live map.", color = MapMuted)
            OutlinedButton(onClick = onHome) { Text("Back to convoy Home") }
            return@Column
        }

        if (!state.supportsRadar) {
            Text("This server cannot share convoy GPS positions. Connect to the matching convoy backend.", color = Color(0xFFFFC68D))
            OutlinedButton(onClick = onHome) { Text("Back to Home") }
            return@Column
        }

        if (!state.radarEnabled) {
            Button(onClick = {
                val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                    ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
                if (granted) sync.toggleRadar(true) else requestLocation()
            }) { Text("Share my live position with convoy") }
        } else {
            OutlinedButton(onClick = { sync.toggleRadar(false) }) { Text("Stop sharing my location") }
        }

        if (state.driving || state.vehicleRestricted) {
            Text("Interactive map is not available while driving. Use a passenger or stop safely.", color = Color(0xFFFFC68D))
            return@Column
        }

        if (positioned.isEmpty()) {
            Text("Waiting for GPS fixes. Other cars must enable location sharing individually; this can take a few seconds.", color = MapMuted)
        } else {
            // MapView uses the ordinary public OpenStreetMap tile service only for this small beta.
            // Replace with an approved paid tile provider before scaling convoy traffic.
            val map = remember(context) {
                Configuration.getInstance().userAgentValue = "CarSyncMusic/1.8 (app.carsync.music)"
                MapView(context).apply {
                    setTileSource(TileSourceFactory.MAPNIK)
                    setMultiTouchControls(true)
                    controller.setZoom(12.5)
                }
            }
            val session = remember(state.code) { mutableSetOf<String>() }
            AndroidView(factory = { map }, modifier = Modifier.fillMaxWidth().height(360.dp),
                update = { view ->
                    val previous = view.overlays.filterIsInstance<Marker>()
                    view.overlays.removeAll(previous.toSet())
                    positioned.forEach { car ->
                        car.location?.let { pos ->
                            val marker = Marker(view)
                            marker.position = GeoPoint(pos.latitude, pos.longitude)
                            marker.title = "${car.name} · ${car.role}"
                            marker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                            view.overlays.add(marker)
                        }
                    }
                    if (session.add("initial")) {
                        val anchor = self?.location ?: positioned.first().location
                        if (anchor != null) view.controller.setCenter(GeoPoint(anchor.latitude, anchor.longitude))
                    }
                    view.invalidate()
                })
            DisposableEffect(map) {
                map.onResume()
                onDispose { map.onPause(); map.onDetach() }
            }
            Text("Map: © OpenStreetMap contributors. Straight-line distance is not road distance. GPS accuracy and mobile coverage affect updates.",
                color = MapMuted, fontSize = 11.sp)
            OutlinedButton(onClick = {
                val own = self?.location ?: positioned.firstOrNull()?.location
                if (own != null) map.controller.animateTo(GeoPoint(own.latitude, own.longitude))
            }) { Text("Center map on convoy") }
        }
        OutlinedButton(onClick = onCars) { Text("View cars and separation details") }
    }
}
