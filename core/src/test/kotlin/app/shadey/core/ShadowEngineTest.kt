package app.shadey.core

import app.shadey.core.model.Building
import app.shadey.core.model.LatLng
import app.shadey.core.model.SolarPosition
import app.shadey.core.model.Sunlight
import app.shadey.core.shade.ShadowEngine
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import kotlin.math.cos

class ShadowEngineTest {

    private val engine = ShadowEngine()
    private val point = LatLng(52.52, 13.405)

    /** A square building [halfMeters] wide, centred [northMeters] north of [point]. */
    private fun building(northMeters: Double, height: Double, halfMeters: Double = 8.0): Building {
        val dLatCenter = northMeters / 111_320.0
        val cLat = point.lat + dLatCenter
        val dLat = halfMeters / 111_320.0
        val dLng = halfMeters / (111_320.0 * cos(Math.toRadians(point.lat)))
        val ring = listOf(
            LatLng(cLat - dLat, point.lng - dLng),
            LatLng(cLat - dLat, point.lng + dLng),
            LatLng(cLat + dLat, point.lng + dLng),
            LatLng(cLat + dLat, point.lng - dLng),
        )
        return Building("b", ring, height)
    }

    private val sunInSouth = SolarPosition(azimuthDeg = 180.0, elevationDeg = 30.0)

    @Test
    fun `tall building toward the sun casts shade`() {
        val b = building(northMeters = -15.0, height = 50.0) // building is to the south
        assertEquals(Sunlight.SHADE, engine.sunlightAt(point, sunInSouth, listOf(b)))
    }

    @Test
    fun `building away from the sun does not shade`() {
        val b = building(northMeters = +15.0, height = 50.0) // building is to the north
        assertEquals(Sunlight.SUN, engine.sunlightAt(point, sunInSouth, listOf(b)))
    }

    @Test
    fun `short building does not reach the point`() {
        val b = building(northMeters = -15.0, height = 2.0) // too short to block a 30 deg sun at 15m
        assertEquals(Sunlight.SUN, engine.sunlightAt(point, sunInSouth, listOf(b)))
    }

    @Test
    fun `night is night regardless of buildings`() {
        val belowHorizon = SolarPosition(azimuthDeg = 180.0, elevationDeg = -5.0)
        assertEquals(Sunlight.NIGHT, engine.sunlightAt(point, belowHorizon, emptyList()))
    }

    @Test
    fun `open sky is sunny`() {
        assertEquals(Sunlight.SUN, engine.sunlightAt(point, sunInSouth, emptyList()))
    }

    @Test
    fun `shadow polygon extends away from the sun`() {
        val b = building(northMeters = 0.0, height = 20.0)
        val proj = app.shadey.core.geo.LocalProjection(point)
        val shadow = engine.castShadow(b, SolarPosition(180.0, 45.0), proj)
        assertNotNull(shadow)
        val footprintMaxLat = b.footprint.maxOf { it.lat }
        // Sun in the south => shadow falls to the north => beyond the building's north edge.
        assertTrue(shadow!!.maxOf { it.lat } > footprintMaxLat) {
            "shadow max lat ${shadow.maxOf { it.lat }} vs footprint $footprintMaxLat"
        }
    }

    @Test
    fun `finds the sunrise transition with no buildings`() {
        val before = Instant.parse("2024-06-21T01:00:00Z") // night in Berlin
        val t = engine.nextTransition(point, emptyList(), before, within = Duration.ofHours(8))
        assertNotNull(t)
        assertEquals(Sunlight.SUN, t!!.to)
        assertTrue(t.at.isAfter(before))
    }
}
