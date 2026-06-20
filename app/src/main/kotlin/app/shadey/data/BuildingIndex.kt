package app.shadey.data

import app.shadey.core.model.Building
import app.shadey.core.model.LatLng
import kotlin.math.cos
import kotlin.math.floor

/**
 * A uniform spatial grid over a set of buildings for fast "which buildings are near point P"
 * (and "which buildings fall in box B") queries.
 *
 * A plain `buildings.filter { … }` is O(all buildings) per call and recomputes each building's
 * centroid every time. Scoring a route issues one such query per ~25 m sample, and ranking spots
 * issues one per spot, so for a city of tens of thousands of buildings those linear scans dominate
 * the cost (and churn the GC with a throwaway list per call). This buckets every building's
 * centroid into a [cellMeters] grid once, computing each centroid a single time, so a query only
 * scans the buildings in the cells it overlaps. Centroids are also cached for callers that need
 * them (e.g. distance sorting).
 */
class BuildingIndex(
    buildings: List<Building>,
    private val cellMeters: Double = 120.0,
) {
    private class Entry(val building: Building, val centroid: LatLng)

    // A single reference latitude fixes the lng-to-metre scale for the whole grid. Over a city-sized
    // extent the cos(latitude) variation is tiny, so cells stay close enough to square.
    private val refLat: Double = if (buildings.isEmpty()) 0.0 else buildings.first().centroid().lat
    private val dLatPerCell = cellMeters / 111_320.0
    private val dLngPerCell = cellMeters / (111_320.0 * cos(Math.toRadians(refLat)).coerceAtLeast(1e-6))
    private val cells = HashMap<Long, ArrayList<Entry>>()
    private val centroids = HashMap<String, LatLng>()

    init {
        for (b in buildings) {
            val c = b.centroid()
            cells.getOrPut(cellKey(c.lat, c.lng)) { ArrayList() }.add(Entry(b, c))
            centroids[b.id] = c
        }
    }

    // Packs the (lat, lng) cell indices into one Long key — high 32 bits lat, low 32 bits lng.
    private fun cellKey(lat: Double, lng: Double): Long {
        val latCell = floor(lat / dLatPerCell).toInt()
        val lngCell = floor(lng / dLngPerCell).toInt()
        return (latCell.toLong() shl 32) or (lngCell.toLong() and 0xffffffffL)
    }

    /** The cached centroid of [b], or a freshly computed one if it wasn't part of this index. */
    fun centroidOf(b: Building): LatLng = centroids[b.id] ?: b.centroid()

    /** Buildings whose centroid lies within the [radiusMeters] box around [p]. */
    fun near(p: LatLng, radiusMeters: Double): List<Building> {
        val dLat = radiusMeters / 111_320.0
        val dLng = radiusMeters / (111_320.0 * cos(Math.toRadians(p.lat)).coerceAtLeast(1e-6))
        return inBox(p.lat - dLat, p.lng - dLng, p.lat + dLat, p.lng + dLng)
    }

    /** Buildings whose centroid lies in the lat/lng box [south, west]–[north, east]. */
    fun inBox(south: Double, west: Double, north: Double, east: Double): List<Building> {
        if (cells.isEmpty()) return emptyList()
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
                        if (e.centroid.lat in south..north && e.centroid.lng in west..east) out.add(e.building)
                    }
                }
                gc++
            }
            lc++
        }
        return out
    }
}
