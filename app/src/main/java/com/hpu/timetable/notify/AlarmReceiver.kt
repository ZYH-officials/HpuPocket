package com.hpu.timetable.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.hpu.timetable.data.CourseInstance
import com.hpu.timetable.data.TimetableRepository
import java.time.LocalTime

/** 闹钟到点：发通知，然后把后续闹钟重排一遍 */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        com.hpu.timetable.AppLog.event("alarm", "闹钟触发 kind=" + intent.getStringExtra("kind"))
        TimetableRepository.init(ctx)
        if (TimetableRepository.loadCached(ctx) == null) return
        when (intent.getStringExtra("kind")) {
            "class" -> {
                if (!com.hpu.timetable.data.Settings.classReminderEnabled(ctx)) return
                val course = CourseInstance(
                    week = intent.getIntExtra("week", 1),
                    day = 1,
                    big = intent.getIntExtra("big", 1),
                    course = intent.getStringExtra("course").orEmpty(),
                    teacher = intent.getStringExtra("teacher").orEmpty(),
                    room = intent.getStringExtra("room").orEmpty(),
                    exp = "",
                )
                val start = runCatching {
                    val parts = intent.getStringExtra("start").orEmpty().split(":")
                    LocalTime.of(parts[0].toInt(), parts[1].toInt())
                }.getOrDefault(LocalTime.of(8, 0))
                Notify.postClassReminder(
                    ctx, course, start,
                    intent.getIntExtra("lead", NotifyScheduler.LEAD_MINUTES.toInt()),
                    intent.getIntExtra("week", 1),
                )
            }
            "week" -> {
                if (!com.hpu.timetable.data.Settings.weekSummaryEnabled(ctx)) return
                SchedulePlanner.nextWeekSummary(ctx)?.let { Notify.postWeekSummary(ctx, it) }
            }
            // rearm：不发通知，只走下面的重排，把链条续到明天
        }
        // 链条续上
        runCatching { NotifyScheduler.rescheduleAll(ctx) }
    }
}
