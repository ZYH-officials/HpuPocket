package com.hpu.timetable.notify

import android.content.Context
import com.hpu.timetable.data.CourseInstance
import com.hpu.timetable.data.ExamItem
import com.hpu.timetable.data.TimetableRepository
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * 从本地课表/考试算出要提醒什么，不碰通知本身。
 * 课程数据走 loadCached + mergeLab，和课表页看到的一致。
 */
object SchedulePlanner {

    /** 5 个大节的开始时间，和课表页一致 */
    val BIG_STARTS = listOf(
        LocalTime.of(8, 0), LocalTime.of(10, 0), LocalTime.of(14, 30),
        LocalTime.of(16, 30), LocalTime.of(19, 0),
    )

    private val DAY_CN = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")

    data class ClassSlot(
        val start: LocalDateTime,
        val course: CourseInstance,
    )

    /** 未来若干天里有课的时刻（已按时间排序） */
    fun upcomingClasses(ctx: Context, days: Int = 8): List<ClassSlot> {
        val base = runCatching {
            TimetableRepository.loadCached(ctx)?.let { TimetableRepository.mergeLab(ctx, it) }
        }.getOrNull().orEmpty()
        if (base.isEmpty()) return emptyList()

        val out = ArrayList<ClassSlot>()
        val today = LocalDate.now()
        for (offset in 0 until days) {
            val date = today.plusDays(offset.toLong())
            val week = TimetableRepository.currentWeek(date)
            val day = date.dayOfWeek.value
            base.filter { it.week == week && it.day == day }.forEach { c ->
                val start = BIG_STARTS.getOrNull((c.big - 1).coerceIn(0, 4)) ?: return@forEach
                out += ClassSlot(date.atTime(start), c)
            }
        }
        return out.sortedBy { it.start }
    }

    data class WeekSummary(
        val week: Int,
        /** 每天几节课，下标 0=周一 */
        val perDayCount: List<Int>,
        val total: Int,
        val exams: List<ExamItem>,
    ) {
        val dayBreakdown: String
            get() = perDayCount.mapIndexedNotNull { i, n -> if (n > 0) DAY_CN[i] + " " + n + " 节" else null }
                .joinToString("、")
    }

    /** 下一个「下周」的概览；没课也没考试就返回 null（不打扰） */
    fun nextWeekSummary(ctx: Context): WeekSummary? {
        val base = runCatching {
            TimetableRepository.loadCached(ctx)?.let { TimetableRepository.mergeLab(ctx, it) }
        }.getOrNull().orEmpty()

        val week = TimetableRepository.currentWeek() + 1
        val monday = TimetableRepository.mondayOf(week)
        val sunday = monday.plusDays(6)

        val courses = base.filter { it.week == week }
        val exams = runCatching { TimetableRepository.loadExams(ctx) }.getOrDefault(emptyList())
            .filter { e ->
                val d = runCatching { LocalDate.parse(e.date) }.getOrNull()
                d != null && !d.isBefore(monday) && !d.isAfter(sunday)
            }

        if (courses.isEmpty() && exams.isEmpty()) return null

        return WeekSummary(
            week = week,
            perDayCount = (1..7).map { d -> courses.count { it.day == d } },
            total = courses.size,
            exams = exams,
        )
    }

    fun dayName(day: Int): String = DAY_CN.getOrElse((day - 1).coerceIn(0, 6)) { "" }
}
