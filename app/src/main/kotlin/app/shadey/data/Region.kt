package app.shadey.data

import app.shadey.core.model.Building
import app.shadey.core.model.LatLng
import kotlin.math.cos

/** A simple lat/lng bounding box used for region checks and spatial filtering. */
data class BoundingBox(val south: Double, val west: Double, val north: Double, val east: Double) {

    fun contains(p: LatLng): Boolean = p.lat in south..north && p.lng in west..east

    fun expandedMeters(meters: Double): BoundingBox {
        val midLat = (south + north) / 2.0
        val dLat = meters / 111_320.0
        val dLng = meters / (111_320.0 * cos(Math.toRadians(midLat)))
        return BoundingBox(south - dLat, west - dLng, north + dLat, east + dLng)
    }

    val centerLat get() = (south + north) / 2.0
    val centerLng get() = (west + east) / 2.0

    companion object {
        fun around(p: LatLng, radiusMeters: Double): BoundingBox {
            val dLat = radiusMeters / 111_320.0
            val dLng = radiusMeters / (111_320.0 * cos(Math.toRadians(p.lat)))
            return BoundingBox(p.lat - dLat, p.lng - dLng, p.lat + dLat, p.lng + dLng)
        }

        fun ofBuildings(buildings: List<Building>): BoundingBox? {
            if (buildings.isEmpty()) return null
            var s = 90.0
            var w = 180.0
            var n = -90.0
            var e = -180.0
            for (b in buildings) for (pt in b.footprint) {
                if (pt.lat < s) s = pt.lat
                if (pt.lat > n) n = pt.lat
                if (pt.lng < w) w = pt.lng
                if (pt.lng > e) e = pt.lng
            }
            return BoundingBox(s, w, n, e)
        }
    }
}

/** Centroid of a footprint (simple vertex average — fine for spatial bucketing). */
fun Building.centroid(): LatLng {
    var lat = 0.0
    var lng = 0.0
    for (p in footprint) {
        lat += p.lat
        lng += p.lng
    }
    val n = footprint.size.coerceAtLeast(1)
    return LatLng(lat / n, lng / n)
}
