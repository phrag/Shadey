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

    fun point(p: LatLng, color: String): String = collection {
        addJsonObject {
            put("type", "Feature")
            putJsonObject("geometry") {
                put("type", "Point")
                putJsonArray("coordinates") { add(p.lng); add(p.lat) }
            }
            putJsonObject("properties") { put("color", color) }
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
}
