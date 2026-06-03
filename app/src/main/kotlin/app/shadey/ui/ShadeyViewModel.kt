package app.shadey.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.shadey.core.data.GeoJsonBuildings
import app.shadey.core.data.SpotsJson
import app.shadey.core.model.Building
import app.shadey.core.model.LatLng
import app.shadey.core.model.Spot
import app.shadey.core.model.SpotCategory
import app.shadey.core.model.SpotSource
import app.shadey.core.rank.SpotRanker
import app.shadey.core.rank.SpotSunInfo
import app.shadey.core.shade.ShadowEngine
import app.shadey.core.solar.SolarCalculator
import app.shadey.data.BoundingBox
import app.shadey.data.SavedSpotsStore
import app.shadey.data.centroid
import app.shadey.map.ClosedBounds
import app.shadey.map.GeoJsonWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

data class DroppedPin(val lat: Double, val lng: Double, val info: SpotSunInfo?)

data class ShadeyUiState(
    val date: LocalDate = LocalDate.now(),
    val timeMinutes: Int = LocalTime.now().hour * 60 + LocalTime.now().minute,
    val isNow: Boolean = true,
    val ranked: List<SpotSunInfo> = emptyList(),
    val selectedId: String? = null,
    val dropped: DroppedPin? = null,
    val shadowsGeoJson: String = GeoJsonWriter.emptyCollection(),
    val spotsGeoJson: String = GeoJsonWriter.emptyCollection(),
    val pinGeoJson: String = GeoJsonWriter.emptyCollection(),
    val sourceLabel: String = "Loading…",
    val busy: Boolean = false,
    val cameraTarget: LatLng? = null,
) {
    val selected: SpotSunInfo? get() = ranked.firstOrNull { it.spot.id == selectedId }
}

class ShadeyViewModel(app: Application) : AndroidViewModel(app) {

    private val store = SavedSpotsStore(app)
    private val engine = ShadowEngine()
    private val ranker = SpotRanker(engine)
    private val zone: ZoneId = ZoneId.systemDefault()

    private val _state = MutableStateFlow(ShadeyUiState())
    val state: StateFlow<ShadeyUiState> = _state.asStateFlow()

    /** Boxhagener Platz, Berlin — the initial map centre. */
    val initialTarget = LatLng(52.51028, 13.45853)

    private var curated: List<Spot> = emptyList()
    private var userSpots: List<Spot> = emptyList()
    private var activeBuildings: List<Building> = emptyList()
    private var bundledBuildings: List<Building> = emptyList()
    private var bundledRegion: BoundingBox? = null
    private var center: LatLng = initialTarget
    private var bounds: ClosedBounds? = null
    private var recomputeJob: Job? = null
    private var buildingsJob: Job? = null

    // Per-building shadow cache, valid while the sun bucket is unchanged. Keyed by building id.
    private val shadowCache = java.util.concurrent.ConcurrentHashMap<String, List<LatLng>>()
    @Volatile private var shadowCacheSunKey: String? = null

    init {
        viewModelScope.launch {
            curated = loadCurated()
            bundledBuildings = loadBundledBuildings()
            if (bundledBuildings.isNotEmpty()) {
                bundledRegion = BoundingBox.ofBuildings(bundledBuildings)
                activeBuildings = bundledBuildings
                _state.update { it.copy(sourceLabel = "Berlin · ${bundledBuildings.size} buildings") }
            }
            scheduleRecompute(immediate = true)
        }
        viewModelScope.launch {
            store.userSpots.collect {
                userSpots = it
                scheduleRecompute()
            }
        }
    }

    fun zone(): ZoneId = zone

    fun instant(s: ShadeyUiState = _state.value): Instant =
        s.date.atStartOfDay(zone).plusMinutes(s.timeMinutes.toLong()).toInstant()

    fun setTime(minutes: Int) {
        _state.update { it.copy(timeMinutes = minutes.coerceIn(0, 1439), isNow = false) }
        scheduleRecompute()
    }

    fun resetToNow() {
        val now = LocalTime.now()
        _state.update {
            it.copy(date = LocalDate.now(), timeMinutes = now.hour * 60 + now.minute, isNow = true)
        }
        scheduleRecompute()
    }

    fun selectSpot(id: String?) = _state.update { it.copy(selectedId = id, dropped = null) }

    fun onMapClick(p: LatLng) {
        viewModelScope.launch {
            val info = withContext(Dispatchers.Default) { evaluatePoint(p) }
            _state.update {
                it.copy(
                    dropped = DroppedPin(p.lat, p.lng, info),
                    selectedId = null,
                    pinGeoJson = GeoJsonWriter.point(p, GeoJsonWriter.colorFor(info.sunlight)),
                )
            }
        }
    }

    fun moveTo(p: LatLng) {
        center = p
        _state.update { it.copy(cameraTarget = p) }
        scheduleRecompute()
    }

    fun onCameraTargetConsumed() = _state.update { it.copy(cameraTarget = null) }

    fun clearDropped() =
        _state.update { it.copy(dropped = null, pinGeoJson = GeoJsonWriter.emptyCollection()) }

    fun saveDropped(name: String) {
        val d = _state.value.dropped ?: return
        viewModelScope.launch {
            store.addOrUpdate(
                Spot(
                    id = "user-${System.currentTimeMillis()}",
                    name = name.ifBlank { "My spot" },
                    lat = d.lat,
                    lng = d.lng,
                    category = SpotCategory.OTHER,
                    source = SpotSource.USER,
                ),
            )
            clearDropped()
        }
    }

    fun removeSpot(id: String) {
        viewModelScope.launch { store.remove(id) }
    }

    fun onCameraIdle(newCenter: LatLng, newBounds: ClosedBounds) {
        center = newCenter
        bounds = newBounds
        // Swap back to bundled data when returning from outside the bundled region.
        if (bundledRegion?.contains(newCenter) == true && activeBuildings !== bundledBuildings) {
            activeBuildings = bundledBuildings
            shadowCache.clear()
            _state.update { it.copy(sourceLabel = "Berlin · ${bundledBuildings.size} buildings") }
        }
        scheduleRecompute()
    }

    /**
     * Building footprints harvested directly from the rendered map tiles (no network).
     * Parsed off the main thread and fed straight into the shadow engine.
     */
    fun onBuildingsQueried(features: List<org.maplibre.geojson.Feature>) {
        // Bundled data is more complete than tile queries — skip when inside the bundled region.
        if (bundledRegion?.contains(center) == true) return
        buildingsJob?.cancel()
        buildingsJob = viewModelScope.launch {
            val buildings = withContext(Dispatchers.Default) {
                app.shadey.map.featuresToBuildings(features)
            }
            activeBuildings = buildings
            _state.update {
                it.copy(sourceLabel = if (buildings.isEmpty()) "No buildings here" else "OpenStreetMap · ${buildings.size} buildings")
            }
            scheduleRecompute()
        }
    }

    private fun evaluatePoint(p: LatLng): SpotSunInfo {
        val now = instant()
        val sun = SolarCalculator.position(p, now)
        val near = buildingsNear(p)
        val tmp = Spot("dropped", "Dropped pin", p.lat, p.lng, source = SpotSource.USER)
        return SpotSunInfo(tmp, engine.sunlightAt(p, sun, near), sun, engine.nextTransition(p, near, now)?.at)
    }

    private fun buildingsNear(p: LatLng, buildings: List<Building> = activeBuildings, radiusMeters: Double = 800.0): List<Building> {
        val box = BoundingBox.around(p, radiusMeters)
        return buildings.filter { box.contains(it.centroid()) }
    }

    private fun buildingsInView(c: LatLng = center, buildings: List<Building> = activeBuildings): List<Building> {
        val b = bounds ?: return buildingsNear(c, buildings)
        val box = BoundingBox(b.south, b.west, b.north, b.east).expandedMeters(150.0)
        return buildings.filter { box.contains(it.centroid()) }
    }

    private fun scheduleRecompute(immediate: Boolean = false) {
        recomputeJob?.cancel()
        recomputeJob = viewModelScope.launch {
            if (!immediate) delay(120) // debounce time-slider scrubbing
            val snapshot = _state.value
            val now = instant(snapshot)
            val spots = (curated + userSpots).distinctBy { it.id }
            // Snapshot mutable fields before background thread — sort comparator must be stable.
            val frozenCenter = center
            val frozenBuildings = activeBuildings
            val (ranked, shadowRings) = withContext(Dispatchers.Default) {
                val r = ranker.rank(spots, now) { buildingsNear(it.latLng, frozenBuildings, radiusMeters = 150.0) }
                val sun = SolarCalculator.position(frozenCenter, now)
                // Sun bucket — shadows are visually identical within ~0.5°, so we cache per
                // (building, sun bucket). Panning at a fixed time is then near-instant.
                val sunKey = "${(sun.azimuthDeg * 2).toInt()}_${(sun.elevationDeg * 2).toInt()}"
                if (sunKey != shadowCacheSunKey || shadowCache.size > 8000) {
                    shadowCache.clear()
                    shadowCacheSunKey = sunKey
                }
                val rings = buildingsInView(frozenCenter, frozenBuildings)
                    .sortedBy { distanceSq(frozenCenter, it.centroid()) }
                    .take(MAX_SHADOWS)
                    .mapNotNull { b ->
                        shadowCache.getOrPut(b.id) { engine.castShadow(b, sun) ?: EMPTY_RING }
                            .takeIf { it.isNotEmpty() }
                    }
                r to rings
            }
            _state.update {
                it.copy(
                    ranked = ranked,
                    spotsGeoJson = GeoJsonWriter.spots(ranked),
                    shadowsGeoJson = GeoJsonWriter.shadows(shadowRings),
                )
            }
        }
    }

    private suspend fun loadBundledBuildings(): List<Building> = withContext(Dispatchers.IO) {
        runCatching {
            getApplication<Application>().assets.open("data/berlin_buildings.geojson")
                .bufferedReader().use { it.readText() }
        }.getOrNull()?.let { GeoJsonBuildings.parse(it) } ?: emptyList()
    }

    private suspend fun loadCurated(): List<Spot> = withContext(Dispatchers.IO) {
        runCatching {
            getApplication<Application>().assets.open("data/spots.json")
                .bufferedReader().use { it.readText() }
        }.getOrNull()?.let { SpotsJson.parse(it) } ?: emptyList()
    }

    // Closest N buildings only — distant ones cast negligible shadows and dominate CPU time.
    private fun distanceSq(a: LatLng, b: LatLng): Double {
        val dLat = a.lat - b.lat
        val dLng = a.lng - b.lng
        return dLat * dLat + dLng * dLng
    }

    private companion object {
        const val MAX_SHADOWS = 300
        val EMPTY_RING = emptyList<LatLng>()
    }
}
