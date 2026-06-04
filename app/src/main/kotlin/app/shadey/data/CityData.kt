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
    suspend fun search(query: String): List<CityHit> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext emptyList()
        val url = "https://nominatim.openstreetmap.org/search?format=jsonv2&limit=6&q=" +
            URLEncoder.encode(query.trim(), "UTF-8")
        val body = httpGet(url) ?: return@withContext emptyList()
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

    private fun shortName(displayName: String, fallback: String): String {
        if (displayName.isBlank()) return fallback
        return displayName.split(",").map { it.trim() }.filter { it.isNotEmpty() }.take(2).joinToString(", ")
    }
}

/** Downloads building footprints + heights for a bounding box from OpenStreetMap (Overpass). */
object BuildingDownloader {
    private val ENDPOINTS = listOf(
        "https://overpass-api.de/api/interpreter",
        "https://overpass.kumi.systems/api/interpreter",
        "https://maps.mail.ru/osm/tools/overpass/api/interpreter",
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

    /** Fetch buildings for [bbox] and return a GeoJSON FeatureCollection string. */
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
        var lastErr: Exception? = null
        for (url in ENDPOINTS) {
            repeat(2) { attempt ->
                try {
                    val body = httpPost(url, "data=" + URLEncoder.encode(query, "UTF-8"))
                    if (body != null) return@withContext overpassToGeoJson(body)
                } catch (ex: Exception) {
                    lastErr = ex
                }
                Thread.sleep(1500L * (attempt + 1))
            }
        }
        throw IOException("Could not reach OpenStreetMap: ${lastErr?.message ?: "unknown error"}")
    }

    private fun overpassToGeoJson(jsonText: String): String {
        val root = JSONObject(jsonText)
        val elements = root.optJSONArray("elements") ?: JSONArray()
        val features = JSONArray()
        for (i in 0 until elements.length()) {
            val el = elements.optJSONObject(i) ?: continue
            val tags = el.optJSONObject("tags") ?: JSONObject()
            val props = JSONObject()
                .put("height", parseHeight(tags))
                .put("osm_id", "${el.optString("type")}/${el.optString("id")}")
            when (el.optString("type")) {
                "way" -> ringOf(el.optJSONArray("geometry"))?.let { ring ->
                    features.put(feature(props, polygon(ring)))
                }
                "relation" -> {
                    val members = el.optJSONArray("members") ?: continue
                    val polys = JSONArray()
                    for (m in 0 until members.length()) {
                        val mem = members.optJSONObject(m) ?: continue
                        if (mem.optString("role") != "outer") continue
                        ringOf(mem.optJSONArray("geometry"))?.let { polys.put(JSONArray().put(it)) }
                    }
                    if (polys.length() > 0) {
                        features.put(feature(props, JSONObject().put("type", "MultiPolygon").put("coordinates", polys)))
                    }
                }
            }
        }
        return JSONObject()
            .put("type", "FeatureCollection")
            .put("attribution", "(c) OpenStreetMap contributors, ODbL")
            .put("features", features)
            .toString()
    }

    private fun feature(props: JSONObject, geometry: JSONObject): JSONObject =
        JSONObject().put("type", "Feature").put("properties", props).put("geometry", geometry)

    private fun polygon(ring: JSONArray): JSONObject =
        JSONObject().put("type", "Polygon").put("coordinates", JSONArray().put(ring))

    /** Convert an Overpass geometry array ([{lat,lon},...]) into a GeoJSON ring ([[lng,lat],...]). */
    private fun ringOf(geometry: JSONArray?): JSONArray? {
        if (geometry == null || geometry.length() < 4) return null
        val ring = JSONArray()
        for (i in 0 until geometry.length()) {
            val p = geometry.optJSONObject(i) ?: continue
            if (!p.has("lat") || !p.has("lon")) continue
            ring.put(JSONArray().put(p.getDouble("lon")).put(p.getDouble("lat")))
        }
        return if (ring.length() >= 4) ring else null
    }

    private fun parseHeight(tags: JSONObject): Double {
        for (key in listOf("height", "building:height")) {
            val v = tags.optString(key)
            if (v.isNotBlank()) {
                val num = v.filter { it.isDigit() || it == '.' }.toDoubleOrNull()
                if (num != null && num > 0) return num
            }
        }
        val levels = tags.optString("building:levels").substringBefore(";").toDoubleOrNull()
        if (levels != null && levels > 0) return levels * METERS_PER_LEVEL
        return DEFAULT_HEIGHT_M
    }
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

private fun httpGet(url: String): String? = request(url, null)
private fun httpPost(url: String, body: String): String? = request(url, body)

private fun request(url: String, postBody: String?): String? {
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
