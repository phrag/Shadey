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
import android.os.Build
import android.os.Looper
import android.view.Surface
import android.view.WindowManager
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
 */
@Composable
fun LocationHeadingTracker(
    enabled: Boolean,
    onLocation: (LatLng) -> Unit,
    onHeading: (Float) -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentOnLocation by rememberUpdatedState(onLocation)
    val currentOnHeading by rememberUpdatedState(onHeading)

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

        val locationListener = LocationListener { loc ->
            val accepted = lastAccepted
            if (accepted != null && !isBetterLocation(loc, accepted)) return@LocationListener
            lastAccepted = loc
            val p = LatLng(loc.latitude, loc.longitude)
            lastLocation = p
            currentOnLocation(p)
        }

        val sensorListener = object : SensorEventListener {
            private val rotationMatrix = FloatArray(9)
            private val remapped = FloatArray(9)
            private val orientation = FloatArray(3)
            private var smoothed = Float.NaN

            override fun onSensorChanged(event: SensorEvent) {
                if (event.sensor.type != Sensor.TYPE_ROTATION_VECTOR) return
                SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
                val (axisX, axisY) = remapAxesForDisplay(displayRotation(context))
                SensorManager.remapCoordinateSystem(rotationMatrix, axisX, axisY, remapped)
                SensorManager.getOrientation(remapped, orientation)
                var deg = Math.toDegrees(orientation[0].toDouble()).toFloat() // magnetic, [-180,180]
                lastLocation?.let { p ->
                    deg += GeomagneticField(
                        p.lat.toFloat(), p.lng.toFloat(), 0f, System.currentTimeMillis(),
                    ).declination
                }
                deg = ((deg % 360f) + 360f) % 360f
                smoothed = smoothAngle(smoothed, deg)
                currentOnHeading(smoothed)
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
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
    if (timeDeltaMs > TWO_MINUTES_MS) return true
    if (timeDeltaMs < -TWO_MINUTES_MS) return false
    val accuracyDelta = new.accuracy - current.accuracy
    val isSignificantlyLessAccurate = accuracyDelta > 200f
    return when {
        accuracyDelta <= 0f -> true
        timeDeltaMs >= 0 && !isSignificantlyLessAccurate -> true
        else -> false
    }
}

private const val TWO_MINUTES_MS = 2 * 60 * 1000L

/** Exponentially smooths a compass angle, taking the shortest path across the 0°/360° wrap. */
private fun smoothAngle(previous: Float, next: Float, alpha: Float = 0.18f): Float {
    if (previous.isNaN()) return next
    var delta = next - previous
    while (delta > 180f) delta -= 360f
    while (delta < -180f) delta += 360f
    val value = previous + alpha * delta
    return ((value % 360f) + 360f) % 360f
}

@Suppress("DEPRECATION")
private fun displayRotation(context: Context): Int =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        context.display?.rotation ?: Surface.ROTATION_0
    } else {
        (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.rotation
    }

/**
 * Axis remap so the heading is correct regardless of how the screen is currently rotated.
 *
 * Walking-navigation use means the phone is held upright facing the user, not flat on a table —
 * remapping onto the device's Z axis (rather than Y) is the standard correction for that "vertical
 * compass" posture. Getting this wrong doesn't just bias the heading, it puts [SensorManager.getOrientation]
 * near gimbal lock for a phone held near-vertical, which reads as the direction "jumping around".
 */
private fun remapAxesForDisplay(rotation: Int): Pair<Int, Int> = when (rotation) {
    Surface.ROTATION_90 -> SensorManager.AXIS_Z to SensorManager.AXIS_MINUS_X
    Surface.ROTATION_180 -> SensorManager.AXIS_MINUS_X to SensorManager.AXIS_MINUS_Z
    Surface.ROTATION_270 -> SensorManager.AXIS_MINUS_Z to SensorManager.AXIS_X
    else -> SensorManager.AXIS_X to SensorManager.AXIS_Z
}
