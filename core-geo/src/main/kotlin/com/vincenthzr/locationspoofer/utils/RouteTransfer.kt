package com.vincenthzr.locationspoofer.utils

/**
 * 单条路线的分享文件格式。
 *
 * 目标是"发给别人、别人能直接导入"，所以：
 * - 自描述：带 `format` 与 `version`，以后改字段还能认；
 * - 兼容纯坐标数组：`[[lat,lng], ...]`、`[{"lat":..,"lng":..}]` 都能读，
 *   方便用户从别的工具或聊天记录里直接贴一段坐标进来；
 * - 坐标校验：纬度 ±90、经度 ±180，越界的点直接丢弃，避免导入后路线飞到大西洋。
 *
 * 手写 JSON 而不是引依赖：core-geo 是纯 JVM 模块，不能依赖 Android 的 org.json；
 * 格式固定且很小，手写比引入序列化框架更省事，也便于做容错解析。
 */
object RouteTransfer {

    const val FORMAT = "locationspoofer.route"
    const val VERSION = 1

    data class RouteFile(val name: String, val points: List<Pair<Double, Double>>)

    /** 编码成分享文件内容（2 位小数足够，GPS 精度远高于此，文件更小） */
    fun encode(name: String, points: List<Pair<Double, Double>>, exportedAt: Long = System.currentTimeMillis()): String {
        val sb = StringBuilder()
        sb.append("{\n")
        sb.append("  \"format\": \"").append(FORMAT).append("\",\n")
        sb.append("  \"version\": ").append(VERSION).append(",\n")
        sb.append("  \"name\": \"").append(escape(name)).append("\",\n")
        sb.append("  \"exportedAt\": ").append(exportedAt).append(",\n")
        sb.append("  \"pointCount\": ").append(points.size).append(",\n")
        sb.append("  \"points\": [\n")
        points.forEachIndexed { i, p ->
            sb.append("    {\"lat\": ").append(coord(p.first))
                .append(", \"lng\": ").append(coord(p.second)).append('}')
            if (i != points.lastIndex) sb.append(',')
            sb.append('\n')
        }
        sb.append("  ]\n")
        sb.append("}\n")
        return sb.toString()
    }

    /**
     * 解析分享文件。返回 null 表示"完全认不出来"（不是路线文件）；
     * 认得出但点太少（< 2）时也返回 null，由调用方提示。
     */
    fun decode(text: String?): RouteFile? {
        if (text.isNullOrBlank()) return null
        val trimmed = text.trim()

        val name = findString(trimmed, "name")?.let(::unescape)?.takeIf { it.isNotBlank() } ?: "导入的路线"
        val points = ArrayList<Pair<Double, Double>>()

        // 1) 本格式：从 points 数组开始逐个抠出 lat/lng
        val pointsIndex = trimmed.indexOf("\"points\"")
        if (pointsIndex >= 0) {
            val body = trimmed.substring(pointsIndex)
            for (obj in objectsIn(body)) {
                val lat = findNumber(obj, "lat")
                val lng = findNumber(obj, "lng")
                if (lat != null && lng != null) addIfValid(points, lat, lng)
            }
        }

        // 2) 纯坐标数组：[[lat,lng], [lat,lng]] 或 [[lng,lat]] —— 按取值范围判断顺序
        if (points.isEmpty()) {
            val arrayStart = trimmed.indexOf('[')
            if (arrayStart >= 0) {
                for (inner in innerArrays(trimmed.substring(arrayStart))) {
                    val nums = numbersIn(inner)
                    if (nums.size >= 2) addIfValid(points, nums[0], nums[1])
                }
            }
        }

        // 3) 裸对象列表：[{"lat":..,"lng":..}, ...]
        if (points.isEmpty()) {
            for (obj in objectsIn(trimmed)) {
                val lat = findNumber(obj, "lat")
                val lng = findNumber(obj, "lng")
                if (lat != null && lng != null) addIfValid(points, lat, lng)
            }
        }

        if (points.size < 2) return null
        return RouteFile(name, points)
    }

    /** 纬度超出 ±90 时尝试与经度互换（有些工具按 lng,lat 输出），都非法则丢弃 */
    private fun addIfValid(out: MutableList<Pair<Double, Double>>, a: Double, b: Double) {
        when {
            a in -90.0..90.0 && b in -180.0..180.0 -> out += a to b
            b in -90.0..90.0 && a in -180.0..180.0 -> out += b to a
            else -> Unit
        }
    }

    // ---------------------------------------------------------------- 极简 JSON 扫描

    /** 从 [body] 中依次取出所有 `{...}` 对象（不处理嵌套，本格式里点对象没有嵌套） */
    private fun objectsIn(body: String): List<String> {
        val out = ArrayList<String>()
        var depth = 0
        var start = -1
        for (i in body.indices) {
            when (body[i]) {
                '{' -> {
                    if (depth == 0) start = i
                    depth++
                }
                '}' -> {
                    if (depth > 0) {
                        depth--
                        if (depth == 0 && start >= 0) out += body.substring(start, i + 1)
                    }
                }
            }
        }
        return out
    }

    /** 取出 `[[..],[..]]` 里的每个内层数组内容 */
    private fun innerArrays(body: String): List<String> {
        val out = ArrayList<String>()
        var depth = 0
        var start = -1
        for (i in body.indices) {
            when (body[i]) {
                '[' -> {
                    depth++
                    if (depth == 2) start = i + 1
                }
                ']' -> {
                    if (depth == 2 && start >= 0) out += body.substring(start, i)
                    if (depth > 0) depth--
                }
            }
        }
        return out
    }

    private fun numbersIn(text: String): List<Double> {
        val out = ArrayList<Double>()
        val regex = Regex("[-+]?\\d+(?:\\.\\d+)?(?:[eE][-+]?\\d+)?")
        for (m in regex.findAll(text)) {
            // 避免把数组下标之类的整数误当成坐标：只接受带小数点的或绝对值 > 0 的数
            m.value.toDoubleOrNull()?.let { out += it }
        }
        return out
    }

    private fun findNumber(obj: String, key: String): Double? {
        val idx = obj.indexOf("\"$key\"")
        if (idx < 0) return null
        val colon = obj.indexOf(':', idx)
        if (colon < 0) return null
        val regex = Regex("^\\s*([-+]?\\d+(?:\\.\\d+)?(?:[eE][-+]?\\d+)?)")
        return regex.find(obj.substring(colon + 1))?.groupValues?.get(1)?.toDoubleOrNull()
    }

    private fun findString(obj: String, key: String): String? {
        val idx = obj.indexOf("\"$key\"")
        if (idx < 0) return null
        val colon = obj.indexOf(':', idx)
        if (colon < 0) return null
        val quote = obj.indexOf('"', colon)
        if (quote < 0) return null
        // 跳过 \" 转义：路线名里带引号时不能用第一个引号当结尾
        var i = quote + 1
        while (i < obj.length) {
            if (obj[i] == '\\') {
                i += 2
                continue
            }
            if (obj[i] == '"') return obj.substring(quote + 1, i)
            i++
        }
        return null
    }

    private fun coord(v: Double): String = String.format(java.util.Locale.US, "%.6f", v)

    private fun escape(s: String): String = s
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "")

    private fun unescape(s: String): String = s
        .replace("\\\"", "\"")
        .replace("\\n", "\n")
        .replace("\\\\", "\\")
}
