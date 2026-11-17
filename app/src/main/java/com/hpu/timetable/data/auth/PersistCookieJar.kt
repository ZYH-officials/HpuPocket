package com.hpu.timetable.data.auth

import android.content.Context
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * 会话 cookie 持久化，App 重启后不用再登录。
 *
 * 内存表放在 companion 里全进程共享：自动重登成功后，之前构造好的 JwClient
 * 也就能立刻看到新 cookie。否则各实例各存一份，重登后重试用的还是旧表，
 * 会继续被判成过期。
 */
class PersistCookieJar(context: Context) : CookieJar {

    private val prefs = context.applicationContext
        .getSharedPreferences("hpu_cookies", Context.MODE_PRIVATE)

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

    fun names(): String = synchronized(LOCK) { mem.values.joinToString(", ") { it.name } }

    /** 供 WebView 的 CookieManager 同步用 */
    fun all(): List<Cookie> = synchronized(LOCK) { mem.values.toList() }

    companion object {
        private val LOCK = Any()
        private val mem = LinkedHashMap<String, Cookie>()
        private var loaded = false

        private fun key(c: Cookie) = c.domain + "/" + c.name + "/" + c.path
    }
}
