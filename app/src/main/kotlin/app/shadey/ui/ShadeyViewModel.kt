package app.shadey.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.shadey.core.data.SpotsJson
import app.shadey.core.model.Building
import app.shadey.core.model.LatLng
import app.shadey.core.model.Spot
import app.shadey.core.model.SpotCategory
import app.shadey.core.model.SpotSource
import app.shadey.core.model.SolarPosition
import app.shadey.core.model.Sunlight
import app.shadey.core.rank.SpotRanker
import app.shadey.core.rank.SpotSunInfo
import app.shadey.core.shade.ShadowEngine
import app.shadey.core.solar.SolarCalculator
import app.shadey.data.BoundingBox
import app.shadey.data.BuildingDownloader
import app.shadey.data.BuildingIndex
import app.shadey.data.CachedCity
import app.shadey.data.CityHit
import app.shadey.data.CityStore
import app.shadey.data.Geocoder
import app.shadey.data.GeoJsonFile
import app.shadey.data.RouteOption
import app.shadey.data.Router
import app.shadey.data.SavedSpotsStore
import app.shadey.data.UpdateChecker
import app.shadey.data.UpdateInfo
import app.shadey.data.WeatherClient
import app.shadey.data.WeatherSnapshot
import app.shadey.map.ClosedBounds
import app.shadey.map.GeoJsonWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

data class DroppedPin(val lat: Double, val lng: Double, val info: SpotSunInfo?)

/** A run of consecutive route samples sharing the same sun/shade state. */
data class RouteSegment(val coords: List<LatLng>, val sunlight: Sunlight)

/** A walking route scored by how much of it is shaded right now. */
data class ScoredRoute(val option: RouteOption, val shadeRatio: Double, val segments: List<RouteSegment>)

data class ShadeyUiState(
    val date: LocalDate = LocalDate.now(),
    val timeMinutes: Int = LocalTime.now().hour * 60 + LocalTime.now().minute,
    val isNow: Boolean = true,
    val ranked: List<SpotSunInfo> = emptyList(),
    val selectedId: String? = null,
    val dropped: DroppedPin? = null,
    /** The next upcoming sunny spell today for the dropped pin / selected spot, if it's currently
     *  shaded or dark. Null when there isn't one (or the point is already in the sun). */
    val sunnyWindow: ShadowEngine.SunWindow? = null,
    val shadowsGeoJson: String = GeoJsonWriter.emptyCollection(),
    val spotsGeoJson: String = GeoJsonWriter.emptyCollection(),
    val pinGeoJson: String = GeoJsonWriter.emptyCollection(),
    val sourceLabel: String = "Loading…",
    val busy: Boolean = false,
    /** Short phase text shown while [busy] — e.g. "Computing shade…". Empty otherwise. */
    val busyLabel: String = "",
    val cameraTarget: LatLng? = null,
    // City download UI.
    val citySearch: List<CityHit> = emptyList(),
    val cachedCities: List<CachedCity> = emptyList(),
    val cityBusy: Boolean = false,
    val cityStatus: String? = null,
    /**
     * Whether Shadey may use the network for place search and city downloads. The base map
     * always loads regardless — this only gates the Nominatim/Overpass requests.
     */
    val allowRoaming: Boolean = true,
    /** True when there's no usable building data yet, so the UI should prompt for a city. */
    val promptCity: Boolean = false,
    // Update checking (opt-in).
    /** A newer GitHub release, when one is available and not yet dismissed. */
    val updateAvailable: UpdateInfo? = null,
    /** True on first run (before the user has chosen) so the UI shows the opt-in prompt. */
    val promptUpdateOptIn: Boolean = false,
    val updateChecksEnabled: Boolean = false,
    val lastUpdateCheck: Long = 0L,
    val checkingForUpdate: Boolean = false,
    /** Live cloud cover/UV for the map centre, when known. Annotation only — never affects shade. */
    val weather: WeatherSnapshot? = null,
    // Shady route planner.
    /** True from tapping the route FAB until the route is cancelled — taps then set origin/dest. */
    val routeActive: Boolean = false,
    val routeOrigin: LatLng? = null,
    val routeDest: LatLng? = null,
    val routeOptions: List<ScoredRoute> = emptyList(),
    val selectedRouteIdx: Int = 0,
    val routeBusy: Boolean = false,
    val routeStatus: String? = null,
    val routeGeoJson: String = GeoJsonWriter.emptyCollection(),
    // Current sun position at the map centre — drives the compass overlay.
    val sunAzimuthDeg: Double = 0.0,
    val sunElevationDeg: Double = 0.0,
    // Live "you are here" marker. Tracking turns on when the user taps My-location and stays on
    // (live, while foregrounded) for the session. Follow recenters the camera as they move until
    // a manual pan disengages it.
    val userTracking: Boolean = false,
    val userFollow: Boolean = false,
    val userLocation: LatLng? = null,
    /** Heading in degrees clockwise from true north, or null until the orientation sensor reports. */
    val userHeadingDeg: Float? = null,
    val userGeoJson: String = GeoJsonWriter.emptyCollection(),
    /** True when the magnetometer is uncalibrated/disturbed, so the UI prompts the figure-8 wave.
     *  Only meaningful while tracking and when the heading is coming from the compass. */
    val compassNeedsCalibration: Boolean = false,
) {
    val selected: SpotSunInfo? get() = ranked.firstOrNull { it.spot.id == selectedId }
    val selectedRoute: ScoredRoute? get() = routeOptions.getOrNull(selectedRouteIdx)
}

class ShadeyViewModel(app: Application) : AndroidViewModel(app) {

    private val store = SavedSpotsStore(app)
    private val cityStore = CityStore(app.filesDir)
    private var searchJob: Job? = null
    private var downloadJob: Job? = null
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
    // The currently active downloaded city, if any — kept separate from the bundled Berlin data
    // above so switching to a downloaded city never permanently loses the bundled dataset.
    private var downloadedCity: CachedCity? = null
    private var downloadedCityBuildings: List<Building> = emptyList()
    private fun downloadedCityRegion(): BoundingBox? =
        downloadedCity?.let { BoundingBox(it.south, it.west, it.north, it.east) }
    private var center: LatLng = initialTarget
    private var bounds: ClosedBounds? = null
    // Centre at the last camera-idle that actually triggered a recompute. Follow-camera re-centres
    // on every GPS fix (roughly once a second while walking), and each recompute rebuilds the shadow
    // GeoJSON and pushes it into the native map source — expensive enough to stall the main thread
    // for hundreds of ms. A walking-pace nudge of a metre or two can't change which buildings are in
    // view or the spot ranking, so only re-trigger once the centre has moved meaningfully.
    private var lastRecomputeCenter: LatLng? = null
    private var recomputeJob: Job? = null
    private var buildingsJob: Job? = null
    private var frameJob: Job? = null
    private var settleJob: Job? = null
    private var weatherJob: Job? = null
    private var routeJob: Job? = null
    private var sunnyWindowJob: Job? = null
    // Keyed by ~1 km grid cell + hour, so panning within an area or scrubbing the time slider
    // doesn't re-fetch — cloud cover barely changes at that resolution within an hour.
    private val weatherCache = java.util.concurrent.ConcurrentHashMap<String, WeatherSnapshot>()

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
    // The sun bucket and map centre the spot ranking was last computed for. Ranking
    // (nextTransition) is the expensive part, so we skip it unless the sun moved or the
    // origin changed enough to matter — checked here (not just via the `rank` flag) so a
    // forced re-rank request can't be lost to a later, unforced recompute cancelling it.
    @Volatile private var rankedSunKey: String? = null
    @Volatile private var rankedCenter: LatLng? = null

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
            // If the user has downloaded a city before, restore it (it wins over bundled Berlin).
            val cached = withContext(Dispatchers.IO) { cityStore.list() }
            _state.update { it.copy(cachedCities = cached) }
            val last = withContext(Dispatchers.IO) { cityStore.lastUsedSlug() }
            val lastCity = cached.firstOrNull { it.slug == last }
            val lastFile = last?.let { withContext(Dispatchers.IO) { cityStore.geoJsonFileOf(it) } }
            val restored = if (lastCity != null && lastFile != null) {
                val b = withContext(Dispatchers.Default) {
                    runCatching { GeoJsonFile.buildings(lastFile) }.getOrDefault(emptyList())
                }
                // Restore the city's building data so shade works if the user is near it, but do
                // NOT move the camera — a silent restore must never yank the view to another city.
                if (b.isNotEmpty()) { activateCity(lastCity, b, moveCamera = false); true } else false
            } else false
            if (!restored) {
                recompute(rank = true, immediate = true)
                // Nothing usable bundled and nothing downloaded yet — guide the user to pick a city.
                if (bundledRegion == null && cached.isEmpty()) {
                    _state.update { it.copy(promptCity = true) }
                }
            }
        }
        viewModelScope.launch {
            store.userSpots.collect {
                userSpots = it
                recompute(rank = true)
            }
        }
        viewModelScope.launch {
            store.allowRoaming.collect { allow -> _state.update { it.copy(allowRoaming = allow) } }
        }
        viewModelScope.launch {
            store.lastUpdateCheck.collect { ts -> _state.update { it.copy(lastUpdateCheck = ts) } }
        }
        viewModelScope.launch {
            // null = never asked → prompt; true → background-check (throttled); false → do nothing.
            store.updateChecksEnabled.collect { enabled ->
                _state.update {
                    it.copy(updateChecksEnabled = enabled == true, promptUpdateOptIn = enabled == null)
                }
                if (enabled == true) maybeCheckForUpdate()
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

    fun selectSpot(id: String?) {
        _state.update { it.copy(selectedId = id, dropped = null) }
        val selected = _state.value.selected
        if (selected == null) {
            sunnyWindowJob?.cancel()
            _state.update { it.copy(sunnyWindow = null) }
        } else {
            scheduleSunnyWindow(selected.spot.latLng, selected.sunlight)
        }
    }

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
            scheduleSunnyWindow(p, info.sunlight)
        }
    }

    fun goToPlace(hit: app.shadey.data.CityHit) = moveTo(LatLng(hit.lat, hit.lng))

    fun dropPinAtCenter() = onMapClick(center)

    // --- Shady route planner -----------------------------------------------------------------

    /** Enter route-planning mode: the next two map taps set the origin, then the destination. */
    fun startRoutePlanning() {
        routeJob?.cancel()
        _state.update {
            it.copy(
                routeActive = true, routeOrigin = null, routeDest = null,
                routeOptions = emptyList(), selectedRouteIdx = 0, routeBusy = false,
                routeStatus = null, routeGeoJson = GeoJsonWriter.emptyCollection(),
                pinGeoJson = GeoJsonWriter.emptyCollection(),
            )
        }
    }

    /** Leave route-planning mode and clear the route + endpoint pins off the map. */
    fun cancelRoutePlanning() {
        routeJob?.cancel()
        _state.update {
            it.copy(
                routeActive = false, routeOrigin = null, routeDest = null,
                routeOptions = emptyList(), selectedRouteIdx = 0, routeBusy = false,
                routeStatus = null, routeGeoJson = GeoJsonWriter.emptyCollection(),
                pinGeoJson = GeoJsonWriter.emptyCollection(),
            )
        }
    }

    /** A tap on the map while route-planning is active: first sets the origin, then the destination. */
    fun onRouteMapTap(p: LatLng) {
        val s = _state.value
        if (!s.routeActive) return
        when {
            s.routeOrigin == null -> _state.update {
                it.copy(
                    routeOrigin = p, routeStatus = null,
                    pinGeoJson = GeoJsonWriter.points(listOf(p to ROUTE_ORIGIN_COLOR)),
                )
            }
            s.routeDest == null -> {
                val origin = s.routeOrigin!! // guaranteed by the branch above having been skipped
                _state.update {
                    it.copy(
                        routeDest = p,
                        pinGeoJson = GeoJsonWriter.points(
                            listOf(origin to ROUTE_ORIGIN_COLOR, p to ROUTE_DEST_COLOR),
                        ),
                    )
                }
                fetchRoutes()
            }
            // Both already set — a further tap starts a fresh pick rather than being ignored.
            else -> _state.update {
                it.copy(
                    routeOrigin = p, routeDest = null, routeOptions = emptyList(), selectedRouteIdx = 0,
                    routeStatus = null, routeGeoJson = GeoJsonWriter.emptyCollection(),
                    pinGeoJson = GeoJsonWriter.points(listOf(p to ROUTE_ORIGIN_COLOR)),
                )
            }
        }
    }

    /**
     * Use the device's current location as the route's start point, skipping the map tap. A null
     * [p] means the fix was unavailable (permission denied, or no recent location) — surface that
     * rather than silently doing nothing, so the user knows to tap the map instead. Setting the
     * origin always clears any half-finished pick so the flow restarts cleanly from "now tap your
     * destination".
     */
    fun useLocationAsRouteStart(p: LatLng?) {
        if (!_state.value.routeActive) return
        if (p == null) {
            _state.update { it.copy(routeStatus = "Couldn't get your location — tap the map to set a start point") }
            return
        }
        _state.update {
            it.copy(
                routeOrigin = p, routeDest = null, routeOptions = emptyList(), selectedRouteIdx = 0,
                routeBusy = false, routeStatus = null, routeGeoJson = GeoJsonWriter.emptyCollection(),
                pinGeoJson = GeoJsonWriter.points(listOf(p to ROUTE_ORIGIN_COLOR)),
            )
        }
    }

    /** Cycle to the next walking alternative (wraps around). */
    fun nextRouteOption() {
        val s = _state.value
        if (s.routeOptions.size < 2) return
        val idx = (s.selectedRouteIdx + 1) % s.routeOptions.size
        _state.update { it.copy(selectedRouteIdx = idx, routeGeoJson = routeGeoJsonFor(s.routeOptions[idx])) }
    }

    private fun fetchRoutes() {
        val s = _state.value
        val origin = s.routeOrigin ?: return
        val dest = s.routeDest ?: return
        if (!s.allowRoaming) {
            _state.update { it.copy(routeStatus = "Network data is off — enable it in Settings to plan a route.") }
            return
        }
        routeJob?.cancel()
        routeJob = viewModelScope.launch {
            _state.update { it.copy(routeBusy = true, routeStatus = null) }
            val raw = runCatching { Router.walkingRoutes(origin, dest) }.getOrDefault(emptyList())
            if (raw.isEmpty()) {
                _state.update { it.copy(routeBusy = false, routeStatus = "No walking route found between those points") }
                return@launch
            }
            val now = instant()
            val frozenBuildings = activeBuildings
            val scored = withContext(Dispatchers.Default) {
                // Reuse the shared spatial index instead of rescanning every building on each of the
                // hundreds of per-sample lookups, and fix the sun position once — it's effectively
                // constant across a few-km city walk at a single instant.
                val index = indexFor(frozenBuildings)
                val sun = SolarCalculator.position(origin, now)
                raw.map { scoreRoute(it, sun, index) }
            }
            // Shadiest first — that's the point of the feature.
            val best = scored.indices.maxByOrNull { scored[it].shadeRatio } ?: 0
            _state.update {
                it.copy(
                    routeBusy = false, routeOptions = scored, selectedRouteIdx = best,
                    routeGeoJson = routeGeoJsonFor(scored[best]),
                )
            }
        }
    }

    private fun routeGeoJsonFor(scored: ScoredRoute) =
        GeoJsonWriter.route(scored.segments.map { it.coords to it.sunlight })

    /** Samples every [ROUTE_SAMPLE_STEP_M] along the route and reuses the shadow engine to score it. */
    private fun scoreRoute(route: RouteOption, sun: SolarPosition, index: BuildingIndex): ScoredRoute {
        val samples = sampleAlong(route.coords, ROUTE_SAMPLE_STEP_M)
        val segments = ArrayList<RouteSegment>()
        var run = ArrayList<LatLng>()
        var runState: Sunlight? = null
        var sunCount = 0
        for (p in samples) {
            val state = engine.sunlightAt(p, sun, index.near(p, radiusMeters = 200.0))
            if (state == Sunlight.SUN) sunCount++
            if (runState != null && state != runState) {
                run.add(p) // shared vertex so adjacent coloured segments connect with no gap
                segments.add(RouteSegment(run, runState))
                run = ArrayList()
            }
            run.add(p)
            runState = state
        }
        if (run.size >= 2 && runState != null) segments.add(RouteSegment(run, runState))
        val shadeRatio = if (samples.isEmpty()) 0.0 else 1.0 - sunCount.toDouble() / samples.size
        return ScoredRoute(route, shadeRatio, segments)
    }

    /** Resamples a polyline at a fixed step (metres), using one local projection for the whole route. */
    private fun sampleAlong(coords: List<LatLng>, stepMeters: Double): List<LatLng> {
        if (coords.size < 2) return coords
        val proj = app.shadey.core.geo.LocalProjection(coords.first())
        val pts = coords.map(proj::toLocal)
        val samples = ArrayList<LatLng>()
        samples.add(coords.first())
        var traveled = 0.0
        var nextMark = stepMeters
        for (i in 1 until pts.size) {
            val a = pts[i - 1]
            val b = pts[i]
            val segLen = (b - a).length()
            if (segLen <= 1e-6) continue
            while (traveled + segLen >= nextMark) {
                val t = (nextMark - traveled) / segLen
                samples.add(proj.toLatLng(a + (b - a) * t))
                nextMark += stepMeters
            }
            traveled += segLen
        }
        if (samples.last() != coords.last()) samples.add(coords.last())
        return samples
    }

    /**
     * Returns the current map viewport expanded by 50% on each side as a viewbox
     * [west, south, east, north] suitable for biasing Nominatim search results.
     */
    fun mapViewbox(): DoubleArray? {
        val b = bounds ?: return null
        val dLat = (b.north - b.south) * 0.5
        val dLng = (b.east - b.west) * 0.5
        return doubleArrayOf(b.west - dLng, b.south - dLat, b.east + dLng, b.north + dLat)
    }

    fun moveTo(p: LatLng) {
        center = p
        _state.update { it.copy(cameraTarget = p) }
        // Force a re-rank: the spot order now depends on distance from the map centre,
        // not just the sun's position, so a moved centre must always refresh it.
        recompute(rank = true)
    }

    fun onCameraTargetConsumed() = _state.update { it.copy(cameraTarget = null) }

    // --- Live location marker ("you are here") -----------------------------------------------

    /**
     * Begin showing and live-tracking the user's location marker, and follow them with the camera.
     * Called from the My-location button (after the location permission is resolved). [initial] is
     * the last-known fix used to centre immediately; continuous updates then arrive via
     * [onUserLocation] from the UI layer's location/sensor listeners.
     */
    fun startLocationFollow(initial: LatLng?) {
        _state.update {
            it.copy(
                userTracking = true,
                userFollow = true,
                userLocation = initial ?: it.userLocation,
                userGeoJson = userMarkerJson(initial ?: it.userLocation, it.userHeadingDeg),
                cameraTarget = initial ?: it.cameraTarget,
            )
        }
        if (initial != null) center = initial
    }

    /** A new continuous location fix. Updates the marker and, while following, recentres the camera. */
    fun onUserLocation(p: LatLng) {
        if (!_state.value.userTracking) return
        _state.update {
            it.copy(
                userLocation = p,
                userGeoJson = userMarkerJson(p, it.userHeadingDeg),
                cameraTarget = if (it.userFollow) p else it.cameraTarget,
            )
        }
        if (_state.value.userFollow) center = p
    }

    /** A new device-orientation reading (degrees clockwise from true north). */
    fun onUserHeading(deg: Float) {
        if (!_state.value.userTracking) return
        _state.update {
            it.copy(userHeadingDeg = deg, userGeoJson = userMarkerJson(it.userLocation, deg))
        }
    }

    /** A manual map gesture stops the camera following the user; the marker keeps tracking. */
    fun disengageFollow() {
        if (_state.value.userFollow) _state.update { it.copy(userFollow = false) }
    }

    /** The orientation sensor's read on whether the compass is trustworthy right now. */
    fun onCompassCalibration(needed: Boolean) {
        if (_state.value.compassNeedsCalibration != needed) {
            _state.update { it.copy(compassNeedsCalibration = needed) }
        }
    }

    private fun userMarkerJson(p: LatLng?, heading: Float?): String =
        if (p == null) GeoJsonWriter.emptyCollection() else GeoJsonWriter.userMarker(p, heading)

    fun clearDropped() {
        sunnyWindowJob?.cancel()
        _state.update { it.copy(dropped = null, sunnyWindow = null, pinGeoJson = GeoJsonWriter.emptyCollection()) }
    }

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
        val city = downloadedCity
        if (bundledRegion?.contains(newCenter) == true) {
            // Swap back to bundled data when returning from outside the bundled region.
            if (activeBuildings !== bundledBuildings) {
                activeBuildings = bundledBuildings
                forgetActiveCity()
                _state.update { it.copy(sourceLabel = "Berlin · ${bundledBuildings.size} buildings") }
            }
        } else if (city != null && downloadedCityRegion()?.contains(newCenter) == true) {
            // Swap back to the active downloaded city's data when returning to it.
            if (activeBuildings !== downloadedCityBuildings) {
                activeBuildings = downloadedCityBuildings
                _state.update { it.copy(sourceLabel = "${city.name} · ${downloadedCityBuildings.size} buildings") }
            }
        } else if (activeBuildings.isNotEmpty()) {
            // The held buildings (bundled/downloaded-city data, or an earlier tile harvest) no
            // longer cover where we're looking — e.g. just left that region, or panned far since
            // the last successful tile query. Drop them instead of leaving a stale building count
            // (and stale shadows) up while tile harvesting catches up to the new view; otherwise
            // the title pill can claim thousands of buildings are loaded while the map shows none
            // of them and renders no shade at all.
            val coverage = BoundingBox.ofBuildings(activeBuildings)?.expandedMeters(STALE_DATA_MARGIN_M)
            if (coverage?.contains(newCenter) != true) {
                activeBuildings = emptyList()
                accumulated.clear()
                forgetActiveCity()
                _state.update { it.copy(sourceLabel = "Loading buildings…") }
            }
        }
        // Force a re-rank: the spot order now depends on distance from the map centre, not just
        // the sun's position, so a moved centre must always refresh it — but skip the (expensive)
        // recompute entirely for sub-threshold moves, e.g. follow-camera nudging the view by a
        // metre or two on every GPS fix while walking. Nothing visible can change at that scale.
        val last = lastRecomputeCenter
        if (last == null || distanceMeters(last, newCenter) >= RECOMPUTE_MIN_MOVE_M) {
            lastRecomputeCenter = newCenter
            recompute(rank = true)
            fetchWeather(newCenter)
        }
    }

    /** Fetch (or reuse a cached) cloud cover/UV reading for [p]. Annotation only — never gates shade. */
    private fun fetchWeather(p: LatLng) {
        if (!_state.value.allowRoaming) return
        val key = weatherKey(p)
        weatherCache[key]?.let { cached ->
            _state.update { it.copy(weather = cached) }
            return
        }
        weatherJob?.cancel()
        weatherJob = viewModelScope.launch {
            delay(500) // debounce rapid panning
            val snapshot = WeatherClient.current(p.lat, p.lng) ?: return@launch
            weatherCache[key] = snapshot
            _state.update { it.copy(weather = snapshot) }
        }
    }

    private fun weatherKey(p: LatLng): String {
        val gridLat = Math.round(p.lat * 100) // ~1.1 km cells
        val gridLng = Math.round(p.lng * 100)
        val hour = java.time.LocalDateTime.now().hour
        return "${gridLat}_${gridLng}_$hour"
    }

    /**
     * Building footprints harvested directly from the rendered map tiles (no network).
     * Parsed off the main thread and fed straight into the shadow engine.
     */
    fun onBuildingsQueried(features: List<org.maplibre.geojson.Feature>, belowZoom: Boolean) {
        // Bundled/downloaded-city data is more complete than tile queries — skip both regions.
        if (bundledRegion?.contains(center) == true) return
        if (downloadedCityRegion()?.contains(center) == true) return
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

    // --- City download (worldwide coverage) -------------------------------------------------

    /** Search OpenStreetMap for a city/place to download. */
    fun searchCities(query: String) {
        if (!_state.value.allowRoaming) {
            _state.update { it.copy(cityStatus = "Network data is off — enable it in Settings to search.") }
            return
        }
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            _state.update { it.copy(cityBusy = true, cityStatus = null) }
            val hits = runCatching { Geocoder.search(query) }.getOrDefault(emptyList())
            _state.update {
                it.copy(cityBusy = false, citySearch = hits,
                    cityStatus = if (hits.isEmpty()) "No matches — try another name" else null)
            }
        }
    }

    fun clearCitySearch() = _state.update { it.copy(citySearch = emptyList(), cityStatus = null) }

    fun dismissCityPrompt() = _state.update { it.copy(promptCity = false) }

    /** Toggle whether the network may be used for place search + city downloads. */
    fun setAllowRoaming(value: Boolean) {
        viewModelScope.launch { store.setAllowRoaming(value) }
    }

    // --- Update checking (opt-in) -----------------------------------------------------------

    /** Persist the user's opt-in choice. The init collector reacts and runs a check if enabled. */
    fun setUpdateChecks(enabled: Boolean) {
        viewModelScope.launch { store.setUpdateChecksEnabled(enabled) }
    }

    /** Dismiss the first-run prompt without persisting a choice — it reappears next launch. */
    fun dismissUpdateOptIn() = _state.update { it.copy(promptUpdateOptIn = false) }

    /** Hide the update banner and remember the tag so the same release isn't shown again. */
    fun dismissUpdate() {
        val tag = _state.value.updateAvailable?.tag
        _state.update { it.copy(updateAvailable = null) }
        if (tag != null) viewModelScope.launch { store.setLastSeenTag(tag) }
    }

    /** Force a check from Settings — surfaces the result even if previously dismissed. */
    fun checkForUpdatesNow() {
        if (!_state.value.allowRoaming) return
        runUpdateCheck(userInitiated = true)
    }

    /** Run a check only if enough time has passed since the last one (and the network is allowed). */
    private suspend fun maybeCheckForUpdate() {
        if (!_state.value.allowRoaming) return
        val last = store.lastUpdateCheck.first()
        if (System.currentTimeMillis() - last < UPDATE_CHECK_INTERVAL_MS) return
        runUpdateCheck()
    }

    private fun runUpdateCheck(userInitiated: Boolean = false) {
        viewModelScope.launch {
            _state.update { it.copy(checkingForUpdate = true) }
            val info = UpdateChecker.checkLatest(currentVersion())
            store.setLastUpdateCheck(System.currentTimeMillis())
            // A user-initiated check shows the result regardless; a background one suppresses a
            // release the user already dismissed.
            val seen = if (userInitiated) "" else store.lastSeenTag.first()
            _state.update {
                it.copy(
                    checkingForUpdate = false,
                    updateAvailable = info?.takeIf { u -> u.tag != seen },
                )
            }
        }
    }

    private fun currentVersion(): String = runCatching {
        val app = getApplication<Application>()
        app.packageManager.getPackageInfo(app.packageName, 0).versionName.orEmpty()
    }.getOrNull().orEmpty()

    /** Re-download a city whose data is already cached (e.g. to get a newer dataset). */
    fun redownloadCity(city: app.shadey.data.CachedCity) {
        downloadCity(CityHit(city.name, city.lat, city.lng, city.south, city.west, city.north, city.east))
    }

    /** Cancel any in-progress city download. */
    fun cancelDownload() {
        downloadJob?.cancel()
        downloadJob = null
        _state.update { it.copy(cityBusy = false, cityStatus = null) }
    }

    /** Download a searched city's buildings, cache them, and switch to it. */
    fun downloadCity(hit: CityHit) {
        if (!_state.value.allowRoaming) {
            _state.update { it.copy(cityStatus = "Network data is off — enable it in Settings to download.") }
            return
        }
        downloadJob = viewModelScope.launch {
            _state.update { it.copy(cityBusy = true, cityStatus = "${hit.name} — connecting…") }
            val slug = CityStore.slugOf(hit.name)
            // Download into a staging file and only commit it over any existing city data once
            // it has parsed to a non-empty building list — a failed re-download keeps the old data.
            val staging = cityStore.stagingFileFor(slug)
            try {
                val bbox = BuildingDownloader.clampedBbox(hit)
                BuildingDownloader.downloadGeoJson(bbox, staging) { status ->
                    _state.update { it.copy(cityStatus = "${hit.name} — $status") }
                }
                _state.update { it.copy(cityStatus = "${hit.name} — loading…") }
                val buildings = withContext(Dispatchers.Default) { GeoJsonFile.buildings(staging) }
                if (buildings.isEmpty()) {
                    withContext(Dispatchers.IO) { staging.delete() }
                    _state.update { it.copy(cityBusy = false, cityStatus = "No buildings found there") }
                    return@launch
                }
                val city = CachedCity(
                    slug, hit.name, hit.lat, hit.lng,
                    bbox[0], bbox[1], bbox[2], bbox[3], buildings.size,
                )
                val updated = withContext(Dispatchers.IO) { cityStore.commit(city, staging); cityStore.list() }
                activateCity(city, buildings)
                _state.update {
                    it.copy(cityBusy = false, cityStatus = null, citySearch = emptyList(),
                        cachedCities = updated)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                staging.delete()
                throw e // let coroutine machinery handle it; state already reset by cancelDownload()
            } catch (e: Exception) {
                staging.delete()
                _state.update { it.copy(cityBusy = false, cityStatus = e.message ?: "Download failed") }
            }
        }
    }

    /** Switch to an already-downloaded city (works offline, instant). */
    fun useCity(slug: String) {
        viewModelScope.launch {
            val file = withContext(Dispatchers.IO) { cityStore.geoJsonFileOf(slug) } ?: return@launch
            val city = withContext(Dispatchers.IO) { cityStore.list() }.firstOrNull { it.slug == slug } ?: return@launch
            val buildings = withContext(Dispatchers.Default) {
                runCatching { GeoJsonFile.buildings(file) }.getOrDefault(emptyList())
            }
            if (buildings.isEmpty()) return@launch
            withContext(Dispatchers.IO) { cityStore.setLastUsed(slug) }
            activateCity(city, buildings)
        }
    }

    /**
     * Make a downloaded city the active region: its data drives shadows and, when [moveCamera] is
     * set, the map jumps to it. An explicit city switch (search → download, or picking a cached
     * city) does want the camera to follow; the silent restore on launch does NOT — otherwise an
     * Activity/process recreation (e.g. Android reclaiming the backgrounded app's memory, then the
     * user returning) re-runs init and teleports the camera to whatever city was last used, which
     * reads as the map randomly jumping mid-session. So the restore keeps the data but leaves the
     * camera wherever it already is.
     */
    private fun activateCity(city: CachedCity, buildings: List<Building>, moveCamera: Boolean = true) {
        downloadedCity = city
        downloadedCityBuildings = buildings
        activeBuildings = buildings
        accumulated.clear()
        shadowCache.clear()
        shadowCacheSunKey = null
        framesViewKey = null
        if (moveCamera) center = LatLng(city.lat, city.lng)
        _state.update {
            it.copy(
                sourceLabel = "${city.name} · ${buildings.size} buildings",
                cameraTarget = if (moveCamera) LatLng(city.lat, city.lng) else it.cameraTarget,
            )
        }
        recompute(rank = true, immediate = true)
    }

    /** Stop treating a downloaded city as "what to restore on next launch" once the map has
     *  panned away from it — otherwise a cold start (including one forced by the OS killing the
     *  backgrounded app) silently jumps the camera back to a city the user isn't even looking at
     *  anymore, instead of resuming wherever they actually left off. */
    private fun forgetActiveCity() {
        if (downloadedCity == null) return
        downloadedCity = null
        downloadedCityBuildings = emptyList()
        viewModelScope.launch(Dispatchers.IO) { cityStore.clearLastUsed() }
    }

    private fun evaluatePoint(p: LatLng): SpotSunInfo {
        val now = instant()
        val sun = SolarCalculator.position(p, now)
        val near = buildingsNear(p, activeBuildings)
        val tmp = Spot("dropped", "Dropped pin", p.lat, p.lng, source = SpotSource.USER)
        return SpotSunInfo(tmp, engine.sunlightAt(p, sun, near), sun, engine.nextTransition(p, near, now)?.at)
    }

    /** Looks ahead for the next sunny spell today at [p], unless it's already sunny. */
    private fun scheduleSunnyWindow(p: LatLng, sunlight: Sunlight) {
        sunnyWindowJob?.cancel()
        _state.update { it.copy(sunnyWindow = null) }
        if (sunlight == Sunlight.SUN) return
        sunnyWindowJob = viewModelScope.launch {
            val now = instant()
            val near = buildingsNear(p)
            val window = withContext(Dispatchers.Default) {
                val endOfDay = now.atZone(zone).toLocalDate().plusDays(1).atStartOfDay(zone).toInstant()
                val within = Duration.between(now, endOfDay)
                if (within.isZero || within.isNegative) null
                else engine.nextSunWindow(p, near, now, within)
            }
            _state.update { it.copy(sunnyWindow = window) }
        }
    }

    // One spatial index per loaded building set, reused across route scoring, spot ranking, and
    // shadow gathering. Rebuilt only when activeBuildings is swapped (a new city / fresh download),
    // keyed by reference identity. @Synchronized because these lookups run on background dispatchers
    // and several coroutines may otherwise race to build the index on the first call after a swap.
    private var spatialIndex: BuildingIndex? = null
    private var spatialIndexFor: List<Building>? = null

    @Synchronized
    private fun indexFor(buildings: List<Building>): BuildingIndex {
        val cached = spatialIndex
        if (cached != null && spatialIndexFor === buildings) return cached
        return BuildingIndex(buildings).also {
            spatialIndex = it
            spatialIndexFor = buildings
        }
    }

    private fun buildingsNear(p: LatLng, buildings: List<Building> = activeBuildings, radiusMeters: Double = 800.0): List<Building> =
        indexFor(buildings).near(p, radiusMeters)

    private fun buildingsInView(c: LatLng = center, buildings: List<Building> = activeBuildings): List<Building> {
        val b = bounds ?: return buildingsNear(c, buildings)
        // Expand the view bbox so buildings just outside screen can still cast shadows into view.
        // At a 10° sun elevation a 30m building casts a ~170m shadow; use 500m to cover low angles.
        val box = BoundingBox(b.south, b.west, b.north, b.east).expandedMeters(500.0)
        return indexFor(buildings).inBox(box.south, box.west, box.north, box.east)
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
            // Surface a phase indicator in the status pill — the heavy castShadow loop below can
            // take several seconds on first hit, and a silent UI looks frozen. Cancellation
            // doesn't reach the clear-busy update at the end; that's fine because the next
            // recompute (which caused the cancellation) re-sets busy=true on its first line.
            _state.update { it.copy(busy = true, busyLabel = "Computing shade…") }
            val now = instant(_state.value)
            val spots = (curated + userSpots).distinctBy { it.id }
            // Snapshot mutable fields before the background thread — the sort comparator below
            // must see a stable centre, and activeBuildings can change on the main thread.
            val frozenCenter = center
            val frozenBuildings = activeBuildings
            val sun = SolarCalculator.position(frozenCenter, now)
            val result = withContext(Dispatchers.Default) {
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
                // Rank when the sun moved, the map centre moved (ranking is centre-relative
                // now), when forced, or on the very first pass. The centre check matters even for
                // unforced calls: a later plain recompute() (e.g. once buildings finish loading)
                // can cancel and replace an in-flight forced re-rank, and it must still notice the
                // origin changed rather than silently reusing a stale order.
                val doRank = rank || rankedSunKey != sunKey || rankedCenter != frozenCenter ||
                    _state.value.ranked.isEmpty()
                val ranked = if (doRank) {
                    rankedSunKey = sunKey
                    rankedCenter = frozenCenter
                    ranker.rank(spots, now, frozenCenter) { buildingsNear(it.latLng, frozenBuildings, radiusMeters = 150.0) }
                } else null
                rings to ranked
            }
            val (rings, ranked) = result
            _state.update {
                it.copy(
                    busy = false,
                    busyLabel = "",
                    shadowsGeoJson = GeoJsonWriter.shadows(rings),
                    ranked = ranked ?: it.ranked,
                    spotsGeoJson = if (ranked != null) GeoJsonWriter.spots(ranked) else it.spotsGeoJson,
                    sunAzimuthDeg = sun.azimuthDeg,
                    sunElevationDeg = sun.elevationDeg,
                )
            }
            // Build the day's frames for this view in the background so scrubbing is instant.
            precomputeFrames()
        }
    }

    /** Buildings to cast shadows for in the live view: those in view, closest first, capped. */
    private fun inViewBuildings(c: LatLng, buildings: List<Building>): List<Building> {
        val idx = indexFor(buildings)
        return buildingsInView(c, buildings)
            .sortedBy { distanceSq(c, idx.centroidOf(it)) }
            .take(MAX_SHADOWS)
    }

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
     *
     * A 2-second settle delay before the heavy computation begins ensures rapid panning (which
     * cancels and restarts this job on every camera-idle event) generates no garbage at all —
     * only a stable view triggers actual work, so the GC churn from creating/discarding 144
     * per-frame shadow JSON strings on every pan is eliminated.
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
            // Wait for the view to settle before doing any expensive allocation.
            // If the user is still panning this job will be cancelled before the delay fires,
            // producing zero garbage. Only a stable view proceeds to actual frame computation.
            delay(2_000L)
            if (!isActive) return@launch
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
                .use { GeoJsonFile.buildings(it) }
        }.getOrDefault(emptyList())
    }

    private suspend fun loadCurated(): List<Spot> = withContext(Dispatchers.IO) {
        runCatching {
            getApplication<Application>().assets.open("data/spots.json")
                .bufferedReader().use { it.readText() }
        }.getOrNull()?.let { SpotsJson.parse(it) } ?: emptyList()
    }

    private fun distanceSq(a: LatLng, b: LatLng): Double {
        val dLat = a.lat - b.lat
        val dLng = a.lng - b.lng
        return dLat * dLat + dLng * dLng
    }

    /** Rough metric distance between two coordinates (equirectangular approx — fine at this scale). */
    private fun distanceMeters(a: LatLng, b: LatLng): Double {
        val meanLat = Math.toRadians((a.lat + b.lat) / 2.0)
        val dLat = Math.toRadians(b.lat - a.lat)
        val dLng = Math.toRadians(b.lng - a.lng) * Math.cos(meanLat)
        return Math.sqrt(dLat * dLat + dLng * dLng) * 6_371_000.0
    }

    private companion object {
        // Closest N buildings only — distant ones cast negligible shadows and dominate CPU time.
        const val MAX_SHADOWS = 600
        val EMPTY_RING = emptyList<LatLng>()
        const val MIN_BUNDLED_BUILDINGS = 1000
        const val MAX_CACHE_ENTRIES = 6000
        const val MAX_ACCUMULATED = 8000
        // How far the map centre can drift from the held buildings' bounding box before that
        // data is considered stale for the current view (see onCameraIdle).
        const val STALE_DATA_MARGIN_M = 3_000.0
        // Minimum centre movement (metres) between camera-idle events before a full recompute
        // runs again — throttles follow-camera, which re-centres on every ~1s GPS fix while
        // walking, from rebuilding the shadow GeoJSON and pushing it into the map far more often
        // than anything visible could actually change.
        const val RECOMPUTE_MIN_MOVE_M = 15.0
        // Background update checks run at most once per day.
        const val UPDATE_CHECK_INTERVAL_MS = 24L * 60 * 60 * 1000
        // Route shade-scoring sample spacing — fine enough to catch individual buildings'
        // shadows without sampling so densely that scoring a multi-km walk gets slow.
        const val ROUTE_SAMPLE_STEP_M = 25.0
        const val ROUTE_ORIGIN_COLOR = "#2ECC71"
        const val ROUTE_DEST_COLOR = "#E74C3C"
        // 15-minute buckets → 96 frames per day instead of 144. Combined with the 2-second
        // settle delay in precomputeFrames(), this cuts per-run garbage and the sustained GC
        // pressure from rapid panning is eliminated entirely.
        const val FRAME_STEP_MIN = 15
    }
}
