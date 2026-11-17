package com.hpu.timetable.notify

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.hpu.timetable.data.Settings
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/**
 * 定时：
 * - 上课前 15 分钟，一节课一个闹钟
 * - 每周日 09:00 推下周概览
 *
 * 每次重排都会先全撤再排（简单可靠），触发后由 AlarmReceiver 再调一次，保证链条不断。
 * 精确闹钟在 Android 12+ 需要用户授权，没授权就退化成允许延迟的 setAndAllowWhileIdle。
 */
object NotifyScheduler {

    const val LEAD_MINUTES = 15L
    private const val REQ_BASE_CLASS = 10_000
    private const val REQ_WEEK = 20_000
    private const val REQ_REARM = 30_000

    fun rescheduleAll(ctx: Context) {
        Notify.ensureChannels(ctx)
        val am = ctx.getSystemService(AlarmManager::class.java) ?: return
        com.hpu.timetable.data.TimetableRepository.init(ctx)
        cancelAll(ctx)
        if (com.hpu.timetable.data.TimetableRepository.loadCached(ctx) == null) return
        val classOn = Settings.classReminderEnabled(ctx)
        val weekOn = Settings.weekSummaryEnabled(ctx)
        if (classOn) scheduleClassReminders(ctx, am)
        if (weekOn) scheduleWeekSummary(ctx, am)
        // 每天清晨自愈一次：小米等 ROM 省电策略可能吞掉某次广播，
        // 只要这个闹钟还在，最多一天内链条就能接上
        if (classOn || weekOn) scheduleDailyRearm(ctx, am)
        com.hpu.timetable.AppLog.event("alarm", "重排提醒 class=$classOn week=$weekOn")
    }

    fun cancelAll(ctx: Context) {
        cancelClassAlarms(ctx)
        val am = ctx.getSystemService(AlarmManager::class.java) ?: return
        listOf(REQ_WEEK to "week", REQ_REARM to "rearm").forEach { (req, kind) ->
            val intent = Intent(ctx, AlarmReceiver::class.java).apply { putExtra("kind", kind) }
            PendingIntent.getBroadcast(ctx, req, intent,
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)?.let {
                am.cancel(it)
                it.cancel()
            }
        }
    }

    // ---------- 上课提醒 ----------

    private fun scheduleClassReminders(ctx: Context, am: AlarmManager) {
        val slots = SchedulePlanner.upcomingClasses(ctx)
        val now = LocalDateTime.now()
        val lead = Settings.classLeadMinutes(ctx).toLong()
        slots.forEachIndexed { idx, slot ->
            val trigger = slot.start.minusMinutes(lead)
            if (!trigger.isAfter(now)) return@forEachIndexed
            val pi = classPendingIntent(ctx, idx, slot, lead.toInt())
            setAlarm(am, trigger, pi)
        }
    }

    private fun classPendingIntent(
        ctx: Context,
        idx: Int,
        slot: SchedulePlanner.ClassSlot,
        leadMinutes: Int,
    ): PendingIntent {
        val i = Intent(ctx, AlarmReceiver::class.java).apply {
            putExtra("kind", "class")
            putExtra("course", slot.course.course)
            putExtra("room", slot.course.room)
            putExtra("teacher", slot.course.teacher)
            putExtra("big", slot.course.big)
            putExtra("week", slot.course.week)
            putExtra("lead", leadMinutes)
            putExtra("start", slot.start.hour.toString() + ":" + slot.start.minute.toString())
        }
        return PendingIntent.getBroadcast(
            ctx,
            REQ_BASE_CLASS + idx,
            i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /**
     * 撤掉所有上课提醒闹钟。
     * 必须用 AlarmManager.cancel(pi) —— 只调 PendingIntent.cancel() 不会把已排的闹钟撤掉，
     * 之前就是因此留下了一堆旧闹钟（关掉开关后还剩 11 个）。
     */
    private fun cancelClassAlarms(ctx: Context) {
        val am = ctx.getSystemService(AlarmManager::class.java) ?: return
        repeat(200) { idx ->
            val i = Intent(ctx, AlarmReceiver::class.java).apply { putExtra("kind", "class") }
            val pi = PendingIntent.getBroadcast(
                ctx, REQ_BASE_CLASS + idx, i,
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
            )
            if (pi != null) {
                am.cancel(pi)
                pi.cancel()
            }
        }
    }

    // ---------- 周概览 ----------

    private fun scheduleWeekSummary(ctx: Context, am: AlarmManager) {
        // 先撤旧的：改过提醒时间之后不能留两条
        run {
            val old = Intent(ctx, AlarmReceiver::class.java).apply { putExtra("kind", "week") }
            PendingIntent.getBroadcast(
                ctx, REQ_WEEK, old,
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
            )?.let {
                am.cancel(it)
                it.cancel()
            }
        }
        val next = nextWeekSummaryTime(
            Settings.weekSummaryDay(ctx),
            Settings.weekSummaryMinute(ctx),
        )
        val i = Intent(ctx, AlarmReceiver::class.java).apply { putExtra("kind", "week") }
        val pi = PendingIntent.getBroadcast(
            ctx, REQ_WEEK, i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        setAlarm(am, next, pi)
    }

    /**
     * 下一次周概览时刻。day 只接受 6（周六）或 7（周日），
     * minute 是当天 0 点起的分钟数。今天正好是那天且还没到点，就取今天。
     */
    fun nextWeekSummaryTime(
        day: Int,
        minute: Int,
        now: LocalDateTime = LocalDateTime.now(),
    ): LocalDateTime {
        val targetDay = if (day == 6) DayOfWeek.SATURDAY else DayOfWeek.SUNDAY
        val safeMinute = minute.coerceIn(0, 23 * 60 + 59)
        val target = LocalTime.of(safeMinute / 60, safeMinute % 60)
        var d = now.toLocalDate()
        while (d.dayOfWeek != targetDay) d = d.plusDays(1)
        var dt = LocalDateTime.of(d, target)
        if (!dt.isAfter(now)) dt = dt.plusWeeks(1)
        return dt
    }

    // ---------- 每日自愈 ----------

    /** 每天 06:30 重排一遍全部闹钟（早于第一节课 07:45 的提醒点） */
    private fun scheduleDailyRearm(ctx: Context, am: AlarmManager) {
        val now = LocalDateTime.now()
        var next = now.toLocalDate().atTime(6, 30)
        if (!next.isAfter(now)) next = next.plusDays(1)
        val i = Intent(ctx, AlarmReceiver::class.java).apply { putExtra("kind", "rearm") }
        val pi = PendingIntent.getBroadcast(
            ctx, REQ_REARM, i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        setAlarm(am, next, pi)
    }

    // ---------- 底层 ----------

    private fun setAlarm(am: AlarmManager, at: LocalDateTime, pi: PendingIntent) {
        val ms = at.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val exact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()
        runCatching {
            if (exact) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, ms, pi)
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, ms, pi)
        }
    }

    fun canScheduleExact(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return ctx.getSystemService(AlarmManager::class.java)?.canScheduleExactAlarms() ?: false
    }
}
