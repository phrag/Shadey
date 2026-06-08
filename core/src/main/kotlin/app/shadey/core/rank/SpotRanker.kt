package app.shadey.core.rank

import app.shadey.core.geo.LocalProjection
import app.shadey.core.model.Building
import app.shadey.core.model.LatLng
import app.shadey.core.model.SolarPosition
import app.shadey.core.model.Spot
import app.shadey.core.model.Sunlight
import app.shadey.core.shade.ShadowEngine
import app.shadey.core.solar.SolarCalculator
import java.time.Instant

/** Evaluation of a single spot at a moment in time. */
data class SpotSunInfo(
    val spot: Spot,
    val sunlight: Sunlight,
    val solar: SolarPosition,
    /** When the sunlight state next changes, if known within the look-ahead window. */
    val nextChange: Instant?,
)

/**
 * Evaluates and ranks spots by proximity to a reference point and how sunny they are
 * right now. Spots near [origin] (typically the centre of the map view) come first —
 * among those, sunny spots lead (the ones that will *stay* sunny longest first), then
 * shaded spots that will become sunny soonest, then night. Spots far from [origin] are
 * ordered the same way among themselves, but always sort below the nearby ones, so a
 * sunny spot across town can no longer outrank what's actually in view.
 */
class SpotRanker(private val engine: ShadowEngine = ShadowEngine()) {

    fun evaluate(spot: Spot, instant: Instant, buildings: List<Building>): SpotSunInfo {
        val sun = SolarCalculator.position(spot.latLng, instant)
        val light = engine.sunlightAt(spot.latLng, sun, buildings)
        val next = engine.nextTransition(spot.latLng, buildings, instant)?.at
        return SpotSunInfo(spot, light, sun, next)
    }

    fun rank(
        spots: List<Spot>,
        instant: Instant,
        origin: LatLng,
        buildingsFor: (Spot) -> List<Building>,
    ): List<SpotSunInfo> =
        spots.map { evaluate(it, instant, buildingsFor(it)) }.sortedWith(ordering(instant, origin))

    private fun ordering(now: Instant, origin: LatLng): Comparator<SpotSunInfo> {
        val projection = LocalProjection(origin)
        return Comparator { a, b ->
            val da = distanceBucket(a.spot, projection)
            val db = distanceBucket(b.spot, projection)
            if (da != db) return@Comparator da - db
            val ra = rankBucket(a)
            val rb = rankBucket(b)
            if (ra != rb) return@Comparator ra - rb
            when (ra) {
                // Sunny: prefer the spot that stays sunny longest.
                0 -> secondsUntil(b.nextChange, now).compareTo(secondsUntil(a.nextChange, now))
                // Shaded: prefer the spot that becomes sunny soonest.
                1 -> secondsUntil(a.nextChange, now).compareTo(secondsUntil(b.nextChange, now))
                else -> b.solar.elevationDeg.compareTo(a.solar.elevationDeg)
            }
        }
    }

    /** 0 = within [NEAR_RADIUS_METERS] of the origin (e.g. the map view), 1 = farther. */
    private fun distanceBucket(spot: Spot, projection: LocalProjection): Int =
        if (projection.toLocal(spot.latLng).length() <= NEAR_RADIUS_METERS) 0 else 1

    private fun rankBucket(info: SpotSunInfo) = when (info.sunlight) {
        Sunlight.SUN -> 0
        Sunlight.SHADE -> 1
        Sunlight.NIGHT -> 2
    }

    private fun secondsUntil(instant: Instant?, now: Instant): Long =
        if (instant == null) Long.MAX_VALUE else (instant.epochSecond - now.epochSecond)

    private companion object {
        /** Spots within this radius of the origin are treated as "nearby" and ranked first. */
        const val NEAR_RADIUS_METERS = 6_000.0
    }
}
