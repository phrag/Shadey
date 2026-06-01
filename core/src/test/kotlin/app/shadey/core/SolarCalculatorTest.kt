package app.shadey.core

import app.shadey.core.model.LatLng
import app.shadey.core.solar.SolarCalculator
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

class SolarCalculatorTest {

    private val berlin = LatLng(52.52, 13.405)

    @Test
    fun `summer solar noon is high and due south`() {
        // ~solar noon in Berlin on the summer solstice (longitude pushes it to ~11:08 UTC).
        val pos = SolarCalculator.position(berlin, Instant.parse("2024-06-21T11:08:00Z"))
        assertTrue(pos.elevationDeg in 58.0..62.0) { "elevation was ${pos.elevationDeg}" }
        assertTrue(pos.azimuthDeg in 170.0..190.0) { "azimuth was ${pos.azimuthDeg}" }
    }

    @Test
    fun `winter solar noon is low`() {
        val pos = SolarCalculator.position(berlin, Instant.parse("2024-12-21T11:08:00Z"))
        assertTrue(pos.elevationDeg in 11.0..18.0) { "elevation was ${pos.elevationDeg}" }
        assertTrue(pos.azimuthDeg in 170.0..190.0) { "azimuth was ${pos.azimuthDeg}" }
    }

    @Test
    fun `sun is below the horizon at local midnight in summer`() {
        val pos = SolarCalculator.position(berlin, Instant.parse("2024-06-21T22:00:00Z")) // 00:00 CEST
        assertTrue(pos.elevationDeg < 0.0) { "elevation was ${pos.elevationDeg}" }
    }

    @Test
    fun `morning sun is in the east`() {
        val pos = SolarCalculator.position(berlin, Instant.parse("2024-06-21T05:00:00Z")) // 07:00 CEST
        assertTrue(pos.elevationDeg > 0.0) { "elevation was ${pos.elevationDeg}" }
        assertTrue(pos.azimuthDeg in 55.0..110.0) { "azimuth was ${pos.azimuthDeg}" }
    }

    @Test
    fun `evening sun is in the west`() {
        val pos = SolarCalculator.position(berlin, Instant.parse("2024-06-21T18:00:00Z")) // 20:00 CEST
        assertTrue(pos.elevationDeg > 0.0) { "elevation was ${pos.elevationDeg}" }
        assertTrue(pos.azimuthDeg in 270.0..310.0) { "azimuth was ${pos.azimuthDeg}" }
    }

    @Test
    fun `azimuth stays within range`() {
        var t = Instant.parse("2024-03-20T00:00:00Z")
        repeat(48) {
            val pos = SolarCalculator.position(berlin, t)
            assertTrue(pos.azimuthDeg in 0.0..360.0)
            assertTrue(pos.elevationDeg in -90.0..90.0)
            t = t.plusSeconds(1800)
        }
        assertEquals(0.0, 0.0) // reached the end without an exception
    }
}
