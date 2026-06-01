package app.shadey.core.model

import kotlinx.serialization.Serializable

/** A geographic coordinate in WGS84 degrees. */
@Serializable
data class LatLng(val lat: Double, val lng: Double)

/**
 * A building modelled as a flat-roofed prism: an outer-ring footprint extruded
 * from [minHeightMeters] to [heightMeters] above ground. Good enough for
 * city-scale shadow casting on the (essentially flat) ground of Berlin.
 */
data class Building(
    val id: String,
    val footprint: List<LatLng>,
    val heightMeters: Double,
    val minHeightMeters: Double = 0.0,
)

@Serializable
enum class SpotCategory { CAFE, BAR, RESTAURANT, PARK, SQUARE, BENCH, WATERSIDE, VIEWPOINT, PLAYGROUND, OTHER }

@Serializable
enum class SpotSource { CURATED, USER, OSM }

/** A place a user might want to sit in the sun. */
@Serializable
data class Spot(
    val id: String,
    val name: String,
    val lat: Double,
    val lng: Double,
    val category: SpotCategory = SpotCategory.OTHER,
    val description: String = "",
    val source: SpotSource = SpotSource.CURATED,
) {
    val latLng: LatLng get() = LatLng(lat, lng)
}

/**
 * Solar position. [azimuthDeg] is measured clockwise from geographic north
 * (0 = N, 90 = E, 180 = S, 270 = W). [elevationDeg] is degrees above the horizon.
 */
data class SolarPosition(val azimuthDeg: Double, val elevationDeg: Double) {
    val isDaylight: Boolean get() = elevationDeg > 0.0
}

/** Direct-sun classification of a point at an instant. */
enum class Sunlight { SUN, SHADE, NIGHT }
