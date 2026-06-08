package app.shadey.core.geo

import app.shadey.core.model.LatLng
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** A 2D vector in local meters (x = east, y = north). */
data class Vec2(val x: Double, val y: Double) {
    operator fun plus(o: Vec2) = Vec2(x + o.x, y + o.y)
    operator fun minus(o: Vec2) = Vec2(x - o.x, y - o.y)
    operator fun times(s: Double) = Vec2(x * s, y * s)
    fun dot(o: Vec2) = x * o.x + y * o.y
    fun cross(o: Vec2) = x * o.y - y * o.x
    fun length() = hypot(x, y)
}

/**
 * Local east-north-up planar projection around an [origin]. Uses a latitude-dependent
 * meters-per-degree approximation that is accurate to well under a meter across a few
 * kilometres — plenty for city-scale shadow casting.
 */
class LocalProjection(val origin: LatLng) {
    private val latRad = Math.toRadians(origin.lat)
    private val mPerDegLat = 111_132.92 - 559.82 * cos(2 * latRad) + 1.175 * cos(4 * latRad)
    private val mPerDegLng = 111_412.84 * cos(latRad) - 93.5 * cos(3 * latRad)

    fun toLocal(p: LatLng): Vec2 =
        Vec2((p.lng - origin.lng) * mPerDegLng, (p.lat - origin.lat) * mPerDegLat)

    fun toLatLng(v: Vec2): LatLng =
        LatLng(origin.lat + v.y / mPerDegLat, origin.lng + v.x / mPerDegLng)

    fun toLocal(ring: List<LatLng>): List<Vec2> = ring.map(::toLocal)
}

object Polygons {

    /** Ray-casting point-in-polygon test. [poly] is an open ring of vertices. */
    fun contains(poly: List<Vec2>, p: Vec2): Boolean {
        var inside = false
        var j = poly.size - 1
        for (i in poly.indices) {
            val a = poly[i]
            val b = poly[j]
            if (((a.y > p.y) != (b.y > p.y)) &&
                (p.x < (b.x - a.x) * (p.y - a.y) / (b.y - a.y) + a.x)
            ) {
                inside = !inside
            }
            j = i
        }
        return inside
    }

    /**
     * Nearest forward distance (t >= 0) at which the ray `origin + t*dir` first meets
     * the polygon boundary, or 0 if [origin] is already inside the polygon.
     * Returns null if the ray never touches the polygon. [dir] should be a unit vector.
     */
    fun firstRayHit(poly: List<Vec2>, origin: Vec2, dir: Vec2): Double? {
        if (contains(poly, origin)) return 0.0
        var best: Double? = null
        var j = poly.size - 1
        for (i in poly.indices) {
            val t = raySegment(origin, dir, poly[i], poly[j])
            if (t != null && (best == null || t < best)) best = t
            j = i
        }
        return best
    }

    /** Distance t >= 0 along `origin + t*dir` where it crosses segment a-b, else null. */
    private fun raySegment(origin: Vec2, dir: Vec2, a: Vec2, b: Vec2): Double? {
        val e = b - a
        val denom = dir.cross(e)
        if (abs(denom) < 1e-12) return null // parallel
        val diff = a - origin
        val t = diff.cross(e) / denom   // distance along ray
        val u = diff.cross(dir) / denom // parameter along segment, must be in [0,1]
        return if (t >= 0.0 && u in 0.0..1.0) t else null
    }

    /** Convex hull (Andrew's monotone chain). Returns a CCW ring. */
    fun convexHull(points: List<Vec2>): List<Vec2> {
        val pts = points.distinct().sortedWith(compareBy({ it.x }, { it.y }))
        if (pts.size < 3) return pts

        fun cross(o: Vec2, a: Vec2, b: Vec2) = (a - o).cross(b - o)

        val lower = ArrayList<Vec2>()
        for (p in pts) {
            while (lower.size >= 2 && cross(lower[lower.size - 2], lower[lower.size - 1], p) <= 0) {
                lower.removeAt(lower.size - 1)
            }
            lower.add(p)
        }
        val upper = ArrayList<Vec2>()
        for (p in pts.asReversed()) {
            while (upper.size >= 2 && cross(upper[upper.size - 2], upper[upper.size - 1], p) <= 0) {
                upper.removeAt(upper.size - 1)
            }
            upper.add(p)
        }
        lower.removeAt(lower.size - 1)
        upper.removeAt(upper.size - 1)
        return lower + upper
    }

    /**
     * A regular [sides]-gon approximating a circle of [radiusMeters] around [center], as a
     * ring of lat/lng. Used to turn a tree's point position and crown radius into a footprint
     * that the (footprint + height) shadow model already knows how to cast shadows from.
     */
    fun circleFootprint(center: LatLng, radiusMeters: Double, sides: Int = 8): List<LatLng> {
        val proj = LocalProjection(center)
        return (0 until sides).map { i ->
            val angle = 2.0 * PI * i / sides
            proj.toLatLng(Vec2(radiusMeters * sin(angle), radiusMeters * cos(angle)))
        }
    }
}
