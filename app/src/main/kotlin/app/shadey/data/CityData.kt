package app.shadey.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.math.max
import kotlin.math.min

/** A place returned by geocoding: a centre and a bounding box. */
data class CityHit(
    val name: String,
    val lat: Double,
    val lng: Double,
    val south: Double,
    val west: Double,
    val north: Double,
    val east: Double,
)

/** A city whose building data has been downloaded and cached on the device. */
data class CachedCity(
    val slug: String,
    val name: String,
    val lat: Double,
    val lng: Double,
    val south: Double,
    val west: Double,
    val north: Double,
    val east: Double,
    val buildingCount: Int,
)

private const val USER_AGENT = "Shadey/1.0 (+https://github.com/phrag/shadey)"

/** City search via OpenStreetMap Nominatim. */
object Geocoder {
    /**
     * Search for places matching [query]. When [viewbox] is supplied (west, south, east, north),
     * Nominatim biases results toward that area without excluding global matches (`bounded=0`).
     * This makes "Ostbahnhof" return the Berlin station first when the map shows Berlin.
     */
    suspend fun search(query: String, viewbox: DoubleArray? = null): List<CityHit> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext emptyList()
        val sb = StringBuilder("https://nominatim.openstreetmap.org/search?format=jsonv2&limit=8&q=")
        sb.append(URLEncoder.encode(query.trim(), "UTF-8"))
        if (viewbox != null && viewbox.size == 4) {
            // Nominatim viewbox order: left (west), top (north), right (east), bottom (south).
            val (w, s, e, n) = viewbox
            sb.append("&viewbox=$w,$n,$e,$s&bounded=0")
        }
        val body = httpGet(sb.toString()) ?: return@withContext emptyList()
        val arr = runCatching { JSONArray(body) }.getOrNull() ?: return@withContext emptyList()
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val lat = o.optString("lat").toDoubleOrNull() ?: return@mapNotNull null
            val lng = o.optString("lon").toDoubleOrNull() ?: return@mapNotNull null
            // Nominatim boundingbox is [south, north, west, east] as strings.
            val bb = o.optJSONArray("boundingbox")
            val south = bb?.optString(0)?.toDoubleOrNull() ?: (lat - 0.03)
            val north = bb?.optString(1)?.toDoubleOrNull() ?: (lat + 0.03)
            val west = bb?.optString(2)?.toDoubleOrNull() ?: (lng - 0.045)
            val east = bb?.optString(3)?.toDoubleOrNull() ?: (lng + 0.045)
            CityHit(shortName(o.optString("display_name"), query), lat, lng, south, west, north, east)
        }
    }

    /**
     * Returns a concise display name from Nominatim's long comma-separated display_name.
     * Keeps up to 3 parts (e.g. "Engelbecken, Luisenstadt, Berlin") so the user can tell
     * apart similarly named places in different cities.
     */
    private fun shortName(displayName: String, fallback: String): String {
        if (displayName.isBlank()) return fallback
        return displayName.split(",").map { it.trim() }.filter { it.isNotEmpty() }.take(3).joinToString(", ")
    }
}

/** Downloads building footprints + heights for a bounding box from OpenStreetMap (Overpass). */
object BuildingDownloader {
    private val ENDPOINTS = listOf(
        "https://overpass-api.de/api/interpreter",
        "https://overpass.kumi.systems/api/interpreter",
    )

    // Cap the downloaded area so a single city stays a manageable size on a phone.
    private const val MAX_HALF_LAT = 0.035 // ~3.9 km
    private const val MAX_HALF_LNG = 0.055

    private const val DEFAULT_HEIGHT_M = 9.0
    private const val METERS_PER_LEVEL = 3.2

    /** Centre the download on the hit and clamp its extent. Returns [south, west, north, east]. */
    fun clampedBbox(hit: CityHit): DoubleArray {
        val south = max(hit.south, hit.lat - MAX_HALF_LAT)
        val north = min(hit.north, hit.lat + MAX_HALF_LAT)
        val west = max(hit.west, hit.lng - MAX_HALF_LNG)
        val east = min(hit.east, hit.lng + MAX_HALF_LNG)
        return doubleArrayOf(south, west, north, east)
    }

    /**
     * Fetch buildings for [bbox], writing a GeoJSON FeatureCollection to [dest].
     *
     * The Overpass response for a large city can be 50–70 MB of JSON, and even the trimmed
     * GeoJSON output is tens of MB. Holding either in a String (let alone a parsed JSON tree)
     * OOMs on devices with a 256 MB heap limit. Instead we:
     *   1. Stream the HTTP response body to a temp file (8 KB chunks, no large in-memory copy).
     *   2. Stream-parse the raw Overpass file with JsonReader, writing each GeoJSON feature
     *      immediately to [dest] — so peak heap is one feature at a time, not the full city.
     *   3. Leave [dest] on disk for the caller to stream-parse (see [GeoJsonFile]) and keep;
     *      on failure the caller should delete it (it may hold a partial write).
     */
    suspend fun downloadGeoJson(bbox: DoubleArray, dest: File, onStatus: (String) -> Unit = {}): Unit = withContext(Dispatchers.IO) {
        val (s, w, n, e) = bbox
        // Trees come along for the ride so the cached city is ready for tree-shade the moment
        // it's switched on — no separate download/toggle gating to keep track of. Individual
        // tree nodes only (not rows/woods yet): the single most common, simplest-to-model case,
        // and Berlin's tree-cadastre import alone makes it worth having.
        val query = """
            [out:json][timeout:120];
            (
              way["building"]($s,$w,$n,$e);
              relation["building"]["type"="multipolygon"]($s,$w,$n,$e);
              node["natural"="tree"]($s,$w,$n,$e);
            );
            out body geom;
        """.trimIndent()
        val postData = "data=" + URLEncoder.encode(query, "UTF-8")
        val tmpFile = File.createTempFile("overpass", ".json")
        try {
            var lastErr: Exception? = null
            for (url in ENDPOINTS) {
                repeat(2) { attempt ->
                    try {
                        val ok = httpPostToFile(
                            url, postData, tmpFile,
                            isCancelled = { !isActive },
                            onProgress = { bytes -> onStatus("Downloading… ${formatMB(bytes)}") },
                        )
                        if (!isActive) throw CancellationException("Cancelled")
                        if (ok) {
                            onStatus("Processing buildings…")
                            overpassFileToGeoJson(tmpFile, dest)
                            return@withContext
                        }
                    } catch (ex: CancellationException) {
                        throw ex
                    } catch (ex: Exception) {
                        lastErr = ex
                    }
                    Thread.sleep(1500L * (attempt + 1))
                }
            }
            throw IOException("Could not reach OpenStreetMap: ${lastErr?.message ?: "unknown error"}")
        } finally {
            tmpFile.delete()
        }
    }

    /** POST [postBody] to [url], streaming the response body into [dest]. Returns false on non-2xx or cancellation. */
    private fun httpPostToFile(
        url: String,
        postBody: String,
        dest: File,
        isCancelled: () -> Boolean = { false },
        onProgress: (bytesWritten: Long) -> Unit = {},
    ): Boolean {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 30_000
            readTimeout = 120_000
            setRequestProperty("User-Agent", USER_AGENT)
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        }
        return try {
            conn.outputStream.use { it.write(postBody.toByteArray()) }
            if (conn.responseCode !in 200..299) return false
            conn.inputStream.use { inp ->
                dest.outputStream().use { out ->
                    val buf = ByteArray(8_192)
                    var total = 0L
                    var n: Int
                    while (inp.read(buf).also { n = it } != -1) {
                        if (isCancelled()) return false
                        out.write(buf, 0, n)
                        total += n
                        onProgress(total)
                    }
                }
            }
            true
        } finally {
            conn.disconnect()
        }
    }

    private fun formatMB(bytes: Long) = "%.1f MB".format(bytes.toDouble() / 1_048_576)

    /**
     * Stream-parse [source] (raw Overpass JSON) writing a GeoJSON FeatureCollection to [dest].
     *
     * Each feature JSONObject is stringified and written immediately so it can be GC'd — peak
     * heap is proportional to one feature, not the whole city. This avoids the OOM crash that
     * occurred when accumulating all features in a JSONArray before calling toString().
     */
    private fun overpassFileToGeoJson(source: File, dest: File) {
        dest.bufferedWriter(Charsets.UTF_8).use { w ->
            w.write("""{"type":"FeatureCollection","attribution":"(c) OpenStreetMap contributors, ODbL","features":[""")
            var first = true
            android.util.JsonReader(java.io.InputStreamReader(source.inputStream(), "UTF-8")).use { r ->
                r.beginObject()
                while (r.hasNext()) {
                    if (r.nextName() == "elements") {
                        r.beginArray()
                        while (r.hasNext()) {
                            parseElement(r)?.let { feature ->
                                if (!first) w.write(",")
                                w.write(feature.toString())
                                first = false
                            }
                        }
                        r.endArray()
                    } else {
                        r.skipValue()
                    }
                }
            }
            w.write("]}")
        }
    }

    private fun parseElement(r: android.util.JsonReader): JSONObject? {
        var type = ""; var id = ""
        var lat = Double.NaN; var lon = Double.NaN
        var tags: Map<String, String> = emptyMap()
        var wayRing: JSONArray? = null
        val outerRings = JSONArray()
        r.beginObject()
        while (r.hasNext()) {
            when (r.nextName()) {
                "type" -> type = r.nextString()
                "id"   -> id = r.nextLong().toString()
                "lat"  -> lat = r.nextDouble()
                "lon"  -> lon = r.nextDouble()
                "tags" -> tags = readTags(r)
                "geometry" -> wayRing = readRing(r)
                "members"  -> readOuters(r, outerRings)
                else -> r.skipValue()
            }
        }
        r.endObject()
        return when (type) {
            "way" -> wayRing?.let { feature(buildingProps(tags, type, id), polygon(it)) }
            "relation" -> if (outerRings.length() > 0) {
                val polys = JSONArray()
                for (i in 0 until outerRings.length()) polys.put(JSONArray().put(outerRings.getJSONArray(i)))
                feature(buildingProps(tags, type, id), JSONObject().put("type", "MultiPolygon").put("coordinates", polys))
            } else null
            "node" -> if (tags["natural"] == "tree" && !lat.isNaN() && !lon.isNaN()) {
                feature(treeProps(tags, id), point(lon, lat))
            } else null
            else -> null
        }
    }

    private fun readRing(r: android.util.JsonReader): JSONArray? {
        val ring = JSONArray()
        var oversized = false
        r.beginArray()
        while (r.hasNext()) {
            var lat = Double.NaN; var lon = Double.NaN
            r.beginObject()
            while (r.hasNext()) {
                when (r.nextName()) {
                    "lat" -> lat = r.nextDouble()
                    "lon" -> lon = r.nextDouble()
                    else  -> r.skipValue()
                }
            }
            r.endObject()
            if (!lat.isNaN() && !lon.isNaN() && !oversized) {
                ring.put(JSONArray().put(lon).put(lat))
                // A single ring with > MAX_RING_POINTS vertices can produce a JSONObject
                // whose toString() exceeds the heap limit (e.g. an OSM administrative
                // boundary tagged as a building). Drain the rest of the ring from the
                // reader and discard this feature rather than OOM.
                if (ring.length() >= MAX_RING_POINTS) oversized = true
            }
        }
        r.endArray()
        return if (!oversized && ring.length() >= 4) ring else null
    }

    private fun readOuters(r: android.util.JsonReader, out: JSONArray) {
        r.beginArray()
        while (r.hasNext()) {
            var role = ""; var ring: JSONArray? = null
            r.beginObject()
            while (r.hasNext()) {
                when (r.nextName()) {
                    "role"     -> role = r.nextString()
                    "geometry" -> ring = readRing(r)
                    else       -> r.skipValue()
                }
            }
            r.endObject()
            if (role == "outer" && ring != null && out.length() < MAX_OUTER_RINGS) out.put(ring)
        }
        r.endArray()
    }

    /** OSM tag values are always JSON strings — read the whole `tags` object as a string map. */
    private fun readTags(r: android.util.JsonReader): Map<String, String> {
        val tags = HashMap<String, String>()
        r.beginObject()
        while (r.hasNext()) tags[r.nextName()] = r.nextString()
        r.endObject()
        return tags
    }

    private fun buildingProps(tags: Map<String, String>, type: String, id: String): JSONObject =
        JSONObject().put("height", buildingHeight(tags)).put("osm_id", "$type/$id")

    private fun buildingHeight(tags: Map<String, String>): Double {
        (tags["height"] ?: tags["building:height"])
            ?.filter { it.isDigit() || it == '.' }?.toDoubleOrNull()
            ?.let { if (it > 0) return it }
        tags["building:levels"]?.substringBefore(";")?.toDoubleOrNull()
            ?.let { if (it > 0) return it * METERS_PER_LEVEL }
        return DEFAULT_HEIGHT_M
    }

    /**
     * Tree properties as point-feature tags: `crown_radius`/`height` are included only when
     * OSM actually has them (most tree nodes don't) — [GeoJsonFile.trees] fills in defaults for
     * the rest. `deciduous` defaults true (most urban street trees are), false only for an
     * explicit `leaf_cycle=evergreen`.
     */
    private fun treeProps(tags: Map<String, String>, id: String): JSONObject {
        val props = JSONObject()
            .put("kind", "tree")
            .put("osm_id", "node/$id")
            .put("deciduous", tags["leaf_cycle"] != "evergreen")
        tags["diameter_crown"]?.toDoubleOrNull()?.let { if (it > 0) props.put("crown_radius", it / 2.0) }
        tags["height"]?.filter { it.isDigit() || it == '.' }?.toDoubleOrNull()?.let { if (it > 0) props.put("height", it) }
        return props
    }

    private fun point(lng: Double, lat: Double): JSONObject =
        JSONObject().put("type", "Point").put("coordinates", JSONArray().put(lng).put(lat))

    private fun feature(props: JSONObject, geometry: JSONObject): JSONObject =
        JSONObject().put("type", "Feature").put("properties", props).put("geometry", geometry)

    private fun polygon(ring: JSONArray): JSONObject =
        JSONObject().put("type", "Polygon").put("coordinates", JSONArray().put(ring))

    /** Rings with more vertices than this are likely OSM admin boundaries mislabelled as
     *  buildings. Calling toString() on a JSONObject with 500k+ coordinates allocates a
     *  String large enough to OOM a 256 MB heap — skip those features instead. */
    private const val MAX_RING_POINTS = 5_000

    /** Cap multipolygon outer rings to bound the per-feature JSON size. */
    private const val MAX_OUTER_RINGS = 50
}

/** Persists downloaded city building data in the app's private files dir. */
class CityStore(private val filesDir: File) {
    private val dir = File(filesDir, "cities").apply {
        mkdirs()
        // Staging files only survive a crash mid-download; they're never valid city data.
        listFiles { f -> f.name.endsWith(".part") }?.forEach { it.delete() }
    }
    private val indexFile = File(dir, "index.json")

    fun list(): List<CachedCity> {
        val text = runCatching { indexFile.readText() }.getOrNull() ?: return emptyList()
        val arr = runCatching { JSONObject(text).optJSONArray("cities") }.getOrNull() ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            CachedCity(
                o.optString("slug"), o.optString("name"),
                o.optDouble("lat"), o.optDouble("lng"),
                o.optDouble("south"), o.optDouble("west"), o.optDouble("north"), o.optDouble("east"),
                o.optInt("buildingCount"),
            )
        }
    }

    fun lastUsedSlug(): String? =
        runCatching { JSONObject(indexFile.readText()).optString("last").ifBlank { null } }.getOrNull()

    /** The city's cached GeoJSON file, or null if it hasn't been (fully) downloaded. */
    fun geoJsonFileOf(slug: String): File? =
        File(dir, "$slug.geojson").takeIf { it.isFile && it.length() > 0L }

    /**
     * Scratch path for an in-progress download. Kept separate from the live `.geojson` so a
     * failed or cancelled (re-)download can never clobber a city's existing data — promote
     * it with [commit] only once it has been validated.
     */
    fun stagingFileFor(slug: String): File = File(dir, "$slug.geojson.part")

    /** Promote [staging] to the city's live GeoJSON and add/update its index entry. */
    fun commit(city: CachedCity, staging: File) {
        val live = File(dir, "${city.slug}.geojson")
        live.delete()
        if (!staging.renameTo(live)) { // same dir, so rename only fails on exotic filesystems
            staging.copyTo(live, overwrite = true)
            staging.delete()
        }
        val others = list().filter { it.slug != city.slug }
        val arr = JSONArray()
        (others + city).forEach { c ->
            arr.put(JSONObject()
                .put("slug", c.slug).put("name", c.name)
                .put("lat", c.lat).put("lng", c.lng)
                .put("south", c.south).put("west", c.west).put("north", c.north).put("east", c.east)
                .put("buildingCount", c.buildingCount))
        }
        indexFile.writeText(JSONObject().put("last", city.slug).put("cities", arr).toString())
    }

    fun setLastUsed(slug: String) {
        val text = runCatching { indexFile.readText() }.getOrNull() ?: return
        val obj = runCatching { JSONObject(text) }.getOrNull() ?: return
        indexFile.writeText(obj.put("last", slug).toString())
    }

    companion object {
        fun slugOf(name: String): String =
            name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(40).ifBlank { "city" }
    }
}

private fun httpGet(url: String): String? = request(url)

private fun request(url: String, postBody: String? = null): String? {
    val conn = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 30_000
        readTimeout = 120_000
        setRequestProperty("User-Agent", USER_AGENT)
        if (postBody != null) {
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        }
    }
    return try {
        if (postBody != null) conn.outputStream.use { it.write(postBody.toByteArray()) }
        if (conn.responseCode !in 200..299) null
        else conn.inputStream.bufferedReader().use { it.readText() }
    } finally {
        conn.disconnect()
    }
}
