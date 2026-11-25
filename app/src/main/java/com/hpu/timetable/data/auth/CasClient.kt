package com.hpu.timetable.data.auth

import com.hpu.timetable.data.catching
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URI
import java.util.concurrent.TimeUnit

/**
 * 河南理工大学统一认证（Apereo CAS 表单流程）。
 * 协议对齐 _research 里已验证的 Python 实现：
 *   1. GET  登录页  → 取 lt / execution
 *   2. GET  /sso/apis/v2/open/captcha?type=EASY → base64 图片 + token
 *   3. POST /cas/login → 用户名/密码/验证码
 */
class CasClient(val cookieJar: PersistCookieJar) {

    /** GET 用：自动跟随重定向（登录页可能先 302 再给 200） */
    private val followClient: OkHttpClient = com.hpu.timetable.data.HttpClients.base.newBuilder()
        .cookieJar(cookieJar)
        .followRedirects(true)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    /** POST 用：不跟随，要靠 Location 判断结果 */
    private val client: OkHttpClient = com.hpu.timetable.data.HttpClients.base.newBuilder()
        .cookieJar(cookieJar)
        .followRedirects(false)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    data class LoginPage(val lt: String, val execution: String)
    data class Captcha(val base64: String, val token: String)

    /** 打开统一认证入口的三种结果，不能把“没有表单”直接当成已登录 */
    sealed interface OpenLoginResult {
        data class Form(val page: LoginPage) : OpenLoginResult
        data object AlreadyAuthenticated : OpenLoginResult
    }

    private val loginUrl = "https://uia.hpu.edu.cn/cas/login?service=" +
        "https%3A%2F%2Fuia.hpu.edu.cn%2Fsso%2Flogin%3Fredirect_uri%3Dhttp%253A%252F%252Fzhlg.hpu.edu.cn%252F" +
        "%26x_client%3Dcas"

    suspend fun openLoginPage(): OpenLoginResult = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(loginUrl).header("User-Agent", UA).build()
        val resp = followClient.newCall(req).execute()
        val html = resp.body?.string().orEmpty()
        val code = resp.code
        val successful = resp.isSuccessful
        val finalHost = resp.request.url.host
        val finalUrl = resp.request.url.toString()
        resp.close()
        com.hpu.timetable.AppLog.d(
            "HPU_CAS", "page code=" + code + " len=" + html.length + " finalHost=" + finalHost,
        )
        if (!successful) error("统一认证 HTTP " + code)
        val lt = Regex("""name="lt" value="([^"]+)"""").find(html)?.groupValues?.get(1).orEmpty()
        val exe = Regex("""name="execution" value="([^"]+)"""").find(html)?.groupValues?.get(1).orEmpty()
        when {
            lt.isNotBlank() && exe.isNotBlank() ->
                OpenLoginResult.Form(LoginPage(lt, exe))

            lt.isNotBlank() || exe.isNotBlank() ->
                error("统一认证登录页不完整，请刷新重试")

            // CAS 仍登录时会 302 到门户；只有确认落在门户才允许跳过表单
            finalUrl.contains("zhlg.hpu.edu.cn") ||
                html.contains("drupalSettings") ||
                html.contains("\"account\":\"") ->
                OpenLoginResult.AlreadyAuthenticated

            else -> error("统一认证返回异常页面（HTTP " + code + "，长度 " + html.length + "）")
        }
    }

    suspend fun fetchCaptcha(): Captcha = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url("https://uia.hpu.edu.cn/sso/apis/v2/open/captcha?type=EASY")
            .header("User-Agent", UA)
            .header("X-Requested-With", "XMLHttpRequest")
            .header("Accept", "application/json, text/plain, */*")
            .build()
        val body = followClient.newCall(req).execute().use { it.body?.string().orEmpty() }
        com.hpu.timetable.AppLog.d("HPU_CAS", "captcha len=" + body.length)
        val json = JSONObject(body)
        Captcha(json.optString("img"), json.optString("token"))
    }

    suspend fun submit(
        username: String,
        password: String,
        captcha: String,
        page: LoginPage,
        captchaToken: String,
    ): Boolean = withContext(Dispatchers.IO) {
        val form = FormBody.Builder()
            .add("username", username)
            .add("password", password)
            .add("lt", page.lt)
            .add("execution", page.execution)
            .add("_eventId", "submit")
            .add("source", "cas")
            .add("captcha", captcha)
            .add("token", captchaToken)
            .build()
        val req = Request.Builder()
            .url("https://uia.hpu.edu.cn/cas/login")
            .header("User-Agent", UA)
            .header("Referer", loginUrl)
            .post(form)
            .build()
        val resp = client.newCall(req).execute()
        val location = resp.header("Location") ?: ""
        val body = resp.body?.string().orEmpty()
        val code = resp.code
        resp.close()
        val accepted = location.contains("sso/redirect") || location.contains("ticket=ST-")
        com.hpu.timetable.AppLog.d(
            "HPU_CAS",
            "submit code=" + code + " accepted=" + accepted + " bodyLen=" + body.length +
                " err=" + Regex("""id="errormes"[^>]*value="([^"]*)"""")
                    .find(body)?.groupValues?.get(1).orEmpty(),
        )
        accepted
    }

    /**
     * 会话过期后的自动重登：抓验证码 → 本地识别 → 提交。
     *
     * 验证码认不出来就换一张重抓，最多试 3 次；全失败返回 false，
     * 由上层退回手动登录（绝不硬猜，猜错会连续失败）。
     */
    suspend fun autoLogin(username: String, password: String): Boolean = withContext(Dispatchers.IO) {
        var attempts = 0
        while (attempts < 2) {
            attempts++
            if (attempts > 1) kotlinx.coroutines.delay(800)
            val opened = catching { openLoginPage() }.getOrNull() ?: return@withContext false
            val page = when (opened) {
                OpenLoginResult.AlreadyAuthenticated -> {
                    // 只有确认落在门户才跳过提交；拿不到表单绝不当成已登录
                    com.hpu.timetable.AppLog.d("HPU_CAS", "no login form (portal confirmed), skip submit")
                    return@withContext true
                }
                is OpenLoginResult.Form -> opened.page
            }
            if (page.lt.isBlank() || page.execution.isBlank()) {
                return@withContext false
            }
            val cap = catching { fetchCaptcha() }.getOrNull() ?: return@withContext false
            val bitmap = catching {
                val bytes = android.util.Base64.decode(cap.base64.split(",").last(), android.util.Base64.DEFAULT)
                android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            }.getOrNull()
            val answer = bitmap?.let { CaptchaSolver.solve(it) }
            com.hpu.timetable.AppLog.d("HPU_CAS", "autoLogin attempt=" + attempts + " captcha=" + answer)
            if (answer == null) continue // 认不出，换一张
            val ok = catching { submit(username, password, answer, page, cap.token) }.getOrDefault(false)
            if (ok) return@withContext true
        }
        false
    }

    /**
     * 跟随 CAS 会话把业务系统打通：拿 ST → /sso/login → 门户 → 实验平台 /hpu/
     * 如果跳转链里出现 /#/common/cas?id=JWT，把 JWT 取出来。
     */
    suspend fun bootstrapLabSession(): String? = withContext(Dispatchers.IO) {
        var url = "http://sj.hpu.edu.cn/hpu/"
        var jwt: String? = null
        repeat(12) {
            val req = Request.Builder().url(url).header("User-Agent", UA).build()
            val resp = client.newCall(req).execute()
            val loc = resp.header("Location")
            resp.close()
            if (loc == null) return@withContext jwt
            val next = if (loc.startsWith("http")) loc else {
                catching { URI(url).resolve(loc).toString() }.getOrNull() ?: return@withContext jwt
            }
            if (next.contains("common/cas") && next.contains("id=")) {
                jwt = next.substringAfter("id=").substringBefore("&")
            }
            url = next
            if (url.contains("homeage")) return@withContext jwt
        }
        jwt
    }

    /** 从门户首页的 drupalSettings 里抠出学号与真实姓名 */
    suspend fun fetchUserInfo(): Pair<String, String>? = withContext(Dispatchers.IO) {
        catching {
            val req = Request.Builder().url("https://zhlg.hpu.edu.cn/").header("User-Agent", UA).build()
            val html = followClient.newCall(req).execute().use { it.body?.string().orEmpty() }
            val account = Regex(""""account":"(\d+)"""").find(html)?.groupValues?.get(1).orEmpty()
            val rawName = Regex(""""name":"([^"]*)"""").find(html)?.groupValues?.get(1).orEmpty()
            val name = unescapeUnicode(rawName)
            com.hpu.timetable.AppLog.d("HPU_CAS", "userInfo len=" + html.length + " acc=" + account + " name=" + name)
            if (account.isBlank() && name.isBlank()) null else account to name
        }.getOrNull()
    }

    private fun unescapeUnicode(s: String): String =
        Regex("""\\u([0-9a-fA-F]{4})""").replace(s) { m ->
            m.groupValues[1].toInt(16).toChar().toString()
        }

    companion object {
        const val UA = "Mozilla/5.0 (Linux; Android 14; 25113PN0EC) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"
    }
}