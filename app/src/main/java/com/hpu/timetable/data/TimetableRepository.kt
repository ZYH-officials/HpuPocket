package com.hpu.timetable.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDate
import java.time.temporal.ChronoUnit

object TimetableRepository {

    /** 学期第 1 周周一，启动时从 Settings 载入 */
    var semesterStart: LocalDate = LocalDate.parse(Settings.DEFAULT_START)
        private set

    fun init(context: Context) {
        semesterStart = Settings.semesterStart(context)
    }

    fun currentWeek(today: LocalDate = LocalDate.now()): Int {
        val weeks = ChronoUnit.WEEKS.between(semesterStart, today).toInt()
        return (weeks + 1).coerceAtLeast(1)
    }

    fun mondayOf(week: Int): LocalDate = semesterStart.plusWeeks((week - 1).toLong())

    private fun cacheFile(context: Context) = File(context.filesDir, "courses.json")
    private fun labFile(context: Context) = File(context.filesDir, "lab_raw.json")
    private fun examFile(context: Context) = File(context.filesDir, "exams.json")

    fun saveExams(context: Context, list: List<com.hpu.timetable.data.ExamItem>) {
        runCatching {
            val arr = JSONArray()
            list.forEach { e ->
                arr.put(JSONObject().apply {
                    put("course", e.course); put("type", e.type)
                    put("date", e.date); put("time", e.timeRange)
                    put("place", e.place); put("status", e.status)
                })
            }
            examFile(context).writeText(arr.toString())
        }
    }

    fun loadExams(context: Context): List<com.hpu.timetable.data.ExamItem> {
        val f = examFile(context)
        if (!f.exists()) return emptyList()
        return runCatching {
            val arr = JSONArray(f.readText())
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                com.hpu.timetable.data.ExamItem(
                    course = o.optString("course"), type = o.optString("type"),
                    date = o.optString("date"), timeRange = o.optString("time"),
                    place = o.optString("place"), status = o.optString("status"),
                )
            }
        }.getOrDefault(emptyList())
    }

    /** 实验课表和学期的绑定关系 */
    enum class LabSemester { NONE, CURRENT, STALE }

    private fun labMetaFile(context: Context) = File(context.filesDir, "lab_meta.json")

    private fun readLabMeta(context: Context): JSONObject? {
        val f = labMetaFile(context)
        if (!f.exists()) return null
        return runCatching { JSONObject(f.readText()) }.getOrNull()
    }

    /** 上次导入实验课时记下的学期起点；老版本没有这个文件，返回 null */
    fun labSavedSemester(context: Context): LocalDate? {
        val raw = readLabMeta(context)?.optString("semesterStart").orEmpty()
        if (raw.isBlank()) return null
        return runCatching { LocalDate.parse(raw) }.getOrNull()
    }

    /**
     * 实验课表属于哪个学期、哪个账号：
     * NONE 没导入过；CURRENT 与当前学期一致；STALE 是旧学期或别的账号，不能合并。
     * 老版本没有 lab_meta.json，按 CURRENT 处理，避免升级后把已有实验课直接清掉。
     */
    fun labSemesterState(context: Context): LabSemester {
        if (loadLabRaw(context) == null) return LabSemester.NONE
        val meta = readLabMeta(context)
        val owner = meta?.optString("account").orEmpty()
        if (owner.isNotBlank() && owner != UserStore.account(context)) {
            com.hpu.timetable.AppLog.d(
                "HPU_LAB",
                "lab_raw 属于账号 " + owner + "，当前账号 " + UserStore.account(context) + "，跳过合并",
            )
            return LabSemester.STALE
        }
        val savedRaw = meta?.optString("semesterStart").orEmpty()
        val saved = savedRaw.takeIf { it.isNotBlank() }
            ?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
            ?: return LabSemester.CURRENT
        return if (saved == Settings.semesterStart(context)) LabSemester.CURRENT else LabSemester.STALE
    }

    /** 实验平台原始响应（跨登录保留） */
    fun loadLabRaw(context: Context): String? {
        val f = labFile(context)
        if (!f.exists()) return null
        val t = runCatching { f.readText() }.getOrNull()
        return if (t.isNullOrBlank()) null else t
    }

    fun saveLabRaw(context: Context, json: String) {
        runCatching { labFile(context).writeText(json) }
        // 记下这次导入属于哪个账号、哪个学期；换账号/换学期都不再合并
        runCatching {
            labMetaFile(context).writeText(
                JSONObject().apply {
                    put("account", UserStore.account(context))
                    put("semesterStart", Settings.semesterStart(context).toString())
                    put("savedAt", System.currentTimeMillis())
                }.toString()
            )
        }
    }

    /** 退出登录时把实验课归属补记到旧账号，保留同账号重登的实验课 */
    fun stampLabOwner(context: Context, account: String) {
        if (account.isBlank() || loadLabRaw(context) == null) return
        runCatching {
            val f = labMetaFile(context)
            val meta = if (f.exists()) {
                runCatching { JSONObject(f.readText()) }.getOrDefault(JSONObject())
            } else {
                JSONObject()
            }
            if (meta.optString("account").isBlank()) {
                meta.put("account", account)
                f.writeText(meta.toString())
            }
        }
    }

    /** 切换账号：旧账号的课表、考试、实验课都不再展示 */
    fun clearForAccountSwitch(context: Context) {
        cacheFile(context).delete()
        examFile(context).delete()
        labFile(context).delete()
        labMetaFile(context).delete()
    }

    /** 教务 + 实验平台合并，并补实验课地点 */
    fun mergeLab(context: Context, base: List<CourseInstance>): List<CourseInstance> {
        if (labSemesterState(context) == LabSemester.STALE) {
            com.hpu.timetable.AppLog.d(
                "HPU_LAB",
                "lab_raw 属于 " + labSavedSemester(context) + " 学期，当前学期起点 " +
                    Settings.semesterStart(context) + "，跳过合并（需重新导入）",
            )
            return base
        }
        val raw = loadLabRaw(context) ?: return base
        val merged = com.hpu.timetable.data.lab.LabParser.merge(raw, base) ?: return base
        val rooms = runCatching {
            val text = context.assets.open("lab_rooms.json").bufferedReader().use { it.readText() }
            val arr = JSONArray(text)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                com.hpu.timetable.data.lab.LabParser.RoomFix(
                    o.optString("course"), o.optInt("week"), o.optInt("day"),
                    o.optInt("big"), o.optString("room"), o.optString("exp"),
                )
            }
        }.getOrDefault(emptyList())
        // 合并后可能又出现相邻大节（教务一条 + 实验一条），再压一次
        return com.hpu.timetable.data.lab.LabParser.mergeAdjacent(
            com.hpu.timetable.data.lab.LabParser.applyRooms(merged, rooms)
        )
    }

    fun loadCached(context: Context): List<CourseInstance>? {
        val f = cacheFile(context)
        if (!f.exists()) return null
        return runCatching {
            val arr = JSONArray(f.readText())
            val list = ArrayList<CourseInstance>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                list += CourseInstance(
                    week = o.optInt("week"), day = o.optInt("day"), big = o.optInt("big"),
                    course = o.optString("course"), teacher = o.optString("teacher"),
                    room = o.optString("room"), exp = o.optString("exp"),
                    span = o.optInt("span", 1),
                    slotFrom = o.optInt("slotFrom", 0), slotTo = o.optInt("slotTo", 0),
                )
            }
            list.ifEmpty { null }
        }.getOrNull()
    }

    fun save(context: Context, list: List<CourseInstance>) {
        val arr = JSONArray()
        list.forEach { c ->
            arr.put(JSONObject().apply {
                put("week", c.week); put("day", c.day); put("big", c.big)
                put("course", c.course); put("teacher", c.teacher)
                put("room", c.room); put("exp", c.exp); put("span", c.span)
                put("slotFrom", c.slotFrom); put("slotTo", c.slotTo)
            })
        }
        cacheFile(context).writeText(arr.toString())
    }

}
