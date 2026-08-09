package app.shadey.map

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.annotation.DrawableRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.shadey.R
import app.shadey.core.model.LatLng as CoreLatLng
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng as MlLatLng
import org.maplibre.android.gestures.MoveGestureDetector
import org.maplibre.geojson.Feature
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource

/** The visible map area reported back to the ViewModel after camera movement. */
data class ClosedBounds(val south: Double, val west: Double, val north: Double, val east: Double)

/** OpenMapTiles only carries the "building" layer at z14+, so shadows need at least this zoom. */
private const val BUILDING_MIN_ZOOM = 14.0

/** Milliseconds to wait before executing a building query after the last trigger fires. */
private const val QUERY_DEBOUNCE_MS = 400L

/** Ceiling on how long a burst of triggers may keep postponing the query (see scheduleQuery). */
private const val QUERY_MAX_WAIT_MS = 1_200L

// Live-marker glide (Organic-Maps-style position interpolation). Per-frame easing factor toward the
// latest fix; a jump beyond the snap distance is placed instantly (not crawled); the glide ends once
// within the converge distance so a stationary marker stops requesting frames.
private const val MARKER_GLIDE_ALPHA = 0.2
private const val MARKER_SNAP_M = 25.0
private const val MARKER_CONVERGE_M = 0.5

/** Holds the currently-drawn marker position across recompositions so each new fix glides from it. */
private class MarkerGlideState(var drawn: CoreLatLng? = null)

/** Rough metric distance between two coordinates (equirectangular approx — fine at marker scale). */
private fun distanceMeters(a: CoreLatLng, b: CoreLatLng): Double {
    val meanLat = Math.toRadians((a.lat + b.lat) / 2.0)
    val dLat = Math.toRadians(b.lat - a.lat)
    val dLng = Math.toRadians(b.lng - a.lng) * Math.cos(meanLat)
    return Math.sqrt(dLat * dLat + dLng * dLng) * 6_371_000.0
}

private class MapHandle(val map: MapLibreMap, val style: Style)

/**
 * A MapLibre map embedded in Compose. Renders the bundled buildings in 3D, an animated
 * ground-shadow overlay, the spots (coloured by sun/shade) and a dropped pin. All data
 * comes in as GeoJSON strings so the map layer has no dependency on the core types.
 */
@Composable
fun ShadeyMap(
    initialTarget: CoreLatLng,
    shadowsGeoJson: String,
    spotsGeoJson: String,
    pinGeoJson: String,
    routeGeoJson: String,
    userLocation: CoreLatLng?,
    userHeading: Float?,
    cameraTarget: CoreLatLng?,
    onMapClick: (CoreLatLng) -> Unit,
    onMapLongClick: (CoreLatLng) -> Unit,
    onCameraIdle: (center: CoreLatLng, bounds: ClosedBounds) -> Unit,
    onBuildingsQueried: (features: List<Feature>, belowZoom: Boolean) -> Unit,
    shouldHarvestBuildings: (center: CoreLatLng) -> Boolean,
    onCameraTargetConsumed: () -> Unit,
    onUserGesture: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val mapView = remember { MapView(context) }
    var handle by remember { mutableStateOf<MapHandle?>(null) }
    // AndroidView's factory runs only once, so click listeners registered inside it must read
    // these through rememberUpdatedState — otherwise they'd forever call the lambda instances
    // from the very first composition, with whatever they captured back then.
    val currentOnMapClick by rememberUpdatedState(onMapClick)
    val currentOnMapLongClick by rememberUpdatedState(onMapLongClick)
    val currentOnUserGesture by rememberUpdatedState(onUserGesture)
    val currentShouldHarvestBuildings by rememberUpdatedState(shouldHarvestBuildings)

    DisposableEffect(lifecycleOwner, mapView) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            mapView.onDestroy()
        }
    }

    AndroidView(
        modifier = modifier,
        factory = {
            mapView.apply {
                onCreate(null)
                getMapAsync { map ->
                    map.moveCamera(
                        CameraUpdateFactory.newCameraPosition(
                            CameraPosition.Builder()
                                .target(MlLatLng(initialTarget.lat, initialTarget.lng))
                                .zoom(15.5)
                                .tilt(0.0)
                                .build(),
                        ),
                    )
                    map.setStyle(Style.Builder().fromUri("https://tiles.openfreemap.org/styles/liberty")) { style ->
                        MapStyles.installLayers(context, style)
                        // All basemap layers whose ID contains "building" — this includes both the
                        // 2D fill layer (visible at z14+) and the 3D extrusion layer (z15+). Using
                        // both means we get building footprints at any zoom the app cares about,
                        // in any city worldwide. queryRenderedFeatures bounds results to the screen
                        // rect, so there is no OOM risk regardless of city density.
                        val buildingLayerIds = style.layers
                            .filter { it.id.contains("building", ignoreCase = true) }
                            .map { it.id }
                            .toTypedArray()
                        map.addOnMapClickListener { p ->
                            currentOnMapClick(CoreLatLng(p.latitude, p.longitude))
                            true
                        }
                        map.addOnMapLongClickListener { p ->
                            mapView.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                            currentOnMapLongClick(CoreLatLng(p.latitude, p.longitude))
                            true
                        }
                        // A user pan/zoom/rotate gesture disengages camera-follow so we never fight
                        // the user for control. Programmatic animateCamera (the follow itself) does
                        // not trigger this, only direct gestures do.
                        map.addOnMoveListener(object : MapLibreMap.OnMoveListener {
                            override fun onMoveBegin(detector: MoveGestureDetector) { currentOnUserGesture() }
                            override fun onMove(detector: MoveGestureDetector) {}
                            override fun onMoveEnd(detector: MoveGestureDetector) {}
                        })
                        // Debounced building query — camera-idle and render-finish can both fire
                        // many times per pan (once per tile zoom level as tiles arrive). We post
                        // a delayed runnable and cancel any pending one, so only the last event
                        // in a burst actually executes the query — BUT with a max-wait: while the
                        // map renders continuously (tiles streaming in, follow-camera easing, the
                        // marker gliding) the events arrive faster than the debounce interval, and
                        // a pure trailing debounce would keep cancelling itself forever. That
                        // starvation is what made shade take ages to appear on a slow connection
                        // (and why backgrounding the app "fixed" it: the render loop paused long
                        // enough for one quiet debounce interval to elapse).
                        val queryHandler = Handler(Looper.getMainLooper())
                        var queryBurstStartMs = 0L
                        val queryRunnable = Runnable {
                            queryBurstStartMs = 0L
                            if (map.cameraPosition.zoom < BUILDING_MIN_ZOOM) {
                                onBuildingsQueried(emptyList(), true)
                                return@Runnable
                            }
                            val target = map.cameraPosition.target
                            if (buildingLayerIds.isNotEmpty() && target != null &&
                                currentShouldHarvestBuildings(CoreLatLng(target.latitude, target.longitude))
                            ) {
                                // Expand rect by 20% on each side so buildings near screen edges
                                // (which can still cast shadows into the view) are included.
                                val w = mapView.width.toFloat()
                                val h = mapView.height.toFloat()
                                val padX = w * 0.2f
                                val padY = h * 0.2f
                                val rect = RectF(-padX, -padY, w + padX, h + padY)
                                val features = map.queryRenderedFeatures(rect, *buildingLayerIds)
                                onBuildingsQueried(features, false)
                            }
                        }
                        fun scheduleQuery() {
                            val now = SystemClock.uptimeMillis()
                            if (queryBurstStartMs == 0L) queryBurstStartMs = now
                            queryHandler.removeCallbacks(queryRunnable)
                            val delay = if (now - queryBurstStartMs >= QUERY_MAX_WAIT_MS) 0L else QUERY_DEBOUNCE_MS
                            queryHandler.postDelayed(queryRunnable, delay)
                        }
                        map.addOnCameraIdleListener {
                            val center = map.cameraPosition.target
                            val b = map.projection.visibleRegion.latLngBounds
                            if (center != null) {
                                onCameraIdle(
                                    CoreLatLng(center.latitude, center.longitude),
                                    ClosedBounds(b.southWest.latitude, b.southWest.longitude, b.northEast.latitude, b.northEast.longitude),
                                )
                            }
                            scheduleQuery()
                        }
                        mapView.addOnDidFinishRenderingMapListener(
                            MapView.OnDidFinishRenderingMapListener { fully -> if (fully) scheduleQuery() }
                        )
                        handle = MapHandle(map, style)
                    }
                }
            }
        },
    )

    LaunchedEffect(handle, shadowsGeoJson) {
        handle?.style?.getSourceAs<GeoJsonSource>("shadows")?.setGeoJson(shadowsGeoJson)
    }
    LaunchedEffect(handle, spotsGeoJson) {
        handle?.style?.getSourceAs<GeoJsonSource>("spots")?.setGeoJson(spotsGeoJson)
    }
    LaunchedEffect(handle, pinGeoJson) {
        handle?.style?.getSourceAs<GeoJsonSource>("pin")?.setGeoJson(pinGeoJson)
    }
    LaunchedEffect(handle, routeGeoJson) {
        handle?.style?.getSourceAs<GeoJsonSource>("route")?.setGeoJson(routeGeoJson)
    }
    // Glide the live location marker between fixes instead of teleporting to each one, the way
    // Organic Maps animates its position puck (its MyPositionController interpolates the drawn
    // position over a duration rather than jumping). A GPS fix lands ~once a second; snapping the
    // dot to each makes a walk look like a series of hops and visually amplifies any residual
    // jitter. Here the drawn position eases toward the latest fix each frame, and the (already
    // upstream-smoothed) heading is applied as-is. Keyed on the fix/heading, so each new value
    // cancels the in-flight glide and continues from wherever the dot currently is; once it has
    // essentially arrived the loop ends and no further frames are requested, so a stationary
    // marker costs nothing. The "user" source holds a single point, so these per-frame rewrites
    // never touch the shadow/building pipeline.
    val markerGlide = remember { MarkerGlideState() }
    LaunchedEffect(handle, userLocation, userHeading) {
        val source = handle?.style?.getSourceAs<GeoJsonSource>("user") ?: return@LaunchedEffect
        val target = userLocation
        if (target == null) {
            markerGlide.drawn = null
            source.setGeoJson(GeoJsonWriter.emptyCollection())
            return@LaunchedEffect
        }
        val start = markerGlide.drawn
        // First fix, or a jump too large to be a step (provider switch / teleport): place directly.
        if (start == null || distanceMeters(start, target) > MARKER_SNAP_M) {
            markerGlide.drawn = target
            source.setGeoJson(GeoJsonWriter.userMarker(target, userHeading))
            return@LaunchedEffect
        }
        var cur: CoreLatLng = start
        while (distanceMeters(cur, target) > MARKER_CONVERGE_M) {
            withFrameNanos { }
            cur = CoreLatLng(
                cur.lat + MARKER_GLIDE_ALPHA * (target.lat - cur.lat),
                cur.lng + MARKER_GLIDE_ALPHA * (target.lng - cur.lng),
            )
            markerGlide.drawn = cur
            source.setGeoJson(GeoJsonWriter.userMarker(cur, userHeading))
        }
        markerGlide.drawn = target
        source.setGeoJson(GeoJsonWriter.userMarker(target, userHeading))
    }
    LaunchedEffect(handle, cameraTarget) {
        val h = handle ?: return@LaunchedEffect
        val target = cameraTarget ?: return@LaunchedEffect
        h.map.animateCamera(CameraUpdateFactory.newLatLng(MlLatLng(target.lat, target.lng)))
        onCameraTargetConsumed()
    }
}

private object MapStyles {
    // Icon ids registered with the style for the live location marker.
    private const val CONE_IMAGE = "user-cone"
    private const val PERSON_IMAGE = "user-person"

    fun installLayers(context: Context, style: Style) {
        style.addSource(GeoJsonSource("shadows", GeoJsonWriter.emptyCollection()))
        style.addSource(GeoJsonSource("spots", GeoJsonWriter.emptyCollection()))
        style.addSource(GeoJsonSource("pin", GeoJsonWriter.emptyCollection()))
        style.addSource(GeoJsonSource("route", GeoJsonWriter.emptyCollection()))
        style.addSource(GeoJsonSource("user", GeoJsonWriter.emptyCollection()))

        style.addImage(CONE_IMAGE, drawableToBitmap(context, R.drawable.ic_user_heading_cone, 168))
        style.addImage(PERSON_IMAGE, personBitmap(context, 90))

        // Ground shadows — inserted below road labels so they show on top of ground/parks
        // but don't cover street text. "road_label" is a stable layer in the Liberty style.
        val shadowLayer = FillLayer("shadows-layer", "shadows").withProperties(
            PropertyFactory.fillColor("#1A2A44"),
            PropertyFactory.fillOpacity(0.55f),
        )
        val firstSymbolLayer = style.layers.firstOrNull { it is org.maplibre.android.style.layers.SymbolLayer }
        if (firstSymbolLayer != null) {
            style.addLayerBelow(shadowLayer, firstSymbolLayer.id)
        } else {
            style.addLayer(shadowLayer)
        }
        // Spots, coloured by sun/shade.
        style.addLayer(
            CircleLayer("spots-layer", "spots").withProperties(
                PropertyFactory.circleRadius(7f),
                PropertyFactory.circleColor(Expression.get("color")),
                PropertyFactory.circleStrokeColor("#FFFFFF"),
                PropertyFactory.circleStrokeWidth(2f),
            ),
        )
        // Shady-route line, coloured per same-sunlight run, over a white casing so the
        // blue reads clearly against any basemap colour (orange roads, shadow overlay).
        style.addLayer(
            LineLayer("route-casing", "route").withProperties(
                PropertyFactory.lineColor("#FFFFFF"),
                PropertyFactory.lineWidth(10f),
                PropertyFactory.lineCap("round"),
                PropertyFactory.lineJoin("round"),
                PropertyFactory.lineOpacity(0.9f),
            ),
        )
        style.addLayer(
            LineLayer("route-layer", "route").withProperties(
                PropertyFactory.lineColor(Expression.get("color")),
                PropertyFactory.lineWidth(6f),
                PropertyFactory.lineCap("round"),
                PropertyFactory.lineJoin("round"),
                PropertyFactory.lineOpacity(1.0f),
            ),
        )
        // Dropped pin / route endpoints (drawn on top).
        style.addLayer(
            CircleLayer("pin-layer", "pin").withProperties(
                PropertyFactory.circleRadius(9f),
                PropertyFactory.circleColor(Expression.get("color")),
                PropertyFactory.circleStrokeColor("#1A1A1A"),
                PropertyFactory.circleStrokeWidth(3f),
            ),
        )
        // Live "you are here" marker (drawn above everything else). The cone fans out in the
        // facing direction and only appears once a heading reading exists ("cone" == true): it
        // rotates by the per-feature "heading" property, aligned to the map so it points at the
        // true-world bearing regardless of map rotation. The ツ face sits on top of it and stays
        // upright (no rotation) so it's always readable, like a pin that the cone swings beneath.
        val coneLayer = SymbolLayer("user-cone-layer", "user").withProperties(
            PropertyFactory.iconImage(CONE_IMAGE),
            PropertyFactory.iconRotate(Expression.get("heading")),
            PropertyFactory.iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_MAP),
            PropertyFactory.iconAnchor(Property.ICON_ANCHOR_BOTTOM),
            PropertyFactory.iconAllowOverlap(true),
            PropertyFactory.iconIgnorePlacement(true),
        )
        coneLayer.setFilter(Expression.eq(Expression.get("cone"), Expression.literal(true)))
        style.addLayer(coneLayer)
        style.addLayer(
            SymbolLayer("user-person-layer", "user").withProperties(
                PropertyFactory.iconImage(PERSON_IMAGE),
                PropertyFactory.iconAllowOverlap(true),
                PropertyFactory.iconIgnorePlacement(true),
            ),
        )
    }

    /** Rasterise a (possibly vector) drawable to a square [sizePx] bitmap for `style.addImage`. */
    private fun drawableToBitmap(context: Context, @DrawableRes id: Int, sizePx: Int): Bitmap {
        val drawable = requireNotNull(ContextCompat.getDrawable(context, id)) { "missing drawable $id" }
        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, sizePx, sizePx)
        drawable.draw(canvas)
        return bitmap
    }

    /** The "you are here" puck (see [ic_user_person]) with a "ツ" face drawn on top — vector
     *  drawables can't render text, so the glyph is painted onto the rasterised bitmap directly. */
    private fun personBitmap(context: Context, sizePx: Int): Bitmap {
        val bitmap = drawableToBitmap(context, R.drawable.ic_user_person, sizePx)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.parseColor("#1A73E8")
            textAlign = Paint.Align.CENTER
            textSize = sizePx * 0.5f
            isFakeBoldText = true
        }
        val canvas = Canvas(bitmap)
        val baselineShift = (paint.descent() + paint.ascent()) / 2f
        canvas.drawText("ツ", sizePx / 2f, sizePx / 2f - baselineShift, paint)
        return bitmap
    }
}
