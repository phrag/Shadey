package app.shadey.core

import app.shadey.core.geo.LocalProjection
import app.shadey.core.geo.Polygons
import app.shadey.core.geo.Vec2
import app.shadey.core.model.LatLng
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs

class GeometryTest {

    // A 10m square sitting to the east of the origin: x in [10,20], y in [-5,5].
    private val square = listOf(Vec2(10.0, -5.0), Vec2(20.0, -5.0), Vec2(20.0, 5.0), Vec2(10.0, 5.0))

    @Test
    fun `point in polygon`() {
        assertTrue(Polygons.contains(square, Vec2(15.0, 0.0)))
        assertFalse(Polygons.contains(square, Vec2(0.0, 0.0)))
        assertFalse(Polygons.contains(square, Vec2(25.0, 0.0)))
    }

    @Test
    fun `ray hits square straight ahead`() {
        val t = Polygons.firstRayHit(square, Vec2(0.0, 0.0), Vec2(1.0, 0.0))
        assertNotNull(t)
        assertTrue(abs(t!! - 10.0) < 1e-6) { "t was $t" }
    }

    @Test
    fun `ray pointing away misses`() {
        assertNull(Polygons.firstRayHit(square, Vec2(0.0, 0.0), Vec2(-1.0, 0.0)))
        assertNull(Polygons.firstRayHit(square, Vec2(0.0, 0.0), Vec2(0.0, 1.0)))
    }

    @Test
    fun `origin inside polygon hits at zero`() {
        val t = Polygons.firstRayHit(square, Vec2(15.0, 0.0), Vec2(1.0, 0.0))
        assertEquals(0.0, t)
    }

    @Test
    fun `convex hull of square with interior point has four corners`() {
        val hull = Polygons.convexHull(square + Vec2(15.0, 0.0))
        assertEquals(4, hull.size)
    }

    @Test
    fun `projection round trips`() {
        val proj = LocalProjection(LatLng(52.52, 13.405))
        val p = LatLng(52.521, 13.407)
        val back = proj.toLatLng(proj.toLocal(p))
        assertTrue(abs(back.lat - p.lat) < 1e-7) { "lat ${back.lat}" }
        assertTrue(abs(back.lng - p.lng) < 1e-7) { "lng ${back.lng}" }
    }

    @Test
    fun `projection distances are sane`() {
        val proj = LocalProjection(LatLng(52.52, 13.405))
        // 0.001 degrees of latitude is about 111 metres everywhere.
        val north = proj.toLocal(LatLng(52.521, 13.405))
        assertTrue(abs(north.y - 111.2) < 2.0) { "north.y ${north.y}" }
        assertTrue(abs(north.x) < 1e-6)
    }

    @Test
    fun `circle footprint vertices sit at the requested radius`() {
        val center = LatLng(52.52, 13.405)
        val proj = LocalProjection(center)
        val ring = Polygons.circleFootprint(center, radiusMeters = 4.0, sides = 8)
        assertEquals(8, ring.size)
        for (v in ring) {
            val d = proj.toLocal(v).length()
            assertTrue(abs(d - 4.0) < 1e-6) { "vertex distance $d" }
        }
    }
}
