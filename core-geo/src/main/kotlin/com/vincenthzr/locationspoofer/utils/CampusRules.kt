package com.vincenthzr.locationspoofer.utils

/**
 * 「校园跑兼容模式」规则表（全绿轨迹）。
 *
 * 数值取自 NekoSportsWorldTool 的全绿轨迹实现（`src/track/generator.rs` 与
 * `src/track/generate_road.rs`）：那套规则是事后提交轨迹点，颜色由服务端按协议字段 +
 * 每段配速判定；本项目是实时喂 GPS，没有协议字段，运动世界自己按「相邻定位点距离 / 时间」
 * 算配速。因此这里只迁移**与配速、抖动、几何**有关的常量，让每一段都落在有效配速窗口内、
 * 且不会被定位噪声伪造出高速段。
 *
 * 单位约定：速度 m/s，距离 m，时间 s。
 */
object CampusRules {

    /**
     * 有效配速下界 ≈ 10'00"/km（1.667 m/s），留余量取 1.90。
     * 低于它时运动世界会把该段标为「配速过慢」。
     */
    const val SPEED_FLOOR_MS = 1.90

    /**
     * 有效配速上界 ≈ 2'21"/km（7.092 m/s），留余量取 6.30。
     * 高于它时运动世界会判「过快（疑似骑车/代跑）」。
     */
    const val SPEED_CEIL_MS = 6.30

    /** 起步热身时长：速度从 [WARMUP_FACTOR] 倍平滑升到 1.0 倍（对应模拟程序的 0.85× ramp） */
    const val WARMUP_SEC = 6.0
    const val WARMUP_FACTOR = 0.85

    /** 到达终点的制动减速度（m/s²）。用于末段限速 sqrt(2·a·剩余距离) */
    const val BRAKE_ACCEL_MS2 = 1.5

    /**
     * 弯道最大向心加速度（m/s²）。
     *
     * 真人过弯时会提前收速，而不是入弯瞬间才减速。参考项目用
     * `v_corner = sqrt(a_c / κ)` 给出弯道限速，再对入弯前的每一段套
     * `v² ≤ v_corner² + 2·a·d`（可刹停约束），得到平滑的预判减速。
     * 取 2.5 m/s²：普通跑者弯道向心加速度的常见上限，陡了会显得像在急刹。
     */
    const val CORNER_ACCEL_MS2 = 2.5

    /**
     * 弯道预判前瞻距离（米）。取 10 m：按可刹停约束，从 6.3 m/s 收到 4.2 m/s 只需
     * `(6.3²−4.2²)/(2×1.5) ≈ 7.3` 米，10 米足够覆盖且不会提前太久开始减速。
     * 参考项目用 40 m 是因为它按 5 秒采样点整段匹配，我们是 1 Hz 连续积分，10 m 更贴合。
     */
    const val CORNER_LOOKAHEAD_M = 10.0

    /** 曲率采样间隔（米） */
    const val CORNER_SAMPLE_M = 2.0

    /**
     * 计算曲率时前后各取多长的弧段（米）。
     *
     * 路线是按地图点连成的折线，顶点处曲率是无穷大。用前后各这么长的航向变化率做近似，
     * 相当于把折线顶点圆角化：**曲率 ≈ 转角 / (2×窗口)**。
     *
     * 取 6 米是为了等价于参考项目 `route.rs:41` 的 `min_radius_m = 6.0`——它先把路网
     * 的直角用半径 6 m 的圆弧切角再算曲率；我们直接吃用户画的折线，没有切角步骤，
     * 就用同量级的窗口把顶点"抹"成 6 m 圆弧。窗口取小了，直角弯会被算成半径 3 m 的
     * 死弯（限速 4.2 m/s）；取大了，缓弯也会被限速。
     */
    const val CURVATURE_WINDOW_M = 6.0

    /** 兼容模式下 GPS 抖动的软幅度（米）：单轴噪声稳态标准差的目标量级 */
    const val JITTER_AMPLITUDE_M = 0.30

    /**
     * 兼容模式下 GPS 抖动合位移硬上限（米），对应模拟程序的 0.6 m（约半条车道）。
     * 普通模式仍用 [PLAIN_JITTER_MAX_M]，避免改动导航类 App 的观感。
     */
    const val JITTER_MAX_M = 0.60
    const val PLAIN_JITTER_AMPLITUDE_M = 2.0
    const val PLAIN_JITTER_MAX_M = 4.0

    /**
     * 抖动噪声的最短/最长周期。周期必须显著长于运动世界的采样间隔（1 Hz），
     * 这样相邻两次定位之间的抖动变化很小，不会凭空造出几 m/s 的瞬时速度。
     */
    const val JITTER_MIN_PERIOD_SEC = 40.0
    const val JITTER_MAX_PERIOD_SEC = 240.0

    /**
     * 抖动里的「秒级跳变」分量（σ，米）。
     *
     * 真实 GPS 不是一条丝滑的正弦：某一秒会突然偏出去几十厘米再回来。只做长周期慢漂时，
     * 相邻两秒的位移永远连续、速度曲线完美平稳，这正是离线风控模型用来识别合成轨迹的
     * 典型特征。这一项给每秒一个独立随机偏移（三点滑动平均压住高频），
     * 幅度取测量噪声量级，不参与配速判定，只让轨迹"看起来是被测出来的"。
     *
     * 取 0.06 m：三点滑动后相邻秒变化的最大值约 0.10 m（实测 3600 s 扫描），
     * 1 Hz 下折算 0.14 m/s 的伪速度，叠在 3 m/s 的真实配速上仍落在有效窗口内。
     */
    const val JITTER_MEASUREMENT_STD_M = 0.06

    /** 每圈（闭环）或每次往返（开放）的横向偏移幅度：模拟程序为 ±0.25 m 基础 + ≤0.12 m 波浪 */
    const val LAP_LATERAL_MAX_M = 0.37
    /** 每圈的切向（沿路线方向）偏移幅度 */
    const val LAP_ALONG_MAX_M = 0.15

    /**
     * 开放路线折返时的换边距离：去程与返程各偏一侧，中间相隔约 2 倍该值，
     * 地图上能看出是「并排两条线」而不是完全重合的一条。
     */
    const val TURNAROUND_SIDE_OFFSET_M = 0.60

    /**
     * 自动闭环阈值：首尾缺口不超过该值、且不超过路线全长的 [CLOSE_MAX_RATIO] 时，
     * 视为用户想跑环线，补齐闭合段。超过则保留折返（避免用一条长直线切过建筑）。
     */
    const val CLOSE_MAX_GAP_M = 60.0
    const val CLOSE_MAX_RATIO = 0.15

    /** 自动步频的合理区间（步/分）。上界对应冲刺，下界对应慢跑的生理下限。 */
    const val CADENCE_MIN_SPM = 100.0
    const val CADENCE_MAX_SPM = 210.0

    /** 慢走步频与分界速度：低于 [WALK_SPEED_MAX_MS] 时回归公式会给出不合理的低步频，单独取值 */
    const val WALK_CADENCE_SPM = 95.0
    const val WALK_SPEED_MAX_MS = 0.8

    /**
     * 校园跑兼容模式下配速起伏的下限（百分比）。
     *
     * 「一条 1 km 的跑步，配速曲线完美平稳」是离线风控模型识别合成轨迹最直接的信号之一。
     * 用户把起伏关掉、或默认值偏低时，这里把起伏抬到这个下限；只抬下限、不封顶，
     * 用户自己要更大幅度仍然生效。取 15%：真实跑者配速波动常见 ±10~20%，
     * 低于 10% 的曲线在 1 km 尺度上已经接近匀速。
     */
    const val CAMPUS_MIN_FLUCTUATION_PCT = 15

    /** 把速度钳到有效配速窗口 */
    fun clampSpeed(v: Double): Double = v.coerceIn(SPEED_FLOOR_MS, SPEED_CEIL_MS)

    /**
     * 速度（m/s）→ 自动步频（步/分）。
     *
     * 公式由**真人记录拟合**得到，不再用手工分段折线：用户 4 条真实记录（步频-配速）
     * 线性回归给出 `cadence = 34.4 + 44.5 * v`，拟合误差 ≤ 2 步/分：
     *
     * | 配速 | 实测步频 | 公式预测 |
     * |------|---------|---------|
     * | 5'31"/km (3.02 m/s) | 170 | 169 |
     * | 6'40"/km (2.50 m/s) | 144 | 146 |
     * | 7'02"/km (2.37 m/s) | 139 | 140 |
     * | 8'19"/km (2.00 m/s) | 125 | 124 |
     *
     * 旧的分段折线在 2.0 m/s 给 145 步/分，比实测 123 高 22 步，慢配速下明显偏快。
     * 慢速（≤ [WALK_SPEED_MAX_MS]）单独给 [WALK_CADENCE_SPM]：回归公式外推到 0.5 m/s
     * 只有 57 步/分，低于正常人走路的步频下限，App 会当成「几乎不动」反过来判异常。
     */
    fun cadenceForSpeed(speedMs: Double): Double =
        if (speedMs <= WALK_SPEED_MAX_MS) {
            WALK_CADENCE_SPM
        } else {
            (34.4 + 44.5 * speedMs).coerceIn(CADENCE_MIN_SPM, CADENCE_MAX_SPM)
        }

    /** 速度（m/s）→ 配速字符串，如 5'33"/km；非法值返回 "--" */
    fun paceLabel(speedMs: Double): String {
        if (speedMs <= 0.05) return "--"
        val sec = (1000.0 / speedMs).toInt()
        if (sec !in 60..3599) return "--"
        return "${sec / 60}'${(sec % 60).toString().padStart(2, '0')}\""
    }
}
