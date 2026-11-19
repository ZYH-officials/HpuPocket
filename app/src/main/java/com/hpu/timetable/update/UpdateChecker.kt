package com.hpu.timetable.update

import android.content.Context
import com.hpu.timetable.BuildConfig
import com.hpu.timetable.data.HttpClients
import com.hpu.timetable.data.catching
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONArray

data class UpdateInfo(
    /** 去掉 v 前缀的版本号，如 2.2.0-dev.1 */
    val version: String,
    val title: String,
    val notes: String,
    /** 优先是 Release 里 .apk 资产的下载地址，没有资产时回退发行页 */
    val url: String,
    val prerelease: Boolean,
)

/**
 * 检查更新：只请求 GitHub 公开 Releases 接口，不携带任何本地数据。
 * 正式包只看正式发行版；开发包（DEV_OPTIONS）连预发行版一起看。
 */
object UpdateChecker {
    private const val API =
        "https://api.github.com/repos/ZYH-officials/HpuPocket/releases?per_page=30"

    fun currentVersion(): String = BuildConfig.VERSION_NAME

    /** 有更新才返回非 null；网络失败返回 failure */
    suspend fun check(@Suppress("UNUSED_PARAMETER") ctx: Context): Result<UpdateInfo?> =
        withContext(Dispatchers.IO) {
            catching {
                val request = Request.Builder()
                    .url(API)
                    .header("Accept", "application/vnd.github+json")
                    .header("User-Agent", "HpuPocket/" + currentVersion())
                    .build()
                val body = HttpClients.base.newBuilder().build()
                    .newCall(request).execute().use { resp ->
                        if (!resp.isSuccessful) error("GitHub 返回 " + resp.code)
                        resp.body?.string() ?: ""
                    }
                val arr = JSONArray(body)
                var best: UpdateInfo? = null
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    if (o.optBoolean("draft")) continue
                    val pre = o.optBoolean("prerelease")
                    if (pre && !BuildConfig.DEV_OPTIONS) continue
                    val tag = o.optString("tag_name").removePrefix("v")
                    if (tag.isBlank()) continue
                    if (best == null || compareVersions(tag, best.version) > 0) {
                        // 和常见做法一致：先找 .apk 资产直链，找不到再回退发行页
                        var url = o.optString("html_url")
                        val assets = o.optJSONArray("assets")
                        if (assets != null) {
                            for (j in 0 until assets.length()) {
                                val a = assets.getJSONObject(j)
                                if (!a.optString("name").endsWith(".apk")) continue
                                val dl = a.optString("browser_download_url")
                                if (dl.isNotBlank()) { url = dl; break }
                            }
                        }
                        best = UpdateInfo(
                            version = tag,
                            title = o.optString("name").ifBlank { tag },
                            notes = o.optString("body"),
                            url = url,
                            prerelease = pre,
                        )
                    }
                }
                val newest = best
                if (newest != null && compareVersions(newest.version, currentVersion()) > 0) {
                    newest
                } else {
                    null
                }
            }
        }

    /** 语义化版本比较：数字段逐位比，无预发行后缀 > 有后缀，后缀按 . 分段比较 */
    fun compareVersions(a: String, b: String): Int {
        fun parse(v: String): Pair<List<Int>, List<String>> {
            val raw = v.trim().removePrefix("v").removePrefix("V")
            val core = raw.substringBefore('-')
            val pre = raw.substringAfter('-', "")
            val nums = core.split('.').map { part ->
                part.takeWhile { it.isDigit() }.toIntOrNull() ?: 0
            }
            return nums to if (pre.isBlank()) emptyList() else pre.split('.')
        }

        val (na, pa) = parse(a)
        val (nb, pb) = parse(b)
        for (i in 0 until maxOf(na.size, nb.size)) {
            val x = na.getOrElse(i) { 0 }
            val y = nb.getOrElse(i) { 0 }
            if (x != y) return x.compareTo(y)
        }
        // 2.2.0 > 2.2.0-dev.1
        if (pa.isEmpty() && pb.isEmpty()) return 0
        if (pa.isEmpty()) return 1
        if (pb.isEmpty()) return -1
        for (i in 0 until maxOf(pa.size, pb.size)) {
            val x = pa.getOrNull(i) ?: return -1
            val y = pb.getOrNull(i) ?: return 1
            val xn = x.toIntOrNull()
            val yn = y.toIntOrNull()
            val cmp = when {
                xn != null && yn != null -> xn.compareTo(yn)
                xn != null -> -1 // 数字标识 < 字母标识
                yn != null -> 1
                else -> x.compareTo(y)
            }
            if (cmp != 0) return cmp
        }
        return 0
    }
}