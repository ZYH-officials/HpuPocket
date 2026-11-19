package com.hpu.timetable.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.RemoteViews
import androidx.core.content.ContextCompat
import com.hpu.timetable.R
import com.hpu.timetable.data.CourseInstance
import com.hpu.timetable.data.TimetableRepository
import com.hpu.timetable.notify.SchedulePlanner
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/**
 * 桌面小组件：今日课程列表（读本地缓存，不发网络请求）。
 * 样式参考拾光课程表：圆角卡片、课程左侧彩色指示条、右侧剩余节数。
 * 数据变化由 App 内调用 updateAll 推送；日期/时区变化和 30 分钟周期由系统广播触发刷新。
 */
class ScheduleWidget : AppWidgetProvider() {

    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        updateAll(ctx)
    }

    override fun onAppWidgetOptionsChanged(
        ctx: Context,
        mgr: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle,
    ) {
        updateAll(ctx)
    }

    override fun onReceive(ctx: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_DATE_CHANGED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            -> updateAll(ctx)
        }
        super.onReceive(ctx, intent)
    }

    companion object {
        private val TIME_FMT = DateTimeFormatter.ofPattern("HH:mm")

        /** 5 个大节的结束时间，和课表页一致 */
        private val BIG_ENDS = listOf(
            LocalTime.of(9, 40), LocalTime.of(11, 40), LocalTime.of(16, 10),
            LocalTime.of(18, 10), LocalTime.of(20, 40),
        )

        // 预置圆角 drawable 切图，比 setImageTintList 在桌面上可靠（tint 会被部分桌面忽略）
        private val COURSE_DOTS = intArrayOf(
            R.drawable.widget_dot_1, R.drawable.widget_dot_2,
            R.drawable.widget_dot_3, R.drawable.widget_dot_4,
            R.drawable.widget_dot_5, R.drawable.widget_dot_6,
            R.drawable.widget_dot_7, R.drawable.widget_dot_8,
        )

        fun updateAll(ctx: Context) {
            runCatching {
                val mgr = AppWidgetManager.getInstance(ctx)
                val ids = mgr.getAppWidgetIds(ComponentName(ctx, ScheduleWidget::class.java))
                if (ids.isEmpty()) return
                ids.forEach { id ->
                    val widthDp = mgr.getAppWidgetOptions(id)
                        .getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 250)
                    // 2x2 这类窄尺寸下放不下右侧剩余节数，直接隐藏
                    mgr.updateAppWidget(id, build(ctx, compact = widthDp < 200))
                }
            }
        }

        private fun dotFor(name: String): Int =
            COURSE_DOTS[Math.floorMod(name.hashCode(), COURSE_DOTS.size)]

        private fun endOf(c: CourseInstance): LocalTime {
            val lastBig = (c.big - 1 + (c.span - 1).coerceAtLeast(0)).coerceIn(0, 4)
            return BIG_ENDS[lastBig]
        }

        /** 没有课时给一句下一节课的提示；没有就返回 null，不编内容 */
        private fun nextClassHint(ctx: Context): String? {
            val now = LocalDateTime.now()
            val slot = runCatching {
                SchedulePlanner.upcomingClasses(ctx).firstOrNull { it.start.isAfter(now) }
            }.getOrNull() ?: return null
            val date = slot.start.toLocalDate()
            val label = when (ChronoUnit.DAYS.between(LocalDate.now(), date)) {
                0L -> "今天"
                1L -> "明天"
                else -> SchedulePlanner.dayName(date.dayOfWeek.value)
            }
            val room = slot.course.room.trim()
            return label + " " + slot.start.toLocalTime().format(TIME_FMT) + " " +
                slot.course.course + if (room.isNotEmpty()) " · " + room else ""
        }

        private fun build(ctx: Context, compact: Boolean): RemoteViews {
            TimetableRepository.init(ctx)
            val views = RemoteViews(ctx.packageName, R.layout.widget_schedule)

            val today = LocalDate.now()
            val week = TimetableRepository.currentWeek(today)
            val day = today.dayOfWeek.value
            val now = LocalTime.now()
            val base = runCatching {
                TimetableRepository.loadCached(ctx)?.let { TimetableRepository.mergeLab(ctx, it) }
            }.getOrNull()
            val courses = base
                ?.filter { it.week == week && it.day == day }
                ?.sortedBy { it.big }
                .orEmpty()

            // 列表滚动到底部能看到明天的课
            val tomorrow = today.plusDays(1)
            val tomorrowCourses = base
                ?.filter {
                    it.week == TimetableRepository.currentWeek(tomorrow) &&
                        it.day == tomorrow.dayOfWeek.value
                }
                ?.sortedBy { it.big }
                .orEmpty()

            views.setTextViewText(
                R.id.widget_header,
                "第 $week 周 · " + SchedulePlanner.dayName(day),
            )

            val remaining = courses.count { endOf(it).isAfter(now) }
            if (courses.isEmpty() || compact) {
                views.setViewVisibility(R.id.widget_header_right, View.GONE)
            } else {
                views.setViewVisibility(R.id.widget_header_right, View.VISIBLE)
                views.setTextViewText(
                    R.id.widget_header_right,
                    if (remaining == 0) "今日已结束" else "今日还剩 $remaining 节",
                )
            }

            // 集合小组件：ListView + RemoteCollectionItems（API 31+，数据量小直接内联）
            val items = RemoteViews.RemoteCollectionItems.Builder()
                .setHasStableIds(true)
                .setViewTypeCount(2)
            var itemId = 0L
            courses.forEach { c ->
                items.addItem(itemId++, buildRow(ctx, c, now, isToday = true))
            }
            if (tomorrowCourses.isNotEmpty()) {
                val section = RemoteViews(ctx.packageName, R.layout.widget_section)
                section.setTextViewText(
                    R.id.widget_section_text,
                    "明天 · " + SchedulePlanner.dayName(tomorrow.dayOfWeek.value) +
                        " · 共 " + tomorrowCourses.size + " 节",
                )
                section.setOnClickFillInIntent(R.id.widget_section_text, Intent())
                items.addItem(itemId++, section)
                tomorrowCourses.forEach { c ->
                    items.addItem(itemId++, buildRow(ctx, c, now, isToday = false))
                }
            }
            views.setRemoteAdapter(R.id.widget_list, items.build())

            if (courses.isEmpty()) {
                views.setViewVisibility(R.id.widget_list_area, View.GONE)
                views.setViewVisibility(R.id.widget_status, View.VISIBLE)
                views.setTextViewText(
                    R.id.widget_status_title,
                    if (base == null) "打开 App 同步课表" else "今天没课",
                )
                val msg = if (base == null) "登录后这里显示今天的课程" else nextClassHint(ctx)
                if (msg.isNullOrBlank()) {
                    views.setViewVisibility(R.id.widget_status_msg, View.GONE)
                } else {
                    views.setViewVisibility(R.id.widget_status_msg, View.VISIBLE)
                    views.setTextViewText(R.id.widget_status_msg, msg)
                }
            } else {
                views.setViewVisibility(R.id.widget_status, View.GONE)
                views.setViewVisibility(R.id.widget_list_area, View.VISIBLE)
            }

            val launch = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)
            if (launch != null) {
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                views.setOnClickPendingIntent(
                    R.id.widget_root,
                    PendingIntent.getActivity(
                        ctx, 0, launch,
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                    ),
                )
                // 列表项点击复用空模板；模板必须可变才能接收条目的 fill-in intent
                views.setPendingIntentTemplate(
                    R.id.widget_list,
                    PendingIntent.getActivity(
                        ctx, 1, launch,
                        PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                    ),
                )
            }
            return views
        }

        private fun buildRow(
            ctx: Context,
            c: CourseInstance,
            now: LocalTime,
            isToday: Boolean,
        ): RemoteViews {
            val row = RemoteViews(ctx.packageName, R.layout.widget_row)
            val start = SchedulePlanner.BIG_STARTS.getOrNull((c.big - 1).coerceIn(0, 4))
                ?: LocalTime.of(8, 0)
            val end = endOf(c)

            row.setTextViewText(R.id.widget_row_start, start.format(TIME_FMT))
            row.setTextViewText(R.id.widget_row_end, end.format(TIME_FMT))
            row.setTextViewText(R.id.widget_row_name, c.course)

            val detail = listOf(c.room.trim(), c.teacher.trim())
                .filter { it.isNotEmpty() }
                .joinToString(" · ")
            if (detail.isBlank()) {
                row.setViewVisibility(R.id.widget_row_detail, View.GONE)
            } else {
                row.setTextViewText(R.id.widget_row_detail, detail)
                row.setViewVisibility(R.id.widget_row_detail, View.VISIBLE)
            }

            row.setImageViewResource(R.id.widget_row_indicator, dotFor(c.course))

            // 今天的课：进行中用主题色突出，已下课的置灰；明天的课用正常色
            val nameColor = when {
                !isToday -> R.color.widget_text_primary
                !end.isAfter(now) -> R.color.widget_text_hint
                !now.isBefore(start) -> R.color.widget_accent
                else -> R.color.widget_text_primary
            }
            row.setTextColor(R.id.widget_row_name, ContextCompat.getColor(ctx, nameColor))
            row.setOnClickFillInIntent(R.id.widget_row_root, Intent())
            return row
        }
    }
}