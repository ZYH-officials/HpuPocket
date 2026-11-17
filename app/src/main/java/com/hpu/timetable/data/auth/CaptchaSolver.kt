package com.hpu.timetable.data.auth

import android.graphics.Bitmap

/**
 * 算术验证码识别：`a+b=?` 的白底彩字图片。
 *
 * 步骤：自适应二值化 → 按列投影切 5 段（数字、运算符、数字、等号、问号）
 *       → 归一化到 14x20 → 与内嵌模板最近邻匹配。
 *
 * 只认加法（48 个样本全是 +）。识别不确定就返回 null，由调用方退回手动输入，
 * 绝不瞎猜——猜错会让登录失败并可能触发风控。
 */
object CaptchaSolver {

    /** 匹配距离超过这个值就当没认出来（14x20=280 像素，允许 70 个差异） */
    private const val MAX_DISTANCE = 70

    private val templates: Map<String, BooleanArray> by lazy {
        CaptchaTemplates.glyphs.mapValues { (_, s) ->
            val cells = s.split("/")
            val arr = BooleanArray(CaptchaTemplates.W * CaptchaTemplates.H)
            for (y in 0 until CaptchaTemplates.H) {
                val row = cells.getOrElse(y) { "" }
                for (x in 0 until CaptchaTemplates.W) {
                    arr[y * CaptchaTemplates.W + x] = row.getOrElse(x) { '0' } == '1'
                }
            }
            arr
        }
    }

    /** 识别成功返回结果字符串，失败返回 null */
    fun solve(bitmap: Bitmap): String? {
        return runCatching {
            val w = bitmap.width
            val h = bitmap.height
            if (w < 20 || h < 10) return null

            val lum = IntArray(w * h)
            var lo = 255
            var hi = 0
            for (y in 0 until h) {
                for (x in 0 until w) {
                    val p = bitmap.getPixel(x, y)
                    val l = ((p shr 16 and 0xFF) * 299 + (p shr 8 and 0xFF) * 587 + (p and 0xFF) * 114) / 1000
                    lum[y * w + x] = l
                    if (l < lo) lo = l
                    if (l > hi) hi = l
                }
            }
            if (hi - lo < 30) return null // 几乎纯色，没有字
            val thr = minOf(lo + ((hi - lo) * 0.45).toInt(), 215)
            val dark = BooleanArray(w * h) { lum[it] < thr }

            val runs = ArrayList<IntArray>() // [x0, x1, y0, y1]
            var start = -1
            for (x in 0 until w) {
                var any = false
                for (y in 0 until h) if (dark[y * w + x]) { any = true; break }
                if (any && start < 0) start = x
                if (!any && start >= 0) {
                    addCol(runs, dark, w, h, start, x - 1)
                    start = -1
                }
            }
            if (start >= 0) addCol(runs, dark, w, h, start, w - 1)

            val segs = runs.filter { it[1] - it[0] + 1 >= 3 && it[3] - it[2] + 1 >= 5 }
                .sortedBy { it[0] }
                .toMutableList()
            // 噪声可能把某个字符切成两段：合并最窄的相邻段，直到剩 5 段
            while (segs.size > 5) {
                var bestI = 0
                var bestGap = Int.MAX_VALUE
                for (i in 0 until segs.size - 1) {
                    val gap = segs[i + 1][0] - segs[i][1]
                    if (gap < bestGap) { bestGap = gap; bestI = i }
                }
                val a = segs[bestI]
                val b = segs[bestI + 1]
                segs[bestI] = intArrayOf(a[0], b[1], minOf(a[2], b[2]), maxOf(a[3], b[3]))
                segs.removeAt(bestI + 1)
            }
            if (segs.size != 5) return null

            val glyphs = segs.map { box -> normalize(dark, w, box) }
            val labels = glyphs.map { g ->
                match(g) ?: return null
            }

            val a = labels[0].toIntOrNull() ?: return null
            val op = labels[1]
            val b = labels[2].toIntOrNull() ?: return null
            when (op) {
                "+" -> (a + b).toString()
                "-" -> (a - b).toString()
                else -> null
            }
        }.getOrNull()
    }

    private fun addCol(runs: ArrayList<IntArray>, dark: BooleanArray, w: Int, h: Int, x0: Int, x1: Int) {
        var top = h
        var bot = -1
        for (x in x0..x1) {
            for (y in 0 until h) {
                if (dark[y * w + x]) {
                    if (y < top) top = y
                    if (y > bot) bot = y
                }
            }
        }
        if (bot >= top) runs.add(intArrayOf(x0, x1, top, bot))
    }

    private fun normalize(dark: BooleanArray, w: Int, box: IntArray): BooleanArray {
        val (x0, x1, y0, y1) = box
        val bw = x1 - x0 + 1
        val bh = y1 - y0 + 1
        val out = BooleanArray(CaptchaTemplates.W * CaptchaTemplates.H)
        for (gy in 0 until CaptchaTemplates.H) {
            val sy = y0 + (gy * bh / CaptchaTemplates.H).coerceAtMost(bh - 1)
            for (gx in 0 until CaptchaTemplates.W) {
                val sx = x0 + (gx * bw / CaptchaTemplates.W).coerceAtMost(bw - 1)
                out[gy * CaptchaTemplates.W + gx] = dark[sy * w + sx]
            }
        }
        return out
    }

    private fun match(g: BooleanArray): String? {
        var best: String? = null
        var bestD = Int.MAX_VALUE
        for ((label, t) in templates) {
            var d = 0
            for (i in g.indices) if (g[i] != t[i]) d++
            if (d < bestD) { bestD = d; best = label }
        }
        return if (bestD <= MAX_DISTANCE) best else null
    }
}
