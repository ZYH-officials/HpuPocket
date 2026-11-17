package com.hpu.timetable.notify

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.hpu.timetable.MainActivity
import com.hpu.timetable.R
import com.hpu.timetable.data.CourseInstance
import java.time.LocalTime

/** 通知渠道、内容拼装、点击跳转 */
object Notify {

    const val EXTRA_PAGE = "open_page"
    const val EXTRA_WEEK = "open_week"

    // 渠道属性创建后不可改，所以带 _v2；ensureChannels 里会把旧渠道删掉
    private const val CH_CLASS = "class_reminder_v2"
    private const val CH_WEEK = "weekly_summary_v2"
    private const val OLD_CH_CLASS = "class_reminder"
    private const val OLD_CH_WEEK = "weekly_summary"

    private val VIBRATE = longArrayOf(0, 400, 200, 400)

    fun ensureChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        runCatching {
            nm.deleteNotificationChannel(OLD_CH_CLASS)
            nm.deleteNotificationChannel(OLD_CH_WEEK)
        }

        val sound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

        nm.createNotificationChannel(
            NotificationChannel(CH_CLASS, "上课提醒", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "临近上课前提醒"
                enableVibration(true)
                vibrationPattern = VIBRATE
                enableLights(true)
                setSound(sound, attrs)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_WEEK, "每周课表概览", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "周日早上推送下周课程与考试"
                enableVibration(true)
                vibrationPattern = VIBRATE
                enableLights(true)
                setSound(sound, attrs)
            }
        )
    }

    /** 上课提醒：标题「XX 快到了」，副行是时间和教室；点开进课表 */
    fun postClassReminder(ctx: Context, course: CourseInstance, start: LocalTime, minutes: Int, week: Int) {
        val where = listOfNotNull(
            course.room.ifBlank { null },
            course.teacher.ifBlank { null },
        ).joinToString(" · ")
        val time = String.format("%02d:%02d", start.hour, start.minute)
        val text = if (where.isBlank()) "$time 上课" else "$time · $where"

        val pi = pageIntent(ctx, "schedule", week)
        val n = NotificationCompat.Builder(ctx, CH_CLASS)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(course.course + " 快到了")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setVibrate(VIBRATE)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()
        runCatching {
            NotificationManagerCompat.from(ctx).notify(1000 + course.big + (week % 100) * 10, n)
        }
    }

    /** 周概览：点开进单独的下周概览页 */
    fun postWeekSummary(ctx: Context, s: SchedulePlanner.WeekSummary) {
        val sb = StringBuilder()
        sb.append("第 ").append(s.week).append(" 周 · ").append(s.total).append(" 节课")
        if (s.exams.isNotEmpty()) sb.append(" · 有 ").append(s.exams.size).append(" 门考试")

        val pi = pageIntent(ctx, "overview", s.week)
        val n = NotificationCompat.Builder(ctx, CH_WEEK)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("下周概览，请查收")
            .setContentText(sb.toString())
            .setStyle(NotificationCompat.BigTextStyle().bigText(sb.toString()))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setVibrate(VIBRATE)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()
        runCatching { NotificationManagerCompat.from(ctx).notify(2000, n) }
    }

    private fun pageIntent(ctx: Context, page: String, week: Int): PendingIntent {
        val i = Intent(ctx, MainActivity::class.java).apply {
            // SINGLE_TOP：App 在前台时也走 onNewIntent，而不是重新创建 Activity
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(EXTRA_PAGE, page)
            putExtra(EXTRA_WEEK, week)
        }
        return PendingIntent.getActivity(
            ctx,
            page.hashCode(),
            i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
