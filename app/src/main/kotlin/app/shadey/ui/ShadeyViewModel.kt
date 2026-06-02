package app.shadey.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.shadey.core.data.SpotsJson
import app.shadey.core.geo.LocalProjection
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
import app.shadey.data.BuildingRepository
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
    val buildingsGeoJson: String = GeoJsonWriter.emptyCollection(),
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

    private val buildingsRepo = BuildingRepository(app)
    private val store = SavedSpotsStore(app)
    private val engine = ShadowEngine()
    private val ranker = SpotRanker(engine)
    private val zone: ZoneId = ZoneId.systemDefault()

    private val _state = MutableStateFlow(ShadeyUiState())
    val state: StateFlow<ShadeyUiState> = _state.asStateFlow()

    /** Boxhagener Platz — where the bundled sample data lives. */
    val initialTarget = LatLng(52.51028, 13.45853)

    private var curated: List<Spot> = emptyList()
    private var userSpots: List<Spot> = emptyList()
    private var activeBuildings: List<Building> = emptyList()
    private var center: LatLng = initialTarget
    private var bounds: ClosedBounds? = null
    private var recomputeJob: Job? = null
    private var lastFetchCenter: LatLng? = null
    private var roamJob: Job? = null

    init {
        viewModelScope.launch {
            curated = loadCurated()
            scheduleRecompute(immediate = true)
            doRoam(center)
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
        scheduleRecompute()
        scheduleRoam(newCenter)
    }

    private fun scheduleRoam(c: LatLng) {
        val prev = lastFetchCenter
        // Only fetch if we've never fetched, or moved more than 300 m from the last fetch.
        if (prev != null && distanceMeters(prev, c) < 300.0) return
        roamJob?.cancel()
        roamJob = viewModelScope.launch { doRoam(c) }
    }

    private suspend fun doRoam(c: LatLng) {
        _state.update { it.copy(busy = true) }
        val fetched = runCatching { buildingsRepo.fetchOsmAround(c) }
        fetched.onSuccess { buildings ->
            lastFetchCenter = c
            activeBuildings = buildings
            _state.update {
                it.copy(
                    busy = false,
                    buildingsGeoJson = GeoJsonWriter.buildings(buildings),
                    sourceLabel = if (buildings.isEmpty()) "No buildings in this area" else "OpenStreetMap · ${buildings.size} buildings",
                )
            }
            if (buildings.isNotEmpty()) scheduleRecompute()
        }
        fetched.onFailure { e ->
            // CancellationException means a newer fetch superseded this one — keep existing data.
            if (e is kotlinx.coroutines.CancellationException) {
                _state.update { it.copy(busy = false) }
                return
            }
            // Network error: keep whatever buildings we already have, just note the issue.
            _state.update { it.copy(busy = false, sourceLabel = if (activeBuildings.isEmpty()) "OSM unavailable — move map to retry" else "OpenStreetMap · ${activeBuildings.size} buildings (cached)") }
        }
    }

    private fun distanceMeters(a: LatLng, b: LatLng): Double {
        val dLat = Math.toRadians(b.lat - a.lat)
        val dLng = Math.toRadians(b.lng - a.lng)
        val sinLat = Math.sin(dLat / 2)
        val sinLng = Math.sin(dLng / 2)
        val h = sinLat * sinLat + Math.cos(Math.toRadians(a.lat)) * Math.cos(Math.toRadians(b.lat)) * sinLng * sinLng
        return 2 * 6_371_000 * Math.asin(Math.sqrt(h))
    }

    private fun evaluatePoint(p: LatLng): SpotSunInfo {
        val now = instant()
        val sun = SolarCalculator.position(p, now)
        val near = buildingsNear(p)
        val tmp = Spot("dropped", "Dropped pin", p.lat, p.lng, source = SpotSource.USER)
        return SpotSunInfo(tmp, engine.sunlightAt(p, sun, near), sun, engine.nextTransition(p, near, now)?.at)
    }

    private fun buildingsNear(p: LatLng, radiusMeters: Double = 800.0): List<Building> {
        val box = BoundingBox.around(p, radiusMeters)
        return activeBuildings.filter { box.contains(it.centroid()) }
    }

    private fun buildingsInView(): List<Building> {
        val b = bounds ?: return buildingsNear(center)
        val box = BoundingBox(b.south, b.west, b.north, b.east).expandedMeters(150.0)
        return activeBuildings.filter { box.contains(it.centroid()) }
    }

    private fun scheduleRecompute(immediate: Boolean = false) {
        recomputeJob?.cancel()
        recomputeJob = viewModelScope.launch {
            if (!immediate) delay(120) // debounce time-slider scrubbing
            val snapshot = _state.value
            val now = instant(snapshot)
            val spots = (curated + userSpots).distinctBy { it.id }
            val (ranked, shadowRings) = withContext(Dispatchers.Default) {
                val r = ranker.rank(spots, now) { buildingsNear(it.latLng) }
                val sun = SolarCalculator.position(center, now)
                val proj = LocalProjection(center)
                val rings = buildingsInView().asSequence()
                    .take(MAX_SHADOWS)
                    .mapNotNull { engine.castShadow(it, sun, proj) }
                    .toList()
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

    private suspend fun loadCurated(): List<Spot> = withContext(Dispatchers.IO) {
        runCatching {
            getApplication<Application>().assets.open("data/spots.json")
                .bufferedReader().use { it.readText() }
        }.getOrNull()?.let { SpotsJson.parse(it) } ?: emptyList()
    }

    private companion object {
        const val MAX_SHADOWS = 4000
    }
}
