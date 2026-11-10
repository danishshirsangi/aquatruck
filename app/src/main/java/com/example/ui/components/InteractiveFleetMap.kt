package com.example.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.GpsFixed
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.LocalShipping
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.CachePolicy
import coil.request.ImageRequest
import com.example.data.model.LocationPoint
import com.example.data.model.StopRecord
import com.example.data.model.TruckEntity
import com.example.data.model.VoiceDeliveryNote
import com.example.ui.theme.CyanWater
import com.example.ui.theme.OceanNavyPrimary
import com.example.ui.theme.StatusActiveGreen
import com.example.ui.theme.StatusAmberWarning
import com.example.ui.theme.StatusOffDutyGrey
import com.example.ui.theme.StatusRedAlert
import com.example.util.LocationUtils
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.tan

enum class MapTileStyle(val displayName: String, val urlPattern: String) {
    OPEN_STREET_MAP("Street Map", "https://tile.openstreetmap.org/{z}/{x}/{y}.png"),
    OSM_DETAILED("Road Network", "https://a.tile.openstreetmap.fr/osmfr/{z}/{x}/{y}.png"),
    TOPO("Terrain & Roads", "https://a.tile.opentopomap.org/{z}/{x}/{y}.png")
}

@Composable
fun InteractiveFleetMap(
    trucks: List<TruckEntity>,
    selectedTruckId: Int?,
    locations: List<LocationPoint>,
    stops: List<StopRecord>,
    modifier: Modifier = Modifier,
    voiceNotes: List<VoiceDeliveryNote> = emptyList(),
    selectedDateMillis: Long = System.currentTimeMillis(),
    onDateSelected: ((Long) -> Unit)? = null,
    selectedHistoryDays: Int = 7,
    onHistoryDaysChanged: ((Int) -> Unit)? = null,
    onTruckSelected: ((Int?) -> Unit)? = null,
    onPlayVoiceNote: ((String) -> Unit)? = null,
    isPlaying: Boolean = false,
    currentlyPlayingPath: String? = null
) {
    val context = LocalContext.current
    val density = LocalDensity.current

    // Zoom level: 12.0 (city overview) to 18.0 (high detail streets & alleys)
    var zoomLevel by remember { mutableFloatStateOf(15.2f) }
    var panOffsetX by remember { mutableFloatStateOf(0f) }
    var panOffsetY by remember { mutableFloatStateOf(0f) }

    var mapStyle by remember { mutableStateOf(MapTileStyle.OPEN_STREET_MAP) }
    var showStylePicker by remember { mutableStateOf(false) }

    var selectedStopRecord by remember { mutableStateOf<StopRecord?>(null) }
    var showDatePickerDialog by remember { mutableStateOf(false) }

    // Fallback device location if no truck has reported coordinates yet
    var deviceLat by remember { mutableDoubleStateOf(0.0) }
    var deviceLng by remember { mutableDoubleStateOf(0.0) }

    LaunchedEffect(Unit) {
        val loc = LocationUtils.getCurrentLocation(context)
        if (loc != null && (loc.latitude != 0.0 || loc.longitude != 0.0)) {
            deviceLat = loc.latitude
            deviceLng = loc.longitude
        }
    }

    // Pulse animation for active live vehicle
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseRadius by infiniteTransition.animateFloat(
        initialValue = 14f,
        targetValue = 38f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "pulseRadius"
    )
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.85f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "pulseAlpha"
    )

    // Filter points based on selected truck
    val filteredLocations = if (selectedTruckId != null) {
        locations.filter { it.truckId == selectedTruckId }
    } else {
        locations
    }

    val filteredStops = if (selectedTruckId != null) {
        stops.filter { it.truckId == selectedTruckId }
    } else {
        stops
    }

    val filteredVoiceNotes = if (selectedTruckId != null) {
        voiceNotes.filter { it.truckId == selectedTruckId }
    } else {
        voiceNotes
    }

    // Identify all valid GPS coordinates
    val validTruckCoords = trucks.filter { it.currentLat != 0.0 && it.currentLng != 0.0 }
    val validPointCoords = filteredLocations.filter { it.latitude != 0.0 && it.longitude != 0.0 }
    val validVoiceCoords = filteredVoiceNotes.filter { it.latitude != 0.0 && it.longitude != 0.0 }

    val hasFleetCoordinates = validTruckCoords.isNotEmpty() || validPointCoords.isNotEmpty() || validVoiceCoords.isNotEmpty()

    // Determine geographic center of map
    val centerLat: Double
    val centerLng: Double

    if (hasFleetCoordinates) {
        val targetTruck = if (selectedTruckId != null) {
            trucks.firstOrNull { it.id == selectedTruckId && it.currentLat != 0.0 }
        } else null

        if (targetTruck != null) {
            centerLat = targetTruck.currentLat
            centerLng = targetTruck.currentLng
        } else if (selectedTruckId == null && validTruckCoords.isNotEmpty()) {
            val minLat = validTruckCoords.minOf { it.currentLat }
            val maxLat = validTruckCoords.maxOf { it.currentLat }
            val minLng = validTruckCoords.minOf { it.currentLng }
            val maxLng = validTruckCoords.maxOf { it.currentLng }
            centerLat = (minLat + maxLat) / 2.0
            centerLng = (minLng + maxLng) / 2.0
        } else {
            val lastPoint = validPointCoords.lastOrNull()
            centerLat = lastPoint?.latitude ?: 0.0
            centerLng = lastPoint?.longitude ?: 0.0
        }
    } else if (deviceLat != 0.0 && deviceLng != 0.0) {
        centerLat = deviceLat
        centerLng = deviceLng
    } else {
        // Default city coordinates fallback until location arrives
        centerLat = 20.5937
        centerLng = 78.9629
    }

    // Mercator Projection Math: converts (lat, lng) to world pixel coordinate at fractional zoom
    fun latLngToWorldPixel(lat: Double, lng: Double, zoom: Float): Offset {
        val scale = 256.0 * 2.0.pow(zoom.toDouble())
        val x = ((lng + 180.0) / 360.0) * scale
        val latRad = Math.toRadians(lat.coerceIn(-85.05112878, 85.05112878))
        val y = (1.0 - ln(tan(Math.PI / 4.0 + latRad / 2.0)) / Math.PI) / 2.0 * scale
        return Offset(x.toFloat(), y.toFloat())
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ ->
                    if (zoom.isFinite() && zoom > 0f) {
                        zoomLevel = (zoomLevel * zoom).coerceIn(11f, 18f)
                    }
                    panOffsetX += pan.x
                    panOffsetY += pan.y
                }
            }
            .testTag("interactive_fleet_map")
    ) {
        val width = constraints.maxWidth.toFloat()
        val height = constraints.maxHeight.toFloat()

        val fitZoomLevel = run {
            if (validTruckCoords.isEmpty()) {
                15.2f
            } else {
                val minLat = validTruckCoords.minOf { it.currentLat }
                val maxLat = validTruckCoords.maxOf { it.currentLat }
                val minLng = validTruckCoords.minOf { it.currentLng }
                val maxLng = validTruckCoords.maxOf { it.currentLng }
                val midLatRadians = Math.toRadians((minLat + maxLat) / 2.0)
                val longitudeSpan = (maxLng - minLng) * kotlin.math.cos(midLatRadians).coerceAtLeast(0.15)
                val latitudeSpan = maxLat - minLat
                val safeWidth = (width * 0.78f).coerceAtLeast(1f)
                val safeHeight = (height * 0.78f).coerceAtLeast(1f)
                val safeLonSpan = longitudeSpan.coerceAtLeast(0.002)
                val safeLatSpan = latitudeSpan.coerceAtLeast(0.002)
                val zoomForLongitude = kotlin.math.ln((360.0 * 256.0) / (safeLonSpan * safeWidth)) / kotlin.math.ln(2.0)
                val zoomForLatitude = kotlin.math.ln((360.0 * 256.0) / (safeLatSpan * safeHeight)) / kotlin.math.ln(2.0)
                kotlin.math.min(zoomForLongitude, zoomForLatitude).toFloat().coerceIn(10.5f, 17.5f)
            }
        }
        val fleetMarkerKey = validTruckCoords.map { it.id }.sorted().joinToString(",")

        LaunchedEffect(selectedTruckId, fleetMarkerKey) {
            if (validTruckCoords.isNotEmpty()) {
                zoomLevel = if (selectedTruckId == null) fitZoomLevel else 15.5f
                panOffsetX = 0f
                panOffsetY = 0f
            }
        }

        val cx = width / 2f
        val cy = height / 2f

        val centerWorldPixel = latLngToWorldPixel(centerLat, centerLng, zoomLevel)

        // Maps any (lat, lng) to actual screen pixel position
        fun projectToScreen(lat: Double, lng: Double): Offset {
            if (lat == 0.0 && lng == 0.0) return Offset(-9999f, -9999f)
            val world = latLngToWorldPixel(lat, lng, zoomLevel)
            val screenX = cx + (world.x - centerWorldPixel.x) + panOffsetX
            val screenY = cy + (world.y - centerWorldPixel.y) + panOffsetY
            return Offset(screenX, screenY)
        }

        // ================= TILE GRID LAYER (REAL ROADS, STREETS, BUILDINGS) =================
        val intZoom = zoomLevel.toInt().coerceIn(10, 18)
        val zoomFraction = zoomLevel - intZoom
        val scaleMultiplier = 2.0.pow(zoomFraction.toDouble()).toFloat()
        val tileSize = (256f * scaleMultiplier)
        val numTilesAtZoom = 1 shl intZoom

        // Screen bounding box in world coordinates
        val minWorldX = centerWorldPixel.x - cx - panOffsetX
        val maxWorldX = centerWorldPixel.x + (width - cx) - panOffsetX
        val minWorldY = centerWorldPixel.y - cy - panOffsetY
        val maxWorldY = centerWorldPixel.y + (height - cy) - panOffsetY

        val minTileX = (minWorldX / tileSize).toInt()
        val maxTileX = (maxWorldX / tileSize).toInt()
        val minTileY = (minWorldY / tileSize).toInt()
        val maxTileY = (maxWorldY / tileSize).toInt()

        val startX = minTileX.coerceIn(0, numTilesAtZoom - 1)
        val endX = maxTileX.coerceIn(0, numTilesAtZoom - 1)
        val startY = minTileY.coerceIn(0, numTilesAtZoom - 1)
        val endY = maxTileY.coerceIn(0, numTilesAtZoom - 1)

        val tileWidthDp = with(density) { tileSize.toDp() }
        val tileHeightDp = with(density) { tileSize.toDp() }

        // Render OpenStreetMap / Carto Street Tiles
        Box(modifier = Modifier.fillMaxSize()) {
            for (tx in startX..endX) {
                for (ty in startY..endY) {
                    val tileWorldX = tx * tileSize
                    val tileWorldY = ty * tileSize

                    val screenX = (cx + (tileWorldX - centerWorldPixel.x) + panOffsetX).roundToInt()
                    val screenY = (cy + (tileWorldY - centerWorldPixel.y) + panOffsetY).roundToInt()

                    val tileUrl = mapStyle.urlPattern
                        .replace("{z}", intZoom.toString())
                        .replace("{x}", tx.toString())
                        .replace("{y}", ty.toString())

                    AsyncImage(
                        model = ImageRequest.Builder(context)
                            .data(tileUrl)
                            .setHeader("User-Agent", "AquaTrack/1.0 (Water Transport Fleet)")
                            .crossfade(true)
                            .diskCachePolicy(CachePolicy.ENABLED)
                            .memoryCachePolicy(CachePolicy.ENABLED)
                            .build(),
                        contentDescription = null,
                        contentScale = ContentScale.FillBounds,
                        modifier = Modifier
                            .size(tileWidthDp, tileHeightDp)
                            .offset { IntOffset(screenX, screenY) }
                    )
                }
            }
        }

        // ================= VECTOR OVERLAY CANVAS (ROUTES, TRUCKS, STOPS, PINS) =================
        Canvas(modifier = Modifier.fillMaxSize()) {
            // 1. Draw 7-Day Route History Polylines along the actual streets
            val pointsByTruck = filteredLocations
                .filter { it.latitude != 0.0 && it.longitude != 0.0 }
                .groupBy { it.truckId }

            pointsByTruck.forEach { (truckId, pts) ->
                if (pts.size >= 2) {
                    val color = when (truckId % 4) {
                        0 -> CyanWater
                        1 -> StatusActiveGreen
                        2 -> StatusAmberWarning
                        else -> Color(0xFF6366F1)
                    }

                    // Outer soft glow on roads
                    val routePath = Path()
                    val p0 = projectToScreen(pts.first().latitude, pts.first().longitude)
                    routePath.moveTo(p0.x, p0.y)

                    for (i in 1 until pts.size) {
                        val pt = projectToScreen(pts[i].latitude, pts[i].longitude)
                        routePath.lineTo(pt.x, pt.y)
                    }

                    drawPath(
                        path = routePath,
                        color = color.copy(alpha = 0.35f),
                        style = Stroke(width = 12f, cap = StrokeCap.Round)
                    )

                    // Core high-contrast line on real road
                    drawPath(
                        path = routePath,
                        color = color,
                        style = Stroke(width = 6f, cap = StrokeCap.Round)
                    )

                    // Breadcrumb dots (every 5-10s ping)
                    pts.forEachIndexed { index, pt ->
                        val pos = projectToScreen(pt.latitude, pt.longitude)
                        drawCircle(color = Color.White, radius = 3.5f, center = pos)
                        if (index == 0) {
                            // Trip Start Flag marker
                            drawCircle(color = StatusActiveGreen, radius = 8f, center = pos)
                            drawCircle(color = Color.White, radius = 4f, center = pos)
                        }
                    }
                }
            }

            // 2. Draw Stop Records with Highlighters (Auto-pinned & Audio attached)
            filteredStops.forEach { stop ->
                if (stop.latitude != 0.0 && stop.longitude != 0.0) {
                    val pos = projectToScreen(stop.latitude, stop.longitude)
                    val isAuto = stop.category == "Auto Drop Point"
                    val isSelected = selectedStopRecord?.id == stop.id
                    val associatedVoice = filteredVoiceNotes.firstOrNull { note ->
                        (note.truckId == stop.truckId && kotlin.math.abs(note.timestamp - stop.startTime) <= 600000L) ||
                        (kotlin.math.abs(note.latitude - stop.latitude) < 0.0015 && kotlin.math.abs(note.longitude - stop.longitude) < 0.0015)
                    }
                    val hasAudio = associatedVoice != null

                    // Highlighter: Selected halo
                    if (isSelected) {
                        drawCircle(color = CyanWater, radius = 28f, style = Stroke(width = 4f), center = pos)
                        drawCircle(color = CyanWater.copy(alpha = 0.25f), radius = 34f, center = pos)
                    }

                    // Highlighter: Audio Attached
                    if (hasAudio) {
                        drawCircle(color = CyanWater.copy(alpha = 0.45f), radius = 22f, center = pos)
                        drawCircle(color = CyanWater, radius = 17f, style = Stroke(width = 3.5f), center = pos)
                    }

                    // Highlighter: Auto Drop Point
                    if (isAuto) {
                        drawCircle(color = StatusAmberWarning.copy(alpha = 0.4f), radius = 24f, center = pos)
                        drawCircle(color = StatusAmberWarning, radius = 15f, style = Stroke(width = 2.5f), center = pos)
                    }

                    // Core marker
                    drawCircle(color = Color.White, radius = 12f, center = pos)
                    val coreColor = when {
                        hasAudio -> CyanWater
                        isAuto -> StatusAmberWarning
                        else -> OceanNavyPrimary
                    }
                    drawCircle(color = coreColor, radius = 9f, center = pos)
                    drawCircle(color = Color.White, radius = 3.5f, center = pos)
                }
            }

            // 3. Draw Voice Delivery Note Pins (Microphone Pins)
            filteredVoiceNotes.forEach { note ->
                if (note.latitude != 0.0 && note.longitude != 0.0) {
                    val pos = projectToScreen(note.latitude, note.longitude)
                    // Halo ring
                    drawCircle(color = CyanWater.copy(alpha = 0.35f), radius = 22f, center = pos)
                    // Pin outer ring
                    drawCircle(color = OceanNavyPrimary, radius = 15f, center = pos)
                    // Cyan center
                    drawCircle(color = CyanWater, radius = 12f, center = pos)
                    // Inner white dot
                    drawCircle(color = Color.White, radius = 5f, center = pos)
                }
            }

            // 4. Draw Live Truck Markers with Pulsing Radar
            trucks.forEach { truck ->
                if (truck.currentLat != 0.0 && truck.currentLng != 0.0) {
                    val pos = projectToScreen(truck.currentLat, truck.currentLng)
                    val isSelected = (selectedTruckId == null || selectedTruckId == truck.id)
                    val truckColor = if (truck.isShiftActive) StatusActiveGreen else StatusOffDutyGrey

                    if (truck.isShiftActive) {
                        drawCircle(
                            color = truckColor.copy(alpha = pulseAlpha),
                            radius = pulseRadius,
                            center = pos
                        )
                    }

                    // Outer white border
                    drawCircle(
                        color = if (isSelected) Color.White else Color(0xFF64748B),
                        radius = 20f,
                        center = pos
                    )
                    // Main badge
                    drawCircle(
                        color = truckColor,
                        radius = 16f,
                        center = pos
                    )
                    // Vehicle core center
                    drawCircle(
                        color = Color.White,
                        radius = 6f,
                        center = pos
                    )
                }
            }
        }

        // ================= CLICKABLE DROP POINT TOUCH OVERLAYS =================
        filteredStops.forEach { stop ->
            if (stop.latitude != 0.0 && stop.longitude != 0.0) {
                val pos = projectToScreen(stop.latitude, stop.longitude)
                if (pos.x in -60f..(width + 60f) && pos.y in -60f..(height + 60f)) {
                    val isAuto = stop.category == "Auto Drop Point"
                    Box(
                        modifier = Modifier
                            .offset {
                                IntOffset(
                                    (pos.x - with(density) { 24.dp.toPx() }).roundToInt(),
                                    (pos.y - with(density) { 24.dp.toPx() }).roundToInt()
                                )
                            }
                            .size(48.dp)
                            .clickable {
                                selectedStopRecord = stop
                            }
                            .testTag("clickable_drop_point_${stop.id}")
                    )
                }
            }
        }

        // ================= EMPTY STATE BANNER (IF AWAITING INITIAL GPS FIX) =================
        if (!hasFleetCoordinates) {
            Surface(
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(20.dp),
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
                shadowElevation = 8.dp
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Surface(
                        shape = CircleShape,
                        color = CyanWater.copy(alpha = 0.15f),
                        modifier = Modifier.size(54.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.Navigation, contentDescription = null, tint = CyanWater, modifier = Modifier.size(28.dp))
                        }
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = "Street Map Ready",
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleMedium,
                        color = OceanNavyPrimary
                    )
                    Text(
                        text = "Surrounding roads and city network loaded. Start a driver shift to track live truck movement every 10s.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
        }

        // ================= TOP HEADER CONTROLS =================
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 10.dp, start = 12.dp, end = 12.dp)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Live Status & Map Style Bar
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
                shadowElevation = 6.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .background(
                                    if (validTruckCoords.any { it.isShiftActive }) StatusActiveGreen else StatusAmberWarning,
                                    CircleShape
                                )
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (validTruckCoords.any { it.isShiftActive }) "LIVE STREET TRACKING (10s)" else "STREET ROADS & 7-DAY TRAIL",
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 11.sp,
                            color = OceanNavyPrimary,
                            letterSpacing = 0.5.sp
                        )
                    }

                    // Map Style Button
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = CyanWater.copy(alpha = 0.15f),
                        modifier = Modifier.clickable { showStylePicker = !showStylePicker }
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Icon(Icons.Default.Layers, contentDescription = null, tint = CyanWater, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(mapStyle.displayName, color = OceanNavyPrimary, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                        }
                    }
                }
            }

            // Map Style Selector Dropdown Card
            if (showStylePicker) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surface,
                    shadowElevation = 10.dp,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Select Map Style", fontWeight = FontWeight.Bold, fontSize = 12.sp, color = OceanNavyPrimary)
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            MapTileStyle.values().forEach { style ->
                                FilterChip(
                                    selected = mapStyle == style,
                                    onClick = {
                                        mapStyle = style
                                        showStylePicker = false
                                    },
                                    label = { Text(style.displayName, fontSize = 11.sp) },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = OceanNavyPrimary,
                                        selectedLabelColor = Color.White
                                    )
                                )
                            }
                        }
                    }
                }
            }

            // Fleet Filter & 7-Day History Chips
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
                shadowElevation = 6.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                    // Truck Filter
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.LocalShipping, contentDescription = null, tint = CyanWater, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Truck:", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            FilterChip(
                                selected = selectedTruckId == null,
                                onClick = { onTruckSelected?.invoke(null) },
                                label = { Text("All", fontSize = 11.sp) },
                                modifier = Modifier.height(28.dp),
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = OceanNavyPrimary,
                                    selectedLabelColor = Color.White
                                )
                            )
                            trucks.forEach { truck ->
                                FilterChip(
                                    selected = selectedTruckId == truck.id,
                                    onClick = { onTruckSelected?.invoke(truck.id) },
                                    label = { Text("T#${truck.id}", fontSize = 11.sp) },
                                    modifier = Modifier.height(28.dp),
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = OceanNavyPrimary,
                                        selectedLabelColor = Color.White
                                    )
                                )
                            }
                        }
                    }

                    // Date Selection Filter for Ride History
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.CalendarMonth, contentDescription = null, tint = CyanWater, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            val dateLabel = SimpleDateFormat("MMM d, yyyy", Locale.getDefault()).format(Date(selectedDateMillis))
                            Text(dateLabel, fontWeight = FontWeight.Bold, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface)
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            val todayCal = java.util.Calendar.getInstance().apply {
                                set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0); set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
                            }
                            val todayStart = todayCal.timeInMillis
                            val yesterdayStart = todayStart - 86400000L
                            val isToday = selectedDateMillis >= todayStart
                            val isYesterday = !isToday && selectedDateMillis >= yesterdayStart

                            FilterChip(
                                selected = isToday,
                                onClick = { onDateSelected?.invoke(System.currentTimeMillis()) },
                                label = { Text("Today", fontSize = 11.sp) },
                                modifier = Modifier.height(28.dp),
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = CyanWater,
                                    selectedLabelColor = Color.White
                                )
                            )

                            FilterChip(
                                selected = isYesterday,
                                onClick = { onDateSelected?.invoke(yesterdayStart + 3600000L) },
                                label = { Text("Yesterday", fontSize = 11.sp) },
                                modifier = Modifier.height(28.dp),
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = CyanWater,
                                    selectedLabelColor = Color.White
                                )
                            )

                            FilterChip(
                                selected = !isToday && !isYesterday,
                                onClick = { showDatePickerDialog = true },
                                label = { Text("📅 Pick Date", fontSize = 11.sp) },
                                modifier = Modifier.height(28.dp),
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = CyanWater,
                                    selectedLabelColor = Color.White
                                )
                            )
                        }
                    }
                }
            }
        }

        // ================= MAP ZOOM & RECENTER CONTROLS =================
        Column(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Re-center on live vehicles
            IconButton(
                onClick = {
                    panOffsetX = 0f
                    panOffsetY = 0f
                    zoomLevel = if (selectedTruckId == null) fitZoomLevel else 15.5f
                },
                modifier = Modifier
                    .size(42.dp)
                    .background(OceanNavyPrimary, CircleShape)
            ) {
                Icon(Icons.Default.MyLocation, contentDescription = "Center on Fleet", tint = Color.White)
            }

            // Zoom In to street/building level
            IconButton(
                onClick = { zoomLevel = (zoomLevel + 0.8f).coerceAtMost(18.0f) },
                modifier = Modifier
                    .size(42.dp)
                    .background(Color.White, CircleShape)
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
            ) {
                Icon(Icons.Default.Add, contentDescription = "Zoom In", tint = OceanNavyPrimary)
            }

            // Zoom Out to city level
            IconButton(
                onClick = { zoomLevel = (zoomLevel - 0.8f).coerceAtLeast(11.0f) },
                modifier = Modifier
                    .size(42.dp)
                    .background(Color.White, CircleShape)
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
            ) {
                Icon(Icons.Default.Remove, contentDescription = "Zoom Out", tint = OceanNavyPrimary)
            }

            // Reset View
            IconButton(
                onClick = {
                    zoomLevel = 15.0f
                    panOffsetX = 0f
                    panOffsetY = 0f
                },
                modifier = Modifier
                    .size(42.dp)
                    .background(Color.White, CircleShape)
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
            ) {
                Icon(Icons.Default.Refresh, contentDescription = "Reset View", tint = OceanNavyPrimary)
            }
        }

        // ================= CLICKED DROP POINT INSPECTION SHEET =================
        if (selectedStopRecord != null) {
            val stop = selectedStopRecord!!
            val isAuto = stop.category == "Auto Drop Point"
            val associatedVoice = filteredVoiceNotes.firstOrNull { note ->
                (note.truckId == stop.truckId && kotlin.math.abs(note.timestamp - stop.startTime) <= 600000L) ||
                (kotlin.math.abs(note.latitude - stop.latitude) < 0.0015 && kotlin.math.abs(note.longitude - stop.longitude) < 0.0015)
            }
            val hasAudio = associatedVoice != null
            val isPlayingThis = (associatedVoice != null && currentlyPlayingPath == associatedVoice.audioFilePath && isPlaying)

            Surface(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 12.dp, start = 12.dp, end = 12.dp)
                    .fillMaxWidth()
                    .testTag("drop_point_inspection_sheet"),
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surface,
                shadowElevation = 10.dp,
                border = androidx.compose.foundation.BorderStroke(
                    1.5.dp,
                    if (hasAudio) CyanWater else if (isAuto) StatusAmberWarning else MaterialTheme.colorScheme.outline
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            if (isAuto) {
                                Surface(shape = RoundedCornerShape(8.dp), color = StatusAmberWarning.copy(alpha = 0.2f)) {
                                    Row(modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Default.FlashOn, contentDescription = null, tint = StatusAmberWarning, modifier = Modifier.size(14.dp))
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("AUTO PINNED (100m/2m)", color = StatusAmberWarning, fontWeight = FontWeight.ExtraBold, fontSize = 10.sp)
                                    }
                                }
                            }
                            if (hasAudio) {
                                Surface(shape = RoundedCornerShape(8.dp), color = CyanWater.copy(alpha = 0.2f)) {
                                    Row(modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Default.Mic, contentDescription = null, tint = CyanWater, modifier = Modifier.size(14.dp))
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("AUDIO ATTACHED", color = CyanWater, fontWeight = FontWeight.ExtraBold, fontSize = 10.sp)
                                    }
                                }
                            }
                            if (!isAuto && !hasAudio) {
                                Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                                    Text("DROP POINT", modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }

                        IconButton(onClick = { selectedStopRecord = null }, modifier = Modifier.size(28.dp)) {
                            Icon(Icons.Default.Close, contentDescription = "Close", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = stop.addressOrLabel,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )

                    val timeFmt = SimpleDateFormat("hh:mm a", Locale.getDefault())
                    val startStr = timeFmt.format(Date(stop.startTime))
                    val endStr = timeFmt.format(Date(stop.endTime))
                    Text(
                        text = "Halted for ${stop.durationMinutes} mins ($startStr - $endStr) • Tanker #${stop.truckId}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    if (associatedVoice != null) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Button(
                            onClick = { onPlayVoiceNote?.invoke(associatedVoice.audioFilePath) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(46.dp)
                                .testTag("play_drop_point_voice_button"),
                            colors = ButtonDefaults.buttonColors(containerColor = if (isPlayingThis) StatusRedAlert else CyanWater),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(
                                imageVector = if (isPlayingThis) Icons.Default.Pause else Icons.Default.PlayArrow,
                                contentDescription = "Play Voice Note",
                                tint = Color.White,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = if (isPlayingThis) "PAUSE DELIVERY AUDIO (${associatedVoice.durationSeconds}s)" else "LISTEN TO DELIVERY AUDIO (${associatedVoice.durationSeconds}s)",
                                fontWeight = FontWeight.ExtraBold,
                                color = Color.White,
                                fontSize = 12.sp
                            )
                        }
                    }
                }
            }
        } else if (filteredVoiceNotes.isNotEmpty()) {
            val latestVoice = filteredVoiceNotes.firstOrNull()
            if (latestVoice != null) {
                Surface(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 12.dp, start = 12.dp, end = 12.dp)
                        .fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.surface,
                    shadowElevation = 8.dp
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f)
                        ) {
                            Surface(
                                shape = CircleShape,
                                color = CyanWater.copy(alpha = 0.2f),
                                modifier = Modifier.size(42.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(Icons.Default.Mic, contentDescription = null, tint = CyanWater, modifier = Modifier.size(22.dp))
                                }
                            }
                            Spacer(modifier = Modifier.width(10.dp))
                            Column {
                                Text(
                                    text = "Latest Delivery: Truck #${latestVoice.truckId}",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = latestVoice.locationLabel,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1
                                )
                            }
                        }

                        IconButton(
                            onClick = { onPlayVoiceNote?.invoke(latestVoice.audioFilePath) },
                            modifier = Modifier
                                .size(42.dp)
                                .background(CyanWater, CircleShape)
                        ) {
                            Icon(Icons.Default.PlayArrow, contentDescription = "Play Audio", tint = Color.White)
                        }
                    }
                }
            }
        }
    }

    // Android Native DatePickerDialog
    if (showDatePickerDialog) {
        val cal = java.util.Calendar.getInstance().apply { timeInMillis = selectedDateMillis }
        android.app.DatePickerDialog(
            context,
            { _, year, month, dayOfMonth ->
                val newCal = java.util.Calendar.getInstance().apply {
                    set(java.util.Calendar.YEAR, year)
                    set(java.util.Calendar.MONTH, month)
                    set(java.util.Calendar.DAY_OF_MONTH, dayOfMonth)
                    set(java.util.Calendar.HOUR_OF_DAY, 12)
                }
                onDateSelected?.invoke(newCal.timeInMillis)
                showDatePickerDialog = false
            },
            cal.get(java.util.Calendar.YEAR),
            cal.get(java.util.Calendar.MONTH),
            cal.get(java.util.Calendar.DAY_OF_MONTH)
        ).apply {
            setOnDismissListener { showDatePickerDialog = false }
            show()
        }
    }
}
