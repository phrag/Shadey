package app.shadey.core.rank

import app.shadey.core.model.Building
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
 * Evaluates and ranks spots by how sunny they are right now. Sunny spots come first
 * (the ones that will *stay* sunny longest lead), then shaded spots that will become
 * sunny soonest, then night.
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
        buildingsFor: (Spot) -> List<Building>,
    ): List<SpotSunInfo> =
        spots.map { evaluate(it, instant, buildingsFor(it)) }.sortedWith(ordering(instant))

    private fun ordering(now: Instant): Comparator<SpotSunInfo> =
        Comparator { a, b ->
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

    private fun rankBucket(info: SpotSunInfo) = when (info.sunlight) {
        Sunlight.SUN -> 0
        Sunlight.SHADE -> 1
        Sunlight.NIGHT -> 2
    }

    private fun secondsUntil(instant: Instant?, now: Instant): Long =
        if (instant == null) Long.MAX_VALUE else (instant.epochSecond - now.epochSecond)
}
