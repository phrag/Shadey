package app.shadey.map

import app.shadey.core.model.Building
import app.shadey.core.model.LatLng
import org.maplibre.geojson.Feature
import org.maplibre.geojson.MultiPolygon
import org.maplibre.geojson.Point
import org.maplibre.geojson.Polygon

private const val DEFAULT_HEIGHT = 9.0
private const val METERS_PER_LEVEL = 3.2

/**
 * Converts a deduplicated list of MapLibre GeoJSON Feature objects directly into [Building]s,
 * bypassing the JSON serialise→parse roundtrip that was causing lag.
 */
fun featuresToBuildings(features: List<Feature>): List<Building> {
    // A building can appear in both the 2D fill layer (often without a height) and the 3D
    // extrusion layer (with render_height). Key by footprint and keep the tallest version so
    // the real height wins, while distinct buildings are never collapsed together.
    val byKey = LinkedHashMap<String, Building>(features.size * 2)
    fun add(id: String, ring: List<LatLng>, height: Double) {
        val existing = byKey[id]
        if (existing == null || height > existing.heightMeters) {
            byKey[id] = Building(id, ring, height)
        }
    }
    for (feature in features) {
        val height = extractHeight(feature)
        when (val geom = feature.geometry()) {
            is Polygon -> outerRing(geom)?.let { ring -> add(featureId(feature, ring), ring, height) }
            is MultiPolygon -> geom.coordinates()?.forEach { poly ->
                outerRingCoords(poly?.getOrNull(0))?.let { ring -> add(featureId(feature, ring), ring, height) }
            }
            else -> Unit
        }
    }
    return ArrayList(byKey.values)
}

/**
 * A stable key for deduplicating a building across tiles. We deliberately do NOT use
 * `feature.id()`: in vector tiles the feature id is tile-local (it restarts per tile), so two
 * different buildings in neighbouring tiles can share an id and would wrongly collapse into one —
 * dropping real buildings and leaving gaps in the shadows. `osm_id` is globally unique when the
 * source carries it; otherwise we key on the footprint geometry (rounded centroid + vertex count),
 * which is stable for the same building and distinct for different ones.
 */
private fun featureId(feature: Feature, ring: List<LatLng>): String {
    feature.properties()?.get("osm_id")?.let { if (!it.isJsonNull) return "osm:${it.asString}" }
    var sumLat = 0.0
    var sumLng = 0.0
    for (p in ring) { sumLat += p.lat; sumLng += p.lng }
    val cLat = Math.round((sumLat / ring.size) * 1e6)
    val cLng = Math.round((sumLng / ring.size) * 1e6)
    return "geo:${cLat}_${cLng}_${ring.size}"
}

private fun extractHeight(feature: Feature): Double {
    val props = feature.properties() ?: return DEFAULT_HEIGHT
    listOf("render_height", "height").forEach { key ->
        props.get(key)?.let { el ->
            val d = if (el.isJsonPrimitive) el.asJsonPrimitive.let {
                if (it.isNumber) it.asDouble else it.asString
                    .filter { ch -> ch.isDigit() || ch == '.' }.toDoubleOrNull() ?: 0.0
            } else 0.0
            if (d > 0) return d
        }
    }
    props.get("building:levels")?.let { el ->
        val levels = if (el.isJsonPrimitive && el.asJsonPrimitive.isNumber)
            el.asDouble else el.asString.toDoubleOrNull() ?: 0.0
        if (levels > 0) return levels * METERS_PER_LEVEL
    }
    return DEFAULT_HEIGHT
}

private fun outerRing(polygon: Polygon): List<LatLng>? =
    outerRingCoords(polygon.coordinates()?.getOrNull(0))

private fun outerRingCoords(ring: List<Point>?): List<LatLng>? {
    if (ring == null || ring.size < 3) return null
    return ring.map { LatLng(it.latitude(), it.longitude()) }
}
