package com.hpu.timetable.data

import android.content.Context
import java.time.LocalDate

/** 可配置项，存在 SharedPreferences */
object Settings {
    private const val KEY_START = "semester_start"
    private const val KEY_THEME = "theme_mode"
    private const val PREFS = "hpu_settings"

    private const val KEY_CLASS_ON = "notify_class_on"
    private const val KEY_CLASS_LEAD = "notify_class_lead"
    private const val KEY_WEEK_ON = "notify_week_on"
    private const val KEY_WEEK_DAY = "notify_week_day"
    private const val KEY_WEEK_MIN = "notify_week_min"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun semesterStart(ctx: Context): LocalDate {
        val raw = prefs(ctx).getString(KEY_START, null) ?: DEFAULT_START
        return runCatching { LocalDate.parse(raw) }.getOrDefault(LocalDate.parse(DEFAULT_START))
    }

    fun setSemesterStart(ctx: Context, date: LocalDate) {
        prefs(ctx).edit().putString(KEY_START, date.toString()).apply()
    }

    // ---------- 外观 ----------

    /** system / light / dark */
    fun themeMode(ctx: Context): String =
        prefs(ctx).getString(KEY_THEME, "system") ?: "system"

    fun setThemeMode(ctx: Context, mode: String) {
        prefs(ctx).edit().putString(KEY_THEME, mode).apply()
    }

    // ---------- 通知设置 ----------

    /** 上课前提醒开关 */
    fun classReminderEnabled(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_CLASS_ON, true)

    fun setClassReminderEnabled(ctx: Context, on: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_CLASS_ON, on).apply()
    }

    /** 提前多少分钟（5/10/15/20/30） */
    fun classLeadMinutes(ctx: Context): Int = prefs(ctx).getInt(KEY_CLASS_LEAD, 15)

    fun setClassLeadMinutes(ctx: Context, minutes: Int) {
        prefs(ctx).edit().putInt(KEY_CLASS_LEAD, minutes).apply()
    }

    /** 每周课表概览开关 */
    fun weekSummaryEnabled(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_WEEK_ON, true)

    fun setWeekSummaryEnabled(ctx: Context, on: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_WEEK_ON, on).apply()
    }

    /** 周概览的星期：6=周六，7=周日（只允许这两天） */
    fun weekSummaryDay(ctx: Context): Int = prefs(ctx).getInt(KEY_WEEK_DAY, 7).let { if (it == 6) 6 else 7 }

    /** 周概览的触发时刻，按当天 0 点起的分钟数 */
    fun weekSummaryMinute(ctx: Context): Int = prefs(ctx).getInt(KEY_WEEK_MIN, 9 * 60)

    fun setWeekSummarySchedule(ctx: Context, day: Int, minute: Int) {
        prefs(ctx).edit()
            .putInt(KEY_WEEK_DAY, if (day == 6) 6 else 7)
            .putInt(KEY_WEEK_MIN, minute.coerceIn(0, 23 * 60 + 59))
            .apply()
    }

    // ---------- 公告 ----------

    fun noticeReadIds(ctx: Context): Set<String> {
        val p = prefs(ctx)
        val saved = p.getStringSet("notice_read_ids", null)
        if (saved != null) return saved.toSet()
        // 兼容早期单条已读记录
        val legacy = p.getString("notice_read_id", "") ?: ""
        return if (legacy.isBlank()) emptySet() else setOf(legacy)
    }

    fun markNoticeRead(ctx: Context, id: String) {
        if (id.isBlank()) return
        val ids = HashSet(noticeReadIds(ctx))
        ids += id
        prefs(ctx).edit().putStringSet("notice_read_ids", ids).apply()
    }

    // ---------- 会话检查 ----------

    /** 上次检查会话的时间戳（毫秒），用来做节流 */
    fun lastSessionCheck(ctx: Context): Long = prefs(ctx).getLong("session_check_at", 0L)

    fun setLastSessionCheck(ctx: Context, at: Long) {
        prefs(ctx).edit().putLong("session_check_at", at).apply()
    }

    /** 上次自动重登失败的时刻，0 表示上次是成功的/没试过；用来做 60 秒冷却 */
    fun lastAutoLogin(ctx: Context): Long = prefs(ctx).getLong("auto_login_at", 0L)

    fun setLastAutoLogin(ctx: Context, at: Long) {
        prefs(ctx).edit().putLong("auto_login_at", at).apply()
    }

    const val DEFAULT_START = "2026-08-31"
}
