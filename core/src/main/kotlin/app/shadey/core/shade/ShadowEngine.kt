package app.shadey.core.shade

import app.shadey.core.geo.LocalProjection
import app.shadey.core.geo.Polygons
import app.shadey.core.geo.Vec2
import app.shadey.core.model.Building
import app.shadey.core.model.LatLng
import app.shadey.core.model.SolarPosition
import app.shadey.core.model.Sunlight
import app.shadey.core.solar.SolarCalculator
import java.time.Duration
import java.time.Instant
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

/**
 * Decides whether a point receives direct sunlight given the surrounding buildings,
 * and projects building shadows onto the ground for display.
 *
 * The core test is a 3D ray cast: shoot a ray from the point toward the sun. A point
 * is shaded if any building's prism blocks that ray — i.e. where the ray first crosses
 * the building footprint, the ray is still below the building's roof.
 */
class ShadowEngine(
    /** Buildings farther than this (horizontally) are ignored when testing a point. */
    private val maxShadowReachMeters: Double = 800.0,
    /** Eye height of the observer above ground. */
    private val observerHeightMeters: Double = 0.0,
) {

    fun sunlightAt(point: LatLng, instant: Instant, buildings: List<Building>): Sunlight =
        sunlightAt(point, SolarCalculator.position(point, instant), buildings)

    fun sunlightAt(point: LatLng, sun: SolarPosition, buildings: List<Building>): Sunlight = when {
        sun.elevationDeg <= 0.0 -> Sunlight.NIGHT
        isBlocked(point, sun, buildings) -> Sunlight.SHADE
        else -> Sunlight.SUN
    }

    /** True if any building blocks the straight line from [point] to the sun. */
    fun isBlocked(point: LatLng, sun: SolarPosition, buildings: List<Building>): Boolean {
        if (sun.elevationDeg <= 0.0) return true
        val proj = LocalProjection(point)
        val azRad = Math.toRadians(sun.azimuthDeg)
        val toSun = Vec2(sin(azRad), cos(azRad)) // horizontal unit vector toward the sun
        val tanEl = tan(Math.toRadians(sun.elevationDeg))
        val origin = Vec2(0.0, 0.0) // the point is the projection origin

        for (b in buildings) {
            if (b.heightMeters <= observerHeightMeters) continue
            val ring = proj.toLocal(b.footprint)
            if (ring.size < 3) continue
            if (nearestVertexDistance(ring) > maxShadowReachMeters) continue
            val tIn = Polygons.firstRayHit(ring, origin, toSun) ?: continue
            if (tIn > maxShadowReachMeters) continue
            val rayHeight = observerHeightMeters + tIn * tanEl
            // Blocked if the ray is below the roof when it reaches the footprint.
            if (rayHeight < b.heightMeters) return true
        }
        return false
    }

    /**
     * Ground shadow polygon for a single building at a given sun position, as a ring of
     * lat/lng. Returns null when the sun is at/below the horizon. [proj] should be a
     * projection shared across all buildings so shadows line up in one coordinate frame.
     */
    fun castShadow(building: Building, sun: SolarPosition, proj: LocalProjection): List<LatLng>? {
        if (sun.elevationDeg <= 0.5) return null
        val ring = proj.toLocal(building.footprint)
        if (ring.size < 3) return null
        val length = building.heightMeters / tan(Math.toRadians(sun.elevationDeg))
        if (length <= 0.0) return null
        val azRad = Math.toRadians(sun.azimuthDeg)
        val shadowVec = Vec2(-sin(azRad), -cos(azRad)) * length // shadow falls away from the sun

        val pts = ArrayList<Vec2>(ring.size * 2)
        pts.addAll(ring)
        for (v in ring) pts.add(v + shadowVec)
        return Polygons.convexHull(pts).map(proj::toLatLng)
    }

    /**
     * Shadow polygon using a projection anchored at the building's own footprint, making the
     * result independent of the map centre. This lets callers cache the output per building.
     */
    fun castShadow(building: Building, sun: SolarPosition): List<LatLng>? {
        val anchor = building.footprint.firstOrNull() ?: return null
        return castShadow(building, sun, LocalProjection(anchor))
    }

    data class Transition(val at: Instant, val to: Sunlight)

    /**
     * The next time after [from] (within [within]) that the sunlight state of [point]
     * changes, scanning in [step] increments and refining to the nearest few seconds.
     */
    fun nextTransition(
        point: LatLng,
        buildings: List<Building>,
        from: Instant,
        within: Duration = Duration.ofHours(8),
        step: Duration = Duration.ofMinutes(5),
    ): Transition? {
        val startState = sunlightAt(point, from, buildings)
        val end = from.plus(within)
        val stepSecs = step.seconds
        var t = from
        while (t.isBefore(end)) {
            val next = t.plusSeconds(stepSecs)
            val state = sunlightAt(point, next, buildings)
            if (state != startState) {
                return Transition(refine(point, buildings, t, next, startState), state)
            }
            t = next
        }
        return null
    }

    private fun refine(point: LatLng, buildings: List<Building>, lo: Instant, hi: Instant, startState: Sunlight): Instant {
        var a = lo
        var b = hi
        repeat(7) {
            val mid = a.plusSeconds((b.epochSecond - a.epochSecond) / 2)
            if (mid == a || mid == b) return b
            if (sunlightAt(point, mid, buildings) == startState) a = mid else b = mid
        }
        return b
    }

    private fun nearestVertexDistance(ring: List<Vec2>): Double {
        var min = Double.MAX_VALUE
        for (v in ring) {
            val d = v.length()
            if (d < min) min = d
        }
        return min
    }
}
