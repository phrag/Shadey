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
    val seen = HashSet<String>(features.size * 2)
    val out = ArrayList<Building>(features.size)
    for (feature in features) {
        val height = extractHeight(feature)
        when (val geom = feature.geometry()) {
            is Polygon -> outerRing(geom)?.let { ring ->
                val id = featureId(feature, ring)
                if (seen.add(id)) out.add(Building(id, ring, height))
            }
            is MultiPolygon -> geom.coordinates()?.forEach { poly ->
                outerRingCoords(poly?.getOrNull(0))?.let { ring ->
                    val id = featureId(feature, ring)
                    if (seen.add(id)) out.add(Building(id, ring, height))
                }
            }
            else -> Unit
        }
    }
    return out
}

/**
 * A stable key for deduplicating a building across tiles. Source-tile features often have no
 * feature id, so we fall back to osm_id and finally to the rounded first vertex — the same
 * building footprint hashes to the same key wherever it appears.
 */
private fun featureId(feature: Feature, ring: List<LatLng>): String {
    feature.id()?.let { return it }
    feature.properties()?.get("osm_id")?.let { if (!it.isJsonNull) return "osm:${it.asString}" }
    val p = ring[0]
    return "geo:${Math.round(p.lat * 1e6)}_${Math.round(p.lng * 1e6)}"
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
