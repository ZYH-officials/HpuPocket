package com.hpu.timetable.data.whq

import android.content.Context
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * 微后勤会话 cookie 持久化。
 * 和教务的 PersistCookieJar 分开存：微后勤是微信扫码登录的独立账号，
 * 退出教务登录不应该把电量的扫码会话一起清掉。
 */
class WhqCookieJar(context: Context) : CookieJar {

    private val prefs = context.applicationContext
        .getSharedPreferences("whq_cookies", Context.MODE_PRIVATE)

    init {
        if (!loaded) {
            synchronized(LOCK) {
                if (!loaded) {
                    prefs.getString("all", null)?.split("|C|")?.forEach { line ->
                        if (line.isBlank()) return@forEach
                        val parts = line.split("|T|")
                        if (parts.size < 2) return@forEach
                        val url = parts[0].toHttpUrlOrNull() ?: return@forEach
                        Cookie.parse(url, parts[1])?.let { mem[key(it)] = it }
                    }
                    loaded = true
                }
            }
        }
    }

    private fun persist() {
        val sb = StringBuilder()
        mem.values.forEach { c ->
            val scheme = if (c.secure) "https" else "http"
            sb.append(scheme).append("://").append(c.domain.removePrefix(".")).append(c.path)
            sb.append("|T|").append(c.toString()).append("|C|")
        }
        prefs.edit().putString("all", sb.toString()).apply()
    }

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        if (cookies.isEmpty()) return
        synchronized(LOCK) {
            cookies.forEach { mem[key(it)] = it }
            persist()
        }
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val now = System.currentTimeMillis()
        return synchronized(LOCK) { mem.values.filter { it.matches(url) && it.expiresAt > now } }
    }

    fun clear() {
        synchronized(LOCK) {
            mem.clear()
            prefs.edit().remove("all").apply()
        }
    }

    companion object {
        private val LOCK = Any()
        private val mem = LinkedHashMap<String, Cookie>()
        private var loaded = false

        private fun key(c: Cookie) = c.domain + "/" + c.name + "/" + c.path
    }
}
