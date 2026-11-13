package com.vincenthzr.locationspoofer.xposed.utils

import com.vincenthzr.locationspoofer.utils.CampusRules
import com.vincenthzr.locationspoofer.utils.GeoMath
import com.vincenthzr.locationspoofer.utils.CoordinateUtils.LatLng
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [RouteEngine] 在校园跑兼容模式下的行为：配速锁窗口、速度与位移同源、位置单调前进。
 */
class RouteEngineCampusTest {

    /** 一条 30°N 附近、正东 2 km 的直线路线 */
    private fun straightRoute(): JSONArray {
        val a = LatLng(30.0, 120.0)
        val b = GeoMath.destination(a, 90.0, 2000.0)
        return JSONArray().apply {
            put(JSONObject().put("lat", a.lat).put("lng", a.lng))
            put(JSONObject().put("lat", b.lat).put("lng", b.lng))
        }
    }

    private fun config(
        campus: Boolean,
        speed: Double,
        startTs: Long = 1_700_000_000_000L,
        extra: (JSONObject) -> Unit = {}
    ): JSONObject = JSONObject().apply {
        put("active", true)
        put("is_route_mode", true)
        put("route_points", straightRoute())
        put("speed_m_s", speed)
        put("start_timestamp", startTs)
        put("campus_mode", campus)
        put("speed_ramp_sec", 4.0)
        put("speed_fluctuation_pct", 10)
        put("cadence_fluctuation_pct", 6)
        put("realism_level", 2)
        put("grade_sensitivity", 0)
        extra(this)
    }

    @Test
    fun `campus mode reports speeds inside the valid pace window`() {
        val cfg = config(true, 3.0)
        val t0 = 1_700_000_000_000L
        for (sec in 0..600) {
            val m = RouteEngine.calculateCurrentPosition(cfg, t0 + sec * 1000L)
            assertTrue(
                "t=$sec speed=${m.speed} 越出窗口",
                m.speed >= CampusRules.SPEED_FLOOR_MS - 0.01f && m.speed <= CampusRules.SPEED_CEIL_MS + 0.01f
            )
        }
    }

    @Test
    fun `campus mode does not report full speed while barely moving`() {
        // 起步第一秒：上报速度必须与真实位移对得上（旧实现位置几乎不动却报 3 m/s）
        val cfg = config(true, 3.0)
        val t0 = 1_700_000_000_000L
        val p0 = RouteEngine.calculateCurrentPosition(cfg, t0)
        val p1 = RouteEngine.calculateCurrentPosition(cfg, t0 + 1000L)
        val moved = GeoMath.distance(LatLng(p0.lat, p0.lng), LatLng(p1.lat, p1.lng))
        val reported = p1.speed.toDouble()
        assertEquals(reported, moved, reported * 0.15)
    }

    @Test
    fun `reported speed matches displacement for the whole run`() {
        val cfg = config(true, 3.0)
        val t0 = 1_700_000_000_000L
        var prev = RouteEngine.calculateCurrentPosition(cfg, t0)
        for (sec in 1..300) {
            val now = RouteEngine.calculateCurrentPosition(cfg, t0 + sec * 1000L)
            val moved = GeoMath.distance(LatLng(prev.lat, prev.lng), LatLng(now.lat, now.lng))
            val reported = now.speed.toDouble()
            assertTrue(
                "t=$sec 位移 $moved m 与上报速度 $reported m/s 不自洽",
                moved <= reported + 0.6 && moved >= reported * 0.7 - 0.2
            )
            prev = now
        }
    }

    @Test
    fun `campus mode advances monotonically along the route`() {
        val cfg = config(true, 3.0)
        val t0 = 1_700_000_000_000L
        var last = -1.0
        for (sec in 0..300 step 5) {
            val m = RouteEngine.calculateCurrentPosition(cfg, t0 + sec * 1000L)
            val progress = GeoMath.distance(LatLng(30.0, 120.0), LatLng(m.lat, m.lng))
            assertTrue("t=$sec 位置回退：$last -> $progress", progress >= last - 15.0)
            last = progress
        }
    }

    @Test
    fun `campus mode stops at the destination distance`() {
        val cfg = config(true, 3.0) {
            it.put("stop_at_destination", true)
        }
        val t0 = 1_700_000_000_000L
        val end = RouteEngine.calculateCurrentPosition(cfg, t0 + 3_600_000L)
        assertEquals(0f, end.speed)
        val target = GeoMath.destination(LatLng(30.0, 120.0), 90.0, 2000.0)
        assertTrue("没有停在终点: ${end.lat},${end.lng}", GeoMath.distance(LatLng(end.lat, end.lng), target) < 5.0)
    }

    @Test
    fun `plain mode keeps speeds outside the window`() {
        // 关闭兼容模式时 15 m/s 仍然按 15 m/s 上报（导航等场景不想要窗口钳制）
        val cfg = config(false, 15.0) { it.put("speed_ramp_sec", 0.0) }
        val t0 = 1_700_000_000_000L
        val m = RouteEngine.calculateCurrentPosition(cfg, t0 + 10_000L)
        assertTrue("plain mode 被误钳制: ${m.speed}", m.speed > 10f)
    }

    @Test
    fun `campus mode resumes from the paused offset as well`() {
        val route = straightRoute()
        fun cfg(offset: Double, start: Long) = JSONObject().apply {
            put("active", true)
            put("is_route_mode", true)
            put("route_points", route)
            put("speed_m_s", 3.0)
            put("start_timestamp", start)
            put("route_distance_offset", offset)
            put("campus_mode", true)
            put("speed_ramp_sec", 4.0)
        }
        // 跑了 100 秒（约 300 m）后暂停，再以同一偏移继续：继续瞬间位置必须连续
        val t0 = 1_700_000_000_000L
        val paused = RouteEngine.calculateCurrentPosition(cfg(0.0, t0), now = t0 + 100_000L)
        val dist = GeoMath.distance(LatLng(30.0, 120.0), LatLng(paused.lat, paused.lng))
        val resumed = RouteEngine.calculateCurrentPosition(cfg(dist, t0 + 500_000L), now = t0 + 500_000L)
        assertTrue(
            "继续瞬间位置跳变: ${GeoMath.distance(LatLng(paused.lat, paused.lng), LatLng(resumed.lat, resumed.lng))}",
            GeoMath.distance(LatLng(paused.lat, paused.lng), LatLng(resumed.lat, resumed.lng)) < 1.0
        )
    }

    @Test
    fun `campus mode is selected by default when the config has no flag`() {
        val cfg = config(true, 3.0).apply { remove("campus_mode") }
        assertTrue(RouteEngine.isCampus(cfg))
    }
}
