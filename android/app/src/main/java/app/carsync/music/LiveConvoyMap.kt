package app.carsync.music

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.BitmapDrawable
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private val MapAccent = Color(0xFFB4F36D)
private val MapMuted = Color(0xFFB8C3CF)
private val MapPanel = Color(0xFF24303D)
private val MapWarning = Color(0xFFFFC68D)

/** Map pins are real opt-in GPS fixes, never inferred from session codes or cell networks. */
@Composable
fun LiveConvoyMap(
    state: AppState,
    sync: SyncCoordinator,
    onCars: () -> Unit,
    onHome: () -> Unit,
    requestLocation: () -> Unit
) {
    val context = LocalContext.current
    var referenceChoice by remember(state.code) { mutableStateOf<String?>(null) }
    val now by produceState(System.currentTimeMillis(), state.code) {
        while (true) {
            value = System.currentTimeMillis()
            delay(3000)
        }
    }
    val snapshot = ConvoyMapData.build(state, referenceChoice, now)
    val located = snapshot.cars.filter { it.usableLocation != null }
    val reference = located.firstOrNull { it.car.memberId == snapshot.referenceId }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("LIVE CONVOY MAP", color = MapAccent, style = MaterialTheme.typography.titleLarge)
        Text("Every member is listed below. Car locations appear only when that car opts in to GPS sharing.",
            color = MapMuted, fontSize = 13.sp)
        Text("${snapshot.locatedCount}/${snapshot.onlineCount} online cars on map · ${snapshot.cars.size} registered",
            color = MapAccent, fontSize = 14.sp)

        if (!state.connected) {
            Text("Join a convoy to see members and their positions.", color = MapMuted)
            OutlinedButton(onClick = onHome) { Text("Back to convoy Home") }
            return@Column
        }
        if (!state.supportsRadar) {
            Text("This session server does not support GPS sharing. Update the convoy server.",
                color = MapWarning)
            OutlinedButton(onClick = onHome) { Text("Back to Home") }
            return@Column
        }

        if (!state.radarEnabled) {
            Button(onClick = {
                val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                    ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
                if (granted) sync.toggleRadar(true) else requestLocation()
            }) { Text("Share my position with the convoy") }
            Text("GPS sharing is OFF on this phone. Enable it on each car whose location you want to display.",
                color = MapWarning, fontSize = 12.sp)
        } else {
            OutlinedButton(onClick = { sync.toggleRadar(false) }) { Text("Stop sharing my location") }
            val ownCar = snapshot.cars.firstOrNull { it.car.memberId == state.memberId }
            if (ownCar?.usableLocation == null) {
                Text("GPS sharing is ON, but no accurate recent position is available. Check device Location settings, allow precise location, or move to a clear view of the sky. An Android head unit without GPS may need a paired phone with location capability.",
                    color = MapWarning, fontSize = 12.sp)
            } else {
                Text("Your position is visible to this convoy · GPS updated ${((now - ownCar.usableLocation.receivedAtMs).coerceAtLeast(0L) / 1000L)} seconds ago.",
                    color = MapAccent, fontSize = 12.sp)
            }
        }

        if (state.driving || state.vehicleRestricted) {
            Text("Interactive map is disabled while driving. Stop safely or let a passenger operate it.", color = MapWarning)
            return@Column
        }

        if (located.isEmpty()) {
            Text("No shared GPS positions are available yet. Connected cars appear in the roster below, but map pins require a recent accurate fix from each participant.",
                color = MapWarning)
        } else {
            val map = remember(context, state.code) {
                Configuration.getInstance().userAgentValue = "CarSyncMusic/1.8 (app.carsync.music)"
                MapView(context).apply {
                    setTileSource(TileSourceFactory.MAPNIK)
                    setMultiTouchControls(true)
                    controller.setZoom(13.0)
                }
            }
            val fittedIds = remember(map) { mutableSetOf<String>() }
            AndroidView(
                factory = { map },
                modifier = Modifier.fillMaxWidth().height(330.dp),
                update = { view ->
                    view.overlays.removeAll { it is Marker || it is Polyline }
                    val base = reference?.usableLocation
                    if (base != null && located.size <= 15) {
                        for (entry in located) {
                            val target = entry.usableLocation ?: continue
                            if (entry.car.memberId == snapshot.referenceId) continue
                            val polyline = Polyline(view).apply {
                                setPoints(listOf(GeoPoint(base.latitude, base.longitude),
                                    GeoPoint(target.latitude, target.longitude)))
                                outlinePaint.color = android.graphics.Color.argb(130, 180, 243, 109)
                                outlinePaint.strokeWidth = 2.5f * context.resources.displayMetrics.density
                            }
                            view.overlays.add(polyline)
                        }
                    }
                    for (entry in located) {
                        val position = entry.usableLocation ?: continue
                        val isSelected = entry.car.memberId == snapshot.referenceId
                        val isSelf = entry.car.memberId == state.memberId
                        val display = entry.car.name.take(16) +
                            if (isSelected) " · Selected" else " · " +
                                (entry.distanceFromReferenceMeters?.let(::formatMapDistance) ?: "—")
                        val marker = Marker(view).apply {
                            this.position = GeoPoint(position.latitude, position.longitude)
                            title = entry.car.name + " · " + entry.car.role
                            snippet = if (isSelected) "Distance reference" else
                                "From ${snapshot.referenceName}: " +
                                    (entry.distanceFromReferenceMeters?.let(::formatMapDistance) ?: "Unavailable")
                            icon = makeCarLabel(context, display, isSelected, isSelf, entry.car.role == "host")
                            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                            setOnMarkerClickListener { _, _ ->
                                referenceChoice = entry.car.memberId
                                true
                            }
                        }
                        view.overlays.add(marker)
                    }
                    val visibleIds = located.map { it.car.memberId }.toSet()
                    if (visibleIds != fittedIds) {
                        fittedIds.clear()
                        fittedIds.addAll(visibleIds)
                        fitAllCars(view, located)
                    }
                    view.invalidate()
                }
            )
            DisposableEffect(map) {
                map.onResume()
                onDispose { map.onPause(); map.onDetach() }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { fitAllCars(map, located) }) { Text("Fit all cars") }
                OutlinedButton(onClick = {
                    val target = reference?.usableLocation
                    if (target != null)
                        map.controller.animateTo(GeoPoint(target.latitude, target.longitude))
                }, enabled = reference != null) { Text("Center selected") }
            }
            Text("Map © OpenStreetMap contributors. Map tiles need internet access. If pins appear over a blank background, check internet and tile-server access. Distances are straight-line estimates, not driving routes.",
                color = MapMuted, fontSize = 11.sp)
        }

        Text("DISTANCES BETWEEN CARS", color = MapAccent, style = MaterialTheme.typography.titleMedium)
        Text("Tap any car with live GPS to measure its separation from every other car. Currently measuring from ${snapshot.referenceName ?: "no available car"}.",
            color = MapMuted, fontSize = 12.sp)
        snapshot.cars.forEach { entry ->
            val selected = entry.car.memberId == snapshot.referenceId
            val usable = entry.usableLocation != null
            Column(
                modifier = Modifier.fillMaxWidth()
                    .background(if (selected) Color(0xFF314A39) else MapPanel, RoundedCornerShape(10.dp))
                    .clickable(enabled = usable) { referenceChoice = entry.car.memberId }
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (entry.car.online) "●" else "○",
                        color = if (entry.car.online) MapAccent else MapMuted)
                    Spacer(Modifier.width(8.dp))
                    Text(entry.car.name, color = Color.White, modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyLarge)
                    Text(if (selected) "REFERENCE" else
                        (entry.distanceFromReferenceMeters?.let(::formatMapDistance) ?: "—"),
                        color = if (usable) MapAccent else MapMuted,
                        fontSize = 13.sp)
                }
                Text("${entry.car.role.uppercase()} · ${entry.locationStatus}",
                    color = if (usable) MapMuted else MapWarning, fontSize = 12.sp)
                val point = entry.usableLocation
                if (point != null) {
                    Text("GPS: ${formatCoordinates(point)} · accuracy ±${point.accuracyMeters.roundToInt()} m · ${(now - point.receivedAtMs).coerceAtLeast(0L) / 1000L}s ago",
                        color = MapMuted, fontSize = 11.sp)
                } else if (entry.car.online) {
                    Text("Location unavailable: ask this car to opt in; do not infer its position.",
                        color = MapMuted, fontSize = 11.sp)
                }
            }
        }
        OutlinedButton(onClick = onCars) { Text("View detailed car connections") }
        Text("Live location is shared only with convoy participants. Public map tiles require internet access and send map-area requests to the tile provider.",
            color = MapMuted, fontSize = 11.sp)
    }
}

private fun formatMapDistance(meters: Double): String =
    ConvoyGeometry.label(meters).removeSuffix(" away")

private fun formatCoordinates(pos: ConvoyPosition) =
    String.format(Locale.US, "%.5f, %.5f", pos.latitude, pos.longitude)

/** Center all currently positioned cars; preserve the user's pan/zoom during normal GPS refreshes. */
private fun fitAllCars(map: MapView, entries: List<ConvoyMapCar>) {
    val locations = entries.mapNotNull { it.usableLocation }
    if (locations.isEmpty()) return
    map.post {
        if (locations.size == 1) {
            val point = locations.first()
            map.controller.setZoom(14.0)
            map.controller.animateTo(GeoPoint(point.latitude, point.longitude))
        } else {
            val n = locations.maxOf { it.latitude }
            val s = locations.minOf { it.latitude }
            val e = locations.maxOf { it.longitude }
            val w = locations.minOf { it.longitude }
            val latPad = max(0.002, (n - s) * 0.14)
            val lonPad = max(0.002, (e - w) * 0.14)
            map.zoomToBoundingBox(BoundingBox(
                min(85.0, n + latPad), min(180.0, e + lonPad),
                max(-85.0, s - latPad), max(-180.0, w - lonPad)), true, 70)
        }
    }
}

/** Readable names and separations are drawn directly on pins, without requiring a tap. */
private fun makeCarLabel(context: Context, text: String, selected: Boolean, own: Boolean, host: Boolean): BitmapDrawable {
    val d = context.resources.displayMetrics.density
    val font = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.WHITE
        textSize = 12f * d
        typeface = android.graphics.Typeface.DEFAULT_BOLD
    }
    val width = (font.measureText(text) + 32f * d).roundToInt().coerceAtLeast(72)
    val height = (35f * d).roundToInt().coerceAtLeast(30)
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = when {
            selected -> android.graphics.Color.rgb(40, 83, 52)
            own -> android.graphics.Color.rgb(38, 65, 96)
            else -> android.graphics.Color.rgb(31, 42, 56)
        }
    }
    canvas.drawRoundRect(RectF(0f, 0f, width.toFloat(), height.toFloat()), 10*d, 10*d, bg)
    val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = when {
            selected || host -> android.graphics.Color.rgb(180, 243, 109)
            own -> android.graphics.Color.rgb(114, 187, 248)
            else -> android.graphics.Color.rgb(236, 241, 246)
        }
    }
    canvas.drawCircle(11f*d, height/2f, 4f*d, dot)
    canvas.drawText(text, 21f*d, height/2f + font.textSize*0.34f, font)
    return BitmapDrawable(context.resources, bitmap)
}
