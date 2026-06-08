package app.shadey.data

import kotlinx.coroutines.Dispatchers
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
     * Fetch buildings for [bbox] and return a GeoJSON FeatureCollection string.
     *
     * The Overpass response for a large city can be 50–70 MB of JSON. Loading it into a
     * String would OOM on devices with a 256 MB heap limit. Instead we:
     *   1. Stream the HTTP response body to a temp file (8 KB chunks, no large in-memory copy).
     *   2. Stream-parse the temp file with JsonReader so we never hold the whole blob in RAM.
     *   3. Delete the temp file immediately after parsing.
     */
    suspend fun downloadGeoJson(bbox: DoubleArray): String = withContext(Dispatchers.IO) {
        val (s, w, n, e) = bbox
        val query = """
            [out:json][timeout:120];
            (
              way["building"]($s,$w,$n,$e);
              relation["building"]["type"="multipolygon"]($s,$w,$n,$e);
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
                        if (httpPostToFile(url, postData, tmpFile)) {
                            return@withContext overpassFileToGeoJson(tmpFile)
                        }
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

    /** POST [postBody] to [url], streaming the response body into [dest]. Returns false on non-2xx. */
    private fun httpPostToFile(url: String, postBody: String, dest: File): Boolean {
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
            if (conn.responseCode !in 200..299) false
            else { conn.inputStream.use { inp -> dest.outputStream().use { inp.copyTo(it) } }; true }
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Stream-parse an Overpass JSON file using [android.util.JsonReader].
     * Reads one element at a time so peak memory is proportional to a single building, not
     * the full response.
     */
    private fun overpassFileToGeoJson(file: File): String {
        val features = JSONArray()
        android.util.JsonReader(java.io.InputStreamReader(file.inputStream(), "UTF-8")).use { r ->
            r.beginObject()
            while (r.hasNext()) {
                if (r.nextName() == "elements") {
                    r.beginArray()
                    while (r.hasNext()) parseElement(r)?.let { features.put(it) }
                    r.endArray()
                } else {
                    r.skipValue()
                }
            }
        }
        return JSONObject()
            .put("type", "FeatureCollection")
            .put("attribution", "(c) OpenStreetMap contributors, ODbL")
            .put("features", features)
            .toString()
    }

    private fun parseElement(r: android.util.JsonReader): JSONObject? {
        var type = ""; var id = ""; var height = DEFAULT_HEIGHT_M
        var wayRing: JSONArray? = null
        val outerRings = JSONArray()
        r.beginObject()
        while (r.hasNext()) {
            when (r.nextName()) {
                "type" -> type = r.nextString()
                "id"   -> id = r.nextLong().toString()
                "tags" -> height = readHeight(r)
                "geometry" -> wayRing = readRing(r)
                "members"  -> readOuters(r, outerRings)
                else -> r.skipValue()
            }
        }
        r.endObject()
        val props = JSONObject().put("height", height).put("osm_id", "$type/$id")
        return when (type) {
            "way" -> wayRing?.let { feature(props, polygon(it)) }
            "relation" -> if (outerRings.length() > 0) {
                val polys = JSONArray()
                for (i in 0 until outerRings.length()) polys.put(JSONArray().put(outerRings.getJSONArray(i)))
                feature(props, JSONObject().put("type", "MultiPolygon").put("coordinates", polys))
            } else null
            else -> null
        }
    }

    private fun readRing(r: android.util.JsonReader): JSONArray? {
        val ring = JSONArray()
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
            if (!lat.isNaN() && !lon.isNaN()) ring.put(JSONArray().put(lon).put(lat))
        }
        r.endArray()
        return if (ring.length() >= 4) ring else null
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
            if (role == "outer") ring?.let { out.put(it) }
        }
        r.endArray()
    }

    private fun readHeight(r: android.util.JsonReader): Double {
        var explicit = Double.NaN; var fromLevels = Double.NaN
        r.beginObject()
        while (r.hasNext()) {
            when (r.nextName()) {
                "height", "building:height" -> {
                    val v = r.nextString().filter { it.isDigit() || it == '.' }.toDoubleOrNull()
                    if (v != null && v > 0) explicit = v
                }
                "building:levels" -> {
                    val v = r.nextString().substringBefore(";").toDoubleOrNull()
                    if (v != null && v > 0) fromLevels = v * METERS_PER_LEVEL
                }
                else -> r.skipValue()
            }
        }
        r.endObject()
        return when {
            !explicit.isNaN()    -> explicit
            !fromLevels.isNaN()  -> fromLevels
            else                 -> DEFAULT_HEIGHT_M
        }
    }

    private fun feature(props: JSONObject, geometry: JSONObject): JSONObject =
        JSONObject().put("type", "Feature").put("properties", props).put("geometry", geometry)

    private fun polygon(ring: JSONArray): JSONObject =
        JSONObject().put("type", "Polygon").put("coordinates", JSONArray().put(ring))

}

/** Persists downloaded city building data in the app's private files dir. */
class CityStore(private val filesDir: File) {
    private val dir = File(filesDir, "cities").apply { mkdirs() }
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

    fun geoJsonOf(slug: String): String? =
        runCatching { File(dir, "$slug.geojson").readText() }.getOrNull()

    fun save(city: CachedCity, geoJson: String) {
        File(dir, "${city.slug}.geojson").writeText(geoJson)
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
