package com.vincenthzr.locationspoofer.utils

import com.vincenthzr.locationspoofer.utils.CoordinateUtils.LatLng
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/**
 * 随机起点：只旋转出发顶点，几何（边长、周长、圈数语义）完全不变。
 */
class RouteStartTest {

    /** 100 m × 100 m 方形闭环，首尾点相同 */
    private fun squareLoop(side: Double = 100.0): List<LatLng> {
        val a = LatLng(30.0, 120.0)
        val b = GeoMath.destination(a, 90.0, side)
        val c = GeoMath.destination(b, 0.0, side)
        val d = GeoMath.destination(c, 270.0, side)
        return listOf(a, b, c, d, a)
    }

    private fun perimeter(points: List<LatLng>): Double {
        var acc = 0.0
        for (i in 1 until points.size) acc += GeoMath.distance(points[i - 1], points[i])
        return acc
    }

    @Test
    fun `reroll keeps the route geometry and only moves the start vertex`() {
        val original = squareLoop()
        val rerolled = RouteStart.reroll(original, campus = true, random = Random(7))
        assertNotNull("闭环路线应能换起点", rerolled)
        rerolled!!

        // 顶点集合不变、闭合格式不变、周长不变
        assertEquals("点数应保持一致（含闭合点）", original.size, rerolled.size)
        assertEquals("周长不能变", perimeter(original), perimeter(rerolled), 1e-6)
        assertEquals("首尾必须相同（闭环格式）", rerolled.first(), rerolled.last())

        // 每个原始顶点都还在（顺序不同而已）
        for (p in original.dropLast(1)) {
            assertTrue(
                "原始顶点 $p 丢失",
                rerolled.any { kotlin.math.abs(it.lat - p.lat) < 1e-9 && kotlin.math.abs(it.lng - p.lng) < 1e-9 }
            )
        }

        // 起点确实变了
        val moved = GeoMath.distance(original.first(), rerolled.first())
        assertTrue("起点没有真正移动: $moved m", moved > 15.0)
    }

    @Test
    fun `reroll never returns the same start when other vertices are far enough`() {
        val original = squareLoop()
        // 多跑几次，每次都应换到别的顶点
        var changes = 0
        repeat(30) { seed ->
            val r = RouteStart.reroll(original, campus = true, random = Random(seed.toLong())) ?: return@repeat
            if (GeoMath.distance(original.first(), r.first()) > 15.0) changes++
        }
        assertEquals("换起点应始终生效", 30, changes)
    }

    @Test
    fun `open route cannot reroll`() {
        val a = LatLng(30.0, 120.0)
        val b = GeoMath.destination(a, 90.0, 500.0)
        val open = listOf(a, b)
        assertFalse(RouteStart.canRandomize(open, campus = true))
        assertNull(RouteStart.reroll(open, campus = true, random = Random(1)))
    }

    @Test
    fun `small gap is only closed in campus mode`() {
        // 1000 m 长、首尾差 30 m：兼容模式下会被 RoutePath 自动补闭合，可以换起点
        val a = LatLng(30.0, 120.0)
        val far = GeoMath.destination(a, 90.0, 1000.0)
        val near = GeoMath.destination(a, 90.0, 30.0)
        val pts = listOf(a, far, near)
        assertTrue("兼容模式应识别为可闭合", RouteStart.canRandomize(pts, campus = true))
        assertFalse("普通模式不补闭合，不能换起点", RouteStart.canRandomize(pts, campus = false))
    }

    @Test
    fun `rotated route positions match a pure rotation of the original`() {
        // 旋转后从新起点出发，走完整圈回到新起点；周长一致、位置连续
        val original = squareLoop()
        val rerolled = RouteStart.reroll(original, campus = false, random = Random(3))!!
        val a = RoutePath(original, campus = false)
        val b = RoutePath(rerolled, campus = false)
        assertEquals("圈长不变", a.lapDistance, b.lapDistance, 1e-6)
        // 两者跑满一圈都回到各自的起点
        val endA = a.positionAt(a.lapDistance, stopAtDestination = false)
        val endB = b.positionAt(b.lapDistance, stopAtDestination = false)
        assertTrue(GeoMath.distance(original.first(), LatLng(endA.lat, endA.lng)) < 1.0)
        assertTrue(GeoMath.distance(rerolled.first(), LatLng(endB.lat, endB.lng)) < 1.0)
    }
}
