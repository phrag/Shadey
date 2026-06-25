package app.shadey.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalContext
import app.shadey.core.model.LatLng

/**
 * Renders nothing — runs continuous location + device-orientation listeners while [enabled] and
 * the app is foregrounded, reporting fixes via [onLocation] and a true-north heading via
 * [onHeading]. Listeners are registered on resume and torn down on pause/dispose so they never
 * drain the battery in the background. Sensors need no permission; location updates start only if
 * the location permission is already granted (the caller requests it before enabling tracking).
 *
 * Heading uses two sources, the way Organic Maps does: while you're actually moving, the GPS
 * course-over-ground ([Location.getBearing]) drives the arrow — it's far steadier and more accurate
 * than the magnetometer once walking — and the compass only takes over when you slow to a stop.
 * [onCalibrationNeeded] fires when the magnetometer is uncalibrated/disturbed (the usual cause of a
 * grossly-wrong heading), so the UI can prompt the figure-8 calibration wave.
 */
@Composable
fun LocationHeadingTracker(
    enabled: Boolean,
    onLocation: (LatLng) -> Unit,
    onHeading: (Float) -> Unit,
    onCalibrationNeeded: (Boolean) -> Unit = {},
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentOnLocation by rememberUpdatedState(onLocation)
    val currentOnHeading by rememberUpdatedState(onHeading)
    val currentOnCalibration by rememberUpdatedState(onCalibrationNeeded)

    DisposableEffect(enabled, lifecycleOwner) {
        if (!enabled) return@DisposableEffect onDispose { }

        val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val hasLocationPermission = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED

        // Latest fix, kept so the magnetic-declination correction (magnetic → true north) can use
        // the user's actual position.
        var lastLocation: LatLng? = null
        // The fix the marker is currently showing, kept so a new one can be weighed against it —
        // GPS and Network providers are both registered below and fire independently, and without
        // this the marker snaps back and forth onto whichever one happened to report last, even
        // when it's far less accurate (Network fixes can easily be 100+ m off).
        var lastAccepted: Location? = null
        // The position actually shown. The map layer eases the drawn marker toward this between
        // fixes (Organic-Maps-style), so we don't low-pass here — this holds the last fix we decided
        // was a real move rather than jitter, and the renderer glides to it.
        var displayed: LatLng? = null

        // --- Shared heading state, fed by both the GPS course and the compass ---
        var headingSmoothed = Float.NaN
        var headingEmitted = Float.NaN
        // elapsedRealtime of the last GPS-course heading. While this is recent the compass yields
        // to the course (the arrow follows your travel direction, which is far steadier).
        var lastCourseAtMs = 0L
        // Last calibration verdict pushed up, so we only notify on a change.
        var lastCalibrationBad: Boolean? = null

        fun emitHeading(deg: Float) {
            headingSmoothed = smoothAngle(headingSmoothed, deg)
            // Deadband: the magnetometer dithers by a degree or two even when the phone is perfectly
            // still, which reads as the cone shimmering. Only push once it has actually turned past
            // a small threshold.
            if (headingEmitted.isNaN() || angleDelta(headingEmitted, headingSmoothed) >= HEADING_MIN_DELTA_DEG) {
                headingEmitted = headingSmoothed
                currentOnHeading(headingSmoothed)
            }
        }

        fun reportCalibration(bad: Boolean) {
            if (lastCalibrationBad == bad) return
            lastCalibrationBad = bad
            currentOnCalibration(bad)
        }

        val locationListener = LocationListener { loc ->
            val accepted = lastAccepted
            // Provider arbitration: don't let a coarse Network fix replace a good GPS one.
            if (accepted != null && !isBetterLocation(loc, accepted)) return@LocationListener
            val raw = LatLng(loc.latitude, loc.longitude)
            val shown = displayed
            if (shown != null && accepted != null) {
                val moved = distanceMeters(shown, raw)
                // Organic Maps' jitter test (gps_track_filter.cpp IsGoodPoint): a fix landing inside
                // the accuracy circle of the position we're already showing — and not itself markedly
                // more accurate (≥2×) — is indistinguishable from staying put, so hold the marker.
                // This, not a low-pass, is what keeps the dot still while you stand: the anchor point
                // doesn't move, so successive wandering fixes keep getting rejected. A genuine step
                // (beyond the circle) or a sudden much-better fix passes through and becomes the new
                // target, which the renderer then glides to. A jump beyond the snap distance always
                // passes (provider switch / teleport).
                val lastAcc = if (accepted.hasAccuracy()) accepted.accuracy.toDouble() else 0.0
                val newAcc = if (loc.hasAccuracy()) loc.accuracy.toDouble() else lastAcc
                val circle = lastAcc.coerceIn(STATIONARY_DEADBAND_MIN_M, STATIONARY_DEADBAND_MAX_M)
                val muchMoreAccurate = lastAcc > 0.0 && newAcc <= 0.5 * lastAcc
                if (moved <= LOCATION_SNAP_M && moved < circle && !muchMoreAccurate) {
                    return@LocationListener
                }
            }
            lastAccepted = loc
            displayed = raw
            lastLocation = raw
            currentOnLocation(raw)
            // Course-over-ground is already true-north referenced (no declination needed). Use it
            // as the heading whenever we're moving fast enough for it to be meaningful — below that
            // the bearing is just GPS noise and the compass is better. Also require a decent
            // reported bearing accuracy: multipath in built-up areas (tall buildings reflecting the
            // signal) can make the course wildly wrong right at walking speed, and trusting it
            // unconditionally is what reads as the heading suddenly being "way off".
            val bearingTrustworthy = !loc.hasBearingAccuracy() || loc.bearingAccuracyDegrees <= COURSE_MAX_BEARING_ACCURACY_DEG
            if (loc.hasBearing() && loc.hasSpeed() && loc.speed >= COURSE_MIN_SPEED_MPS && bearingTrustworthy) {
                lastCourseAtMs = SystemClock.elapsedRealtime()
                emitHeading(loc.bearing)
            }
        }

        val sensorListener = object : SensorEventListener {
            private val rotationMatrix = FloatArray(9)

            override fun onSensorChanged(event: SensorEvent) {
                if (event.sensor.type != Sensor.TYPE_ROTATION_VECTOR) return
                // The rotation vector reports its estimated heading accuracy (radians) in values[4]
                // on most devices — a direct, reliable "is the compass trustworthy" signal.
                if (event.values.size >= 5 && event.values[4] >= 0f) {
                    reportCalibration(event.values[4] > HEADING_ACCURACY_BAD_RAD)
                }
                // While GPS course is driving the arrow (we're moving), let it own the heading —
                // course-over-ground is steadier than the compass once walking.
                if (SystemClock.elapsedRealtime() - lastCourseAtMs < COURSE_HOLD_MS) return
                SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
                // Facing = the horizontal direction the phone points, built from two device axes in
                // the world frame (ENU): the top edge (+Y) and the back of the phone (−Z). Both
                // point the same way along the ground for a given facing, so summing their
                // horizontal projections weights each by how horizontal it currently is. When the
                // phone is flat the top edge leads; held upright to read the map, the back leads —
                // one continuous formula, accurate at any tilt, with none of the gimbal-lock
                // instability the old getOrientation-after-remap had near vertical (the cause of the
                // heading being wrong/jumpy). Columns of R are each device axis in world coords:
                // device +Y → (R[1],R[4],R[7]), device Z → (R[2],R[5],R[8]); ENU index 0=East,1=North.
                val east = rotationMatrix[1] - rotationMatrix[2]
                val north = rotationMatrix[4] - rotationMatrix[5]
                if (east == 0f && north == 0f) return // phone exactly edge-on — no horizontal facing
                var deg = Math.toDegrees(Math.atan2(east.toDouble(), north.toDouble())).toFloat()
                lastLocation?.let { p ->
                    deg += GeomagneticField(
                        p.lat.toFloat(), p.lng.toFloat(), 0f, System.currentTimeMillis(),
                    ).declination
                }
                deg = ((deg % 360f) + 360f) % 360f
                emitHeading(deg)
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
                // Fallback calibration signal for devices that don't fill in values[4].
                when (accuracy) {
                    SensorManager.SENSOR_STATUS_UNRELIABLE,
                    SensorManager.SENSOR_STATUS_ACCURACY_LOW -> reportCalibration(true)
                    SensorManager.SENSOR_STATUS_ACCURACY_HIGH -> reportCalibration(false)
                }
            }
        }

        var active = false
        fun register() {
            if (active) return
            active = true
            rotationSensor?.let {
                sensorManager.registerListener(sensorListener, it, SensorManager.SENSOR_DELAY_UI)
            }
            if (hasLocationPermission) {
                @Suppress("MissingPermission")
                runCatching {
                    locationManager.requestLocationUpdates(
                        LocationManager.GPS_PROVIDER, 1000L, 1f, locationListener, Looper.getMainLooper(),
                    )
                }
                @Suppress("MissingPermission")
                runCatching {
                    locationManager.requestLocationUpdates(
                        LocationManager.NETWORK_PROVIDER, 2000L, 5f, locationListener, Looper.getMainLooper(),
                    )
                }
            }
        }
        fun unregister() {
            if (!active) return
            active = false
            sensorManager.unregisterListener(sensorListener)
            locationManager.removeUpdates(locationListener)
        }

        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> register()
                Lifecycle.Event.ON_PAUSE -> unregister()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        // The effect (re)starts while already foregrounded (the user just tapped a button), so the
        // ON_RESUME above won't fire again — register immediately if we're already resumed.
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) register()

        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            unregister()
        }
    }
}

/**
 * Whether [new] should replace [current] as the live fix. Loosely follows Android's classic
 * "best location" heuristic: a much newer fix wins outright (a stale one isn't worth keeping just
 * because it was more accurate), otherwise prefer whichever is more accurate, and only let a less
 * accurate same-or-newer fix through if it isn't drastically worse.
 */
private fun isBetterLocation(new: Location, current: Location): Boolean {
    val timeDeltaMs = new.time - current.time
    // A long-stale current fix is worth replacing even with a coarser one (e.g. GPS dropped and only
    // Network is left) — otherwise we'd freeze on an old position.
    if (timeDeltaMs > TWO_MINUTES_MS) return true
    if (timeDeltaMs < -TWO_MINUTES_MS) return false
    val accuracyDelta = new.accuracy - current.accuracy
    return when {
        // More (or equally) accurate — always take it.
        accuracyDelta <= 0f -> true
        // Only a little worse but fresh — fine; this is normal GPS accuracy breathing.
        accuracyDelta <= ACCURACY_TOLERANCE_M && timeDeltaMs >= 0 -> true
        // Meaningfully less accurate (the classic case: a ~50 m Network fix arriving over a ~10 m
        // GPS one) — reject it. Accepting it is what teleported the marker tens of metres and then
        // snapped it back on the next GPS fix.
        else -> false
    }
}

private const val TWO_MINUTES_MS = 2 * 60 * 1000L
// A new fix more than this much less accurate (metres) than the one we're showing is rejected, so a
// coarse Network fix can't snap the marker away from a good GPS position.
private const val ACCURACY_TOLERANCE_M = 30f

// Below this much turn (degrees) a new heading is suppressed, killing magnetometer shimmer.
private const val HEADING_MIN_DELTA_DEG = 2f
// A position jump larger than this (metres) is always accepted — covers the first real GPS fix
// after a coarse network one and any genuine teleport, neither of which should be held as "jitter".
private const val LOCATION_SNAP_M = 25.0
// The jitter circle: a fix within this radius (metres) of the shown position — clamped to the fix's
// reported accuracy — is treated as wander and ignored, so the marker holds still while you stand.
private const val STATIONARY_DEADBAND_MIN_M = 3.0
private const val STATIONARY_DEADBAND_MAX_M = 10.0
// Above this speed (m/s ≈ 2.5 km/h, a slow walk) GPS course-over-ground drives the heading instead
// of the compass; below it the bearing is mostly noise so the magnetometer is preferred.
private const val COURSE_MIN_SPEED_MPS = 0.7f
// Above this reported bearing accuracy (degrees) the GPS course is too unreliable to trust over
// the compass — common in urban canyons where building reflections degrade the fix.
private const val COURSE_MAX_BEARING_ACCURACY_DEG = 45f
// How long a GPS-course heading keeps priority over the compass after the last qualifying fix, so a
// brief pause between fixes (or at a crossing) doesn't immediately hand the arrow back to a noisier
// magnetometer reading.
private const val COURSE_HOLD_MS = 4_000L
// Estimated heading accuracy (radians) above which the compass is treated as needing calibration.
// ~0.6 rad ≈ 34°; beyond that the reading is too far off to trust.
private const val HEADING_ACCURACY_BAD_RAD = 0.6f

/** Exponentially smooths a compass angle, taking the shortest path across the 0°/360° wrap. */
private fun smoothAngle(previous: Float, next: Float, alpha: Float = 0.18f): Float {
    if (previous.isNaN()) return next
    var delta = next - previous
    while (delta > 180f) delta -= 360f
    while (delta < -180f) delta += 360f
    val value = previous + alpha * delta
    return ((value % 360f) + 360f) % 360f
}

/** Shortest absolute angular distance between two compass bearings, in degrees [0, 180]. */
private fun angleDelta(a: Float, b: Float): Float {
    var delta = Math.abs(a - b) % 360f
    if (delta > 180f) delta = 360f - delta
    return delta
}

/** Rough metric distance between two coordinates (equirectangular approx — fine at this scale). */
private fun distanceMeters(a: LatLng, b: LatLng): Double {
    val meanLat = Math.toRadians((a.lat + b.lat) / 2.0)
    val dLat = Math.toRadians(b.lat - a.lat)
    val dLng = Math.toRadians(b.lng - a.lng) * Math.cos(meanLat)
    return Math.sqrt(dLat * dLat + dLng * dLng) * 6_371_000.0
}
