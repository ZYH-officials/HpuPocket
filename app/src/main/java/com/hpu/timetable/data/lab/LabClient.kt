package com.hpu.timetable.data.lab

import com.hpu.timetable.data.catching
import com.hpu.timetable.data.auth.PersistCookieJar
import com.hpu.timetable.data.auth.SessionExpiredException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * 实验平台（sj.hpu.edu.cn）直接取课表，不再依赖 WebView 注入。
 *
 * 2026-11-09 在电脑上用设备 cookie 跑通的链路：
 *   1. GET  http://sj.hpu.edu.cn/hpu/
 *      带着 CAS 的 cookie 会一路 302 到 http://sj.hpu.edu.cn/#/common/cas?id=<JWT>。
 *      前端源码里就是 `zxStorage.setItem("key1", $route.query.id)`，
 *      所以 URL 上这个 id 本身就是 Authorization 令牌，不需要再换票。
 *   2. POST http://sj.hpu.edu.cn/teach/teach/stuTime/listStuYearterm
 *      teach 出现两次是前端拦截器给首段模块名又补了一次前缀的结果，
 *      少一层（/teach/stuTime/...）直接 404。
 *   3. 必须带齐 6 个头：Authorization / zhxhsign / timestamp / nonce / signature / random。
 *      缺 random 或签名错，一律回「接口未经验证,请联系管理员!」。
 *
 * 返回体就是 WebView 里 hook 到的同一份 JSON（result -> "大节_星期" -> 数组），
 * 所以 [LabParser] 的解析、按周展开、合并、补地点全部照旧。
 */
class LabClient(private val cookieJar: PersistCookieJar) {

    private val client: OkHttpClient = com.hpu.timetable.data.HttpClients.base.newBuilder()
        .cookieJar(cookieJar)
        .followRedirects(true)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .build()

    private val ua = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"

    /** 拿 JWT。会话过期时这里会落到 CAS 登录页，抛出去让 SessionGate 重登再重试 */
    private fun fetchJwt(): String {
        val resp = client.newCall(
            Request.Builder().url(SSO_ENTRY).header("User-Agent", ua).build()
        ).execute()
        val finalUrl = resp.request.url.toString()
        resp.close()
        if (finalUrl.contains("uia.hpu.edu.cn/cas/login")) throw SessionExpiredException()
        val jwt = finalUrl.substringAfter("id=", "").substringBefore("&")
        if (jwt.isBlank()) {
            error("实验平台没给出会话令牌（落地页：" + finalUrl.take(100) + "）")
        }
        return jwt
    }

    suspend fun fetchTimetable(): Result<String> = withContext(Dispatchers.IO) {
        catching {
            val jwt = fetchJwt()
            val builder = Request.Builder()
                .url(TIMETABLE_API)
                .post("{}".toRequestBody(JSON))
                .header("User-Agent", ua)
            signHeaders(jwt).forEach { (k, v) -> builder.header(k, v) }

            val body = client.newCall(builder.build()).execute().use { resp ->
                if (!resp.isSuccessful) error("实验平台 HTTP " + resp.code)
                resp.body?.string().orEmpty()
            }
            com.hpu.timetable.AppLog.d(
                "HPU_LAB",
                "listStuYearterm len=" + body.length + " head=" + body.take(120),
            )

            val json = catching { JSONObject(body) }.getOrNull()
                ?: error("实验平台返回的不是 JSON")
            val code = json.optInt("code", 0)
            if (code != 200) {
                error("实验平台返回：" + json.optString("msg").ifBlank { "code=" + code })
            }
            val result = json.optJSONObject("result") ?: error("实验平台没有返回课表数据")
            if (result.length() == 0) error("实验平台课表是空的")
            body
        }
    }

    /**
     * 签名头。算法来自前端 bundle（密钥就硬编码在里面）：
     *   zhxhsign = HMAC-SHA256("Authorization=" + JWT, ZHXH_KEY).hex.upper()
     *   signature = HMAC-SHA512(timestamp + "-" + nonce, SIG_KEY).hex
     */
    private fun signHeaders(jwt: String): Map<String, String> {
        val ts = System.currentTimeMillis().toString()
        val nonce = randomToken() + randomToken()
        return mapOf(
            "Authorization" to jwt,
            "zhxhsign" to hmacHex("HmacSHA256", ZHXH_KEY, "Authorization=" + jwt).uppercase(),
            "timestamp" to ts,
            "nonce" to nonce,
            "random" to ts,
            "signature" to hmacHex("HmacSHA512", SIG_KEY, ts + "-" + nonce),
        )
    }

    /** 前端是 Math.random().toString(36).substring(2,15)，等价于 13 位小写字母数字 */
    private fun randomToken(): String {
        val chars = "abcdefghijklmnopqrstuvwxyz0123456789"
        val sb = StringBuilder(13)
        repeat(13) { sb.append(chars[rnd.nextInt(chars.length)]) }
        return sb.toString()
    }

    private fun hmacHex(algo: String, key: String, data: String): String {
        val mac = Mac.getInstance(algo)
        mac.init(SecretKeySpec(key.toByteArray(Charsets.UTF_8), algo))
        return mac.doFinal(data.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val SSO_ENTRY = "http://sj.hpu.edu.cn/hpu/"
        const val TIMETABLE_API = "http://sj.hpu.edu.cn/teach/teach/stuTime/listStuYearterm"

        /** 前端 bundle 里的硬编码密钥；学校升级平台可能会换，换了就退回 WebView 导入 */
        const val ZHXH_KEY = "zhxintd201020301"
        const val SIG_KEY = "zxtd_256-bit-secret-key-2025-8-7"

        val JSON = "application/json;charset=UTF-8".toMediaType()
        val rnd = java.util.Random()
    }
}
