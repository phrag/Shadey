package app.shadey.core

import app.shadey.core.model.Building
import app.shadey.core.model.LatLng
import app.shadey.core.model.Spot
import app.shadey.core.model.Sunlight
import app.shadey.core.rank.SpotRanker
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.math.cos

class SpotRankerTest {

    private val ranker = SpotRanker()
    private val noon = Instant.parse("2024-06-21T11:08:00Z") // sun high in the south over Berlin

    private fun southBuilding(of: Spot, height: Double): Building {
        val half = 8.0
        val cLat = of.lat - 15.0 / 111_320.0
        val dLat = half / 111_320.0
        val dLng = half / (111_320.0 * cos(Math.toRadians(of.lat)))
        return Building(
            "b",
            listOf(
                LatLng(cLat - dLat, of.lng - dLng),
                LatLng(cLat - dLat, of.lng + dLng),
                LatLng(cLat + dLat, of.lng + dLng),
                LatLng(cLat + dLat, of.lng - dLng),
            ),
            height,
        )
    }

    @Test
    fun `sunny spot is ranked above a shaded one`() {
        val sunny = Spot("sunny", "Open Square", 52.52, 13.405)
        val shaded = Spot("shaded", "Behind Tower", 52.50, 13.45)
        // Origin near both spots, so distance doesn't decide the order — sun state does.
        val ranked = ranker.rank(listOf(shaded, sunny), noon, sunny.latLng) { spot ->
            if (spot.id == "shaded") listOf(southBuilding(spot, 60.0)) else emptyList()
        }
        assertEquals("sunny", ranked.first().spot.id)
        assertEquals(Sunlight.SUN, ranked.first().sunlight)
        assertEquals(Sunlight.SHADE, ranked.last().sunlight)
    }

    @Test
    fun `everything in the open is sunny at noon`() {
        val spots = listOf(
            Spot("a", "A", 52.52, 13.40),
            Spot("b", "B", 52.51, 13.41),
        )
        val ranked = ranker.rank(spots, noon, spots.first().latLng) { emptyList() }
        assertEquals(2, ranked.size)
        assertEquals(listOf(Sunlight.SUN, Sunlight.SUN), ranked.map { it.sunlight })
    }

    @Test
    fun `a nearby shaded spot outranks a sunny spot far across town`() {
        // Mirrors the reported bug: looking at one neighbourhood showed a sunny spot
        // ~19 km away as the lead result, ahead of anything actually nearby.
        val origin = LatLng(52.51, 13.46) // e.g. the centre of the map view
        val nearby = Spot("nearby", "Corner Café", 52.508, 13.455)
        val faraway = Spot("faraway", "Lake Across Town", 52.40, 13.20)
        val ranked = ranker.rank(listOf(faraway, nearby), noon, origin) { spot ->
            if (spot.id == "nearby") listOf(southBuilding(spot, 60.0)) else emptyList()
        }
        assertEquals(listOf("nearby", "faraway"), ranked.map { it.spot.id })
        assertEquals(Sunlight.SHADE, ranked.first().sunlight)
        assertEquals(Sunlight.SUN, ranked.last().sunlight)
    }
}
