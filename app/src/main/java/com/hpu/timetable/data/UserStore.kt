package com.hpu.timetable.data

import android.content.Context

/** 登录用户基本信息（学号 / 姓名）与本机登录状态，只存在本地 */
object UserStore {
    private const val KEY_ACCOUNT = "account"
    private const val KEY_NAME = "name"
    private const val KEY_LOGGED_IN = "logged_in"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("hpu_user", Context.MODE_PRIVATE)

    fun save(ctx: Context, account: String, name: String) {
        prefs(ctx).edit().putString(KEY_ACCOUNT, account).putString(KEY_NAME, name).apply()
    }

    /**
     * CAS 确认认证成功后调用。登录状态是独立持久化的，不依赖课表请求是否成功。
     */
    fun markLoggedIn(ctx: Context, account: String, name: String) {
        prefs(ctx).edit()
            .putString(KEY_ACCOUNT, account)
            .putString(KEY_NAME, name)
            .putBoolean(KEY_LOGGED_IN, true)
            .apply()
    }

    /**
     * 是否已在本机完成过认证且未退出登录。
     * 老版本没有 logged_in 键：有 account 说明曾经认证成功，迁移一次后落盘。
     */
    fun isLoggedIn(ctx: Context): Boolean {
        val p = prefs(ctx)
        if (p.contains(KEY_LOGGED_IN)) return p.getBoolean(KEY_LOGGED_IN, false)
        val legacy = account(ctx).isNotBlank()
        if (legacy) p.edit().putBoolean(KEY_LOGGED_IN, true).apply()
        return legacy
    }

    fun account(ctx: Context): String = prefs(ctx).getString(KEY_ACCOUNT, "") ?: ""
    fun name(ctx: Context): String = prefs(ctx).getString(KEY_NAME, "") ?: ""

    fun setName(ctx: Context, name: String) {
        prefs(ctx).edit().putString(KEY_NAME, name).apply()
    }

    fun clear(ctx: Context) {
        prefs(ctx).edit().clear().apply()
    }
}