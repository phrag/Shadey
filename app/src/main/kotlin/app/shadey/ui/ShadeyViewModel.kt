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
import app.shadey.core.model.Sunlight
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
    // Buildings harvested from tiles, accumulated across pans (insertion-ordered for LRU eviction).
    private val accumulated = LinkedHashMap<String, Building>()
    private var bundledBuildings: List<Building> = emptyList()
    private var bundledRegion: BoundingBox? = null
    private var center: LatLng = initialTarget
    private var bounds: ClosedBounds? = null
    private var recomputeJob: Job? = null
    private var buildingsJob: Job? = null
    private var frameJob: Job? = null
    private var settleJob: Job? = null

    // Precomputed "shadow movie" for the current view + date: a shadow (and spot-colour) frame
    // per FRAME_STEP-minute bucket of the day. Once built, scrubbing the time slider is a pure
    // map lookup with zero geometry work, so it feels instant.
    private val shadowFrames = java.util.concurrent.ConcurrentHashMap<Int, String>()
    private val spotFrames = java.util.concurrent.ConcurrentHashMap<Int, String>()
    @Volatile private var framesViewKey: String? = null

    // Per-building shadow cache, valid while the sun bucket is unchanged. Keyed by building id.
    // It persists across pans/zooms so revealing a previously-seen area is instant.
    private val shadowCache = java.util.concurrent.ConcurrentHashMap<String, List<LatLng>>()
    @Volatile private var shadowCacheSunKey: String? = null
    // The sun bucket the spot ranking was last computed for. Ranking (nextTransition) is the
    // expensive part, so we only redo it when the sun moves — never on a plain pan.
    @Volatile private var rankedSunKey: String? = null

    init {
        viewModelScope.launch {
            curated = loadCurated()
            bundledBuildings = loadBundledBuildings()
            // Only treat bundled data as authoritative if it is the real dataset.
            // A tiny file (< 1000 buildings) means CI fell back to the sample — in that
            // case we leave bundledRegion null so tile-based queries are used instead.
            if (bundledBuildings.size >= MIN_BUNDLED_BUILDINGS) {
                bundledRegion = BoundingBox.ofBuildings(bundledBuildings)
                activeBuildings = bundledBuildings
                _state.update { it.copy(sourceLabel = "Berlin · ${bundledBuildings.size} buildings") }
            }
            recompute(rank = true, immediate = true)
        }
        viewModelScope.launch {
            store.userSpots.collect {
                userSpots = it
                recompute(rank = true)
            }
        }
    }

    fun zone(): ZoneId = zone

    fun instant(s: ShadeyUiState = _state.value): Instant =
        s.date.atStartOfDay(zone).plusMinutes(s.timeMinutes.toLong()).toInstant()

    fun setTime(minutes: Int) {
        val m = minutes.coerceIn(0, 1439)
        _state.update { it.copy(timeMinutes = m, isNow = false) }
        // Instant path: if the day's frames are built for this view, just swap in the frame.
        val frame = if (framesViewKey == viewKey()) shadowFrames[bucketOf(m)] else null
        if (frame != null) {
            val spotFrame = spotFrames[bucketOf(m)]
            _state.update {
                it.copy(shadowsGeoJson = frame, spotsGeoJson = spotFrame ?: it.spotsGeoJson)
            }
        } else {
            recompute(rank = false) // frames not ready yet — compute this instant on the fly
        }
        // Once scrubbing stops, do the exact ranking (with next-change times) for the spot list.
        settleJob?.cancel()
        settleJob = viewModelScope.launch {
            delay(250)
            recompute(rank = true)
        }
    }

    fun resetToNow() {
        val now = LocalTime.now()
        _state.update {
            it.copy(date = LocalDate.now(), timeMinutes = now.hour * 60 + now.minute, isNow = true)
        }
        recompute(rank = true)
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
        recompute()
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
            _state.update { it.copy(sourceLabel = "Berlin · ${bundledBuildings.size} buildings") }
        }
        recompute()
    }

    /**
     * Building footprints harvested directly from the rendered map tiles (no network).
     * Parsed off the main thread and fed straight into the shadow engine.
     */
    fun onBuildingsQueried(features: List<org.maplibre.geojson.Feature>, belowZoom: Boolean) {
        // Bundled data is more complete than tile queries — skip when inside the bundled region.
        if (bundledRegion?.contains(center) == true) return
        buildingsJob?.cancel()
        buildingsJob = viewModelScope.launch {
            if (belowZoom) {
                accumulated.clear()
                activeBuildings = emptyList()
                _state.update { it.copy(sourceLabel = "Zoom in to see shade") }
                recompute()
                return@launch
            }
            val buildings = withContext(Dispatchers.Default) {
                app.shadey.map.featuresToBuildings(features)
            }
            // Empty means tiles aren't rendered this instant (zoom transition, eviction) — keep
            // what we have so shadows don't blink out between camera events.
            if (buildings.isEmpty()) return@launch
            // Accumulate across pans so shadows for already-seen blocks stay available without
            // recomputation. The shadow cache (keyed by building id) is not cleared, so only the
            // genuinely new buildings get a shadow computed.
            for (b in buildings) accumulated[b.id] = b
            while (accumulated.size > MAX_ACCUMULATED) {
                val oldest = accumulated.keys.iterator().next()
                accumulated.remove(oldest)
            }
            activeBuildings = accumulated.values.toList()
            _state.update { it.copy(sourceLabel = "OpenStreetMap · ${activeBuildings.size} buildings") }
            recompute()
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

    /**
     * Recompute the ground shadows for the current view and, when the sun has moved (or [rank]
     * is forced), the spot ranking.
     *
     * The design keeps panning instant: casting a building's shadow is cached per (building, sun
     * bucket), so a pan only computes shadows for newly-revealed buildings and reuses the rest.
     * The ranking — whose `nextTransition` scan is the expensive part — is skipped entirely unless
     * the sun bucket changed, since a pan at a fixed time can't change any spot's sun/shade state.
     */
    private fun recompute(rank: Boolean = false, immediate: Boolean = false) {
        recomputeJob?.cancel()
        recomputeJob = viewModelScope.launch {
            if (!immediate) delay(80) // debounce slider scrubbing and back-to-back camera events
            val now = instant(_state.value)
            val spots = (curated + userSpots).distinctBy { it.id }
            // Snapshot mutable fields before the background thread — the sort comparator below
            // must see a stable centre, and activeBuildings can be swapped on the main thread.
            val frozenCenter = center
            val frozenBuildings = activeBuildings
            val result = withContext(Dispatchers.Default) {
                val sun = SolarCalculator.position(frozenCenter, now)
                // Sun bucket — shadows are visually identical within ~0.5°. Cache per bucket.
                val sunKey = "${(sun.azimuthDeg * 2).toInt()}_${(sun.elevationDeg * 2).toInt()}"
                if (sunKey != shadowCacheSunKey || shadowCache.size > MAX_CACHE_ENTRIES) {
                    shadowCache.clear()
                    shadowCacheSunKey = sunKey
                }
                val rings = inViewBuildings(frozenCenter, frozenBuildings)
                    .mapNotNull { b ->
                        shadowCache.getOrPut(b.id) { engine.castShadow(b, sun) ?: EMPTY_RING }
                            .takeIf { it.isNotEmpty() }
                    }
                // Rank only when the sun moved, when forced, or on the very first pass.
                val doRank = rank || rankedSunKey != sunKey || _state.value.ranked.isEmpty()
                val ranked = if (doRank) {
                    rankedSunKey = sunKey
                    ranker.rank(spots, now) { buildingsNear(it.latLng, frozenBuildings, radiusMeters = 150.0) }
                } else null
                rings to ranked
            }
            val (rings, ranked) = result
            _state.update {
                it.copy(
                    shadowsGeoJson = GeoJsonWriter.shadows(rings),
                    ranked = ranked ?: it.ranked,
                    spotsGeoJson = if (ranked != null) GeoJsonWriter.spots(ranked) else it.spotsGeoJson,
                )
            }
            // Build the day's frames for this view in the background so scrubbing is instant.
            precomputeFrames()
        }
    }

    /** Buildings to cast shadows for: those in view, closest first, capped for performance. */
    private fun inViewBuildings(c: LatLng, buildings: List<Building>): List<Building> =
        buildingsInView(c, buildings)
            .sortedBy { distanceSq(c, it.centroid()) }
            .take(MAX_SHADOWS)

    private fun bucketOf(minutes: Int): Int = (minutes / FRAME_STEP_MIN) * FRAME_STEP_MIN

    /** Identifies the view + date the frames are valid for. Rounded so tiny jitter doesn't bust it. */
    private fun viewKey(): String {
        val b = bounds
        val box = if (b != null)
            "${(b.south * 1000).toInt()}_${(b.west * 1000).toInt()}_${(b.north * 1000).toInt()}_${(b.east * 1000).toInt()}"
        else "none"
        return "$box|${_state.value.date}|${activeBuildings.size}"
    }

    private fun rankBucket(s: Sunlight) = when (s) {
        Sunlight.SUN -> 0
        Sunlight.SHADE -> 1
        Sunlight.NIGHT -> 2
    }

    /**
     * Precompute a shadow + spot-colour frame for every daylight bucket of the current day, for
     * the buildings in the current view. Runs once per view (skipped if already built) and is
     * cancelled when the view changes. After it completes, [setTime] is a pure lookup.
     */
    private fun precomputeFrames() {
        val key = viewKey()
        if (framesViewKey == key) return
        frameJob?.cancel()
        val frozenCenter = center
        val frozenBuildings = activeBuildings
        val date = _state.value.date
        val spots = (curated + userSpots).distinctBy { it.id }
        frameJob = viewModelScope.launch(Dispatchers.Default) {
            val inView = inViewBuildings(frozenCenter, frozenBuildings)
            val near = spots.associate { it.id to buildingsNear(it.latLng, frozenBuildings, radiusMeters = 150.0) }
            val shadows = HashMap<Int, String>()
            val spotsByBucket = HashMap<Int, String>()
            var m = 0
            while (m <= 1439) {
                if (!isActive) return@launch
                val t = date.atStartOfDay(zone).plusMinutes(m.toLong()).toInstant()
                val sun = SolarCalculator.position(frozenCenter, t)
                shadows[m] = if (sun.elevationDeg > 0.5) {
                    GeoJsonWriter.shadows(inView.mapNotNull { engine.castShadow(it, sun)?.takeIf { r -> r.isNotEmpty() } })
                } else {
                    GeoJsonWriter.emptyCollection()
                }
                val infos = spots.map { s ->
                    val ss = SolarCalculator.position(s.latLng, t)
                    val light = if (ss.elevationDeg <= 0.0) Sunlight.NIGHT
                        else engine.sunlightAt(s.latLng, ss, near[s.id] ?: emptyList())
                    SpotSunInfo(s, light, ss, null)
                }.sortedWith(compareBy({ rankBucket(it.sunlight) }, { -it.solar.elevationDeg }))
                spotsByBucket[m] = GeoJsonWriter.spots(infos)
                m += FRAME_STEP_MIN
            }
            if (isActive) {
                shadowFrames.clear(); shadowFrames.putAll(shadows)
                spotFrames.clear(); spotFrames.putAll(spotsByBucket)
                framesViewKey = key
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
        const val MAX_SHADOWS = 400
        val EMPTY_RING = emptyList<LatLng>()
        const val MIN_BUNDLED_BUILDINGS = 1000
        const val MAX_CACHE_ENTRIES = 6000
        const val MAX_ACCUMULATED = 8000
        const val FRAME_STEP_MIN = 10
    }
}
