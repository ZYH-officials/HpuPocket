package com.hpu.timetable.data.jw

import com.hpu.timetable.data.ScoreItem

/**
 * 解析教务成绩表。
 *
 * 真实响应是 beangle 框架的片段 HTML：`<table class="gridtable">` + `<thead>` 表头 +
 * `<tbody id="gridXXX_data">` 数据行。表头文案可能是「课程名称 / 学分 / 总成绩」，
 * 也可能带「课程代码、课程性质、绩点」等列，所以按表头名字定位列，不写死下标。
 * 拿不到表头时退化成「第 2 列课程名、最后一列分数」。
 */
object ScoreParser {

    private val DOT = setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)

    fun parse(html: String): List<ScoreItem> {
        val table = Regex("<table[^>]*class=\"[^\"]*gridtable[^\"]*\"[^>]*>(.*?)</table>", DOT)
            .find(html)?.groupValues?.get(1)
            ?: Regex("<table[^>]*>(.*?)</table>", DOT).find(html)?.groupValues?.get(1)
            ?: return emptyList()

        val head = Regex("<thead[^>]*>(.*?)</thead>", DOT).find(table)?.groupValues?.get(1).orEmpty()
        val headCells = cells(head).ifEmpty {
            // 没有 thead：取第一行当表头
            Regex("<tr[^>]*>(.*?)</tr>", DOT).find(table)?.groupValues?.get(1)?.let { cells(it) }.orEmpty()
        }
        if (headCells.isEmpty()) return emptyList()

        val iCourse = headCells.indexOfFirst { it.contains("课程名称") || it == "课程" }
            .let { if (it >= 0) it else 1 }
        val iCredit = headCells.indexOfFirst { it.contains("学分") }
        val iScore = headCells.indexOfFirst { it.contains("成绩") }
            .let { if (it >= 0) it else headCells.lastIndex }

        val body = Regex("<tbody[^>]*>(.*?)</tbody>", DOT).find(table)?.groupValues?.get(1)
            ?: table.substringAfter("</thead>", table)

        val out = ArrayList<ScoreItem>()
        for (row in Regex("<tr[^>]*>(.*?)</tr>", DOT).findAll(body)) {
            val c = cells(row.groupValues[1])
            if (c.size <= maxOf(iCourse, iScore)) continue
            val course = c.getOrElse(iCourse) { "" }
            val score = c.getOrElse(iScore) { "" }
            if (course.isBlank() || score.isBlank()) continue
            // 跳过误入的表头行
            if (course.contains("课程名称")) continue
            out += ScoreItem(
                course = course,
                credit = if (iCredit >= 0) c.getOrElse(iCredit) { "" } else "",
                score = score,
            )
        }
        return out
    }

    /** 取一段 HTML 里所有 td/th 的纯文本 */
    private fun cells(seg: String): List<String> =
        Regex("<t[dh][^>]*>(.*?)</t[dh]>", DOT).findAll(seg)
            .map { plain(it.groupValues[1]) }
            .toList()

    private fun plain(s: String): String = s
        .replace(Regex("<[^>]+>"), " ")
        .replace("&nbsp;", " ")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace(Regex("""\s+"""), " ")
        .trim()
}
