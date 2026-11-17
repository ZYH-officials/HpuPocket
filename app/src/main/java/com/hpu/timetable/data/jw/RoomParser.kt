package com.hpu.timetable.data.jw

import com.hpu.timetable.data.FreeRoom

/**
 * 解析「空闲教室查询」返回的表格。
 *
 * 结构：`table.gridtable` + `<thead>` 里的 `<th>`：
 * 序号 / 名称 / 教学楼 / 校区 / 教室设备配置 / 容量。
 * 数据行在 `<tbody id="gridXXX_data">`，和成绩、考试同一个 beangle grid，
 * 但列布局不同，所以按表头名定位。
 */
object RoomParser {

    private val DOT = setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)

    fun parse(html: String): List<FreeRoom> {
        val table = Regex("<table[^>]*class=\"[^\"]*gridtable[^\"]*\"[^>]*>(.*?)</table>", DOT)
            .find(html)?.groupValues?.get(1) ?: return emptyList()

        val head = Regex("<thead[^>]*>(.*?)</thead>", DOT).find(table)?.groupValues?.get(1).orEmpty()
        val headers = cells(head)
        if (headers.isEmpty()) return emptyList()

        val iName = headers.indexOfFirst { it.contains("名称") }.let { if (it >= 0) it else 1 }
        val iBuilding = headers.indexOfFirst { it.contains("教学楼") }.let { if (it >= 0) it else 2 }
        val iCampus = headers.indexOfFirst { it.contains("校区") }.let { if (it >= 0) it else 3 }
        val iType = headers.indexOfFirst { it.contains("设备") || it.contains("类型") }
        val iCapacity = headers.indexOfFirst { it.contains("容量") }

        val body = Regex("<tbody[^>]*>(.*?)</tbody>", DOT).find(table)?.groupValues?.get(1)
            ?: table.substringAfter("</thead>", table)

        val out = ArrayList<FreeRoom>()
        for (row in Regex("<tr[^>]*>(.*?)</tr>", DOT).findAll(body)) {
            val c = cells(row.groupValues[1])
            if (c.size <= iName) continue
            val name = c.getOrElse(iName) { "" }
            if (name.isBlank() || name == "名称") continue
            out += FreeRoom(
                name = name,
                building = c.getOrElse(iBuilding) { "" },
                campus = c.getOrElse(iCampus) { "" },
                type = if (iType >= 0) c.getOrElse(iType) { "" } else "",
                capacity = if (iCapacity >= 0) c.getOrElse(iCapacity) { "" } else "",
            )
        }
        return out
    }

    private fun cells(seg: String): List<String> =
        Regex("<t[dh][^>]*>(.*?)</t[dh]>", DOT).findAll(seg)
            .map { plain(it.groupValues[1]) }
            .toList()

    private fun plain(s: String): String = s
        .replace(Regex("<[^>]+>"), " ")
        .replace("&nbsp;", " ")
        .replace("&amp;", "&")
        .replace(Regex("""\s+"""), " ")
        .trim()
}
