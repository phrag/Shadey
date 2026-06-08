package app.shadey.ui

import android.Manifest
import android.content.pm.PackageManager
import android.location.LocationManager
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
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
import androidx.compose.material3.BottomSheetScaffold
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberBottomSheetScaffoldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.shadey.core.model.Sunlight
import app.shadey.core.model.SpotSource
import app.shadey.core.rank.SpotSunInfo
import app.shadey.data.CityHit
import app.shadey.data.Geocoder
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapScreen(vm: ShadeyViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val zone = remember { vm.zone() }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var showSettings by remember { mutableStateOf(false) }
    var showCities by remember { mutableStateOf(false) }
    val sheetState = rememberBottomSheetScaffoldState()

    // Search state — lives entirely in the UI layer
    var searchActive by rememberSaveable { mutableStateOf(false) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var searchResults by remember { mutableStateOf<List<CityHit>>(emptyList()) }
    var searchBusy by remember { mutableStateOf(false) }

    LaunchedEffect(state.promptCity) {
        if (state.promptCity) { showCities = true; vm.dismissCityPrompt() }
    }

    // Expand the sheet when a pin is dropped or a spot is selected so the card is visible.
    LaunchedEffect(state.dropped, state.selected) {
        if (state.dropped != null || state.selected != null) {
            sheetState.bottomSheetState.expand()
        }
    }

    // Debounced search-as-you-type, biased toward the current map viewport
    LaunchedEffect(searchQuery) {
        if (searchQuery.length >= 2) {
            delay(400)
            searchBusy = true
            val viewbox = vm.mapViewbox()
            searchResults = runCatching { Geocoder.search(searchQuery, viewbox) }.getOrDefault(emptyList())
            searchBusy = false
        } else {
            searchResults = emptyList()
        }
    }

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

    BottomSheetScaffold(
        scaffoldState = sheetState,
        sheetPeekHeight = 190.dp,
        sheetShape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
        sheetTonalElevation = 4.dp,
        sheetShadowElevation = 12.dp,
        sheetDragHandle = {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Box(
                    Modifier.padding(vertical = 10.dp)
                        .size(width = 36.dp, height = 4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f))
                )
            }
        },
        sheetContent = {
            Column(
                Modifier.fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 8.dp)
                    .navigationBarsPadding()
            ) {
                // Dropped pin / selected spot card (shown when relevant)
                state.dropped?.let { pin ->
                    DroppedCard(pin, zone, onSave = vm::saveDropped, onDismiss = vm::clearDropped)
                }
                state.selected?.let { info ->
                    SelectedCard(
                        info, zone,
                        onRemove = { vm.removeSpot(info.spot.id); vm.selectSpot(null) },
                        onDismiss = { vm.selectSpot(null) },
                    )
                }

                // Time + date row
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
                            Icon(Icons.Filled.Schedule, null, Modifier.size(18.dp))
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

                val isExpanded = sheetState.bottomSheetState.currentValue == SheetValue.Expanded

                if (isExpanded) {
                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Spots",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = { vm.dropPinAtCenter(); }) {
                            Icon(Icons.Filled.Add, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Add spot here")
                        }
                    }
                    Spacer(Modifier.height(2.dp))
                    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 320.dp)) {
                        items(state.ranked, key = { it.spot.id }) { info ->
                            SpotRow(
                                info, zone,
                                selected = info.spot.id == state.selectedId,
                                onClick = {
                                    vm.selectSpot(info.spot.id)
                                    vm.moveTo(app.shadey.core.model.LatLng(info.spot.lat, info.spot.lng))
                                },
                            )
                        }
                    }
                } else if (state.ranked.isNotEmpty()) {
                    // Compact summary row — swipe up or tap to expand
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { scope.launch { sheetState.bottomSheetState.expand() } }
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
        },
    ) { _ ->
        // Map fills the whole screen regardless of sheet scaffold padding
        Box(Modifier.fillMaxSize()) {
            ShadeyMapLayer(state, vm)

            // Search overlay (when active, replaces the title pill)
            if (searchActive) {
                Surface(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .statusBarsPadding()
                        .padding(horizontal = 8.dp, vertical = 8.dp)
                        .fillMaxWidth(),
                    shape = RoundedCornerShape(28.dp),
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 4.dp,
                    shadowElevation = 4.dp,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (searchBusy) {
                            CircularProgressIndicator(
                                Modifier.padding(start = 14.dp).size(20.dp),
                                strokeWidth = 2.dp,
                            )
                        } else {
                            Icon(
                                Icons.Filled.Search,
                                null,
                                Modifier.padding(start = 14.dp).size(20.dp),
                                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                            )
                        }
                        TextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            singleLine = true,
                            placeholder = { Text("Search places…") },
                            modifier = Modifier.weight(1f),
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent,
                                focusedIndicatorColor = Color.Transparent,
                                unfocusedIndicatorColor = Color.Transparent,
                            ),
                        )
                        IconButton(onClick = {
                            searchActive = false
                            searchQuery = ""
                            searchResults = emptyList()
                        }) { Icon(Icons.Filled.Close, "Close search") }
                    }
                }

                // Search results card
                if (searchResults.isNotEmpty()) {
                    Card(
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .statusBarsPadding()
                            .padding(top = 68.dp)
                            .padding(horizontal = 8.dp)
                            .fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                    ) {
                        Column {
                            searchResults.take(6).forEachIndexed { idx, hit ->
                                val parts = hit.name.split(", ")
                                val title = parts.first()
                                val subtitle = parts.drop(1).joinToString(", ")
                                Row(
                                    Modifier.fillMaxWidth()
                                        .clickable {
                                            vm.goToPlace(hit)
                                            searchActive = false
                                            searchQuery = ""
                                            searchResults = emptyList()
                                        }
                                        .padding(horizontal = 16.dp, vertical = 11.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(
                                        Icons.Filled.Search,
                                        null,
                                        Modifier.size(16.dp),
                                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f),
                                    )
                                    Spacer(Modifier.width(12.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(title, style = MaterialTheme.typography.bodyMedium)
                                        if (subtitle.isNotEmpty()) {
                                            Text(
                                                subtitle,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                                                maxLines = 1,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            } else {
                // Normal title pill
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
                            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        }
                    }
                }
            }

            // Top-right button column
            Column(
                modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (!searchActive) {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
                        tonalElevation = 3.dp, shadowElevation = 3.dp,
                    ) {
                        IconButton(onClick = { searchActive = true }) {
                            Icon(Icons.Filled.Search, "Search")
                        }
                    }
                }
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
                    tonalElevation = 3.dp, shadowElevation = 3.dp,
                ) {
                    IconButton(onClick = { showSettings = true }) {
                        Icon(Icons.Filled.Settings, "Settings")
                    }
                }
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
                    tonalElevation = 3.dp, shadowElevation = 3.dp,
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
        }
    }

    if (showSettings) SettingsDialog(onDismiss = { showSettings = false })
    if (showCities) CitiesDialog(state, vm, onDismiss = { showCities = false })
}

@Composable
private fun CitiesDialog(state: ShadeyUiState, vm: ShadeyViewModel, onDismiss: () -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        title = { Text("Cities") },
        text = {
            Column {
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
                            Icon(Icons.Filled.Public, null, Modifier.size(18.dp),
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
        onMapClick = {}, // map taps no longer drop pins
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
private fun DroppedCard(pin: DroppedPin, zone: ZoneId, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var name by remember(pin) { mutableStateOf("") }
    Card(Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Dot(pin.info?.sunlight)
                Spacer(Modifier.width(8.dp))
                Text("New spot", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, "Dismiss") }
            }
            pin.info?.let { info ->
                Text(statusLine(info, zone), style = MaterialTheme.typography.bodyMedium)
                Text(sunDetail(info), style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
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
                    Icon(Icons.Filled.Add, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Save")
                }
            }
        }
    }
}

@Composable
private fun SelectedCard(info: SpotSunInfo, zone: ZoneId, onRemove: () -> Unit, onDismiss: () -> Unit) {
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
                Text(info.spot.description, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
            }
            Text(sunDetail(info), style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
            if (info.spot.source == SpotSource.USER) {
                TextButton(onClick = onRemove) { Text("Remove spot") }
            }
        }
    }
}

@Composable
private fun SettingsDialog(onDismiss: () -> Unit) {
    val uriHandler = LocalUriHandler.current
    val context = LocalContext.current
    val versionName = remember {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull().orEmpty()
    }
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
                Spacer(Modifier.height(16.dp))
                Text("About", style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(4.dp))
                Text("Made by phrag", style = MaterialTheme.typography.bodyMedium)
                LinkRow("Email: phrag@duck.com") { uriHandler.openUri("mailto:phrag@duck.com") }
                LinkRow("GitHub: github.com/phrag") { uriHandler.openUri("https://github.com/phrag") }
                LinkRow("Project: github.com/phrag/shadey") {
                    uriHandler.openUri("https://github.com/phrag/shadey")
                }
                Spacer(Modifier.height(8.dp))
                if (versionName.isNotEmpty()) {
                    Text(
                        "Version $versionName",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                LinkRow("Changelog") {
                    uriHandler.openUri("https://github.com/phrag/shadey/releases")
                }
            }
        },
    )
}

@Composable
private fun LinkRow(label: String, onClick: () -> Unit) {
    Text(
        label,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.clickable(onClick = onClick),
    )
}

@Composable
private fun Dot(sunlight: Sunlight?) {
    Box(
        Modifier.size(14.dp).clip(CircleShape)
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
