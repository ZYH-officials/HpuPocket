package com.hpu.timetable.data.lab

import com.hpu.timetable.data.CourseInstance
import org.json.JSONObject

/**
 * 实验平台「个人总课表」的响应解析。
 * 结构：result = { "1_1": { "1": [ {course_name, teacher_name, week, week_day, section} ] } }
 * 外层 key 是 section_week_day，内层是并列分组。
 * 注意：这里的 week 是校历口径（和教务差一位），不需要偏移。
 */
object LabParser {

    /**
     * 原始响应里的实验安排条数（按周展开前的条数），只用来做导入提示。
     * 按周展开后的条数会翻很多倍，拿它提示容易被当成出错。
     */
    fun countRaw(json: String): Int {
        val result = runCatching { JSONObject(json).optJSONObject("result") }.getOrNull() ?: return 0
        var n = 0
        result.keys().forEach { cell ->
            val groups = result.optJSONObject(cell) ?: return@forEach
            groups.keys().forEach { g -> n += groups.optJSONArray(g)?.length() ?: 0 }
        }
        return n
    }

    fun merge(json: String, existing: List<CourseInstance>): List<CourseInstance>? {
        val root = runCatching { JSONObject(json) }.getOrNull() ?: return null
        val result = root.optJSONObject("result") ?: return null
        if (result.length() == 0) return null

        val out = existing.toMutableList()
        val seen = HashSet<String>()
        existing.forEach { seen += key(it.course, it.week, it.day, it.big) }

        for (cellKey in result.keys()) {
            val groups = result.optJSONObject(cellKey) ?: continue
            for (g in groups.keys()) {
                val arr = groups.optJSONArray(g) ?: continue
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val course = pretty(o.optString("course_name"))
                    val teacher = o.optString("teacher_name")
                    val day = o.optInt("week_day")
                    val big = o.optInt("section")
                    expandWeeks(o.optString("week")).forEach { w ->
                        val k = key(course, w, day, big)
                        if (seen.add(k)) {
                            out += CourseInstance(
                                week = w, day = day, big = big,
                                course = course, teacher = teacher, room = "", exp = "",
                            )
                        }
                    }
                }
            }
        }
        return out
    }

    /** 和 JwClient 保持一致的课程名规范化：b-1 → B-1，末尾 b → B */
    private fun pretty(name: String): String {
        var s = name.replace(Regex("""b-(\d)"""), "B-$1")
        if (Regex("""[\u4e00-\u9fa5]b$""").containsMatchIn(s)) s = s.dropLast(1) + "B"
        return s
    }

    /** 实验课表补全：课程|周|天|大节 → 教室 + 实验项目名 */
    data class RoomFix(val course: String, val week: Int, val day: Int, val big: Int, val room: String, val exp: String)

    fun applyRooms(list: List<CourseInstance>, rooms: List<RoomFix>): List<CourseInstance> {
        if (rooms.isEmpty()) return list
        val idx = rooms.associateBy { key(it.course, it.week, it.day, it.big) }
        return list.map { c ->
            val hit = idx[key(c.course, c.week, c.day, c.big)]
            if (hit != null && c.room.isBlank()) c.copy(room = hit.room, exp = hit.exp) else c
        }
    }

    /** 同一门课、同一天、相邻大节 → 合并成一条跨节记录 */
    fun mergeAdjacent(list: List<CourseInstance>): List<CourseInstance> {
        val out = ArrayList<CourseInstance>(list.size)
        list.groupBy { Triple(it.week, it.day, it.course) }.forEach { (_, group) ->
            val sorted = group.sortedBy { it.big }
            var i = 0
            while (i < sorted.size) {
                // 同大节（小节 1、2）或相邻大节（小节 2、3）都要合并
                var maxBig = sorted[i].big
                var j = i + 1
                while (j < sorted.size) {
                    val b = sorted[j].big
                    // 同一大节内的多条必合并；
                    // 相邻大节只允许「第1、2大节」合并（中间只有 20 分钟课间），
                    // 跨午休（2-3）、跨放学（3-4）、跨晚休（4-5）都不合并
                    val sameBig = b == maxBig
                    val morningAdjacent = sorted[i].big == 1 && b == 2 && maxBig == 1
                    if (sameBig || morningAdjacent) {
                        maxBig = maxOf(maxBig, b)
                        j++
                    } else break
                }
                val group = sorted.subList(i, j)
                val span = maxBig - sorted[i].big + 1
                val from = group.mapNotNull { it.slotFrom.takeIf { s -> s > 0 } }.minOrNull() ?: 0
                val to = group.mapNotNull { it.slotTo.takeIf { s -> s > 0 } }.maxOrNull() ?: 0
                out += sorted[i].copy(span = span, slotFrom = from, slotTo = to)
                i = j
            }
        }
        return out.sortedWith(compareBy({ it.week }, { it.day }, { it.big }))
    }

    private fun key(course: String, week: Int, day: Int, big: Int) =
        course.trim() + "|" + week + "|" + day + "|" + big

    /** "5-18" / "5-15,17" / "10,12-18" / "14" → 周次集合 */
    private fun expandWeeks(spec: String): List<Int> {
        val out = ArrayList<Int>()
        spec.split(",").forEach { part ->
            val t = part.trim()
            if (t.isEmpty()) return@forEach
            if (t.contains("-")) {
                val (a, b) = t.split("-").take(2).map { it.trim().toIntOrNull() ?: return@forEach }
                if (a <= b) for (w in a..b) out += w
            } else {
                t.toIntOrNull()?.let { out += it }
            }
        }
        return out.distinct()
    }
}