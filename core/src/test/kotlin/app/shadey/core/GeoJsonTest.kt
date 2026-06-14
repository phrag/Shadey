package app.shadey.core

import app.shadey.core.data.GeoJsonBuildings
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs

class GeoJsonTest {

    // A FeatureCollection like the one BuildingDownloader emits: one building polygon, plus a
    // stray Point feature the building parser must ignore.
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
              "properties": {"natural": "tree", "osm_id": "node/2"},
              "geometry": {"type": "Point", "coordinates": [13.402, 52.502]}
            }
          ]
        }
    """.trimIndent()

    @Test
    fun `building parser ignores point-features`() {
        val buildings = GeoJsonBuildings.parse(mixedCollection)
        assertEquals(1, buildings.size)
        assertTrue(abs(buildings.first().heightMeters - 21.0) < 1e-9)
    }
}
