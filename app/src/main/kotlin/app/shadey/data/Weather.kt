package app.shadey.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Current cloud cover and UV index for one location, from Open-Meteo. */
data class WeatherSnapshot(val cloudCoverPct: Int, val uvIndex: Double)

/** Live weather conditions via [Open-Meteo](https://open-meteo.com/) — no API key, no auth. */
object WeatherClient {
    private const val USER_AGENT = "Shadey/1.0 (+https://github.com/phrag/shadey)"

    suspend fun current(lat: Double, lng: Double): WeatherSnapshot? = withContext(Dispatchers.IO) {
        val url = "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lng" +
            "&current=cloud_cover,uv_index"
        val body = runCatching { httpGet(url) }.getOrNull() ?: return@withContext null
        val current = runCatching { JSONObject(body).optJSONObject("current") }.getOrNull()
            ?: return@withContext null
        val cloud = current.optDouble("cloud_cover", Double.NaN)
        if (cloud.isNaN()) return@withContext null
        val uv = current.optDouble("uv_index", 0.0)
        WeatherSnapshot(cloudCoverPct = cloud.toInt().coerceIn(0, 100), uvIndex = uv)
    }

    private fun httpGet(url: String): String? {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 15_000
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
