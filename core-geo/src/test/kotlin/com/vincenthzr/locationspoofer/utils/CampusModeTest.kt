package com.vincenthzr.locationspoofer.utils

import com.vincenthzr.locationspoofer.utils.CoordinateUtils.LatLng
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test


/**
 * 校园跑兼容模式：配速窗口、自动闭环、每圈错开、折返换边、时间确定性抖动。
 *
 * 这些规则的作用是让实时喂给运动世界的每一段「位移 ÷ 时间」都落在有效配速窗口内，
 * 且不会因为 GPS 抖动伪造出几 m/s 的假速度——那是详情页红色路段的直接来源。
 */
class CampusModeTest {

    private val start = 1_789_398_037_217L

    private fun progress(
        speed: Double,
        campus: Boolean,
        stop: Double? = null,
        pct: Int = 10,
        ramp: Double = 4.0
    ) = RouteProgress(
        MotionRealism.Session(start, MotionRealism.Level.MEDIUM, pct, 6, ramp, 50),
        speed,
        ramp,
        null,
        campus,
        stop
    )

    @Test
    fun `campus mode keeps every speed inside the valid pace window`() {
        for (base in listOf(1.4, 3.0, 5.5, 15.0)) {
            val p = progress(base, campus = true)
            var t = 0.0
            while (t <= 1200.0) {
                val v = p.speedAt(t)
                assertTrue(
                    "base=$base t=$t speed=$v 越出窗口",
                    v >= CampusRules.SPEED_FLOOR_MS - 1e-9 && v <= CampusRules.SPEED_CEIL_MS + 1e-9
                )
                t += 1.0
            }
        }
    }

    @Test
    fun `campus mode never starts from a standstill`() {
        // 旧实现起步 4 秒位置几乎不动、速度却报满值，运动世界会判该段配速异常。
        val p = progress(3.0, campus = true)
        assertTrue("起步瞬间速度过低: ${p.speedAt(0.0)}", p.speedAt(0.0) >= CampusRules.SPEED_FLOOR_MS)
        val d1 = p.distanceAt(1.0)
        assertTrue("起步 1 秒位移过小: $d1", d1 >= CampusRules.SPEED_FLOOR_MS * 0.9)
    }

    @Test
    fun `campus mode brakes smoothly and stops exactly at the target distance`() {
        val stop = 1000.0
        val p = progress(3.0, campus = true, stop = stop)
        assertEquals(stop, p.distanceAt(10_000.0), 1e-6)
        assertTrue(p.arrivedAt(10_000.0))
        // 末段速度连续下降，不会满速瞬间归零：每秒降幅不超过制动减速度（1.5 m/s²）
        var last = Double.MAX_VALUE
        var t = 0.0
        while (t <= 10_000.0 && p.distanceAt(t) < stop - 0.5) {
            val v = p.speedAt(t)
            assertTrue(
                "速度在末段出现跳变: t=$t $last -> $v",
                last == Double.MAX_VALUE || last - v <= CampusRules.BRAKE_ACCEL_MS2 + 0.2
            )
            last = v
            t += 1.0
        }
        assertTrue("末段没有减速: ${p.speedAt(10_000.0)}", p.speedAt(10_000.0) <= CampusRules.SPEED_FLOOR_MS + 1e-6)
    }

    @Test
    fun `campus mode is deterministic regardless of query order`() {
        val a = progress(3.0, campus = true, stop = 2000.0)
        val b = progress(3.0, campus = true, stop = 2000.0)
        val times = listOf(0.5, 30.0, 777.0, 5.0, 1800.0, 12.0)
        for (t in times) a.distanceAt(t)
        for (t in times.reversed()) b.distanceAt(t)
        for (t in times) assertEquals(a.distanceAt(t), b.distanceAt(t), 0.0)
    }

    @Test
    fun `plain mode keeps the original behaviour`() {
        // 关闭兼容模式时基准速度不做钳制，1.4 m/s 仍是 1.4 m/s
        val p = progress(1.4, campus = false, ramp = 0.0, pct = 0)
        assertEquals(1.4, p.speedAt(100.0), 1e-6)
    }

    private fun line(step: Double, count: Int): List<LatLng> =
        (0 until count).map { GeoMath.destination(LatLng(30.0, 120.0), 90.0, step * it) }

    @Test
    fun `campus mode closes a small gap instead of running back and forth`() {
        // 去程 1000 m 后回到距起点 30 m 的位置：视为想跑环线
        val a = LatLng(30.0, 120.0)
        val far = GeoMath.destination(a, 90.0, 1000.0)
        val near = GeoMath.destination(a, 90.0, 30.0)
        val pts = listOf(a, far, near)
        val open = RoutePath(pts, campus = false)
        val closed = RoutePath(pts, campus = true)
        assertFalse("原实现把 30 m 缺口当开放路线", open.isClosedLoop)
        assertTrue("30 m 缺口应自动闭合", closed.isClosedLoop)
        assertTrue(closed.totalDistance > open.totalDistance)

        // 越过原点位表末尾后继续朝闭合方向走，而不是原路折返（折返时航向会掉头 180°）
        val before = closed.positionAt(closed.totalDistance - 5.0, stopAtDestination = false)
        val after = closed.positionAt(closed.totalDistance + 5.0, stopAtDestination = false)
        val dBefore = GeoMath.distance(LatLng(before.lat, before.lng), LatLng(after.lat, after.lng))
        assertTrue("闭合段出现跳点: $dBefore", dBefore < 12.0)
        val back = closed.positionAt(50.0, stopAtDestination = false)
        assertTrue(
            "闭合后应继续前进而不是折返",
            GeoMath.distance(LatLng(after.lat, after.lng), LatLng(back.lat, back.lng)) < 80.0
        )
    }

    @Test
    fun `campus mode keeps out-and-back legs side by side`() {
        // 一条 500 m 直线，终点离起点 500 m：保持折返，但去程与返程不能完全重叠
        val pts = listOf(LatLng(30.0, 120.0), GeoMath.destination(LatLng(30.0, 120.0), 90.0, 500.0))
        val path = RoutePath(pts, campus = true)
        assertFalse(path.isClosedLoop)
        val out = path.positionAt(250.0, stopAtDestination = false)
        val back = path.positionAt(500.0 + 250.0, stopAtDestination = false)
        val gap = GeoMath.distance(out.asLatLng(), back.asLatLng())
        assertTrue("去程与返程几乎重合: $gap m", gap >= 0.8)
        // 折返点处仍然连续：换边幅度在端点归零
        val atTurn = path.positionAt(500.0, stopAtDestination = false)
        val justBefore = path.positionAt(499.0, stopAtDestination = false)
        assertTrue(GeoMath.distance(atTurn.asLatLng(), justBefore.asLatLng()) < 2.5)
    }

    @Test
    fun `campus mode offsets each lap so they are not identical lines`() {
        val a = LatLng(30.0, 120.0)
        val b = GeoMath.destination(a, 0.0, 100.0)
        val c = GeoMath.destination(b, 90.0, 100.0)
        val d = GeoMath.destination(c, 180.0, 100.0)
        val path = RoutePath(listOf(a, b, c, d, a), campus = true)
        assertEquals(path.totalDistance, path.lapDistance, 1.0)
        val lap = path.lapDistance
        val u = 0.25 // 圈中段，包络已接近 1
        val first = path.positionAt(lap * u, stopAtDestination = false)
        val second = path.positionAt(lap * (1.0 + u), stopAtDestination = false)
        val gap = GeoMath.distance(
            LatLng(first.lat, first.lng),
            LatLng(second.lat, second.lng)
        )
        assertTrue("两圈中段几乎重合: $gap m", gap > 0.2)
        // 圈缝连续：跨过一圈边界时位置不能跳
        val nearEnd = path.positionAt(lap - 0.5, stopAtDestination = false)
        val nearStart = path.positionAt(lap + 0.5, stopAtDestination = false)
        assertTrue("圈缝出现跳点", GeoMath.distance(LatLng(nearEnd.lat, nearEnd.lng), LatLng(nearStart.lat, nearStart.lng)) < 2.0)
    }

    @Test
    fun `campus jitter is time deterministic and bounded`() {
        val j = CampusJitter.campus(42L)
        val a = j.offsetAt(1_700_000_000_000L)
        val b = CampusJitter.campus(42L).offsetAt(1_700_000_000_000L)
        assertEquals(a[0], b[0], 0.0)
        assertEquals(a[1], b[1], 0.0)
        var t = 1_700_000_000_000L
        while (t < 1_700_003_600_000L) {
            val o = j.offsetAt(t)
            assertTrue("抖动超出上限: ${kotlin.math.sqrt(o[0] * o[0] + o[1] * o[1])}", kotlin.math.sqrt(o[0] * o[0] + o[1] * o[1]) <= CampusRules.JITTER_MAX_M + 1e-9)
            // 相邻 1 秒（运动世界的采样间隔）抖动变化远小于一次定位位移
            val next = j.offsetAt(t + 1000L)
            val step = kotlin.math.sqrt((o[0] - next[0]) * (o[0] - next[0]) + (o[1] - next[1]) * (o[1] - next[1]))
            assertTrue("相邻秒抖动跳变过大: $step", step <= 0.15)
            t += 1000L
        }
    }

    @Test
    fun `campus jitter scales with sample interval`() {
        val j = CampusJitter.campus(42L)
        val dtScale = 0.2
        var t = 1_700_000_000_000L
        while (t < 1_700_003_600_000L) {
            val o = j.offsetAt(t, dtScale)
            val len = kotlin.math.sqrt(o[0] * o[0] + o[1] * o[1])
            // 合位移上限随 dtScale 收缩；0.15 m 是缩放后的硬下限（参考 generator.rs:368 的 max(0.15)），
            // 所以容差要把它覆盖进来
            assertTrue(
                "缩放后抖动超出上限: $len",
                len <= CampusRules.JITTER_MAX_M * dtScale + 0.05
            )
            // 偏移量与截断上限都按 dtScale 线性缩放（此幅度下不会触发截断），
            // 相邻两点的抖动差也应同比缩小——这正是伪速度的来源
            val next = j.offsetAt(t + 1000L, dtScale)
            val full = j.offsetAt(t)
            val fullNext = j.offsetAt(t + 1000L)
            val stepScaled = kotlin.math.sqrt((o[0] - next[0]) * (o[0] - next[0]) + (o[1] - next[1]) * (o[1] - next[1]))
            val stepFull = kotlin.math.sqrt((full[0] - fullNext[0]) * (full[0] - fullNext[0]) + (full[1] - fullNext[1]) * (full[1] - fullNext[1]))
            assertTrue(
                "相邻秒抖动变化未随采样间隔收缩: $stepScaled vs $stepFull",
                stepScaled <= stepFull * dtScale + 1e-9
            )
            // 默认参数保持旧行为，现有调用点不受影响
            assertEquals(full[0], j.offsetAt(t, 1.0)[0], 0.0)
            assertEquals(full[1], j.offsetAt(t, 1.0)[1], 0.0)
            t += 1000L
        }
    }

    @Test
    fun `realistic jitter is not a perfect sinusoid`() {
        // 「完美平稳」本身就是可疑特征：只叠加长周期正弦时，相邻秒的位移永远连续。
        // 这里验证秒级测量噪声确实让抖动不再平滑，同时不越出合位移上限。
        val j = CampusJitter.campus(42L)
        var maxStep = 0.0
        var t = 1_700_000_000_000L
        var rough = 0
        var total = 0
        while (t < 1_700_001_800_000L) {
            val a = j.offsetAt(t)
            val b = j.offsetAt(t + 1000L)
            val step = kotlin.math.sqrt((a[0] - b[0]) * (a[0] - b[0]) + (a[1] - b[1]) * (a[1] - b[1]))
            maxStep = maxOf(maxStep, step)
            // 「不平滑」的判据：相邻秒变化超过毫米级，说明存在测量噪声而不是纯正弦
            if (step > 0.01) rough++
            total++
            t += 1000L
        }
        assertTrue("抖动完全平滑，缺少测量噪声: maxStep=$maxStep", maxStep > 0.01)
        assertTrue("不平滑的采样占比过低: $rough/$total", rough > total / 2)
        // 仍不能造成假速度：1 s 内抖动变化 < 0.25 m → 伪速度 < 0.25 m/s
        assertTrue("相邻秒抖动跳变过大: $maxStep", maxStep <= 0.25)
    }

    @Test
    fun `pace profile has non-smooth bursts rather than pure sine`() {
        // 结构性起伏：真实跑步会有突然快几秒再慢回来的一段，而不是几组正弦的叠加。
        val s = MotionRealism.Session(start, MotionRealism.Level.OFF, 30)
        val speeds = (0..1800 step 5).map { s.speed(3.0, it.toDouble()) }
        val mean = speeds.average()
        val sd = kotlin.math.sqrt(speeds.map { (it - mean) * (it - mean) }.average())
        assertTrue("速度几乎没有起伏: sd=$sd", sd > 0.05)
        // 起伏必须仍在用户设置的额度内（30%）
        assertTrue("速度越出设置额度: ${speeds.min()}..${speeds.max()}",
            speeds.min() >= 3.0 * 0.6 - 1e-9 && speeds.max() <= 3.0 * 1.4 + 1e-9)
    }

    /**
     * 端到端「像不像真跑」：400 m 操场环跑 3 圈、1 Hz 采样、叠加兼容模式抖动，
     * 检查离线模型最常看的几个统计量——速度分布、相邻秒位移、是否存在完美平稳的段。
     *
     * 这不是精度测试，而是**反例测试**：任何一项退化成"完美平稳 / 完美贴合"，
     * 都说明真实噪声被某个改动抹掉了。
     */
    @Test
    fun `simulated run looks measured rather than generated`() {
        val centre = LatLng(30.0, 120.0)
        // 半径 63.66 m 的圆 ≈ 400 m 操场
        val ring = (0 until 90).map { a -> GeoMath.destination(centre, a * 4.0, 63.66) } +
            GeoMath.destination(centre, 0.0, 63.66)
        val path = RoutePath(ring, campus = true)
        val lap = path.lapDistance
        val stop = lap * 3
        val session = MotionRealism.Session(start, MotionRealism.Level.MEDIUM, 15, 6, 4.0, 50)
        val p = RouteProgress(session, 3.0, 4.0, null, true, stop) { d -> path.cornerSpeedLimit(d) }
        val jitter = CampusJitter.campus(0x5EED1A3B7C209F11L)

        val speeds = ArrayList<Double>()
        val steps = ArrayList<Double>()
        var prev: LatLng? = null
        var t = 0
        while (p.distanceAt(t.toDouble()) < stop - 1.0) {
            val now = start + t * 1000L
            val pos = path.positionAt(p.distanceAt(t.toDouble()), stopAtDestination = false)
            val j = jitter.apply(pos.lat, pos.lng, now, 0.2)
            val cur = LatLng(j.first, j.second)
            speeds += p.speedAt(t.toDouble())
            prev?.let { steps += GeoMath.distance(it, cur) }
            prev = cur
            t++
        }

        val mean = speeds.average()
        val sd = kotlin.math.sqrt(speeds.map { (it - mean) * (it - mean) }.average())
        // ① 配速必须有真实起伏：完美平稳是合成轨迹最直接的信号
        assertTrue("配速几乎没有起伏: sd=$sd", sd > 0.10)

        // ② 速度不能是少数几个离散值（量化感）
        val distinct = speeds.map { Math.round(it * 100) }.toSet().size
        assertTrue("速度取值过于离散: $distinct 档", distinct > speeds.size / 10)

        // ③ 相邻 1 秒的位移必须落在有效配速窗口内（含抖动造成的伪速度）
        assertTrue("出现超速段: ${steps.max()}", steps.max() <= CampusRules.SPEED_CEIL_MS + 0.15)
        assertTrue("出现几乎静止的段: ${steps.min()}", steps.min() >= 0.6)

        // ④ 位移本身也要有起伏，而不是每秒严格相等
        val stepMean = steps.average()
        val stepSd = kotlin.math.sqrt(steps.map { (it - stepMean) * (it - stepMean) }.average())
        assertTrue("相邻秒位移过于均匀: sd=$stepSd", stepSd > 0.05)
    }

    @Test
    fun `pace label matches the running preset`() {
        assertEquals("5'33\"", CampusRules.paceLabel(3.0))
        // 下界 1.90 m/s ≈ 8'46"/km，仍低于运动世界 10'00" 的判红线，留了余量
        assertEquals("8'46\"", CampusRules.paceLabel(CampusRules.SPEED_FLOOR_MS))
        assertEquals("2'38\"", CampusRules.paceLabel(CampusRules.SPEED_CEIL_MS))
        assertEquals("--", CampusRules.paceLabel(0.0))
    }

    @Test
    fun `auto cadence matches real runner records`() {
        // 用户 4 条真实跑步记录：配速 → 速度 → 实测步频
        val records = listOf(
            Triple("5'31\"", 1000.0 / (5 * 60 + 31), 170.0),
            Triple("6'40\"", 1000.0 / (6 * 60 + 40), 144.0),
            Triple("7'02\"", 1000.0 / (7 * 60 + 2), 139.0),
            Triple("8'19\"", 1000.0 / (8 * 60 + 19), 125.0)
        )
        for ((pace, speed, measured) in records) {
            val calc = CampusRules.cadenceForSpeed(speed)
            assertTrue(
                "配速 $pace (${speed} m/s)：预计 $calc，实测 $measured，相差超过 3 步/分",
                kotlin.math.abs(calc - measured) <= 3.0
            )
        }
    }

    @Test
    fun `auto cadence handles slow walking and stays monotonic`() {
        // 慢走：回归公式在 0.8 m/s 以下会外推出不合理的低步频，必须保留单独的慢走取值
        assertEquals(CampusRules.WALK_CADENCE_SPM, CampusRules.cadenceForSpeed(0.0), 0.0)
        assertEquals(CampusRules.WALK_CADENCE_SPM, CampusRules.cadenceForSpeed(0.5), 0.0)
        assertEquals(CampusRules.WALK_CADENCE_SPM, CampusRules.cadenceForSpeed(CampusRules.WALK_SPEED_MAX_MS), 0.0)

        // 全速度区间单调不减，且始终落在合理区间内
        var last = Double.NEGATIVE_INFINITY
        var v = 0.0
        while (v <= 8.0) {
            val c = CampusRules.cadenceForSpeed(v)
            assertTrue("速度 $v 时步频倒退: $last -> $c", c >= last)
            assertTrue("速度 $v 时步频越界: $c", c >= CampusRules.WALK_CADENCE_SPM && c <= CampusRules.CADENCE_MAX_SPM)
            last = c
            v += 0.01
        }
    }

    /** 反查"跑到累计距离 d 的时刻"（速度 5 m/s、无起伏时约等于 d/5） */
    private fun turningTime(p: RouteProgress, d: Double): Double {
        var t = 0.0
        while (t < 200.0 && p.distanceAt(t) < d) t += 0.05
        return t
    }

    /** 一个 100 m × 100 m 的方形闭环（四个直角弯），模拟操场/校园环路 */
    private fun squareLoop(side: Double = 100.0): List<LatLng> {
        val a = LatLng(30.0, 120.0)
        val b = GeoMath.destination(a, 90.0, side)
        val c = GeoMath.destination(b, 0.0, side)
        val d = GeoMath.destination(c, 270.0, side)
        return listOf(a, b, c, d, a)
    }

    @Test
    fun `corner limits are infinite on straights and finite at right angles`() {
        val path = RoutePath(squareLoop(), campus = true)
        // 第一条边的中段：离两个顶点都超过前瞻距离，不应限速
        val straight = path.cornerSpeedLimit(50.0)
        assertTrue("直道中段不应限速: $straight", straight.isInfinite())

        // 顶点前 2 米（已进入前瞻范围）：必须限速，且不会把人刹到低于有效配速下限
        val corner = path.cornerSpeedLimit(98.0)
        assertTrue("直角弯前应限速: $corner", corner.isFinite())
        assertTrue("弯道限速 ${corner} 低于下限，会把人刹停", corner >= CampusRules.SPEED_FLOOR_MS)
        assertTrue("弯道限速 ${corner} 过高，等于没减速", corner <= CampusRules.SPEED_CEIL_MS)
        // 等价于 6 m 半径圆角：sqrt(2.5 × 6) ≈ 3.87 m/s，容许窗口平滑带来的几成偏差
        assertTrue("弯道限速 $corner 与 6 m 圆角理论值差太远", corner in 3.0..5.2)

        // 关闭兼容模式时不做弯道预判
        val plain = RoutePath(squareLoop(), campus = false)
        assertTrue(plain.cornerSpeedLimit(60.0).isInfinite())
    }

    @Test
    fun `corner braking slows the pace model before the turn`() {
        val path = RoutePath(squareLoop(), campus = true)
        val session = MotionRealism.Session(start, MotionRealism.Level.MEDIUM, 0, 0, 0.0, 0)
        // 关闭配速起伏，只看弯道限速本身
        fun progress(corner: Boolean) = RouteProgress(
            session, 5.0, 0.0, null, true, null,
            if (corner) { d -> path.cornerSpeedLimit(d) } else null
        )

        // 顶点在 100 m 处；进入前瞻范围后就开始收速（5 m/s 时大约在 93 m 附近）
        val withCorner = progress(true)
        val withoutCorner = progress(false)
        val tNearTurn = turningTime(withoutCorner, 95.0)
        val beforeTurn = withCorner.speedAt(tNearTurn)
        assertTrue(
            "入弯前未收速: $beforeTurn vs ${withoutCorner.speedAt(tNearTurn)}",
            beforeTurn < withoutCorner.speedAt(tNearTurn) - 0.01
        )

        // 直道中段（离两个弯都远）不应被限速
        assertTrue(
            "直道中段被误限速: ${withCorner.speedAt(45.0)} vs ${withoutCorner.speedAt(45.0)}",
            kotlin.math.abs(withCorner.speedAt(45.0) - withoutCorner.speedAt(45.0)) < 0.01
        )

        // 减速连续：相邻秒的速度差不超过可刹停约束允许值
        var last = Double.MAX_VALUE
        var t = 0.0
        while (t <= 60.0) {
            val v = withCorner.speedAt(t)
            assertTrue(
                "弯道前出现速度跳变: t=$t $last -> $v",
                last == Double.MAX_VALUE ||
                    last - v <= CampusRules.CORNER_ACCEL_MS2 * 1.0 + 1.0
            )
            last = v
            t += 1.0
        }
    }
}

private fun RoutePath.Position.asLatLng() = LatLng(lat, lng)
