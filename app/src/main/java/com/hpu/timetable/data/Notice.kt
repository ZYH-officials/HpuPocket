package com.hpu.timetable.data

import android.content.Context
import com.hpu.timetable.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.File
import java.util.concurrent.TimeUnit

data class Notice(
    val id: String,
    val title: String,
    val body: String,
    val date: String,
    val pinned: Boolean,
    val link: String,
)

/**
 * 公告：优先拉取公开仓 docs/notice.json，失败时用上次成功缓存或内置兜底。
 * 远端解析成功但列表为空时写空缓存，表示公告已撤下。
 */
object NoticeRepository {
    private const val CACHE_NAME = "notice.json"
    private const val RAW_URL =
        "https://raw.githubusercontent.com/ZYH-officials/HpuPocket/main/docs/notice.json"
    private const val CDN_URL =
        "https://cdn.jsdelivr.net/gh/ZYH-officials/HpuPocket@main/docs/notice.json"

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
            .build()
    }

    private fun cacheFile(ctx: Context) = File(ctx.filesDir, CACHE_NAME)

    fun loadForDisplay(ctx: Context): List<Notice> {
        val file = cacheFile(ctx)
        if (file.exists()) {
            val raw = runCatching { file.readText() }.getOrNull()
            if (raw != null) {
                val parsed = parse(raw)
                if (parsed != null) return sorted(parsed)
            }
        }
        val bundled = runCatching {
            ctx.assets.open("notice.json").bufferedReader().use { it.readText() }
        }.getOrNull() ?: return emptyList()
        return parse(bundled)?.let(::sorted).orEmpty()
    }

    suspend fun refresh(ctx: Context): Result<List<Notice>> = withContext(Dispatchers.IO) {
        com.hpu.timetable.data.catching {
            var lastError: Exception? = null
            for (url in listOf(RAW_URL, CDN_URL)) {
                val raw = try {
                    fetch(url)
                } catch (e: Exception) {
                    lastError = e
                    continue
                }
                val parsed = parse(raw)
                if (parsed == null) {
                    lastError = IllegalStateException("公告 JSON 格式不正确")
                    continue
                }
                runCatching { cacheFile(ctx).writeText(raw) }
                AppLog.event("notice", "公告刷新成功：" + parsed.size + " 条")
                return@catching sorted(parsed)
            }
            throw lastError ?: IllegalStateException("公告拉取失败")
        }
    }

    private fun fetch(url: String): String {
        val req = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .header("User-Agent", "HpuPocket")
            .build()
        return client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("公告 HTTP " + resp.code)
            val body = resp.body?.string().orEmpty()
            if (body.length > 200_000) error("公告内容过大")
            if (body.isBlank()) error("公告内容为空")
            body
        }
    }

    private fun parse(raw: String): List<Notice>? {
        val root = runCatching { JSONTokener(raw).nextValue() }.getOrNull() ?: return null
        val arr: JSONArray = when (root) {
            is JSONArray -> root
            is JSONObject -> root.optJSONArray("notices") ?: return null
            else -> return null
        }
        val out = ArrayList<Notice>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (!o.optBoolean("enabled", true)) continue
            val id = o.optString("id").trim()
            val title = o.optString("title").trim()
            if (id.isBlank() || title.isBlank()) continue
            out += Notice(
                id = id,
                title = title,
                body = o.optString("body").trim(),
                date = o.optString("date").trim(),
                pinned = o.optBoolean("pinned", false),
                link = o.optString("link").trim(),
            )
        }
        return out
    }

    private fun sorted(list: List<Notice>): List<Notice> =
        list.sortedWith(
            compareByDescending<Notice> { it.pinned }.thenByDescending { it.date }
        )
}