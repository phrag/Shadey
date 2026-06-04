package app.shadey.map

import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.shadey.core.model.LatLng as CoreLatLng
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng as MlLatLng
import org.maplibre.geojson.Feature
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource

/** The visible map area reported back to the ViewModel after camera movement. */
data class ClosedBounds(val south: Double, val west: Double, val north: Double, val east: Double)

/** OpenMapTiles only carries the "building" layer at z14+, so shadows need at least this zoom. */
private const val BUILDING_MIN_ZOOM = 14.0

/** Milliseconds to wait before executing a building query after the last trigger fires. */
private const val QUERY_DEBOUNCE_MS = 400L

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
    cameraTarget: CoreLatLng?,
    onMapClick: (CoreLatLng) -> Unit,
    onCameraIdle: (center: CoreLatLng, bounds: ClosedBounds) -> Unit,
    onBuildingsQueried: (features: List<Feature>, belowZoom: Boolean) -> Unit,
    onCameraTargetConsumed: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val mapView = remember { MapView(context) }
    var handle by remember { mutableStateOf<MapHandle?>(null) }

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
                                .tilt(45.0)
                                .build(),
                        ),
                    )
                    map.setStyle(Style.Builder().fromUri("https://tiles.openfreemap.org/styles/liberty")) { style ->
                        MapStyles.installLayers(style)
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
                            onMapClick(CoreLatLng(p.latitude, p.longitude))
                            true
                        }
                        // Debounced building query — camera-idle and render-finish can both fire
                        // many times per pan (once per tile zoom level as tiles arrive). We post
                        // a delayed runnable and cancel any pending one, so only the last event
                        // in a burst actually executes the query.
                        val queryHandler = Handler(Looper.getMainLooper())
                        val queryRunnable = Runnable {
                            if (map.cameraPosition.zoom < BUILDING_MIN_ZOOM) {
                                onBuildingsQueried(emptyList(), true)
                                return@Runnable
                            }
                            if (buildingLayerIds.isNotEmpty()) {
                                val rect = RectF(0f, 0f, mapView.width.toFloat(), mapView.height.toFloat())
                                val features = map.queryRenderedFeatures(rect, *buildingLayerIds)
                                onBuildingsQueried(features, false)
                            }
                        }
                        fun scheduleQuery() {
                            queryHandler.removeCallbacks(queryRunnable)
                            queryHandler.postDelayed(queryRunnable, QUERY_DEBOUNCE_MS)
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
    LaunchedEffect(handle, cameraTarget) {
        val h = handle ?: return@LaunchedEffect
        val target = cameraTarget ?: return@LaunchedEffect
        h.map.animateCamera(CameraUpdateFactory.newLatLng(MlLatLng(target.lat, target.lng)))
        onCameraTargetConsumed()
    }
}

private object MapStyles {
    fun installLayers(style: Style) {
        style.addSource(GeoJsonSource("shadows", GeoJsonWriter.emptyCollection()))
        style.addSource(GeoJsonSource("spots", GeoJsonWriter.emptyCollection()))
        style.addSource(GeoJsonSource("pin", GeoJsonWriter.emptyCollection()))

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
        // Dropped pin (drawn on top).
        style.addLayer(
            CircleLayer("pin-layer", "pin").withProperties(
                PropertyFactory.circleRadius(9f),
                PropertyFactory.circleColor(Expression.get("color")),
                PropertyFactory.circleStrokeColor("#1A1A1A"),
                PropertyFactory.circleStrokeWidth(3f),
            ),
        )
    }
}
