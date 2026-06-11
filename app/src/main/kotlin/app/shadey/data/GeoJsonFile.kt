package app.shadey.data

import android.util.JsonReader
import android.util.JsonToken
import app.shadey.core.model.Building
import app.shadey.core.model.LatLng
import app.shadey.core.model.Tree
import java.io.File
import java.io.InputStream
import java.io.InputStreamReader

/**
 * Streaming readers for GeoJSON FeatureCollection files (downloaded cities, the bundled
 * Berlin asset). Unlike the DOM parsers in `:core` ([app.shadey.core.data.GeoJsonBuildings]),
 * these never hold the file text or a parsed JSON tree in memory — only one feature at a
 * time plus the compact result list. For a large city the text + DOM approach peaks at
 * several times the multi-MB file size, which exhausts a 256 MB heap and (because the DOM
 * parser swallows the OutOfMemoryError) surfaced as a bogus "No buildings found there".
 *
 * Property semantics mirror the core parsers exactly: heights from `height`/`render_height`
 * (metres, number or messy string) or `building:levels`; tree features are `Point`s with
 * `properties.kind == "tree"`.
 */
object GeoJsonFile {

    fun buildings(
        file: File,
        defaultHeightMeters: Double = 9.0,
        metersPerLevel: Double = 3.2,
    ): List<Building> = file.inputStream().use { buildings(it, defaultHeightMeters, metersPerLevel) }

    fun buildings(
        input: InputStream,
        defaultHeightMeters: Double = 9.0,
        metersPerLevel: Double = 3.2,
    ): List<Building> {
        val out = ArrayList<Building>()
        var auto = 0
        forEachFeature(input) { f ->
            val height = extractHeight(f.props, defaultHeightMeters, metersPerLevel)
            val minHeight = f.props["min_height"]?.toDoubleOrNull() ?: 0.0
            val baseId = f.id ?: f.props["id"] ?: f.props["osm_id"] ?: "b${auto++}"
            when (f.geomType) {
                "Polygon" -> outerRing(f.coords)?.let { out.add(Building(baseId, it, height, minHeight)) }
                "MultiPolygon" -> (f.coords as? List<*>)?.forEachIndexed { i, poly ->
                    outerRing(poly)?.let { out.add(Building("$baseId:$i", it, height, minHeight)) }
                }
            }
        }
        return out
    }

    fun trees(
        file: File,
        defaultCrownRadiusMeters: Double = 3.0,
        defaultHeightMeters: Double = 12.0,
    ): List<Tree> = file.inputStream().use { trees(it, defaultCrownRadiusMeters, defaultHeightMeters) }

    fun trees(
        input: InputStream,
        defaultCrownRadiusMeters: Double = 3.0,
        defaultHeightMeters: Double = 12.0,
    ): List<Tree> {
        val out = ArrayList<Tree>()
        var auto = 0
        forEachFeature(input) { f ->
            if (f.props["kind"] != "tree" || f.geomType != "Point") return@forEachFeature
            val pt = f.coords as? List<*> ?: return@forEachFeature
            val lng = pt.getOrNull(0) as? Double ?: return@forEachFeature
            val lat = pt.getOrNull(1) as? Double ?: return@forEachFeature
            val crownRadius = f.props["crown_radius"]?.toDoubleOrNull()?.takeIf { it > 0 }
                ?: defaultCrownRadiusMeters
            val height = f.props["height"]?.toDoubleOrNull()?.takeIf { it > 0 }
                ?: defaultHeightMeters
            val deciduous = f.props["deciduous"]?.toBooleanStrictOrNull() ?: true
            val id = f.id ?: f.props["osm_id"] ?: "t${auto++}"
            out.add(Tree(id, LatLng(lat, lng), crownRadius, height, deciduous))
        }
        return out
    }

    /** One feature's worth of data — the only thing held in memory at a time. */
    private class RawFeature(
        val id: String?,
        val props: Map<String, String>,
        val geomType: String?,
        val coords: Any?,
    )

    private inline fun forEachFeature(input: InputStream, action: (RawFeature) -> Unit) {
        JsonReader(InputStreamReader(input, Charsets.UTF_8)).use { r ->
            r.isLenient = true
            r.beginObject()
            while (r.hasNext()) {
                if (r.nextName() == "features") {
                    r.beginArray()
                    while (r.hasNext()) action(readFeature(r))
                    r.endArray()
                } else {
                    r.skipValue()
                }
            }
            r.endObject()
        }
    }

    private fun readFeature(r: JsonReader): RawFeature {
        var id: String? = null
        var props: Map<String, String> = emptyMap()
        var geomType: String? = null
        var coords: Any? = null
        r.beginObject()
        while (r.hasNext()) {
            when (r.nextName()) {
                "id" -> id = r.nextString()
                "properties" -> props = readProps(r)
                "geometry" -> if (r.peek() == JsonToken.NULL) r.nextNull() else {
                    r.beginObject()
                    while (r.hasNext()) {
                        when (r.nextName()) {
                            "type" -> geomType = r.nextString()
                            "coordinates" -> coords = readNested(r)
                            else -> r.skipValue()
                        }
                    }
                    r.endObject()
                }
                else -> r.skipValue()
            }
        }
        r.endObject()
        return RawFeature(id, props, geomType, coords)
    }

    /** Primitive property values as strings (numbers/booleans included); nested values skipped. */
    private fun readProps(r: JsonReader): Map<String, String> {
        if (r.peek() == JsonToken.NULL) { r.nextNull(); return emptyMap() }
        val m = HashMap<String, String>()
        r.beginObject()
        while (r.hasNext()) {
            val key = r.nextName()
            when (r.peek()) {
                JsonToken.STRING, JsonToken.NUMBER -> m[key] = r.nextString()
                JsonToken.BOOLEAN -> m[key] = r.nextBoolean().toString()
                else -> r.skipValue()
            }
        }
        r.endObject()
        return m
    }

    /** Coordinates as nested lists of Double, whatever the nesting depth (Point…MultiPolygon). */
    private fun readNested(r: JsonReader): Any? = when (r.peek()) {
        JsonToken.BEGIN_ARRAY -> {
            val list = ArrayList<Any?>()
            r.beginArray()
            while (r.hasNext()) list.add(readNested(r))
            r.endArray()
            list
        }
        JsonToken.NUMBER -> r.nextDouble()
        else -> { r.skipValue(); null }
    }

    private fun outerRing(coords: Any?): List<LatLng>? {
        val ring = (coords as? List<*>)?.getOrNull(0) as? List<*> ?: return null
        val pts = ring.mapNotNull { p ->
            val pt = p as? List<*> ?: return@mapNotNull null
            val lng = pt.getOrNull(0) as? Double
            val lat = pt.getOrNull(1) as? Double
            if (lat != null && lng != null) LatLng(lat, lng) else null
        }
        return if (pts.size >= 3) pts else null
    }

    private fun extractHeight(props: Map<String, String>, default: Double, metersPerLevel: Double): Double {
        (props["height"] ?: props["render_height"])
            ?.let { it.toDoubleOrNull() ?: it.filter { c -> c.isDigit() || c == '.' }.toDoubleOrNull() }
            ?.let { if (it > 0) return it }
        props["building:levels"]?.toDoubleOrNull()
            ?.let { if (it > 0) return it * metersPerLevel }
        return default
    }
}
