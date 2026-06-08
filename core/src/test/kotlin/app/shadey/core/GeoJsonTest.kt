package app.shadey.core

import app.shadey.core.data.GeoJsonBuildings
import app.shadey.core.data.GeoJsonTrees
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs

class GeoJsonTest {

    // A FeatureCollection like the one BuildingDownloader emits: one building polygon and two
    // tree point-features — one with OSM-measured crown/height, one bare (the common case).
    private val mixedCollection = """
        {
          "type": "FeatureCollection",
          "features": [
            {
              "type": "Feature",
              "properties": {"height": 21.0, "osm_id": "way/1"},
              "geometry": {"type": "Polygon", "coordinates": [[[13.40,52.50],[13.401,52.50],[13.401,52.501],[13.40,52.501],[13.40,52.50]]]}
            },
            {
              "type": "Feature",
              "properties": {"kind": "tree", "osm_id": "node/2", "deciduous": true, "crown_radius": 4.5, "height": 15.0},
              "geometry": {"type": "Point", "coordinates": [13.402, 52.502]}
            },
            {
              "type": "Feature",
              "properties": {"kind": "tree", "osm_id": "node/3", "deciduous": false},
              "geometry": {"type": "Point", "coordinates": [13.403, 52.503]}
            }
          ]
        }
    """.trimIndent()

    @Test
    fun `parses tree points with measured crown and height`() {
        val trees = GeoJsonTrees.parse(mixedCollection)
        assertEquals(2, trees.size)
        val measured = trees.first { it.id == "node/2" }
        assertTrue(abs(measured.crownRadiusMeters - 4.5) < 1e-9)
        assertTrue(abs(measured.heightMeters - 15.0) < 1e-9)
        assertTrue(measured.deciduous)
        assertEquals(52.502, measured.position.lat, 1e-9)
        assertEquals(13.402, measured.position.lng, 1e-9)
    }

    @Test
    fun `falls back to defaults when crown and height are missing`() {
        val trees = GeoJsonTrees.parse(mixedCollection, defaultCrownRadiusMeters = 3.0, defaultHeightMeters = 12.0)
        val bare = trees.first { it.id == "node/3" }
        assertTrue(abs(bare.crownRadiusMeters - 3.0) < 1e-9)
        assertTrue(abs(bare.heightMeters - 12.0) < 1e-9)
        assertFalse(bare.deciduous)
    }

    @Test
    fun `building parser ignores tree point-features and vice versa`() {
        val buildings = GeoJsonBuildings.parse(mixedCollection)
        assertEquals(1, buildings.size)
        assertTrue(abs(buildings.first().heightMeters - 21.0) < 1e-9)
        assertEquals(2, GeoJsonTrees.parse(mixedCollection).size)
    }
}
