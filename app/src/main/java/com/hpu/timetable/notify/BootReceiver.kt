package com.hpu.timetable.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** 开机/应用更新后把闹钟补回来（否则重启一次就再也不提醒了） */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            runCatching { NotifyScheduler.rescheduleAll(ctx) }
            runCatching { com.hpu.timetable.widget.ScheduleWidget.updateAll(ctx) }
        }
    }
}
