package app.shadey.ui

import android.Manifest
import android.content.pm.PackageManager
import android.location.LocationManager
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.core.content.ContextCompat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.shadey.core.model.Sunlight
import app.shadey.core.model.SpotSource
import app.shadey.core.rank.SpotSunInfo
import java.time.Instant
import java.time.ZoneId
import kotlin.math.roundToInt

@Composable
fun MapScreen(vm: ShadeyViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val zone = remember { vm.zone() }
    var showSettings by remember { mutableStateOf(false) }
    var showCities by remember { mutableStateOf(false) }
    val context = LocalContext.current

    fun getAndMoveToLocation() {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        @Suppress("MissingPermission")
        val loc = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
        if (loc != null) vm.moveTo(app.shadey.core.model.LatLng(loc.latitude, loc.longitude))
    }

    val locationPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) getAndMoveToLocation() }

    Box(Modifier.fillMaxSize()) {
        ShadeyMapLayer(state, vm)

        // Title + data-source status.
        Surface(
            modifier = Modifier.align(Alignment.TopStart).statusBarsPadding().padding(12.dp),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
            tonalElevation = 3.dp,
            shadowElevation = 3.dp,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            ) {
                Icon(
                    Icons.Filled.WbSunny,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Column {
                    Text("Shadey", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    Text(
                        state.sourceLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    )
                }
                if (state.busy) {
                    Spacer(Modifier.width(8.dp))
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                }
            }
        }

        // Top-right button column: settings + locate-me
        Column(
            modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
                tonalElevation = 3.dp,
                shadowElevation = 3.dp,
            ) {
                IconButton(onClick = { showSettings = true }) {
                    Icon(Icons.Filled.Settings, "Settings")
                }
            }
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
                tonalElevation = 3.dp,
                shadowElevation = 3.dp,
            ) {
                IconButton(onClick = { showCities = true }) {
                    Icon(Icons.Filled.Public, "Cities")
                }
            }
            FloatingActionButton(
                onClick = {
                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
                        == PackageManager.PERMISSION_GRANTED
                    ) {
                        getAndMoveToLocation()
                    } else {
                        locationPermLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                    }
                },
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.primary,
            ) {
                Icon(Icons.Filled.MyLocation, "My location")
            }
        }

        // Bottom control panel — collapsed by default, expandable to show spots.
        var spotsExpanded by rememberSaveable { mutableStateOf(false) }
        Surface(
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
            shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 4.dp,
            shadowElevation = 12.dp,
        ) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 16.dp)) {
                // Drag handle — tap to toggle spots list
                Box(
                    Modifier.fillMaxWidth().clickable { spotsExpanded = !spotsExpanded },
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        Modifier
                            .padding(vertical = 10.dp)
                            .size(width = 36.dp, height = 4.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f))
                    )
                }

                state.dropped?.let { pin ->
                    DroppedCard(pin, zone, onSave = vm::saveDropped, onDismiss = vm::clearDropped)
                }
                state.selected?.let { info ->
                    SelectedCard(
                        info,
                        zone,
                        onRemove = { vm.removeSpot(info.spot.id); vm.selectSpot(null) },
                        onDismiss = { vm.selectSpot(null) },
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        formatTime(state.timeMinutes),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        state.date.toString(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    )
                    Spacer(Modifier.weight(1f))
                    if (!state.isNow) {
                        TextButton(onClick = vm::resetToNow) {
                            Icon(Icons.Filled.Schedule, null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Now")
                        }
                    }
                }
                Slider(
                    value = state.timeMinutes.toFloat(),
                    onValueChange = { vm.setTime(it.roundToInt()) },
                    valueRange = 0f..1439f,
                )

                if (spotsExpanded) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Sunniest spots",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(4.dp))
                    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 260.dp)) {
                        items(state.ranked, key = { it.spot.id }) { info ->
                            SpotRow(
                                info,
                                zone,
                                selected = info.spot.id == state.selectedId,
                                onClick = {
                                    vm.selectSpot(info.spot.id)
                                    vm.moveTo(app.shadey.core.model.LatLng(info.spot.lat, info.spot.lng))
                                },
                            )
                        }
                    }
                } else if (state.ranked.isNotEmpty()) {
                    // Compact summary row when collapsed
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { spotsExpanded = true }
                            .padding(vertical = 6.dp),
                    ) {
                        val top = state.ranked.first()
                        Box(
                            Modifier.size(10.dp).clip(CircleShape)
                                .background(Color(android.graphics.Color.parseColor(
                                    app.shadey.map.GeoJsonWriter.colorFor(top.sunlight)
                                )))
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            top.spot.name,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            "${state.ranked.size} spots ›",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                        )
                    }
                }
            }
        }

        if (showSettings) {
            SettingsDialog(onDismiss = { showSettings = false })
        }
        if (showCities) {
            CitiesDialog(state, vm, onDismiss = { showCities = false })
        }
    }
}

@Composable
private fun CitiesDialog(state: ShadeyUiState, vm: ShadeyViewModel, onDismiss: () -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        title = { Text("Cities") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    "Download a city's buildings once — it then works offline and instantly.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                )
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        singleLine = true,
                        placeholder = { Text("Search a city") },
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = { vm.searchCities(query) }, enabled = !state.cityBusy && query.isNotBlank()) {
                        Icon(Icons.Filled.Search, "Search")
                    }
                }

                if (state.cityBusy) {
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text(state.cityStatus ?: "Working…", style = MaterialTheme.typography.bodySmall)
                    }
                } else state.cityStatus?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }

                if (state.citySearch.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    Text("Results", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                    state.citySearch.forEach { hit ->
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                                .clickable(enabled = !state.cityBusy) { vm.downloadCity(hit) }
                                .padding(vertical = 10.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Filled.Public, null, modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(10.dp))
                            Text(hit.name, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                            Text("Download", style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }

                if (state.cachedCities.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    Text("Downloaded", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                    state.cachedCities.forEach { c ->
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                                .clickable { vm.useCity(c.slug); onDismiss() }
                                .padding(vertical = 10.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(c.name, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                            Text("${c.buildingCount}", style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                        }
                    }
                }
            }
        },
    )
}

@Composable
private fun ShadeyMapLayer(state: ShadeyUiState, vm: ShadeyViewModel) {
    app.shadey.map.ShadeyMap(
        initialTarget = vm.initialTarget,
        shadowsGeoJson = state.shadowsGeoJson,
        spotsGeoJson = state.spotsGeoJson,
        pinGeoJson = state.pinGeoJson,
        cameraTarget = state.cameraTarget,
        onMapClick = vm::onMapClick,
        onCameraIdle = vm::onCameraIdle,
        onBuildingsQueried = vm::onBuildingsQueried,
        onCameraTargetConsumed = vm::onCameraTargetConsumed,
        modifier = Modifier.fillMaxSize(),
    )
}

@Composable
private fun SpotRow(info: SpotSunInfo, zone: ZoneId, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .background(
                if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f) else Color.Transparent,
            )
            .padding(horizontal = 6.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Dot(info.sunlight)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(info.spot.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
            Text(
                statusLine(info, zone),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
            )
        }
    }
}

@Composable
private fun DroppedCard(
    pin: DroppedPin,
    zone: ZoneId,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember(pin) { mutableStateOf("") }
    Card(Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Dot(pin.info?.sunlight)
                Spacer(Modifier.width(8.dp))
                Text("Dropped pin", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, "Dismiss") }
            }
            pin.info?.let { info ->
                Text(statusLine(info, zone), style = MaterialTheme.typography.bodyMedium)
                Text(sunDetail(info), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    placeholder = { Text("Name this spot") },
                    modifier = Modifier.weight(1f),
                )
                Button(onClick = { onSave(name) }) {
                    Icon(Icons.Filled.Add, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Save")
                }
            }
        }
    }
}

@Composable
private fun SelectedCard(
    info: SpotSunInfo,
    zone: ZoneId,
    onRemove: () -> Unit,
    onDismiss: () -> Unit,
) {
    Card(Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Dot(info.sunlight)
                Spacer(Modifier.width(8.dp))
                Text(info.spot.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, "Close") }
            }
            Text(statusLine(info, zone), style = MaterialTheme.typography.bodyMedium)
            if (info.spot.description.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(info.spot.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
            }
            Text(sunDetail(info), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
            if (info.spot.source == SpotSource.USER) {
                TextButton(onClick = onRemove) { Text("Remove spot") }
            }
        }
    }
}

@Composable
private fun SettingsDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
        title = { Text("Settings") },
        text = {
            Column {
                Text(
                    "Berlin is built in. Use the globe button to search and download any other " +
                        "city's buildings for offline, instant shade.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Map data © OpenStreetMap contributors (ODbL).",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )
            }
        },
    )
}

@Composable
private fun Dot(sunlight: Sunlight?) {
    Box(
        Modifier
            .size(14.dp)
            .clip(CircleShape)
            .background(sunlight?.let(::sunlightColor) ?: Color.Gray),
    )
}

private fun sunlightColor(s: Sunlight): Color = when (s) {
    Sunlight.SUN -> Color(0xFFF5A623)
    Sunlight.SHADE -> Color(0xFF5B6B7B)
    Sunlight.NIGHT -> Color(0xFF3A3F4B)
}

private fun formatTime(minutes: Int): String = "%02d:%02d".format(minutes / 60, minutes % 60)

private fun formatInstant(instant: Instant, zone: ZoneId): String {
    val t = instant.atZone(zone).toLocalTime()
    return "%02d:%02d".format(t.hour, t.minute)
}

private fun statusLine(info: SpotSunInfo, zone: ZoneId): String = when (info.sunlight) {
    Sunlight.SUN -> info.nextChange?.let { "In the sun · shade at ${formatInstant(it, zone)}" } ?: "In the sun"
    Sunlight.SHADE -> info.nextChange?.let { "In shade · sun at ${formatInstant(it, zone)}" } ?: "In shade"
    Sunlight.NIGHT -> "After dark"
}

private fun sunDetail(info: SpotSunInfo): String =
    "Sun ${info.solar.elevationDeg.roundToInt()}° elevation · ${info.solar.azimuthDeg.roundToInt()}° azimuth"
