package com.vincenthzr.locationspoofer.utils

import com.vincenthzr.locationspoofer.utils.CoordinateUtils.LatLng
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 用真实的操场点位（约 31.52N / 104.67E，5 个田径场打卡点围成的环）跑一遍校园跑兼容模式，
 * 检查几件在实机上会直接表现为"红段"或"GPS 漂移"的事：
 * 1. 相邻采样（1 Hz）的位移不能明显超过当前上报速度；
 * 2. 位置不能出现地图尺度的跳变；
 * 3. 每圈不能完全重合；
 * 4. 跨圈边界处不能跳点。
 *
 * 这些点取自 NekoSportsWorldTool 的 `points_cache.json`（同一所学校）。
 */
class CampusRealWorldGeometryTest {

    private val gcj = listOf(
        LatLng(31.520435, 104.669172),
        LatLng(31.520811, 104.670035),
        LatLng(31.521250, 104.669780),
        LatLng(31.519920, 104.669448),
        LatLng(31.520267, 104.670164)
    )

    private val start = 1_789_398_037_217L

    private fun progress(speed: Double, stop: Double?) = RouteProgress(
        MotionRealism.Session(start, MotionRealism.Level.MEDIUM, 10, 6, 4.0, 50),
        3.0,
        4.0,
        { d ->
            val p = RoutePath(gcj, campus = true).positionAt(d, stopAtDestination = false)
            AltitudeModel.gradeAlong(10.0, 3.0, p.lat, p.lng, p.bearing.toDouble())
        },
        true,
        stop
    )

    @Test
    fun `real checkpoints produce a continuous, pace-consistent track`() {
        val path = RoutePath(gcj, campus = true)
        assertTrue("路线应有效", path.isValid)
        val lap = path.lapDistance
        val stop = lap * 3
        val p = progress(3.0, stop)

        var prev: RoutePath.Position? = null
        var prevSpeed = 0.0
        var worstStep = 0.0
        var worstJump = 0.0
        var t = 0.0
        while (t <= 1800.0) {
            val elapsed = t
            val traveled = p.distanceAt(elapsed)
            val pos = path.positionAtFollowingMotionWithLaps(traveled, 3, stopAtDestination = false)
            val reported = if (pos.arrived) 0.0 else p.speedAt(elapsed)
            prev?.let {
                val moved = GeoMath.distance(LatLng(it.lat, it.lng), LatLng(pos.lat, pos.lng))
                if (!pos.arrived) {
                    // 1 秒位移不应明显超过上报速度：折角处弦长略短于弧长，
                    // 再叠加 0.6 m 的抖动上限，留 0.9 m 余量已能覆盖正常几何误差
                    worstStep = maxOf(worstStep, moved - reported - 0.9)
                    // 地图尺度跳变：超过 30 m 就是异常
                    worstJump = maxOf(worstJump, moved)
                }
            }
            prevSpeed = reported
            prev = pos
            t += 1.0
        }
        assertTrue("相邻秒位移明显超过上报速度：超 $worstStep m", worstStep <= 0.02)
        assertTrue("出现地图尺度跳变：$worstJump m", worstJump < 30.0)
        assertTrue("速度窗口异常：$prevSpeed", prevSpeed <= CampusRules.SPEED_CEIL_MS + 1e-6)
    }

    @Test
    fun `real checkpoints keep consecutive laps apart`() {
        val path = RoutePath(gcj, campus = true)
        val lap = path.lapDistance
        var maxGap = 0.0
        var u = 0.2
        while (u <= 0.8) {
            val a = path.positionAt(lap * u, stopAtDestination = false)
            val b = path.positionAt(lap * (1 + u), stopAtDestination = false)
            maxGap = maxOf(maxGap, GeoMath.distance(LatLng(a.lat, a.lng), LatLng(b.lat, b.lng)))
            u += 0.05
        }
        assertTrue("相邻两圈中段偏移过小：$maxGap m", maxGap > 0.2)
    }

    @Test
    fun `real checkpoints have no seam jump at lap boundary`() {
        val path = RoutePath(gcj, campus = true)
        val lap = path.lapDistance
        val before = path.positionAt(lap - 0.3, stopAtDestination = false)
        val after = path.positionAt(lap + 0.3, stopAtDestination = false)
        val jump = GeoMath.distance(LatLng(before.lat, before.lng), LatLng(after.lat, after.lng))
        assertTrue("圈边界跳点：$jump m", jump < 2.0)
    }
}
