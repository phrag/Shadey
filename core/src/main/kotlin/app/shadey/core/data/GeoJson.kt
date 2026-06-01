package app.shadey.core.data

import app.shadey.core.model.Building
import app.shadey.core.model.LatLng
import app.shadey.core.model.Spot
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Parses a GeoJSON FeatureCollection of building polygons into [Building]s. Heights are
 * read from `height` / `render_height` (metres) or estimated from `building:levels`.
 * This is the same shape the data-pipeline emits and that the OSM Overpass fetch returns.
 */
object GeoJsonBuildings {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun parse(text: String, defaultHeightMeters: Double = 9.0, metersPerLevel: Double = 3.2): List<Building> {
        val features = runCatching { json.parseToJsonElement(text).jsonObject["features"]?.jsonArray }
            .getOrNull() ?: return emptyList()
        val out = ArrayList<Building>(features.size)
        var auto = 0
        for (feature in features) {
            val obj = feature as? JsonObject ?: continue
            val geom = obj["geometry"] as? JsonObject ?: continue
            val props = obj["properties"] as? JsonObject ?: JsonObject(emptyMap())
            val height = extractHeight(props, defaultHeightMeters, metersPerLevel)
            val minHeight = props["min_height"]?.jsonPrimitive?.doubleOrNull ?: 0.0
            val baseId = obj["id"]?.jsonPrimitive?.contentOrNull
                ?: props["id"]?.jsonPrimitive?.contentOrNull
                ?: "b${auto++}"
            when (geom["type"]?.jsonPrimitive?.contentOrNull) {
                "Polygon" -> outerRing((geom["coordinates"] as? JsonArray))?.let {
                    out.add(Building(baseId, it, height, minHeight))
                }
                "MultiPolygon" -> {
                    val polys = geom["coordinates"] as? JsonArray ?: continue
                    polys.forEachIndexed { i, poly ->
                        outerRing(poly as? JsonArray)?.let {
                            out.add(Building("$baseId:$i", it, height, minHeight))
                        }
                    }
                }
            }
        }
        return out
    }

    private fun outerRing(coords: JsonArray?): List<LatLng>? {
        val ring = coords?.getOrNull(0) as? JsonArray ?: return null
        val pts = ring.mapNotNull { c ->
            val arr = c as? JsonArray ?: return@mapNotNull null
            val lng = arr.getOrNull(0)?.jsonPrimitive?.doubleOrNull
            val lat = arr.getOrNull(1)?.jsonPrimitive?.doubleOrNull
            if (lat != null && lng != null) LatLng(lat, lng) else null
        }
        return if (pts.size >= 3) pts else null
    }

    private fun extractHeight(props: JsonObject, default: Double, metersPerLevel: Double): Double {
        (props["height"]?.jsonPrimitive ?: props["render_height"]?.jsonPrimitive)
            ?.let { it.doubleOrNull ?: it.contentOrNull?.filter { ch -> ch.isDigit() || ch == '.' }?.toDoubleOrNull() }
            ?.let { if (it > 0) return it }
        props["building:levels"]?.jsonPrimitive
            ?.let { it.doubleOrNull ?: it.contentOrNull?.toDoubleOrNull() }
            ?.let { if (it > 0) return it * metersPerLevel }
        return default
    }
}

/** Parses the curated/user spots file (a JSON array of [Spot]). */
object SpotsJson {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun parse(text: String): List<Spot> =
        runCatching { json.decodeFromString<List<Spot>>(text) }.getOrDefault(emptyList())

    fun encode(spots: List<Spot>): String = json.encodeToString(spots)
}
