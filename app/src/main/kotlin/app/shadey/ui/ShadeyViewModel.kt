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
import app.shadey.core.model.Sunlight
import app.shadey.core.rank.SpotRanker
import app.shadey.core.rank.SpotSunInfo
import app.shadey.core.shade.ShadowEngine
import app.shadey.core.solar.SolarCalculator
import app.shadey.data.BoundingBox
import app.shadey.data.BuildingDownloader
import app.shadey.data.CachedCity
import app.shadey.data.CityHit
import app.shadey.data.CityStore
import app.shadey.data.Geocoder
import app.shadey.data.GeoJsonFile
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
import kotlinx.coroutines.isActive
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
    /**
     * Whether tree canopies (where the data is known) cast shade alongside buildings. Off by
     * default — tree position/size data is far rougher than building footprints (often
     * estimated), and currently only available for freshly downloaded cities.
     */
    val treeShade: Boolean = false,
    /** True while treeShade is on but the current city has no tree data (needs re-download). */
    val treeShadeNoData: Boolean = false,
    /** Non-empty when the active city has trees loaded, e.g. "4 521 trees". */
    val treeCountLabel: String = "",
    /** True when the user just toggled tree shade on but there is no tree data — opens the Cities dialog. */
    val promptTreeDownload: Boolean = false,
    /** True when there's no usable building data yet, so the UI should prompt for a city. */
    val promptCity: Boolean = false,
) {
    val selected: SpotSunInfo? get() = ranked.firstOrNull { it.spot.id == selectedId }
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
    // Tree canopies for the active city, pre-converted to the short solid prisms the shadow
    // engine already knows how to cast/test (see Tree.canopy()) — populated only for cities
    // downloaded since tree fetching was added; empty (and harmless) everywhere else.
    private var activeTreeCanopies: List<Building> = emptyList()
    private var activeCitySlug: String? = null
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
    // The sun bucket and map centre the spot ranking was last computed for. Ranking
    // (nextTransition) is the expensive part, so we skip it unless the sun moved or the
    // origin changed enough to matter — checked here (not just via the `rank` flag) so a
    // forced re-rank request can't be lost to a later, unforced recompute cancelling it.
    @Volatile private var rankedSunKey: String? = null
    @Volatile private var rankedCenter: LatLng? = null
    @Volatile private var rankedTreeShade: Boolean = false

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
                if (b.isNotEmpty()) { activateCity(lastCity, b); true } else false
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
            // Restores the persisted value on launch (and keeps it in sync thereafter). Only
            // acts when the value actually changes from what's already showing. If a city is
            // already loaded when it fires, start the lazy tree load now; otherwise activateCity
            // will do it once the city is ready (startup race).
            store.treeShade.collect { on ->
                val changed = _state.value.treeShade != on
                _state.update { it.copy(treeShade = on) }
                if (changed) {
                    if (on && activeTreeCanopies.isEmpty() && activeCitySlug != null) {
                        loadTreesForActiveCity()
                    }
                    recompute(rank = true)
                }
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

    fun goToPlace(hit: app.shadey.data.CityHit) = moveTo(LatLng(hit.lat, hit.lng))

    fun dropPinAtCenter() = onMapClick(center)

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
        // Force a re-rank: the spot order now depends on distance from the map centre,
        // not just the sun's position, so a moved centre must always refresh it.
        recompute(rank = true)
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

    fun dismissTreeDownloadPrompt() = _state.update { it.copy(promptTreeDownload = false) }

    /** Toggle whether the network may be used for place search + city downloads. */
    fun setAllowRoaming(value: Boolean) {
        viewModelScope.launch { store.setAllowRoaming(value) }
    }

    /** Toggle whether tree canopies (when known) cast shade alongside buildings. */
    fun setTreeShade(value: Boolean) {
        _state.update {
            it.copy(
                treeShade = value,
                treeShadeNoData = if (!value) false else it.treeShadeNoData,
                treeCountLabel = if (!value) "" else it.treeCountLabel,
            )
        }
        viewModelScope.launch {
            store.setTreeShade(value)
            if (value && activeTreeCanopies.isEmpty()) loadTreesForActiveCity(showPrompt = true)
        }
        recompute(rank = true)
    }

    /** Re-download a city whose data is already cached (e.g. to get a newer dataset with trees). */
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

    /** Make a downloaded city the active region: its data drives shadows and the map jumps to it. */
    private fun activateCity(city: CachedCity, buildings: List<Building>) {
        bundledBuildings = buildings
        bundledRegion = BoundingBox(city.south, city.west, city.north, city.east)
        activeBuildings = buildings
        activeTreeCanopies = emptyList()
        activeCitySlug = city.slug
        accumulated.clear()
        shadowCache.clear()
        shadowCacheSunKey = null
        framesViewKey = null
        center = LatLng(city.lat, city.lng)
        _state.update {
            it.copy(
                sourceLabel = "${city.name} · ${buildings.size} buildings",
                cameraTarget = center,
                treeShadeNoData = false,
                treeCountLabel = "",
            )
        }
        // Tree canopies are loaded lazily when the toggle is on — avoids a second full GeoJSON
        // parse on every startup for users who leave the toggle off (the common case).
        if (_state.value.treeShade) {
            viewModelScope.launch { loadTreesForActiveCity() }
        }
        recompute(rank = true, immediate = true)
    }

    /**
     * Loads tree canopies for the currently-active downloaded city from its cached GeoJSON.
     * Sets [ShadeyUiState.treeShadeNoData] if the city pre-dates tree data or has no trees.
     * When [showPrompt] is true (only for explicit user toggle) also sets
     * [ShadeyUiState.promptTreeDownload] to auto-open the Cities dialog.
     * No-ops if no city is active (bundled Berlin).
     */
    private suspend fun loadTreesForActiveCity(showPrompt: Boolean = false) {
        val slug = activeCitySlug ?: run {
            if (_state.value.treeShade) _state.update {
                it.copy(treeShadeNoData = true, promptTreeDownload = showPrompt)
            }
            return
        }
        val file = withContext(Dispatchers.IO) { cityStore.geoJsonFileOf(slug) } ?: run {
            if (_state.value.treeShade) _state.update {
                it.copy(treeShadeNoData = true, promptTreeDownload = showPrompt)
            }
            return
        }
        val canopies = withContext(Dispatchers.Default) {
            runCatching { GeoJsonFile.trees(file) }.getOrDefault(emptyList())
                .take(MAX_TREES).map { it.canopy() }
        }
        activeTreeCanopies = canopies
        val noData = canopies.isEmpty() && _state.value.treeShade
        _state.update {
            it.copy(
                treeShadeNoData = noData,
                promptTreeDownload = if (noData) showPrompt else false,
                treeCountLabel = if (canopies.isNotEmpty()) "${canopies.size} trees" else "",
            )
        }
        if (canopies.isNotEmpty()) recompute(rank = true)
    }

    /**
     * Everything that should currently cast/block sunlight: buildings, plus — when the
     * tree-shade toggle is on and the active city has tree data — synthetic canopy prisms.
     * Merging here (rather than into [activeBuildings] itself) means flipping the toggle
     * never needs a re-download or re-parse, just a recompute.
     */
    private fun shadowSources(): List<Building> =
        if (_state.value.treeShade && activeTreeCanopies.isNotEmpty()) activeBuildings + activeTreeCanopies
        else activeBuildings

    private fun evaluatePoint(p: LatLng): SpotSunInfo {
        val now = instant()
        val sun = SolarCalculator.position(p, now)
        val near = buildingsNear(p, shadowSources())
        val tmp = Spot("dropped", "Dropped pin", p.lat, p.lng, source = SpotSource.USER)
        return SpotSunInfo(tmp, engine.sunlightAt(p, sun, near), sun, engine.nextTransition(p, near, now)?.at)
    }

    private fun buildingsNear(p: LatLng, buildings: List<Building> = activeBuildings, radiusMeters: Double = 800.0): List<Building> {
        val box = BoundingBox.around(p, radiusMeters)
        return buildings.filter { box.contains(it.centroid()) }
    }

    private fun buildingsInView(c: LatLng = center, buildings: List<Building> = activeBuildings): List<Building> {
        val b = bounds ?: return buildingsNear(c, buildings)
        // Expand the view bbox so buildings just outside screen can still cast shadows into view.
        // At a 10° sun elevation a 30m building casts a ~170m shadow; use 500m to cover low angles.
        val box = BoundingBox(b.south, b.west, b.north, b.east).expandedMeters(500.0)
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
            // must see a stable centre, and activeBuildings/treeShade can change on the main thread.
            val frozenCenter = center
            val frozenTreeShade = _state.value.treeShade
            val frozenBuildings = shadowSources()
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
                // Rank when the sun moved, the map centre moved (ranking is centre-relative
                // now), the tree-shade toggle flipped (it changes which buildings feed the sun
                // test), when forced, or on the very first pass. The centre/toggle checks matter
                // even for unforced calls: a later plain recompute() (e.g. once buildings finish
                // loading) can cancel and replace an in-flight forced re-rank, and it must still
                // notice the origin or sources changed rather than silently reusing a stale order.
                val doRank = rank || rankedSunKey != sunKey || rankedCenter != frozenCenter ||
                    rankedTreeShade != frozenTreeShade || _state.value.ranked.isEmpty()
                val ranked = if (doRank) {
                    rankedSunKey = sunKey
                    rankedCenter = frozenCenter
                    rankedTreeShade = frozenTreeShade
                    ranker.rank(spots, now, frozenCenter) { buildingsNear(it.latLng, frozenBuildings, radiusMeters = 150.0) }
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
        // Including the tree-shade flag (and how many canopies are in play) means flipping the
        // toggle busts the precomputed day frames just like a building-set change would.
        val trees = if (_state.value.treeShade) activeTreeCanopies.size else 0
        return "$box|${_state.value.date}|${activeBuildings.size}|$trees"
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
        val frozenBuildings = shadowSources()
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
                .use { GeoJsonFile.buildings(it) }
        }.getOrDefault(emptyList())
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
        const val MAX_SHADOWS = 600
        val EMPTY_RING = emptyList<LatLng>()
        const val MIN_BUNDLED_BUILDINGS = 1000
        const val MAX_CACHE_ENTRIES = 6000
        const val MAX_ACCUMULATED = 8000
        const val FRAME_STEP_MIN = 10
        // A generous cap on how many tree canopies a city keeps active. Dense urban tree
        // cadastres (Berlin's alone lists ~700k trees citywide) could otherwise hand the shadow
        // engine tens of thousands of extra prisms for one download — this bounds that without
        // needing the download bbox query itself to be smarter about it.
        const val MAX_TREES = 6000
    }
}
