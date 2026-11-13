package com.vincenthzr.locationspoofer.utils

import kotlin.math.cos

/**
 * 时间确定性 GPS 抖动。
 *
 * 旧实现是「每调用一次就推进一步」的 OU 随机游走：抖动幅度取决于**被读取的频率**
 * 而不是墙上时间。目标 App 每秒采样时，漂移可以在两点之间累计到 ±4 m，折算成瞬时速度
 * 就是几 m/s 的假尖峰——正是运动世界零星红段的来源之一。
 *
 * 这里改成以**绝对时间**为自变量、种子固定的平滑正弦叠加（[MotionRealism.SmoothNoise]）：
 * - 同一时刻在任何进程、任何调用顺序下都得到完全相同的偏移；
 * - 周期远长于 1 Hz 采样间隔，相邻两次定位的抖动变化只有厘米级；
 * - 合位移截断到 [CampusRules.JITTER_MAX_M]，不会让轨迹漂出道路。
 *
 * 偏移量按米计算，由调用方换算成经纬度增量，保证南北、东西两个方向幅度一致。
 */
class CampusJitter(
    seed: Long,
    private val amplitudeM: Double = CampusRules.JITTER_AMPLITUDE_M,
    private val maxM: Double = CampusRules.JITTER_MAX_M
) {

    // 两轴用不同盐值，避免 x/y 同步波动成一条斜线
    private val noiseX = MotionRealism.SmoothNoise(seed xor 0x51ED_2703L, CampusRules.JITTER_MIN_PERIOD_SEC, CampusRules.JITTER_MAX_PERIOD_SEC)
    private val noiseY = MotionRealism.SmoothNoise(seed xor 0x7A31_9C45L, CampusRules.JITTER_MIN_PERIOD_SEC, CampusRules.JITTER_MAX_PERIOD_SEC)

    // 秒级跳变分量：真实 GPS 的测量噪声。只有慢漂时相邻两秒的位移永远丝滑、
    // 速度曲线完美平稳，这是离线模型识别合成轨迹的典型特征。
    private val measX = MotionRealism.JitterNoise(seed xor 0x3B1F_9A62L, CampusRules.JITTER_MEASUREMENT_STD_M)
    private val measY = MotionRealism.JitterNoise(seed xor 0x6C42_D7B1L, CampusRules.JITTER_MEASUREMENT_STD_M)

    /**
     * 相对 [LatLng] 的米制偏移 (east, north)。
     *
     * [dtScale] 按目标 App 的**采样间隔**缩放抖动：参考实现（模拟程序 generator.rs:361-368）
     * 的结论是相邻两点各自偏 0.6 m、间隔 1 s 就会凭空造出 1.2 m/s 的伪速度。抖动是「位置误差」，
     * 相邻两点的误差差值 ÷ 间隔就是伪速度，所以它必须随 dt 一起缩小。默认 1.0 保持旧行为。
     */
    fun offsetAt(epochMs: Long, dtScale: Double = 1.0): DoubleArray {
        val t = epochMs / 1000.0
        // 慢漂 + 秒级测量噪声；两分量同样按 dtScale 收缩
        var x = (amplitudeM * noiseX.value(t) + measX.value(epochMs)) * dtScale
        var y = (amplitudeM * noiseY.value(t) + measY.value(epochMs)) * dtScale
        // 上限同样缩放，但保留下限 0.15 m：缩到 0 会让轨迹「完美贴合路线」，
        // 反而成了比真实 GPS 更可疑的特征（参考 generator.rs:368 的 max(0.15)）。
        val limit = (maxM * dtScale).coerceAtLeast(MIN_MAX_M)
        val len = kotlin.math.sqrt(x * x + y * y)
        if (len > limit && len > 0.0) {
            val k = limit / len
            x *= k
            y *= k
        }
        return doubleArrayOf(x, y)
    }

    /** 给经纬度加上时间确定性抖动；[lat] 只用于把米换算成度 */
    fun apply(lat: Double, lng: Double, epochMs: Long, dtScale: Double = 1.0): Pair<Double, Double> {
        val (east, north) = offsetAt(epochMs, dtScale).let { it[0] to it[1] }
        val dLat = north / METERS_PER_DEG_LAT
        val dLng = east / (METERS_PER_DEG_LAT * cos(Math.toRadians(lat)).coerceAtLeast(0.05))
        return Pair(lat + dLat, lng + dLng)
    }

    companion object {
        const val METERS_PER_DEG_LAT = 111_132.0

        /**
         * 缩放后合位移截断的下限（米）。参考 generator.rs:368 的 `max(0.15)`：
         * 抖动收缩过度时轨迹会完美贴在线上，同样不像真实 GPS。
         */
        const val MIN_MAX_M = 0.15

        /** 普通模式：幅度 2 m、截断 4 m，保持旧观感但去掉「读取频率放大」的缺陷 */
        fun plain(seed: Long): CampusJitter =
            CampusJitter(seed, CampusRules.PLAIN_JITTER_AMPLITUDE_M, CampusRules.PLAIN_JITTER_MAX_M)

        fun campus(seed: Long): CampusJitter = CampusJitter(seed)
    }
}
