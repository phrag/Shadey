package app.shadey.core.rank

import app.shadey.core.geo.LocalProjection
import app.shadey.core.model.Building
import app.shadey.core.model.LatLng
import app.shadey.core.model.Spot
import app.shadey.core.model.Sunlight
import app.shadey.core.shade.ShadowEngine
import app.shadey.core.solar.SolarCalculator
import java.time.Instant

/** Evaluation of a single spot at a moment in time. */
data class SpotSunInfo(
    val spot: Spot,
    val sunlight: Sunlight,
    val solar: app.shadey.core.model.SolarPosition,
    /** When the sunlight state next changes, if known within the look-ahead window. */
    val nextChange: Instant?,
)

/**
 * Evaluates and ranks spots by proximity to a reference point and how sunny they are
 * right now. Spots near [origin] (within [NEAR_RADIUS_METERS]) come before far-away ones.
 * Within that constraint the ordering is: sunny first, shaded second, night last. Among
 * spots with the same near/far status AND the same sun state, the closer spot ranks higher
 * so panning the map visibly reorders the list even when all spots share the near bucket.
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
    ): List<SpotSunInfo> {
        val projection = LocalProjection(origin)
        return spots
            .map { spot -> evaluate(spot, instant, buildingsFor(spot)) to projection.toLocal(spot.latLng).length() }
            .sortedWith { (a, da), (b, db) ->
                // 1. Near before far.
                val ba = if (da <= NEAR_RADIUS_METERS) 0 else 1
                val bb = if (db <= NEAR_RADIUS_METERS) 0 else 1
                if (ba != bb) return@sortedWith ba - bb
                // 2. Best sun state first (sunny > shaded > night).
                val ra = rankBucket(a); val rb = rankBucket(b)
                if (ra != rb) return@sortedWith ra - rb
                // 3. Closer beats farther within the same bucket — makes panning reorder the list.
                val dc = da.compareTo(db)
                if (dc != 0) return@sortedWith dc
                // 4. Sun-time tiebreaker.
                when (ra) {
                    0 -> secondsUntil(b.nextChange, instant).compareTo(secondsUntil(a.nextChange, instant))
                    1 -> secondsUntil(a.nextChange, instant).compareTo(secondsUntil(b.nextChange, instant))
                    else -> b.solar.elevationDeg.compareTo(a.solar.elevationDeg)
                }
            }
            .map { (info, _) -> info }
    }

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
