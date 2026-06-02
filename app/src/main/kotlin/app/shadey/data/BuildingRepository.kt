package app.shadey.data

import android.content.Context
import app.shadey.core.data.GeoJsonBuildings
import app.shadey.core.model.Building
import app.shadey.core.model.LatLng
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Source of building geometry. Bundled (offline) data is the default; OSM is only
 * consulted on demand for areas outside the bundled region, and only if allowed.
 */
class BuildingRepository(private val context: Context) {

    @Volatile
    private var bundled: List<Building> = emptyList()

    @Volatile
    var bundledGeoJson: String = "{\"type\":\"FeatureCollection\",\"features\":[]}"
        private set

    @Volatile
    var bundledRegion: BoundingBox? = null
        private set

    private val overpass = OverpassClient()
    private val osmCache = LinkedHashMap<String, List<Building>>()

    suspend fun loadBundled(): List<Building> = withContext(Dispatchers.IO) {
        if (bundled.isEmpty()) {
            val text = readAsset("data/berlin_buildings.geojson")
                ?: readAsset("data/sample_buildings.geojson")
            if (text != null) {
                bundledGeoJson = text
                bundled = GeoJsonBuildings.parse(text)
                bundledRegion = BoundingBox.ofBuildings(bundled)
            }
        }
        bundled
    }

    fun isCovered(p: LatLng): Boolean =
        bundledRegion?.expandedMeters(100.0)?.contains(p) ?: false

    fun allBundled(): List<Building> = bundled

    /** Cached OSM fetch for a small area around [p]. Throws if the network request fails. */
    suspend fun fetchOsmAround(p: LatLng, radiusMeters: Double = 700.0): List<Building> {
        val box = BoundingBox.around(p, radiusMeters)
        val key = "%.3f_%.3f_%.3f_%.3f".format(box.south, box.west, box.north, box.east)
        osmCache[key]?.let { return it }
        val fetched = overpass.fetchBuildings(box) // throws on network/HTTP error
        if (fetched.isNotEmpty()) {
            osmCache[key] = fetched
            if (osmCache.size > 24) osmCache.remove(osmCache.keys.first())
        }
        return fetched
    }

    private fun readAsset(path: String): String? =
        runCatching { context.assets.open(path).bufferedReader().use { it.readText() } }.getOrNull()
}
