package app.shadey.core.solar

import app.shadey.core.model.LatLng
import app.shadey.core.model.SolarPosition
import java.time.Instant
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.tan

/**
 * Solar position from the NOAA solar-position equations. Accurate to a fraction of a
 * degree, which is far better than the resolution of building data — and it is pure
 * arithmetic, so it runs fully on-device with no network or data tables.
 */
object SolarCalculator {

    fun position(at: LatLng, instant: Instant): SolarPosition {
        val jd = instant.epochSecond / 86400.0 + 2440587.5 + instant.nano / 1e9 / 86400.0
        val t = (jd - 2451545.0) / 36525.0 // Julian centuries since J2000.0

        val l0 = norm360(280.46646 + t * (36000.76983 + 0.0003032 * t))
        val m = 357.52911 + t * (35999.05029 - 0.0001537 * t)
        val mRad = Math.toRadians(m)
        val e = 0.016708634 - t * (0.000042037 + 0.0000001267 * t)

        val c = sin(mRad) * (1.914602 - t * (0.004817 + 0.000014 * t)) +
            sin(2 * mRad) * (0.019993 - 0.000101 * t) +
            sin(3 * mRad) * 0.000289
        val trueLong = l0 + c
        val lambda = trueLong - 0.00569 - 0.00478 * sin(Math.toRadians(125.04 - 1934.136 * t))
        val lambdaRad = Math.toRadians(lambda)

        val eps0 = 23.0 + (26.0 + (21.448 - t * (46.815 + t * (0.00059 - t * 0.001813))) / 60.0) / 60.0
        val eps = eps0 + 0.00256 * cos(Math.toRadians(125.04 - 1934.136 * t))
        val epsRad = Math.toRadians(eps)

        val declRad = asin(sin(epsRad) * sin(lambdaRad))

        // Equation of time, in minutes.
        val y = tan(epsRad / 2.0).pow(2)
        val l0Rad = Math.toRadians(l0)
        val eqTime = 4.0 * Math.toDegrees(
            y * sin(2 * l0Rad) - 2 * e * sin(mRad) +
                4 * e * y * sin(mRad) * cos(2 * l0Rad) -
                0.5 * y * y * sin(4 * l0Rad) -
                1.25 * e * e * sin(2 * mRad),
        )

        // True solar time in minutes (work in UTC; longitude shifts it east/west).
        val utcMinutes = instant.epochSecond.mod(86400L) / 60.0 + instant.nano / 1e9 / 60.0
        val trueSolarTime = (utcMinutes + eqTime + 4.0 * at.lng).mod(1440.0)
        val hourAngle = trueSolarTime / 4.0 - 180.0 // degrees, 0 at solar noon
        val haRad = Math.toRadians(hourAngle)

        val latRad = Math.toRadians(at.lat)
        val cosZenith = (
            sin(latRad) * sin(declRad) +
                cos(latRad) * cos(declRad) * cos(haRad)
            ).coerceIn(-1.0, 1.0)
        val zenithRad = acos(cosZenith)
        val elevation = 90.0 - Math.toDegrees(zenithRad)

        val denom = cos(latRad) * sin(zenithRad)
        val azimuth = if (abs(denom) < 1e-9) {
            180.0 // sun directly overhead / at a pole: azimuth is undefined, pick south
        } else {
            val cosAz = ((sin(latRad) * cosZenith - sin(declRad)) / denom).coerceIn(-1.0, 1.0)
            val a = Math.toDegrees(acos(cosAz))
            if (hourAngle > 0.0) norm360(a + 180.0) else norm360(540.0 - a)
        }
        return SolarPosition(azimuth, elevation)
    }

    private fun norm360(v: Double): Double {
        val x = v % 360.0
        return if (x < 0) x + 360.0 else x
    }
}
