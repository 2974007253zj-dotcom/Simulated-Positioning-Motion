package com.vincenthzr.locationspoofer.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteTransferTest {

    private val points = listOf(
        31.461234 to 104.741234,
        31.462345 to 104.742345,
        31.463456 to 104.743456
    )

    @Test
    fun `encode then decode round-trips name and points`() {
        val text = RouteTransfer.encode("操场 5 圈", points, exportedAt = 1_700_000_000_000L)
        val decoded = RouteTransfer.decode(text)!!
        assertEquals("操场 5 圈", decoded.name)
        assertEquals(points.size, decoded.points.size)
        points.zip(decoded.points).forEach { (a, b) ->
            assertEquals(a.first, b.first, 1e-6)
            assertEquals(a.second, b.second, 1e-6)
        }
    }

    @Test
    fun `encoded file carries format and version for future compatibility`() {
        val text = RouteTransfer.encode("r", points)
        assertTrue(text.contains("\"format\": \"${RouteTransfer.FORMAT}\""))
        assertTrue(text.contains("\"version\": ${RouteTransfer.VERSION}"))
    }

    @Test
    fun `plain coordinate array is accepted`() {
        val text = "[[31.461234,104.741234],[31.462345,104.742345]]"
        val decoded = RouteTransfer.decode(text)!!
        assertEquals(2, decoded.points.size)
        assertEquals(31.461234, decoded.points[0].first, 1e-6)
        assertEquals(104.741234, decoded.points[0].second, 1e-6)
    }

    @Test
    fun `lng lat order is detected and swapped`() {
        // 经度 104 超出纬度范围，自动按 lng,lat 处理
        val text = "[[104.741234,31.461234],[104.742345,31.462345]]"
        val decoded = RouteTransfer.decode(text)!!
        assertEquals(31.461234, decoded.points[0].first, 1e-6)
        assertEquals(104.741234, decoded.points[0].second, 1e-6)
    }

    @Test
    fun `bare object list without wrapper is accepted`() {
        val text = """[{"lat":31.5,"lng":104.7},{"lat":31.6,"lng":104.8}]"""
        val decoded = RouteTransfer.decode(text)!!
        assertEquals(2, decoded.points.size)
    }

    @Test
    fun `out of range points are dropped`() {
        val text = """{"format":"locationspoofer.route","version":1,"points":[
            {"lat":31.5,"lng":104.7},{"lat":999,"lng":104.8},{"lat":31.6,"lng":104.8}]}"""
        val decoded = RouteTransfer.decode(text)!!
        assertEquals(2, decoded.points.size)
    }

    @Test
    fun `fewer than two points is not a route`() {
        assertNull(RouteTransfer.decode("""{"points":[{"lat":31.5,"lng":104.7}]}"""))
        assertNull(RouteTransfer.decode(""))
        assertNull(RouteTransfer.decode("not a route at all"))
    }

    @Test
    fun `quotes and newlines in the name survive the round trip`() {
        val name = "路线 \"A\"\n第二行"
        val decoded = RouteTransfer.decode(RouteTransfer.encode(name, points))!!
        assertEquals(name, decoded.name)
    }
}
