package app.shadey.map

import app.shadey.core.model.Building
import app.shadey.core.model.LatLng
import app.shadey.core.model.Sunlight
import app.shadey.core.rank.SpotSunInfo
import kotlinx.serialization.json.JsonArrayBuilder
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonArray
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** Builds GeoJSON strings for MapLibre sources from core model objects. */
object GeoJsonWriter {

    private fun collection(features: JsonArrayBuilder.() -> Unit): String = buildJsonObject {
        put("type", "FeatureCollection")
        putJsonArray("features", features)
    }.toString()

    fun emptyCollection(): String = collection { }

    fun spots(infos: List<SpotSunInfo>): String = collection {
        infos.forEach { info ->
            addJsonObject {
                put("type", "Feature")
                putJsonObject("geometry") {
                    put("type", "Point")
                    putJsonArray("coordinates") { add(info.spot.lng); add(info.spot.lat) }
                }
                putJsonObject("properties") {
                    put("id", info.spot.id)
                    put("name", info.spot.name)
                    put("color", colorFor(info.sunlight))
                }
            }
        }
    }

    fun point(p: LatLng, color: String): String = points(listOf(p to color))

    /**
     * The live "you are here" marker: one Point feature carrying the facing direction. [heading]
     * is degrees clockwise from true north (null until the orientation sensor has a reading). The
     * `cone` flag drives a layer filter so the directional cone only shows once a heading exists,
     * leaving just the person silhouette before then.
     */
    fun userMarker(p: LatLng, heading: Float?): String = collection {
        addJsonObject {
            put("type", "Feature")
            putJsonObject("geometry") {
                put("type", "Point")
                putJsonArray("coordinates") { add(p.lng); add(p.lat) }
            }
            putJsonObject("properties") {
                put("heading", (heading ?: 0f).toDouble())
                put("cone", heading != null)
            }
        }
    }

    /** Multiple point markers in one source — e.g. a route's start and end pins. */
    fun points(items: List<Pair<LatLng, String>>): String = collection {
        items.forEach { (p, color) ->
            addJsonObject {
                put("type", "Feature")
                putJsonObject("geometry") {
                    put("type", "Point")
                    putJsonArray("coordinates") { add(p.lng); add(p.lat) }
                }
                putJsonObject("properties") { put("color", color) }
            }
        }
    }

    /** A route rendered as one LineString feature per same-sunlight run, coloured accordingly. */
    fun route(segments: List<Pair<List<LatLng>, Sunlight>>): String = collection {
        segments.forEach { (coords, sunlight) ->
            if (coords.size < 2) return@forEach
            addJsonObject {
                put("type", "Feature")
                putJsonObject("geometry") {
                    put("type", "LineString")
                    putJsonArray("coordinates") { coords.forEach { pt -> addJsonArray { add(pt.lng); add(pt.lat) } } }
                }
                putJsonObject("properties") { put("color", routeColorFor(sunlight)) }
            }
        }
    }

    fun shadows(rings: List<List<LatLng>>): String = collection {
        rings.forEach { ring ->
            addJsonObject {
                put("type", "Feature")
                putJsonObject("geometry") {
                    put("type", "Polygon")
                    putJsonArray("coordinates") { addRing(ring) }
                }
                putJsonObject("properties") { }
            }
        }
    }

    fun buildings(list: List<Building>): String = collection {
        list.forEach { b ->
            addJsonObject {
                put("type", "Feature")
                putJsonObject("geometry") {
                    put("type", "Polygon")
                    putJsonArray("coordinates") { addRing(b.footprint) }
                }
                putJsonObject("properties") { put("height", b.heightMeters) }
            }
        }
    }

    private fun JsonArrayBuilder.addRing(ring: List<LatLng>) {
        addJsonArray {
            ring.forEach { pt -> addJsonArray { add(pt.lng); add(pt.lat) } }
            ring.firstOrNull()?.let { addJsonArray { add(it.lng); add(it.lat) } } // close ring
        }
    }

    fun colorFor(s: Sunlight): String = when (s) {
        Sunlight.SUN -> "#F5A623"
        Sunlight.SHADE -> "#5B6B7B"
        Sunlight.NIGHT -> "#3A3F4B"
    }

    /**
     * Route lines use their own blue palette instead of [colorFor]: the sun-orange there
     * disappears against the basemap's orange major roads, and the shade grey against the
     * shadow overlay. Blues read as "route" (like every navigation app) while still coding
     * the sun/shade split — dark blue for shaded stretches, light sky-blue for sunny ones.
     */
    fun routeColorFor(s: Sunlight): String = when (s) {
        Sunlight.SUN -> "#4FC3F7"
        Sunlight.SHADE -> "#1565C0"
        Sunlight.NIGHT -> "#283593"
    }
}
