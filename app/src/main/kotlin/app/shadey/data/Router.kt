package app.shadey.data

import app.shadey.core.model.LatLng
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** One walking route alternative between two points. */
data class RouteOption(
    val coords: List<LatLng>,
    val distanceMeters: Double,
    val durationSeconds: Double,
)

/** Walking directions via the public [OSRM](https://project-osrm.org/) demo server. */
object Router {
    private const val BASE_URL = "https://router.project-osrm.org/route/v1/walking"
    private const val USER_AGENT = "Shadey/1.0 (+https://github.com/phrag/shadey)"

    /** Up to three walking alternatives between [origin] and [dest], or empty on failure. */
    suspend fun walkingRoutes(origin: LatLng, dest: LatLng): List<RouteOption> = withContext(Dispatchers.IO) {
        val url = "$BASE_URL/${origin.lng},${origin.lat};${dest.lng},${dest.lat}" +
            "?alternatives=true&overview=full&geometries=geojson&steps=false"
        val body = runCatching { httpGet(url) }.getOrNull() ?: return@withContext emptyList()
        val o = runCatching { JSONObject(body) }.getOrNull() ?: return@withContext emptyList()
        if (o.optString("code") != "Ok") return@withContext emptyList()
        val routes = o.optJSONArray("routes") ?: return@withContext emptyList()
        (0 until routes.length()).mapNotNull { i -> parseRoute(routes.optJSONObject(i)) }
    }

    private fun parseRoute(r: JSONObject?): RouteOption? {
        if (r == null) return null
        val coordsArr = r.optJSONObject("geometry")?.optJSONArray("coordinates") ?: return null
        val coords = (0 until coordsArr.length()).mapNotNull { j ->
            val pt = coordsArr.optJSONArray(j)
            if (pt == null || pt.length() < 2) null else LatLng(pt.optDouble(1), pt.optDouble(0))
        }
        if (coords.size < 2) return null
        return RouteOption(coords, r.optDouble("distance"), r.optDouble("duration"))
    }

    private fun httpGet(url: String): String? {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 20_000
            setRequestProperty("User-Agent", USER_AGENT)
        }
        return try {
            if (conn.responseCode !in 200..299) null
            else conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }
}
