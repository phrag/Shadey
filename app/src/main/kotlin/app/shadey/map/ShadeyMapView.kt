package app.shadey.map

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

private class MapHandle(val map: MapLibreMap, val style: Style)

/**
 * A MapLibre map embedded in Compose. Renders the bundled buildings in 3D, an animated
 * ground-shadow overlay, the spots (coloured by sun/shade) and a dropped pin. All data
 * comes in as GeoJSON strings so the map layer has no dependency on the core types.
 */
@Composable
fun ShadeyMap(
    initialTarget: CoreLatLng,
    buildingsGeoJson: String,
    shadowsGeoJson: String,
    spotsGeoJson: String,
    pinGeoJson: String,
    cameraTarget: CoreLatLng?,
    onMapClick: (CoreLatLng) -> Unit,
    onCameraIdle: (center: CoreLatLng, bounds: ClosedBounds) -> Unit,
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
                        MapStyles.installLayers(style, buildingsGeoJson)
                        map.addOnMapClickListener { p ->
                            onMapClick(CoreLatLng(p.latitude, p.longitude))
                            true
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
                        }
                        handle = MapHandle(map, style)
                    }
                }
            }
        },
    )

    LaunchedEffect(handle, buildingsGeoJson) {
        handle?.style?.getSourceAs<GeoJsonSource>("buildings")?.setGeoJson(buildingsGeoJson)
    }
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
    fun installLayers(style: Style, buildingsGeoJson: String) {
        style.addSource(GeoJsonSource("shadows", GeoJsonWriter.emptyCollection()))
        style.addSource(GeoJsonSource("buildings", buildingsGeoJson))
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
