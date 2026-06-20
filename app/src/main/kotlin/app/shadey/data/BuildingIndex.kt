package app.shadey.data

import app.shadey.core.model.Building
import app.shadey.core.model.LatLng
import kotlin.math.cos
import kotlin.math.floor

/**
 * A uniform spatial grid over a set of buildings for fast "which buildings are near point P"
 * queries.
 *
 * A plain `buildings.filter { near(p) }` is O(all buildings) per call and recomputes each
 * building's centroid every time. Scoring a route issues one such query per ~25 m sample, so for
 * a city of tens of thousands of buildings that linear scan dominates the cost (and churns the GC
 * with a throwaway list per sample). This buckets every building's centroid into a [cellMeters]
 * grid once, computing each centroid a single time, so a query only scans the buildings in the
 * cells its radius overlaps.
 */
class BuildingIndex(
    buildings: List<Building>,
    private val cellMeters: Double = 120.0,
) {
    private class Entry(val building: Building, val lat: Double, val lng: Double)

    // A single reference latitude fixes the lng-to-metre scale for the whole grid. Over a city-sized
    // extent the cos(latitude) variation is tiny, so cells stay close enough to square.
    private val refLat: Double = if (buildings.isEmpty()) 0.0 else buildings.first().centroid().lat
    private val dLatPerCell = cellMeters / 111_320.0
    private val dLngPerCell = cellMeters / (111_320.0 * cos(Math.toRadians(refLat)).coerceAtLeast(1e-6))
    private val cells = HashMap<Long, ArrayList<Entry>>()

    init {
        for (b in buildings) {
            val c = b.centroid()
            cells.getOrPut(cellKey(c.lat, c.lng)) { ArrayList() }.add(Entry(b, c.lat, c.lng))
        }
    }

    // Packs the (lat, lng) cell indices into one Long key — high 32 bits lat, low 32 bits lng.
    private fun cellKey(lat: Double, lng: Double): Long {
        val latCell = floor(lat / dLatPerCell).toInt()
        val lngCell = floor(lng / dLngPerCell).toInt()
        return (latCell.toLong() shl 32) or (lngCell.toLong() and 0xffffffffL)
    }

    /** Buildings whose centroid lies within the [radiusMeters] box around [p]. */
    fun near(p: LatLng, radiusMeters: Double): List<Building> {
        if (cells.isEmpty()) return emptyList()
        val dLat = radiusMeters / 111_320.0
        val dLng = radiusMeters / (111_320.0 * cos(Math.toRadians(p.lat)).coerceAtLeast(1e-6))
        val south = p.lat - dLat
        val north = p.lat + dLat
        val west = p.lng - dLng
        val east = p.lng + dLng
        val latLo = floor(south / dLatPerCell).toInt()
        val latHi = floor(north / dLatPerCell).toInt()
        val lngLo = floor(west / dLngPerCell).toInt()
        val lngHi = floor(east / dLngPerCell).toInt()
        val out = ArrayList<Building>()
        var lc = latLo
        while (lc <= latHi) {
            var gc = lngLo
            while (gc <= lngHi) {
                val bucket = cells[(lc.toLong() shl 32) or (gc.toLong() and 0xffffffffL)]
                if (bucket != null) {
                    for (e in bucket) {
                        if (e.lat in south..north && e.lng in west..east) out.add(e.building)
                    }
                }
                gc++
            }
            lc++
        }
        return out
    }
}
