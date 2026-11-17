package com.hpu.timetable.data.hw

import com.hpu.timetable.data.catching
import android.content.Context
import com.hpu.timetable.data.auth.PersistCookieJar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** 作业平台登录态失效（401 / AUTH_*）：上层可以用存下来的密码重登一次 */
class HwAuthException(message: String) : java.io.IOException(message)

/** 一条作业。字段按平台返回映射，取不到的留空 */
data class HwItem(
    val course: String,
    val title: String,
    val status: String,
    val deadline: String,
    val score: String,
    /** 还没提交 = 未完成，首页「还有 N 项」就数它 */
    val pending: Boolean = false,
)

/**
 * 数你最灵作业平台（smartestu.cn）学生端。
 *
 * 这个平台不属于河南理工大学，是第三方 SaaS；登录凭据要单独存（见 [HwStore]）。
 * 协议（2026-11-09 从浏览器 Network 面板与前端 bundle 双份对出来）：
 *
 *  1. POST /api/auth/login
 *     body {schoolCode:"hpu", schoolUserLocalId:学号, schoolUserId:"hpu-学号", password}
 *  2. GET  /api/auth/session → {user, sessionContext, csrfToken}
 *  3. 之后每个请求都要带 X-Auth-Protocol: cookie-v1 + X-Session-Context + X-CSRF-Token，
 *     少 X-Auth-Protocol 会被回 426 AUTH_CLIENT_UPDATE_REQUIRED（看着像"网站已更新"）。
 *  4. POST /api/homework/student/course/query  body {} → 学生绑定的课程
 *  5. POST /api/homework/student/mark/queryHomeworks
 *     body {courseIds:[5462,7301], scene:"homework", pageSize:20, pageNo:1}
 *     不带 courseIds 会返回空列表——这是第一版查不到作业的原因。
 *  6. 响应是「课程 → 作业」两层：
 *     data.courseHomeworkDTOList[].studentCourseHomeworkDTOList[]，
 *     作业字段 name / endTime / score / totalScore / submission_status / review_status。
 */
class HomeworkClient(
    private val ctx: Context,
    private val jar: PersistCookieJar,
    private val platform: HwPlatform,
) {

    private val client: OkHttpClient = com.hpu.timetable.data.HttpClients.base.newBuilder()
        .cookieJar(jar)
        .followRedirects(true)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .build()

    private val ua = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"

    private var sessionContext: String? = HwStore.session(ctx, platform.id)?.first
    private var csrfToken: String? = HwStore.session(ctx, platform.id)?.second

    /** 已经配置过账号密码 */
    fun hasCredentials(): Boolean = HwStore.credentials(ctx, platform.id) != null

    fun account(): String = HwStore.account(ctx, platform.id)

    /** 登录：学号 + 平台密码。成功后把 sessionContext / csrfToken 存下来 */
    suspend fun login(account: String, password: String): Result<Unit> = withContext(Dispatchers.IO) {
        catching {
            val body = JSONObject().apply {
                put("schoolCode", platform.schoolCode)
                put("schoolUserLocalId", account)
                put("schoolUserId", platform.schoolCode + "-" + account)
                put("password", password)
            }.toString()
            val loginBody = call("POST", "/api/auth/login", body)
            com.hpu.timetable.AppLog.d("HPU_HW", "login ok len=" + loginBody.length)
            catching {
                val o = JSONObject(loginBody)
                o.optString("sessionContext").takeIf { it.isNotBlank() }?.let { sessionContext = it }
                o.optString("csrfToken").takeIf { it.isNotBlank() }?.let { csrfToken = it }
            }

            val sess = call("GET", "/api/auth/session", null)
            com.hpu.timetable.AppLog.d("HPU_HW", "session len=" + sess.length + " head=" + sess.take(160))
            val o = JSONObject(sess)
            val sc = o.optString("sessionContext")
            val csrf = o.optString("csrfToken")
            val uid = o.optJSONObject("user")?.optString("id").orEmpty()
            if (sc.isBlank() || csrf.isBlank() || uid.isBlank()) {
                error("作业平台登录响应不完整")
            }
            sessionContext = sc
            csrfToken = csrf
            HwStore.saveSession(ctx, platform.id, sc, csrf)
            HwStore.saveCredentials(ctx, platform.id, account, password)
            Unit
        }
    }

    /** 拉作业：先拿课程，再按课程 id 批量查（平台要求 courseIds，否则恒返回空） */
    suspend fun fetchHomeworks(pageSize: Int = 50): Result<List<HwItem>> = withContext(Dispatchers.IO) {
        catching {
            val courses = fetchCourses()
            com.hpu.timetable.AppLog.d("HPU_HW", "courses=" + courses.size + " ids=" + courses.map { it.id })
            val ids = courses.map { it.id }
            val body = callWithRelogin(QUERY_PATH, payload(ids, pageSize))
            com.hpu.timetable.AppLog.d("HPU_HW", "homeworks len=" + body.length + " head=" + body.take(160))
            dump("homeworks", body)
            parseNested(body)
        }
    }

    private data class HwCourse(val id: Long, val name: String)

    private suspend fun fetchCourses(): List<HwCourse> {
        val body = callWithRelogin("/api/homework/student/course/query", "{}")
        com.hpu.timetable.AppLog.d("HPU_HW", "courseQuery len=" + body.length + " head=" + body.take(160))
        dump("courses", body)
        val root = catching { JSONObject(body) }.getOrNull() ?: return emptyList()
        val arr = findArray(root) ?: return emptyList()
        val out = ArrayList<HwCourse>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = pick(o, "courseId", "course_id", "id", "_id").toLongOrNull() ?: continue
            out += HwCourse(id, pick(o, "courseName", "course_name", "name", "title"))
        }
        return out
    }

    private fun findArray(root: JSONObject): JSONArray? {
        val data = root.opt("data")
        if (data is JSONArray) return data
        if (data is JSONObject) {
            for (k in data.keys()) {
                (data.opt(k) as? JSONArray)?.let { return it }
            }
        }
        for (k in root.keys()) {
            (root.opt(k) as? JSONArray)?.let { return it }
        }
        return null
    }

    private fun payload(ids: List<Long>, pageSize: Int): String = JSONObject().apply {
        put("courseIds", JSONArray().apply { ids.forEach { put(it) } })
        put("scene", "homework")
        put("pageNo", 1)
        put("pageSize", pageSize)
    }.toString()

    /** 响应是课程 → 作业两层，按课程名把作业摊平 */
    private fun parseNested(body: String): List<HwItem> {
        val root = catching { JSONObject(body) }.getOrNull() ?: error("作业平台返回不是 JSON")
        val data = root.optJSONObject("data") ?: return emptyList()
        val courses = data.optJSONArray("courseHomeworkDTOList") ?: return emptyList()
        // 先按「可操作程度」排：未提交 → 批改中 → 已批改，同组内按截止时间近的在前
        val rows = ArrayList<Triple<Int, String, HwItem>>()
        for (i in 0 until courses.length()) {
            val c = courses.optJSONObject(i) ?: continue
            val courseName = c.optString("courseName")
            val list = c.optJSONArray("studentCourseHomeworkDTOList") ?: continue
            for (j in 0 until list.length()) {
                val h = list.optJSONObject(j) ?: continue
                rows += Triple(
                    rank(h),
                    h.optString("endTime"),
                    HwItem(
                        course = courseName,
                        title = h.optString("name"),
                        status = statusText(h),
                        deadline = fmtTime(h.optString("endTime")),
                        score = scoreText(h),
                        pending = h.optString("submission_status") == "not_submitted",
                    ),
                )
            }
        }
        return rows.sortedWith(compareBy<Triple<Int, String, HwItem>> { it.first }
            .thenBy { if (it.first == 0) it.second.ifBlank { "9999" } else "" }
            .thenByDescending { if (it.first != 0) it.second else "" })
            .map { it.third }
    }

    /** 0 未提交 / 1 已提交待批改 / 2 已批改 */
    private fun rank(h: JSONObject): Int {
        if (h.optString("submission_status") == "not_submitted") return 0
        val es = h.optJSONObject("exercise_status")
        val submitted = es?.optInt("submitted", 0) ?: 0
        val graded = es?.optInt("graded", 0) ?: 0
        val pending = es?.optInt("pending", 0) ?: 0
        return if (submitted > 0 && graded >= submitted && pending == 0) 2 else 1
    }

    /**
     * 批改状态看 exercise_status 和 status，**不能看 review_status**：
     * review_status 是"人工复核"状态，系统批改过也一直保持 not_reviewed。
     */
    private fun statusText(h: JSONObject): String {
        if (h.optString("submission_status") == "not_submitted") return "未提交"
        val es = h.optJSONObject("exercise_status")
        val submitted = es?.optInt("submitted", 0) ?: 0
        val graded = es?.optInt("graded", 0) ?: 0
        val pending = es?.optInt("pending", 0) ?: 0
        return when {
            submitted == 0 -> "已提交"
            graded >= submitted && pending == 0 -> "已批改"
            pending > 0 || graded < submitted -> "批改中"
            else -> "已提交"
        }
    }

    /** 只有 status=2（已批改）才显示分数，避免未交/批改中的 0 分误导 */
    private fun scoreText(h: JSONObject): String {
        if (h.optInt("status", -1) != 2) return ""
        val total = h.optString("totalScore").toDoubleOrNull() ?: return ""
        val score = h.opt("score")?.toString()?.toDoubleOrNull() ?: return ""
        if (total <= 0) return ""
        return trimNum(score) + "/" + trimNum(total)
    }

    private fun trimNum(d: Double): String =
        if (d % 1.0 == 0.0) d.toInt().toString() else d.toString()

    /** 平台给的是 UTC ISO（2026-11-17T07:05:08.100Z），转成本地「MM-dd HH:mm」 */
    private fun fmtTime(iso: String): String = catching {
        java.time.Instant.parse(iso).atZone(java.time.ZoneId.systemDefault())
            .format(java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm"))
    }.getOrDefault(iso)

    private suspend fun callWithRelogin(path: String, jsonBody: String): String =
        catching { call("POST", path, jsonBody) }.getOrElse { e ->
            if (e !is HwAuthException) throw e
            com.hpu.timetable.AppLog.d("HPU_HW", "session expired, relogin")
            val creds = HwStore.credentials(ctx, platform.id) ?: throw e
            login(creds.first, creds.second).getOrThrow()
            call("POST", path, jsonBody)
        }

    private fun call(method: String, path: String, jsonBody: String?): String {
        val builder = Request.Builder().url(platform.baseUrl + path)
            .header("X-Auth-Protocol", "cookie-v1")
            .header("User-Agent", ua)
            .header("Accept", "application/json")
            .header("Origin", platform.baseUrl)
            .header("Referer", platform.baseUrl + platform.refererPath)
        sessionContext?.let { builder.header("X-Session-Context", it) }
        csrfToken?.let { builder.header("X-CSRF-Token", it) }
        if (method == "POST") {
            builder.post((jsonBody ?: "{}").toRequestBody(JSON))
        } else {
            builder.get()
        }
        client.newCall(builder.build()).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (resp.code == 426) error("作业平台接口已更新，请更新 App；暂时可到平台网页查看作业")
            if (resp.code == 401) throw HwAuthException("登录已失效")
            if (!resp.isSuccessful) {
                val msg = catching {
                    val o = JSONObject(text)
                    o.optString("message").ifBlank { o.optString("code") }
                }.getOrNull().orEmpty()
                val code = catching { JSONObject(text).optString("code") }.getOrNull().orEmpty()
                if (code.startsWith("AUTH_")) throw HwAuthException(msg.ifBlank { code })
                error(msg.ifBlank { "作业平台 HTTP " + resp.code })
            }
            return text
        }
    }

    /** 排查用：把响应分块打进日志（logcat 单条约 4KB） */
    private fun dump(tag: String, body: String) {
        catching {
            val step = 3000
            var i = 0
            while (i < body.length) {
                val end = minOf(i + step, body.length)
                com.hpu.timetable.AppLog.d("HPU_HW", "dump[" + tag + "]" + (i / step) + " " + body.substring(i, end))
                i = end
            }
        }
    }

    private fun pick(o: JSONObject, vararg keys: String): String {
        for (k in keys) {
            val v = o.opt(k) ?: continue
            if (v == JSONObject.NULL) continue
            val s = v.toString().trim()
            if (s.isNotEmpty() && s != "null") return s
        }
        return ""
    }

    private companion object {
                const val QUERY_PATH = "/api/homework/student/mark/queryHomeworks"
        val JSON = "application/json".toMediaType()
    }
}
