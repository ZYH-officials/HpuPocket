package com.hpu.timetable.data.whq

import android.content.Context
import com.hpu.timetable.AppLog
import com.hpu.timetable.data.HttpClients
import com.hpu.timetable.data.catching
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * 微后勤（whgfwdt.hpu.edu.cn）宿舍电量查询。
 *
 * 登录只能微信扫码：生成 instanceToken → 二维码指向 /fapi/wxApi/oiGet →
 * 用户扫码后轮询 getWebLoginScanStatus，success 时响应里 Set-Cookie 会话。
 *
 * ⚠️ get_balance_raw / get_record 有 TLS 指纹门禁：非浏览器指纹（如 git-bash 的
 * OpenSSL curl）会被静默拦成 HTTP 200 + `[]`。OkHttp 需真机实测；被拦时改为
 * WebView 内发请求。
 */
class WhqClient(context: Context) {

    private val jar = WhqCookieJar(context.applicationContext)
    private val jsonType = "application/json; charset=utf-8".toMediaType()
    private val ua = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"

    private val client = HttpClients.base.newBuilder()
        .cookieJar(jar)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(65, TimeUnit.SECONDS)
        .callTimeout(70, TimeUnit.SECONDS)
        .build()

    /** 一个已绑定的表（照明/空调各一条） */
    data class Room(val area: String, val raw: JSONObject) {
        val key: String =
            runCatching { raw.getJSONArray("room").getString(0) }.getOrNull() ?: name

        val name: String
            get() = runCatching { raw.getJSONArray("room").getString(1) }.getOrNull()
                ?: runCatching { raw.getJSONArray("room").getString(0) }.getOrNull()
                ?: "未知房间"
    }

    private fun get(path: String) = Request.Builder()
        .url(BASE + path)
        .header("User-Agent", ua)
        .header("Accept", "application/json, text/plain, */*")
        .header("Referer", BASE + "/hnlg/index.html")
        .build()

    private fun postJson(path: String, body: JSONObject) = Request.Builder()
        .url(BASE + path)
        .post(body.toString().toRequestBody(jsonType))
        .header("User-Agent", ua)
        .header("Accept", "application/json, text/plain, */*")
        .header("Origin", BASE)
        .header("Referer", BASE + "/hnlg/index.html")
        .build()

    private fun execute(req: Request, timeoutSec: Long = 70): String {
        val c = if (timeoutSec >= 70) client
        else client.newBuilder().callTimeout(timeoutSec, TimeUnit.SECONDS).build()
        return c.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("微后勤 HTTP " + resp.code)
            resp.body?.string().orEmpty()
        }
    }

    /** getAuthz 里带 loggedIn，用来判断扫码会话是否还有效 */
    suspend fun loggedIn(): Result<Boolean> = withContext(Dispatchers.IO) {
        catching {
            val body = execute(get("/fapi/index/getAuthz"), 8)
            val o = JSONObject(body)
            o.optJSONObject("data")?.optBoolean("loggedIn") ?: o.optBoolean("loggedIn")
        }
    }

    /** pending / success / expired / invalid_token */
    suspend fun pollStatus(token: String): Result<String> = withContext(Dispatchers.IO) {
        catching {
            val body = execute(
                postJson("/fapi/index/getWebLoginScanStatus", JSONObject().put("tokenParam", token)),
                8,
            )
            val o = JSONObject(body)
            val fromData = o.optJSONObject("data")?.optString("status").orEmpty()
            fromData.ifBlank { o.optString("status") }
        }
    }

    /** 已绑房间；没绑定时接口直接返回字符串 un_choose，这里返回 null */
    suspend fun rooms(area: String): Result<List<Room>?> = withContext(Dispatchers.IO) {
        catching {
            val body = execute(get("/fapi/utility_bill/index?ref=" + area), 20).trim()
            if (body == "un_choose" || body == "\"un_choose\"" || body.isBlank()) {
                return@catching null
            }
            val arr = JSONArray(body)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                if (!o.has("room")) return@mapNotNull null
                Room(area, o)
            }
        }
    }

    /**
     * 原始余额字符串（如 "松园7#326照明剩余电量:-37.54"）。
     * 上游电表系统偶发超时：照 H5 重试一次（间隔 180ms）。
     */
    suspend fun balance(room: Room): Result<List<String>> = withContext(Dispatchers.IO) {
        catching {
            val reqBody = JSONObject().put("area", room.area).put("room", room.raw)
            var last: Exception? = null
            for (attempt in 0..1) {
                try {
                    val body = execute(postJson("/fapi/utility_bill/get_balance_raw", reqBody)).trim()
                    val arr = JSONArray(body)
                    val raws = (0 until arr.length()).map { arr.optString(it) }
                    AppLog.event("whq", "balance " + room.name + " raw=" + raws.size)
                    return@catching raws
                } catch (e: Exception) {
                    last = e
                    if (attempt == 0) Thread.sleep(180)
                }
            }
            throw last ?: error("电量查询失败")
        }
    }

    suspend fun logout(): Result<Unit> = withContext(Dispatchers.IO) {
        catching { execute(postJson("/fapi/index/logOut", JSONObject()), 8); Unit }
    }

    fun clearSession() = jar.clear()

    companion object {
        const val BASE = "https://whgfwdt.hpu.edu.cn"
        const val AREA_SOUTH = "south"
        const val AREA_NORTH = "north"

        fun areaLabel(area: String): String =
            if (area == AREA_SOUTH) "南宿舍区" else "北宿舍区"

        fun newToken(): String {
            val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
            return "instanceToken" + (1..20).map { chars.random() }.joinToString("")
        }

        fun qrContent(token: String): String {
            val target = BASE + "/#/home/webLoginRes?terminal=mobile&from=scan_login"
            return BASE + "/fapi/wxApi/oiGet?" + token + "&url=" +
                URLEncoder.encode(target, "UTF-8")
        }

        private val BALANCE_RE = Regex(
            "(剩余充值电量|备用剩余电量|剩余补助电量|照明剩余(?:总)?电量|" +
                "空调剩余(?:总)?电量|照明|空调|剩余(?:总)?电量)[:：]\\s*([-+]?\\d+(?:\\.\\d+)?)"
        )

        /** 把原始字符串解析成「标签 → 数值」，与微后勤 H5 的正则一致 */
        fun parseBalance(raws: List<String>): List<Pair<String, String>> {
            val out = LinkedHashMap<String, String>()
            raws.forEach { raw ->
                BALANCE_RE.findAll(raw).forEach { m ->
                    val label = when (val g = m.groupValues[1]) {
                        "剩余补助电量" -> "备用剩余电量"
                        "剩余总电量" -> "剩余电量"
                        else -> g
                    }
                    val number = m.groupValues[2].toDoubleOrNull()
                    val text = if (number != null && number in -50_000.0..500_000.0) {
                        String.format("%.2f", number)
                    } else "--"
                    if (!out.containsKey(label) || out[label] == "--") out[label] = text
                }
            }
            return out.entries.take(3).map { it.key to it.value }
        }
    }
}
