package com.vincenthzr.locationspoofer.utils

import com.vincenthzr.locationspoofer.utils.CoordinateUtils.LatLng
import java.util.Random

/**
 * 闭环路线的随机起点。
 *
 * 同一条环线每次从同一个点出发，地图上看起来过于规律。这里的做法是把点表**旋转**到
 * 环上的另一个顶点：起始点变了，但走过的几何完全不变（边长、周长、圈数语义都不动），
 * 因此 App 与 Xposed 两端仍然各自从同一份 `route_points` 建 `RoutePath`，
 * 不需要新增任何配置字段，也不存在"两端随机数对不上"的问题。
 *
 * 只支持闭环路线：开放路线（去程 + 折返）旋转后首尾之间会多出一条长边，
 * 路线形状会变，所以直接拒绝。
 */
object RouteStart {

    /** 首尾距离小于该值视为严格闭环（与 [RoutePath.isClosedLoop] 同一口径） */
    private const val CLOSED_EPS_M = 5.0

    /** 少于这么多点无法构成环 */
    private const val MIN_POINTS = 3

    /**
     * 新起点与旧起点的最小距离（米）。太小的话地图上看起来像"没换"，
     * 用户会以为按钮没生效。环太小时退化为"只要不是同一个顶点"。
     */
    private const val MIN_SHIFT_M = 15.0

    /**
     * 这条路线能否随机起点：严格闭环，或兼容模式下会被自动补闭合的"近闭环"。
     *
     * @param campus 校园跑兼容模式；只有开启时 [RoutePath] 才会把 ≤60 m 的缺口补上
     */
    fun canRandomize(points: List<LatLng>, campus: Boolean): Boolean {
        if (points.size < MIN_POINTS) return false
        val gap = GeoMath.distance(points.first(), points.last())
        if (gap <= CLOSED_EPS_M) return true
        if (!campus) return false
        val total = polylineLength(points)
        return total > 0.0 &&
            gap <= CampusRules.CLOSE_MAX_GAP_M &&
            gap <= total * CampusRules.CLOSE_MAX_RATIO
    }

    /**
     * 把闭环路线旋转到随机顶点，返回新的点表（首尾点相同）。
     * 不能随机（开放路线 / 点太少）时返回 null。
     *
     * 新点表与原路线**几何完全相同**：只是起点从 `points.first()` 换成了环上的另一个顶点。
     * 随机源由调用方传入，便于测试复现；App 端每次调用用新的 [Random]。
     */
    fun reroll(points: List<LatLng>, campus: Boolean, random: Random = Random()): List<LatLng>? {
        if (!canRandomize(points, campus)) return null

        // 去掉重复的闭合点，得到"唯一环"；后面统一在环上操作，最后再把起点补回末尾
        val ring = if (GeoMath.distance(points.first(), points.last()) <= CLOSED_EPS_M) {
            points.dropLast(1)
        } else {
            points
        }
        val n = ring.size
        if (n < MIN_POINTS) return null

        val oldStart = ring.first()
        // 候选下标：原起点以外、且距离足够远的顶点
        val far = (1 until n).filter { GeoMath.distance(ring[it], oldStart) >= MIN_SHIFT_M }
        val candidates = far.ifEmpty { (1 until n).toList() }
        if (candidates.isEmpty()) return null

        val k = candidates[random.nextInt(candidates.size)]
        // 旋转：ring[k..] + ring[0..k]
        val rotated = ring.subList(k, n) + ring.subList(0, k)
        // 闭环表示：末尾补回起点，与用户手画的闭环格式一致
        return rotated + rotated.first()
    }

    private fun polylineLength(points: List<LatLng>): Double {
        var acc = 0.0
        for (i in 1 until points.size) acc += GeoMath.distance(points[i - 1], points[i])
        return acc
    }
}
