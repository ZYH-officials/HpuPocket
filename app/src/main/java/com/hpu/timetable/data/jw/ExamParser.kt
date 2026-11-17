package com.hpu.timetable.data.jw

import com.hpu.timetable.data.ExamItem

/** 解析教务考试表格 */
object ExamParser {

    fun parse(html: String): List<ExamItem> {
        val out = ArrayList<ExamItem>()
        val rows = Regex("<tr[^>]*>(.*?)</tr>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
            .findAll(html)
        for (row in rows) {
            val cells = Regex("<td[^>]*>(.*?)</td>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
                .findAll(row.groupValues[1])
                .map { c ->
                    c.groupValues[1]
                        .replace(Regex("<[^>]+>"), " ")
                        .replace(Regex("""\w+\s*=\s*"[^"]*"""), " ")
                        .replace(Regex("""\s+"""), " ")
                        .trim()
                }
                .toList()
            if (cells.size < 7 || !cells[0].matches(Regex(""".*\d+\.\d+.*"""))) continue
            val course = cells[1].substringBefore("(").trim()
            if (course.isBlank() || course == "课程名称") continue
            val type = cells[2]
            val date = cells.getOrElse(3) { "" }
            val arrange = cells.getOrElse(4) { "" }
            val place = cells.getOrElse(5) { "" }
                .replace(Regex("""\w+\s*=\s*"[^"]*"""), " ")
                .replace(Regex("""\s+"""), " ")
                .trim()
            val status = cells.getOrElse(6) { "" }
            var d = date
            var t = ""
            if (arrange.matches(Regex(""".*\d{2}:\d{2}.*"""))) {
                t = arrange
            } else if (date.matches(Regex(""".*\d{2}:\d{2}.*"""))) {
                val parts = date.split(" ")
                d = parts.firstOrNull().orEmpty()
                t = parts.drop(1).joinToString(" ")
            }
            out += ExamItem(course, type, d, t, place, status)
        }
        return out
    }
}
