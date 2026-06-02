package app.shadey.data

import app.shadey.core.model.Building
import app.shadey.core.model.LatLng
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Opt-in fetch of building footprints from OpenStreetMap (Overpass API). Only used when
 * the user roams outside the bundled, offline region AND enables it in Settings.
 */
class OverpassClient(
    private val endpoints: List<String> = listOf(
        "https://overpass-api.de/api/interpreter",
        "https://overpass.kumi.systems/api/interpreter",
        "https://overpass.private.coffee/api/interpreter",
        "https://overpass.openstreetmap.ru/cgi/interpreter",
    ),
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    /** Returns buildings, or throws an exception describing what went wrong. */
    suspend fun fetchBuildings(box: BoundingBox): List<Building> = withContext(Dispatchers.IO) {
        val query = "[out:json][timeout:25];\n" +
            "(way[\"building\"](${box.south},${box.west},${box.north},${box.east}););\n" +
            "out body geom;"

        // Race all mirrors in parallel; first successful response wins.
        coroutineScope {
            val winner = CompletableDeferred<List<Building>>()
            val jobs = endpoints.map { url ->
                async(Dispatchers.IO) {
                    try {
                        val req = Request.Builder()
                            .url(url)
                            .header("User-Agent", "Shadey/1.0 (sun/shade app)")
                            .post(FormBody.Builder().add("data", query).build())
                            .build()
                        val result = client.newCall(req).execute().use { resp ->
                            if (resp.isSuccessful) resp.body?.string()?.let(::parse) else null
                        }
                        if (result != null) winner.complete(result)
                    } catch (_: Exception) { /* mirror failed, others may still win */ }
                }
            }
            // If all mirrors finish without a winner, fail.
            launch {
                jobs.forEach { it.join() }
                winner.completeExceptionally(RuntimeException("All Overpass mirrors failed"))
            }
            winner.await().also { jobs.forEach { it.cancel() } }
        }
    }

    private fun parse(body: String): List<Building> {
        val elements = runCatching {
            json.parseToJsonElement(body).jsonObject["elements"]?.jsonArray
        }.getOrNull() ?: return emptyList()

        val out = ArrayList<Building>()
        for (el in elements) {
            val obj = el.jsonObject
            if (obj["type"]?.jsonPrimitive?.contentOrNull != "way") continue
            val geom = obj["geometry"]?.jsonArray ?: continue
            val ring = geom.mapNotNull { node ->
                val g = node.jsonObject
                val lat = g["lat"]?.jsonPrimitive?.doubleOrNull
                val lon = g["lon"]?.jsonPrimitive?.doubleOrNull
                if (lat != null && lon != null) LatLng(lat, lon) else null
            }
            if (ring.size < 3) continue
            val tags = obj["tags"]?.jsonObject
            val height = heightFromTags(tags)
            val id = "way/${obj["id"]?.jsonPrimitive?.contentOrNull ?: out.size}"
            out.add(Building(id, ring, height))
        }
        return out
    }

    private fun heightFromTags(tags: kotlinx.serialization.json.JsonObject?): Double {
        if (tags == null) return DEFAULT_HEIGHT
        tags["height"]?.jsonPrimitive?.contentOrNull
            ?.filter { it.isDigit() || it == '.' }?.toDoubleOrNull()
            ?.let { if (it > 0) return it }
        tags["building:levels"]?.jsonPrimitive?.contentOrNull
            ?.substringBefore(';')?.trim()?.toDoubleOrNull()
            ?.let { if (it > 0) return it * METERS_PER_LEVEL }
        return DEFAULT_HEIGHT
    }

    private companion object {
        const val DEFAULT_HEIGHT = 9.0
        const val METERS_PER_LEVEL = 3.2
    }
}
