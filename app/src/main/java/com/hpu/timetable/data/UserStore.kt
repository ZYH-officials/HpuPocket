package com.hpu.timetable.data

import android.content.Context

/** 登录用户基本信息（学号 / 姓名），只存在本地 */
object UserStore {
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("hpu_user", Context.MODE_PRIVATE)

    fun save(ctx: Context, account: String, name: String) {
        prefs(ctx).edit().putString("account", account).putString("name", name).apply()
    }

    fun account(ctx: Context): String = prefs(ctx).getString("account", "") ?: ""
    fun name(ctx: Context): String = prefs(ctx).getString("name", "") ?: ""

    fun clear(ctx: Context) {
        prefs(ctx).edit().clear().apply()
    }
}
